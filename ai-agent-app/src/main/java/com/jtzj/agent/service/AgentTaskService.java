package com.jtzj.agent.service;

import com.jtzj.agent.core.error.AgentException;
import com.jtzj.agent.runtime.AgentTaskRunner;
import com.jtzj.agent.runtime.SkillRepositoryFactory;
import com.jtzj.agent.core.error.ErrorCodes;
import com.jtzj.agent.runtime.TaskWorkspaceFactory;
import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.model.AgentTaskRequest;
import com.jtzj.agent.core.model.TaskRecord;
import com.jtzj.agent.core.model.TaskStatus;
import com.jtzj.agent.store.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task admission and lookup.
 *
 * <p>The database is the queue: inserting a row in {@code accepted} state <em>is</em> the
 * enqueue, and {@link TaskPoller} picks rows up from there. Nothing is held in memory, so a
 * restart neither loses queued work nor requires the caller to resubmit.
 *
 * <p>Idempotency: submitting an existing {@code taskId} never re-runs work — it returns the
 * current row with {@code created=false}.
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    /** Poll cadence while a {@code sync=true} caller waits for a terminal state. */
    private static final long SYNC_POLL_MS = 200L;

    private final TaskStore store;
    private final TaskPoller poller;
    private final TaskWorkspaceFactory workspaceFactory;
    private final SkillRepositoryFactory skillRepositoryFactory;
    private final CallbackNotifier callbackNotifier;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public AgentTaskService(TaskStore store, TaskPoller poller,
                            TaskWorkspaceFactory workspaceFactory,
                            SkillRepositoryFactory skillRepositoryFactory,
                            CallbackNotifier callbackNotifier,
                            AgentProperties props, ObjectMapper mapper) {
        this.store = store;
        this.poller = poller;
        this.callbackNotifier = callbackNotifier;
        this.workspaceFactory = workspaceFactory;
        this.skillRepositoryFactory = skillRepositoryFactory;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * @param record the row after admission (freshly created or pre-existing)
     * @param created false for an idempotent replay
     * @param budget the clamped wall-clock budget applied to this run
     */
    public record Submission(TaskRecord record, boolean created, Duration budget) {
    }

    /**
     * Validate and admit a task. A {@code accepted} row is immediately eligible for a worker.
     *
     * @throws AgentException INVALID_REQUEST on size/schema/id violations,
     *                        CONCURRENCY_LIMITED when the queue-depth cap is hit
     */
    public Submission submit(AgentTaskRequest request) {
        validate(request);

        String taskId = resolveTaskId(request);
        Duration budget = resolveBudget(request);
        // Parsed here so a malformed schema fails the request, not the background run.
        JsonNode schema = toAgentScopeJson(request.outputSchema());
        // Unknown skill names are rejected here too: an empty skill view would otherwise let the
        // agent answer confidently without the guidance the caller believed it selected.
        List<String> skills = skillRepositoryFactory.validateSelection(request.skills());
        // Selected skill bodies are injected into the prompt, so their size is a request-time
        // property and must be rejected here rather than discovered mid-run.
        int inlineChars = skillRepositoryFactory.inlineFor(skills).length();
        int inlineCap = props.getSkills().getMaxInlineChars();
        if (inlineChars > inlineCap) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "selected skills total " + inlineChars + " characters, over the limit of "
                            + inlineCap + " (agent.skills.max-inline-chars); select fewer skills",
                    false);
        }
        // Resolve the main-model profile now: absent → configured default; unknown name → reject
        // rather than silently falling back (a typo must not quietly downgrade flash to pro or vice
        // versa). resolveModel throws IllegalArgumentException on an unknown profile.
        String modelName =
                (request.model() == null || request.model().isBlank())
                        ? props.getDefaultModel() : request.model().trim();
        try {
            props.resolveModel(modelName);
        } catch (IllegalArgumentException e) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "invalid 'model': " + e.getMessage(), false);
        }

        TaskRecord candidate = new TaskRecord();
        candidate.setTaskId(taskId);
        candidate.setStatus(TaskStatus.ACCEPTED);
        candidate.setInstruction(request.instruction());
        // schema is already the normalised Jackson 2 tree; store it as text
        candidate.setOutputSchema(schema == null ? null : schema.toString());
        candidate.setMetadata(wireText(request.metadata()));
        candidate.setTimeoutSeconds((int) budget.toSeconds());
        // Persisted so a task claimed after a restart still sees the same skill selection.
        candidate.setSkillNames(skills.isEmpty() ? null : String.join(",", skills));
        // Persisted so a task claimed after a restart still runs on the same model profile.
        candidate.setModelName(modelName);
        candidate.setCallbackUrl(normalizeCallbackUrl(request.callbackUrl()));
        candidate.setCreatedAt(Instant.now());

        boolean created = store.insertIfAbsent(candidate);
        if (!created) {
            TaskRecord existing = store.find(taskId)
                    .orElseThrow(() -> new AgentException(ErrorCodes.AGENT_EXECUTION_FAILED,
                            "task row vanished between insert and read: " + taskId, true));
            log.info("task {} replay, status={}", taskId, existing.getStatus().wire());
            return new Submission(existing, false, budgetOf(existing));
        }

        // Backpressure now applies to queue depth rather than a bounded in-memory queue.
        int cap = props.getExecution().getMaxQueuedTasks();
        if (cap > 0 && store.countByStatus(TaskStatus.ACCEPTED) > cap) {
            store.deleteIfStatus(taskId, TaskStatus.ACCEPTED.wire());
            throw new AgentException(ErrorCodes.CONCURRENCY_LIMITED,
                    "queue depth exceeds agent.execution.max-queued-tasks (" + cap + ")", true);
        }

        log.info("task {} queued, budgetSeconds={} hasSchema={} skills={}",
                taskId, budget.toSeconds(), schema != null,
                skills.isEmpty() ? "(all)" : skills);
        return new Submission(candidate, true, budget);
    }

    public Optional<TaskRecord> find(String taskId) {
        return store.find(taskId);
    }

    /**
     * Cancel a queued or running task.
     *
     * <p>If a thread in this process is running it, that thread is interrupted so the work
     * stops promptly. A run on another instance keeps going but cannot write its result,
     * because terminal writes are guarded against {@code cancelled} rows.
     *
     * @return false when the task is unknown or already terminal
     */
    public boolean cancel(String taskId) {
        boolean moved = store.cancelIfActive(taskId, Instant.now());
        if (!moved) {
            return false;
        }
        boolean interrupted = poller.interrupt(taskId);
        log.info("task {} cancelled (localInterrupt={})", taskId, interrupted);
        // Trigger callback with cancelled state
        store.find(taskId).ifPresent(callbackNotifier::notifyIfConfigured);
        return true;
    }

    /**
     * Wait for a task to settle, for {@code sync=true} requests.
     *
     * @return false when the budget elapsed first — the task keeps running and the caller
     *         should fall back to polling the same id
     */
    public boolean awaitSettled(String taskId, Duration budget) {
        long deadlineNanos = System.nanoTime() + Math.max(1L, budget.toNanos());
        while (System.nanoTime() < deadlineNanos) {
            Optional<TaskRecord> record = store.find(taskId);
            if (record.isPresent() && record.get().getStatus().isTerminal()) {
                return true;
            }
            try {
                Thread.sleep(SYNC_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        // One final read in case the row settled during the last sleep.
        return store.find(taskId).map(record -> record.getStatus().isTerminal()).orElse(false);
    }

    /** Queue occupancy and worker identity for health reporting. */
    public int activeCount() {
        return poller.activeCount();
    }

    public long queuedCount() {
        return poller.queuedCount();
    }

    public String workerId() {
        return poller.workerId();
    }

    /** Installed skill names, for the health endpoint and for callers that want to discover them. */
    public List<String> availableSkills() {
        return skillRepositoryFactory.availableNames();
    }

    private void validate(AgentTaskRequest request) {
        if (request == null) {
            throw invalid("request body is required");
        }
        AgentProperties.Limits limits = props.getLimits();
        if (request.instruction() == null || request.instruction().isBlank()) {
            throw invalid("instruction must not be blank");
        }
        if (request.instruction().length() > limits.getMaxInstructionChars()) {
            throw invalid("instruction exceeds " + limits.getMaxInstructionChars() + " characters");
        }
        if (lengthOf(request.outputSchema()) > limits.getMaxSchemaChars()) {
            throw invalid("outputSchema exceeds " + limits.getMaxSchemaChars() + " characters");
        }
        if (lengthOf(request.metadata()) > limits.getMaxMetadataChars()) {
            throw invalid("metadata exceeds " + limits.getMaxMetadataChars() + " characters");
        }
        tools.jackson.databind.JsonNode schema = request.outputSchema();
        if (schema != null && !schema.isNull() && !schema.isObject()) {
            throw invalid("outputSchema must be a JSON object");
        }
        // taskId doubles as a directory name, so it gets the workspace allowlist.
        String taskId = request.taskId();
        if (taskId != null && !taskId.isBlank() && !TaskWorkspaceFactory.isValidTaskId(taskId)) {
            throw invalid("taskId must match [A-Za-z0-9][A-Za-z0-9._+-]{0,63}"
                    + " and must not contain path separators");
        }
        // Callback URL: must be http/https, max 1024 chars.
        String cb = request.callbackUrl();
        if (cb != null && !cb.isBlank()) {
            String trimmed = cb.trim();
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw invalid("callbackUrl must start with http:// or https://");
            }
            if (trimmed.length() > 1024) {
                throw invalid("callbackUrl exceeds 1024 characters");
            }
        }
    }

    private static AgentException invalid(String message) {
        return new AgentException(ErrorCodes.INVALID_REQUEST, message, false);
    }

    private static int lengthOf(tools.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? 0 : node.toString().length();
    }

    private static String normalizeCallbackUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.trim();
    }

    private String resolveTaskId(AgentTaskRequest request) {
        String provided = request.taskId();
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Per-request timeout, clamped to the configured ceiling. */
    private Duration resolveBudget(AgentTaskRequest request) {
        int configured = props.getExecution().getDefaultTimeoutSeconds();
        int ceiling = props.getExecution().getMaxTimeoutSeconds();
        Integer requested = request.options() == null ? null : request.options().timeoutSeconds();
        int seconds = requested == null || requested <= 0 ? configured : Math.min(requested, ceiling);
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private static Duration budgetOf(TaskRecord record) {
        int seconds = record.getTimeoutSeconds() == null || record.getTimeoutSeconds() <= 0
                ? 120
                : record.getTimeoutSeconds();
        return Duration.ofSeconds(seconds);
    }

    /** Serialise a Jackson 3 (HTTP-side) tree for storage. */
    private static String wireText(tools.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    /**
     * Convert the Jackson 3 tree received over HTTP into the Jackson 2 tree AgentScope expects.
     * The two libraries share no types, so text is the interchange format.
     */
    private JsonNode toAgentScopeJson(tools.jackson.databind.JsonNode wire) {
        if (wire == null || wire.isNull()) {
            return null;
        }
        try {
            return mapper.readTree(wire.toString());
        } catch (Exception e) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "outputSchema could not be parsed: " + e.getMessage(), false, e);
        }
    }
}

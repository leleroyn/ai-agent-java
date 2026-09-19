package com.example.agent.api;

import com.example.agent.agent.ToolkitFactory;
import com.example.agent.config.AgentProperties;
import com.example.agent.model.AgentTaskRequest;
import com.example.agent.model.TaskRecord;
import com.example.agent.model.TaskStatusView;
import com.example.agent.service.AgentTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public task API.
 *
 * <pre>
 * POST   /api/v1/agent/task          submit a task
 * GET    /api/v1/agent/task/{id}     poll status and result
 * DELETE /api/v1/agent/task/{id}     cancel a queued or running task
 * GET    /api/v1/agent/tools         tools currently registered for a task (name + description)
 * GET    /api/v1/agent/skills        installed skill names
 * GET    /api/v1/agent/health        queue occupancy and liveness
 * </pre>
 *
 * <p>Every endpoint answers the same envelope, {@code {"code", "message", "data"}}, and the
 * business outcome is carried by the numeric {@code code} — not by the HTTP status, which stays
 * 200 for handled cases including rejections. See {@link ApiResponse} and {@link ApiCodes}.
 *
 * <p>No authentication is applied, by explicit decision. Do not expose this service to an
 * untrusted network while {@code agent.tools.shell} is enabled.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentTaskService service;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final ToolkitFactory toolkitFactory;

    public AgentController(AgentTaskService service, AgentProperties props, ObjectMapper mapper,
                           ToolkitFactory toolkitFactory) {
        this.service = service;
        this.props = props;
        this.mapper = mapper;
        this.toolkitFactory = toolkitFactory;
    }

    /**
     * Submit a task.
     *
     * <p>{@code code=0} means the submission succeeded: the task exists and {@code data.status}
     * says where it stands. This covers both cases — a brand new task and a repeat of an existing
     * {@code taskId} — because a repeat is not a failure; the caller asked for this id to be in
     * the system, and it is. Repeat submissions never re-run work, so retrying a lost response is
     * always safe.
     *
     * <p>With {@code options.sync=true} the call blocks until the task settles. If the budget
     * elapses first the response is still {@code code=0} with a non-terminal {@code data.status}
     * — the task keeps running, poll {@code GET /task/{id}} for it.
     */
    @PostMapping("/task")
    public ApiResponse<TaskStatusView> submit(@Valid @RequestBody AgentTaskRequest request) {
        AgentTaskService.Submission submission = service.submit(request);

        if (submission.created() && request.syncRequested()) {
            String taskId = submission.record().getTaskId();
            service.awaitSettled(taskId, submission.budget());
            return ApiResponse.ok(service.find(taskId)
                    .map(record -> TaskStatusView.of(record, mapper))
                    .orElseGet(() -> TaskStatusView.of(submission.record(), mapper)));
        }

        // New task (async) or an existing id: either way, hand back its current state.
        return ApiResponse.ok(TaskStatusView.of(submission.record(), mapper));
    }

    /** {@code code=0} with the task in {@code data}, or {@code TASK_NOT_FOUND}. */
    @GetMapping("/task/{taskId}")
    public ApiResponse<TaskStatusView> get(@PathVariable String taskId) {
        return service.find(taskId)
                .map(record -> ApiResponse.ok(TaskStatusView.of(record, mapper)))
                .orElseGet(() -> ApiResponse.error(ApiCodes.TASK_NOT_FOUND,
                        "task not found: " + taskId));
    }

    /**
     * Cancel a queued or running task.
     *
     * <p>Returns the task as it now stands ({@code status=cancelled}) so the caller does not need
     * a follow-up read. Unknown id gives {@code TASK_NOT_FOUND}; a task that already finished
     * gives {@code TASK_NOT_CANCELABLE} naming its current state.
     */
    @DeleteMapping("/task/{taskId}")
    public ApiResponse<TaskStatusView> cancel(@PathVariable String taskId) {
        Optional<TaskRecord> existing = service.find(taskId);
        if (existing.isEmpty()) {
            return ApiResponse.error(ApiCodes.TASK_NOT_FOUND, "task not found: " + taskId);
        }
        if (!service.cancel(taskId)) {
            return ApiResponse.error(ApiCodes.TASK_NOT_CANCELABLE,
                    "task is already terminal, nothing to cancel (status="
                            + existing.get().getStatus().wire() + ")");
        }
        return ApiResponse.ok(service.find(taskId)
                .map(record -> TaskStatusView.of(record, mapper))
                .orElse(null));
    }

    /**
     * The tools currently registered for a task, as the model sees them ({@code name} +
     * {@code description}). Reflects the live {@code agent.tools.*} config; useful for operators
     * to confirm which capabilities (shell, file tools, media understand/extract, time) are on.
     */
    @GetMapping("/tools")
    public ApiResponse<List<Map<String, Object>>> tools() {
        return ApiResponse.ok(toolkitFactory.registeredTools());
    }

    /**
     * Installed skill names, sorted.
     *
     * <p>Deliberately not part of {@code /health}: this list changes when an operator drops a
     * SKILL.md into the skills directory, which says nothing about whether the service is
     * alive, and health gets polled far more often than skills get looked at.
     */
    @GetMapping("/skills")
    public ApiResponse<List<String>> skills() {
        return ApiResponse.ok(service.availableSkills());
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "active", service.activeCount(),
                "queued", service.queuedCount(),
                "maxConcurrent", props.getExecution().getMaxConcurrent(),
                "maxQueuedTasks", props.getExecution().getMaxQueuedTasks(),
                "workerId", service.workerId(),
                "model", props.getModel().getName(),
                "permissionMode", props.getRunner().getPermissionMode()));
    }
}

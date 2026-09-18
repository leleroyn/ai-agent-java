package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Gives every task a private working directory: {@code <agent.tools.working-dir>/<taskId>}.
 *
 * <p>Two reasons: tasks cannot read or overwrite each other's files, and a task's output is
 * easy to locate after the fact.
 *
 * <p><b>Security:</b> {@code taskId} is caller-supplied and now becomes a path segment. It is
 * therefore validated against a strict allowlist and re-checked after resolution, because
 * {@code taskId=../../somewhere} combined with the write tool would otherwise mean arbitrary
 * filesystem writes outside the sandbox. The file tools' own base-directory check still
 * applies on top of this.
 */
@Component
public class TaskWorkspaceFactory {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkspaceFactory.class);

    /**
     * First character must be alphanumeric, so a bare {@code ..} or a leading dot can never
     * form a traversal; separators are simply not in the set.
     */
    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    private final AgentProperties props;

    public TaskWorkspaceFactory(AgentProperties props) {
        this.props = props;
    }

    /** Format check usable at submit time, so bad ids are rejected before any work happens. */
    public static boolean isValidTaskId(String taskId) {
        return taskId != null && SAFE_TASK_ID.matcher(taskId).matches();
    }

    /** Sandbox root that all task directories live under. */
    public Path root() {
        return Path.of(props.getTools().getWorkingDir()).toAbsolutePath().normalize();
    }

    /**
     * Resolve and create this task's private directory.
     *
     * @return absolute path to {@code <root>/<taskId>}
     * @throws AgentException INVALID_REQUEST for a malformed or escaping id;
     *                        AGENT_EXECUTION_FAILED when the directory cannot be created
     */
    public Path create(String taskId) {
        if (!isValidTaskId(taskId)) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "taskId must match [A-Za-z0-9][A-Za-z0-9._+-]{0,63} and must not contain"
                            + " path separators", false);
        }
        Path root = root();
        Path dir = root.resolve(taskId).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            // Belt and braces: the allowlist above should make this unreachable.
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "taskId resolved outside the workspace root", false);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new AgentException(ErrorCodes.AGENT_EXECUTION_FAILED,
                    "cannot create task workspace '" + dir + "': " + e.getMessage(), true, e);
        }
        log.debug("task workspace ready: {}", dir);
        return dir;
    }

    /**
     * Remove a task directory when retention is disabled. Never recurses outside the root.
     *
     * @return true when something was deleted
     */
    public boolean delete(String taskId) {
        if (!isValidTaskId(taskId)) {
            return false;
        }
        Path root = root();
        Path dir = root.resolve(taskId).normalize();
        if (!dir.startsWith(root) || dir.equals(root) || !Files.exists(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("could not delete '{}'", path, e);
                }
            });
            log.debug("task workspace removed: {}", dir);
            return true;
        } catch (IOException e) {
            log.warn("could not clean task workspace '{}': {}", dir, e.getMessage());
            return false;
        }
    }
}

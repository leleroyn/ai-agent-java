package com.example.agent.agent;

import com.example.agent.agent.PdfService;
import com.example.agent.agent.tool.DocumentUnderstandTools;
import com.example.agent.agent.tool.ImageUnderstandTools;
import com.example.agent.agent.tool.SystemTimeTools;
import com.example.agent.config.AgentProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Assembles the tool set exposed to the agent, scoped to one task's private directory.
 *
 * <p>File tools are constructed with that directory as their base, and AgentScope enforces it
 * at execution time (observed rejecting a path outside the base), so a task cannot touch
 * another task's files or the host outside its own directory.
 *
 * <p>All built-in tools are enabled by configuration default. Combined with
 * {@code permission-mode: BYPASS} the shell is effectively remote command execution
 * <em>within that sandbox</em>. Set the {@code agent.tools.*} flags or a stricter permission
 * mode to harden.
 */
@Component
public class ToolkitFactory {

    private static final Logger log = LoggerFactory.getLogger(ToolkitFactory.class);

    private final AgentProperties props;
    private final SystemTimeTools systemTimeTools;
    private final ImageUnderstandTools imageUnderstandTools;
    private final PdfService pdfService;

    public ToolkitFactory(AgentProperties props, SystemTimeTools systemTimeTools,
                          ImageUnderstandTools imageUnderstandTools, PdfService pdfService) {
        this.props = props;
        this.systemTimeTools = systemTimeTools;
        this.imageUnderstandTools = imageUnderstandTools;
        this.pdfService = pdfService;
    }

    /**
     * @param taskDir this task's private directory, created by {@link TaskWorkspaceFactory};
     *                file tools are sandboxed to it
     */
    public Toolkit build(Path taskDir) {
        AgentProperties.Tools t = props.getTools();
        String base = taskDir.toAbsolutePath().normalize().toString();
        Toolkit toolkit = new Toolkit();
        List<String> registered = new ArrayList<>();

        if (t.isShell()) {
            // Three-arg form binds the shell's working directory to this task's sandbox.
            ShellCommandTool shell = new ShellCommandTool(base, new HashSet<>(t.getShellAllowedCommands()), null);
            for (String command : t.getShellAllowedCommands()) {
                if (command != null && !command.isBlank()) {
                    shell.addAllowedCommand(command.trim());
                }
            }
            register(toolkit, shell, "shell", registered);
        }
        if (t.isReadFile()) {
            register(toolkit, new ReadFileTool(base), "read-file", registered);
        }
        if (t.isWriteFile()) {
            register(toolkit, new WriteFileTool(base), "write-file", registered);
        }
        if (t.isTodo()) {
            register(toolkit, new TodoTools(), "todo", registered);
        }

        // 自定义工具：系统时间。默认全局启用——模型不知道现在是几点，任何相对时间判断
        // 都需要这个基准。要加自己的工具：仿 SystemTimeTools 写一个类，在这一段注册即可——
        // 无状态工具用 Spring 单例复用是安全的，需要每任务上下文（如任务目录）就每任务 new 一个。
        if (t.isSystemTime()) {
            register(toolkit, systemTimeTools, "system-time", registered);
        }

        // 自定义工具：图片理解。图片走独立的视觉模型（agent.vision），字节不进主模型上下文；
        // 这是“某些图不想经过主模型”这一需求的实现方式。无状态，单例复用安全。
        if (t.isImageUnderstand()) {
            register(toolkit, imageUnderstandTools, "image-understand", registered);
        }

        // 自定义工具：文档理解。针对大/多页扫描 PDF，服务端逐页栅格化后分批交给视觉模型。需要
        // 任务沙箱放下载与临时页图，故每任务 new 一个（非常规 Spring 单例），与文件工具同模式。
        if (t.isDocumentUnderstand()) {
            register(toolkit, new DocumentUnderstandTools(pdfService, taskDir), "document-understand", registered);
        }

        log.debug("toolkit assembled enabled={} taskDir={}", registered, base);
        return toolkit;
    }

    /**
     * Register one tool, retrying once and then degrading instead of failing the whole task.
     *
     * <p>AgentScope 2.0.3 builds parameter schemas with victools + Jackson 2.21. Introspecting
     * {@code TodoTools$TodoItem} (an explicit {@code @JsonCreator} type) can lose an internal
     * race when several workers assemble toolkits at the same time under the Spring Boot fat
     * jar, surfacing as
     * <em>"Conflicting property-based creators: already had explicit creator ... encountered
     * another"</em>. Measured: one task out of six died this way while its five siblings were
     * fine, and the same registration never failed in isolation. Once introspection is cached a
     * retry succeeds, so retry first; if it still fails, drop only that tool so the task can
     * run rather than reporting a spurious execution failure.
     */
    private void register(Toolkit toolkit, Object tool, String name, List<String> registered) {
        try {
            toolkit.registerTool(tool);
            registered.add(name);
        } catch (RuntimeException first) {
            try {
                toolkit.registerTool(tool);
                registered.add(name);
                log.warn("tool {} registered on retry after a schema race: {}",
                        name, rootMessage(first));
            } catch (RuntimeException second) {
                log.error("tool {} is unavailable after retry; continuing without it: {}",
                        name, rootMessage(second));
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}

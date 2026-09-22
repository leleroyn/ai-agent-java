package com.jtzj.agent.runtime;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.PdfService;
import com.jtzj.agent.core.service.VisionClient;
import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>Custom tools are data-driven.</b> Every custom tool lives in the {@code ai-agent-tools}
 * module and contributes an {@link AgentToolProvider} bean; the container injects the full
 * {@code List} and this factory simply iterates it. The factory therefore knows <em>nothing</em>
 * about which custom tools exist — <b>adding, removing or changing a custom tool never requires
 * editing this class</b>. A custom tool is registered unless {@code agent.tools.<provider-name>}
 * is explicitly {@code false} (default enabled), which keeps the existing per-tool config keys and
 * their {@code AGENT_TOOL_*} env-var overrides working unchanged.
 */
@Component
public class ToolkitFactory {

    private static final Logger log = LoggerFactory.getLogger(ToolkitFactory.class);

    private final AgentProperties props;
    private final VisionClient visionClient;
    private final PdfService pdfService;
    private final ObjectMapper mapper;
    private final Environment env;
    private final org.springframework.context.ApplicationContext context;
    private final List<AgentToolProvider> providers;

    public ToolkitFactory(AgentProperties props,
                          VisionClient visionClient,
                          PdfService pdfService,
                          @Qualifier("agentScopeObjectMapper") ObjectMapper mapper,
                          Environment env,
                          org.springframework.context.ApplicationContext context,
                          List<AgentToolProvider> providers) {
        this.props = props;
        this.visionClient = visionClient;
        this.pdfService = pdfService;
        this.mapper = mapper;
        this.env = env;
        this.context = context;
        // Stable, alphabetical registration order regardless of bean-discovery order.
        List<AgentToolProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparing(AgentToolProvider::name));
        this.providers = sorted;
    }

    /**
     * @param taskDir this task's private directory, created by {@link TaskWorkspaceFactory};
     *                file tools are sandboxed to it
     */
    public Toolkit build(Path taskDir) {
        return build(taskDir, true);
    }

    /**
     * @param includeBuiltins when {@code false}, skip the framework built-in tools (shell, read/write
     *                        file, list dir, todo) and register only this project's own tools. Used
     *                        by {@link #registeredTools()} so the endpoint reports the custom tools,
     *                        not the stock AgentScope ones. Custom tools are registered in both modes.
     */
    public Toolkit build(Path taskDir, boolean includeBuiltins) {
        AgentProperties.Tools t = props.getTools();
        String base = taskDir.toAbsolutePath().normalize().toString();
        Toolkit toolkit = new Toolkit();
        List<String> registered = new ArrayList<>();

        // Framework built-ins — these belong to the app, so their flags stay typed config here.
        if (includeBuiltins && t.isShell()) {
            // Three-arg form binds the shell's working directory to this task's sandbox.
            ShellCommandTool shell = new ShellCommandTool(base, new HashSet<>(t.getShellAllowedCommands()), null);
            for (String command : t.getShellAllowedCommands()) {
                if (command != null && !command.isBlank()) {
                    shell.addAllowedCommand(command.trim());
                }
            }
            register(toolkit, shell, "shell", registered);
        }
        if (includeBuiltins && t.isReadFile()) {
            register(toolkit, new ReadFileTool(base), "read-file", registered);
        }
        if (includeBuiltins && t.isWriteFile()) {
            register(toolkit, new WriteFileTool(base), "write-file", registered);
        }
        if (includeBuiltins && t.isTodo()) {
            register(toolkit, new TodoTools(), "todo", registered);
        }

        // Custom tools — contributed by the ai-agent-tools module, discovered via Spring. This loop
        // is tool-agnostic: it never references a concrete tool class, so new tools need no change
        // here. Each provider decides its own construction from the task-scoped ToolContext.
        ToolContext ctx = newToolContext(taskDir);
        for (AgentToolProvider provider : providers) {
            if (!toolEnabled(provider.name())) {
                continue;
            }
            register(toolkit, provider.createTool(ctx), provider.name(), registered);
        }

        log.debug("toolkit assembled enabled={} taskDir={}", registered, base);
        return toolkit;
    }

    /**
     * The <b>custom</b> tools this project registers, as the model sees them: each
     * {@code {name, description}} comes straight from the assembled {@link Toolkit} schemas, so
     * this reflects the live {@code agent.tools.*} config. Framework built-ins (shell / read-write
     * file / list dir / todo) are intentionally excluded.
     *
     * <p>Built against a throwaway probe directory (the configured working-dir root); tool names
     * and descriptions do not depend on the per-task sandbox, and constructing the tools only
     * stores that base path — nothing on disk is touched.
     */
    public List<Map<String, Object>> registeredTools() {
        Path probe = Path.of(props.getTools().getWorkingDir()).toAbsolutePath().normalize();
        // includeBuiltins=false：只列本项目自定义的工具，不含框架内置的 shell / 读写文件 / 列目录 / todo。
        Toolkit toolkit = build(probe, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSchema schema : toolkit.getToolSchemas()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", schema.getName());
            entry.put("description", schema.getDescription());
            out.add(entry);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    /**
     * Enable flag for a custom tool: read {@code agent.tools.<name>} from the environment
     * (relaxed binding keeps the existing kebab-case keys and their {@code AGENT_TOOL_*} env-var
     * placeholders working). Absent means enabled.
     */
    private boolean toolEnabled(String name) {
        Boolean v = env.getProperty("agent.tools." + name, Boolean.class);
        return v == null || v;
    }

    /** A {@link ToolContext} bound to one task, backed by the shared services and the bean container. */
    private ToolContext newToolContext(Path taskDir) {
        return new ToolContext() {
            @Override
            public Path taskDir() {
                return taskDir;
            }

            @Override
            public AgentProperties properties() {
                return props;
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public VisionClient vision() {
                return visionClient;
            }

            @Override
            public PdfService pdf() {
                return pdfService;
            }

            @Override
            public <T> T bean(Class<T> type) {
                return context.getBean(type);
            }
        };
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

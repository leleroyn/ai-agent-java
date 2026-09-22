package com.jtzj.agent.core.spi;

/**
 * Marker by which a custom tool contributes itself to the agent's {@link
 * io.agentscope.core.tool.Toolkit}. Each tool in the {@code ai-agent-tools} module ships one
 * Spring {@code @Component} implementation; the application collects them all (Spring injects
 * the {@code List}) and registers them per task, so <b>adding or changing a tool only ever
 * touches the tools module</b> — no edit to the toolkit assembly code is required.
 *
 * <p>The {@link #name()} doubles as the configuration key: the tool is registered unless
 * {@code agent.tools.<name>} is explicitly set to {@code false}. See the toolkit factory in the
 * application module for how the flag is read.
 */
public interface AgentToolProvider {

    /**
     * Unique tool identifier in kebab-case (e.g. {@code image-understand}). Used as the
     * enable-flag key {@code agent.tools.<name>} and for stable, alphabetical registration.
     */
    String name();

    /**
     * Build a tool instance for one task. Stateless tools may return a shared instance; tools that
     * need the per-task sandbox directory or other task-scoped state read it from {@code context}.
     *
     * @param context task-scoped dependencies (sandbox dir, shared services, properties)
     * @return an object carrying one or more {@code @Tool}-annotated methods
     */
    Object createTool(ToolContext context);
}

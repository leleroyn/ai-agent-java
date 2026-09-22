package com.jtzj.agent.core.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.PdfService;
import com.jtzj.agent.core.service.VisionClient;

import java.nio.file.Path;

/**
 * Task-scoped dependencies handed to every {@link AgentToolProvider} when it builds its tool.
 *
 * <p>Exposes the pieces custom tools commonly need — the per-task sandbox directory, application
 * properties, the shared JSON mapper, and the shared vision / PDF services — plus a generic
 * {@link #bean(Class)} escape hatch so a tool can obtain <em>any</em> Spring bean without the
 * toolkit assembly code having to know about it. The convenience accessors are shortcuts for the
 * most frequently used beans.
 */
public interface ToolContext {

    /** This task's private sandbox directory; local-file tools must confine themselves to it. */
    Path taskDir();

    /** Application properties (the {@code agent.*} config tree). */
    AgentProperties properties();

    /** The shared {@code agentScopeObjectMapper}. */
    ObjectMapper mapper();

    /** Shared vision-model client used by the image tools. */
    VisionClient vision();

    /** Shared PDF service used by the document tools. */
    PdfService pdf();

    /** Any Spring bean, by type — the extension point for tools needing a service not covered above. */
    <T> T bean(Class<T> type);
}

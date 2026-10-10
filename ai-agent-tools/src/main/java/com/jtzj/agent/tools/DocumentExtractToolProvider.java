package com.jtzj.agent.tools;

import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/** Contributes {@link DocumentExtractTools} (structured field extraction: image/PDF/TXT). */
@Component
public class DocumentExtractToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "document-extract";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new DocumentExtractTools(
                context.vision(), context.pdf(), context.text(),
                context.properties(), context.mapper(), context.taskDir());
    }
}

package com.jtzj.agent.tools;

import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/** Contributes {@link DocumentUnderstandTools} (free-form document Q&A: image/PDF/TXT). */
@Component
public class DocumentUnderstandToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "document-understand";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new DocumentUnderstandTools(
                context.vision(), context.pdf(), context.text(), context.properties(), context.taskDir());
    }
}

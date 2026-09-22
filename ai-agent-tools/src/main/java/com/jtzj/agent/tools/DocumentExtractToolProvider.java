package com.jtzj.agent.tools;

import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/** Contributes {@link DocumentExtractTools} (whole-document structured extraction with page provenance). */
@Component
public class DocumentExtractToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "document-extract";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new DocumentExtractTools(context.pdf(), context.taskDir());
    }
}

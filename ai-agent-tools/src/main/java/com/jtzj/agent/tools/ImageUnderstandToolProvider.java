package com.jtzj.agent.tools;

import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/** Contributes {@link ImageUnderstandTools} (free-form image Q&A via the vision model). */
@Component
public class ImageUnderstandToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "image-understand";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new ImageUnderstandTools(context.vision(), context.properties(), context.taskDir());
    }
}

package com.jtzj.agent.tools;

import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/** Contributes {@link ImageExtractTools} (structured field extraction from images). */
@Component
public class ImageExtractToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "image-extract";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new ImageExtractTools(
                context.vision(), context.properties(), context.mapper(), context.taskDir());
    }
}

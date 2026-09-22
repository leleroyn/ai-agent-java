package com.jtzj.agent.tools;

import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/** Contributes {@link SystemTimeTools} (stateless) to the agent toolkit. */
@Component
public class SystemTimeToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "system-time";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new SystemTimeTools();
    }
}

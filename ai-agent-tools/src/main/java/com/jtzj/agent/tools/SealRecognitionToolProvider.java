package com.jtzj.agent.tools;

import com.jtzj.agent.core.service.IpsClient;
import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Contributes {@link SealRecognitionTools} ({@code recognize_seal}).
 *
 * <p>Toggle: {@code agent.tools.seal-recognition} / {@code AGENT_TOOL_SEAL_RECOGNITION}.
 */
@Component
public class SealRecognitionToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "seal-recognition";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new SealRecognitionTools(context.bean(IpsClient.class), context.properties(),
                context.mapper(), context.taskDir());
    }
}
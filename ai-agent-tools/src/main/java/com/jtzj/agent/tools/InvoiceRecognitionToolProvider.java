package com.jtzj.agent.tools;

import com.jtzj.agent.core.service.IpsClient;
import com.jtzj.agent.core.spi.AgentToolProvider;
import com.jtzj.agent.core.spi.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Contributes {@link InvoiceRecognitionTools} ({@code recognize_invoice}).
 *
 * <p>Toggle: {@code agent.tools.invoice-recognition} / {@code AGENT_TOOL_INVOICE_RECOGNITION}.
 */
@Component
public class InvoiceRecognitionToolProvider implements AgentToolProvider {

    @Override
    public String name() {
        return "invoice-recognition";
    }

    @Override
    public Object createTool(ToolContext context) {
        return new InvoiceRecognitionTools(context.bean(IpsClient.class), context.properties(),
                context.mapper(), context.taskDir());
    }
}
package com.jtzj.agent.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * Records which tools one agent run actually invoked.
 *
 * <p>{@link AgentTaskRunner} uses this to tell a grounded answer from a fabricated one: when an
 * instruction points at a document but the run used no tool at all, the "answer" can only have
 * come from the model's own memory.
 *
 * <p>This is the {@code MiddlewareBase} form of the old {@code core.hook.Hook} API, which AgentScope
 * 2.0.4 deprecates for removal. {@code onActing} fires once per acting round with the tool calls
 * about to run, so a call is recorded even when the tool itself later returns an error result —
 * which is what the grounding guard needs.
 */
final class ToolUsageMiddleware implements MiddlewareBase {

    private final Set<String> invokedTools;

    ToolUsageMiddleware(Set<String> invokedTools) {
        this.invokedTools = invokedTools;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        for (ToolUseBlock use : input.toolCalls()) {
            if (use != null && use.getName() != null) {
                invokedTools.add(use.getName());
            }
        }
        return next.apply(input);
    }
}

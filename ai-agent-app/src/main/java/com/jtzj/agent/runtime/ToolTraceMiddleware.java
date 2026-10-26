package com.jtzj.agent.runtime;

import com.jtzj.agent.core.support.ToolTrace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Records the ordered tool-call chain of one agent run into {@link ToolTrace}.
 *
 * <p>{@code onActing} fires once per acting round with the tool calls about to run, so a call is
 * recorded even when the tool itself later returns an error result — which is what the grounding
 * guard in {@link AgentTaskRunner} needs: a document task that used no tool can only have been
 * answered from the model's own memory.
 *
 * <p>Durations and outcomes come from the event stream the acting phase emits: the matching
 * {@link ToolResultEndEvent} carries the tool call id and its final state. A stream that ends
 * without one (cancellation, timeout, error) closes the open calls explicitly, so the chain never
 * shows a tool that is still "running" after the task is terminal.
 *
 * <p>This is the {@code MiddlewareBase} form of the old {@code core.hook.Hook} API, which
 * AgentScope 2.0.4 deprecates for removal.
 */
final class ToolTraceMiddleware implements MiddlewareBase {

    private final ToolTrace trace;

    ToolTraceMiddleware(ToolTrace trace) {
        this.trace = trace;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        for (ToolUseBlock use : input.toolCalls()) {
            if (use != null && use.getName() != null) {
                trace.start(use.getId(), use.getName(), use.getInput());
            }
        }
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ToolResultEndEvent end) {
                        trace.finish(end.getToolCallId(),
                                end.getState() == null ? null : end.getState().getValue());
                    }
                })
                .doOnError(e -> trace.finishOpen("error"))
                .doOnCancel(() -> trace.finishOpen("interrupted"));
    }
}

package com.jtzj.agent.runtime;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Records which tools one agent run actually invoked.
 *
 * <p>{@link AgentTaskRunner} uses this to tell a grounded answer from a fabricated one: when an
 * instruction points at a document but the run used no tool at all, the "answer" can only have
 * come from the model's own memory.
 */
final class ToolUsageHook implements Hook {

    private final Set<String> invokedTools;

    ToolUsageHook(Set<String> invokedTools) {
        this.invokedTools = invokedTools;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting) {
            ToolUseBlock use = acting.getToolUse();
            if (use != null && use.getName() != null) {
                invokedTools.add(use.getName());
            }
        }
        return Mono.just(event);
    }
}
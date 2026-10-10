package com.jtzj.agent.tools;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;

/**
 * 工具返回值包装。
 *
 * <p>框架的 {@code DefaultToolResultConverter} 对任何返回值都再序列化一遍 JSON，只有
 * {@code ToolResultBlock} 会原样透传。工具之前直接返回 String，模型看到的就是
 * {@code "\"{\\"status\\":...}\""} ——整段 JSON 外面又套一层引号和转义，既费 token，
 * 读起来也不像权威结论。
 */
final class ToolResults {

    private ToolResults() {
    }

    static ToolResultBlock text(String content) {
        String safe = content == null ? "" : content;
        return ToolResultBlock.of(List.of(TextBlock.builder().text(safe).build()));
    }
}

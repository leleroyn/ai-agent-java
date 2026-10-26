package com.jtzj.agent.core.model;

/**
 * 一次工具调用 = {@code agent_tool_call} 表里的一行（记录逻辑见 core.support.ToolTrace）。
 *
 * <p>只记调用事实，不记返回值：OCR 全文动辄几万字符，落库没有意义，结果本身在 result_json /
 * result_text 里。
 *
 * @param seq        本次任务里的第几次调用，从 1 开始；与 task_id + attempt 组成唯一键
 * @param tool       工具名。走 shell 的 {@code ips} 子命令记的是 shell 工具本身
 * @param args       调用参数（JSON 文本，服务端按 agent.trace.max-args-chars 截断）；采集关闭时为 null
 * @param atMs       距本次任务开始的毫秒数，用来看调用在时间上的分布
 * @param durationMs 该次调用的耗时；调用没等到结果就中断时为 null
 * @param state      结果状态，取 {@code ToolResultState} 字面值：success / error / denied /
 *                   interrupted（流被取消）/ running（没有结果事件收尾）
 */
public record ToolCallRecord(int seq, String tool, String args, long atMs,
                             Long durationMs, String state) {
}

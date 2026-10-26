package com.jtzj.agent.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次任务内的<b>工具调用链</b>：模型按什么顺序调了哪些工具、参数是什么、每次跑了多久、结果如何。
 *
 * <p>动机：任务跑完只剩 resultText 和 token 数，出问题时回答不了「这条营业执照为什么漏了字段」
 * ——是模型根本没调工具，还是调了两次 OCR 第二次失败，还是走了 shell 里的 {@code ips} 子命令。
 * 调用链把那一段过程留下来，任务详情里直接可见。
 *
 * <p>与 {@link CallLedger} 的分工：台账管<b>去重</b>（同参数不重跑，只存在任务目录里）；这里管
 * <b>观测</b>：按顺序记，落库 {@code agent_task.tool_trace}，并出现在任务查询响应里。
 *
 * <p>只记调用事实，不记返回内容：OCR 全文动辄几万字符，落库没有意义，结果本身在 result_json /
 * result_text 里。参数按 {@code maxArgsChars} 截断、条数按 {@code maxEntries} 截断，因为一条 shell
 * 命令可以带很长的路径列表。count 记真实次数，所以截断后仍看得出「一共调了 12 次」。
 */
public final class ToolTrace {

    private static final Logger log = LoggerFactory.getLogger(ToolTrace.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次调用：start 时建立，工具结果事件到达时补上耗时与状态。 */
    private static final class Call {
        final int seq;
        final String tool;
        final String args;
        final long atMs;
        final long startedAtNanos;
        volatile Long durationMs;
        volatile String state;

        Call(int seq, String tool, String args, long atMs, long startedAtNanos) {
            this.seq = seq;
            this.tool = tool;
            this.args = args;
            this.atMs = atMs;
            this.startedAtNanos = startedAtNanos;
        }
    }

    private final boolean enabled;
    private final int maxEntries;
    private final int maxArgsChars;
    private final long startedAtNanos = System.nanoTime();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Set<String> seenTools = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Call> open = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    private ToolTrace(boolean enabled, int maxEntries, int maxArgsChars) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxArgsChars = Math.max(16, maxArgsChars);
    }

    /**
     * 关掉采集：链不落库也不上线（{@link #toJson()} 恒为 null，不占数据库列、响应里没有该键），
     * 但调用次数与工具名照记——{@link #isEmpty()} 是「文档任务必须调过工具」的判定依据，
     * 关掉采集时它必须照常工作，否则每条文档任务都会被强制重试甚至判失败。
     */
    public static ToolTrace of(boolean enabled, int maxEntries, int maxArgsChars) {
        return new ToolTrace(enabled, maxEntries, maxArgsChars);
    }

    /** 工具即将执行：callId 用来把后面的结果事件对回这一次调用。 */
    public void start(String callId, String tool, Map<String, Object> args) {
        if (tool == null) {
            return;
        }
        // 计数与工具名永远记（enabled 只决定参数和链 JSON 留不留）：接地判定要用它。
        Call call = new Call(total.incrementAndGet(), tool, enabled ? argsText(args) : null,
                (System.nanoTime() - startedAtNanos) / 1_000_000L, System.nanoTime());
        seenTools.add(tool);
        if (calls.size() < maxEntries) {
            calls.add(call);
        }
        if (callId != null) {
            open.put(callId, call);
        }
    }

    /** 工具结果事件到达。state 取 {@code ToolResultState} 的字面值（success / error / denied / ...）。 */
    public void finish(String callId, String state) {
        Call call = callId == null ? null : open.remove(callId);
        if (call == null) {
            return;
        }
        call.state = state == null ? "success" : state;
        call.durationMs = (System.nanoTime() - call.startedAtNanos) / 1_000_000L;
    }

    /** 没有结果事件收尾的调用（取消、流中断）：统一按 state 记掉，不留一条永远在跑的。 */
    public void finishOpen(String state) {
        for (String id : open.keySet()) {
            finish(id, state);
        }
    }

    /** 本任务真实发生的调用次数（含被截断没记进链的）。 */
    public int size() {
        return total.get();
    }

    /** 一次工具都没调过。给「文档任务必须调过工具」的判定用。 */
    public boolean isEmpty() {
        return total.get() == 0;
    }

    /** 去重后的工具名，按首次调用顺序。 */
    public Set<String> tools() {
        return new LinkedHashSet<>(seenTools);
    }

    /**
     * 落库与上线用的 JSON：{@code {"count":7,"truncated":false,"tools":[...],"calls":[{"seq":1,
     * "tool":"understand_document","args":"{...}","atMs":120,"ms":8300,"state":"success"}]}}。
     *
     * @return null when nothing was recorded
     */
    public String toJson() {
        int count = total.get();
        if (!enabled || count == 0) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("count", count);
        root.put("truncated", calls.size() < count);
        ArrayNode names = root.putArray("tools");
        tools().forEach(names::add);
        ArrayNode arr = root.putArray("calls");
        for (Call call : calls) {
            ObjectNode node = arr.addObject();
            node.put("seq", call.seq);
            node.put("tool", call.tool);
            if (call.args != null) {
                node.put("args", call.args);
            }
            node.put("atMs", call.atMs);
            if (call.durationMs != null) {
                node.put("ms", call.durationMs);
            }
            node.put("state", call.state == null ? "running" : call.state);
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("tool trace serialization failed: {}", e.toString());
            return null;
        }
    }

    private String argsText(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        String text;
        try {
            text = MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            text = String.valueOf(args);
        }
        if (text.length() <= maxArgsChars) {
            return text;
        }
        return text.substring(0, maxArgsChars) + "...(+" + (text.length() - maxArgsChars) + " chars)";
    }
}

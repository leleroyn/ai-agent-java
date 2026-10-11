package com.jtzj.agent.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次任务内的<b>工具调用台账</b>：同一工具 + 同一组参数只真跑一次，第二次直接返回上一次的返回。
 *
 * <p>动机：模型会在同一任务里把同一个来源反复喂给同一个工具（实测 {@code recognize_seal} 连调 4 次、
 * {@code understand_document} 连调 5 次），每次都是一次真实的 IPS / 模型调用，而结论不会变。
 *
 * <p>它<b>不是内容缓存</b>：台账就是任务目录里的 {@code .ocr/calls.json}，只存工具自己的结构化返回
 * （超过 {@value #MAX_RESULT_CHARS} 字符就不存），OCR 全文、图片字节、下载的文件都不进台账也不进内存；
 * 任务目录删掉台账就没了，跨任务也不复用。
 *
 * <p>只记成功：以 {@code Error:} 开头的返回不入台账，接口抖动时模型还能重试。
 */
public final class CallLedger {

    private static final Logger log = LoggerFactory.getLogger(CallLedger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object WRITE_LOCK = new Object();
    private static final String FILE = ".ocr/calls.json";
    private static final int MAX_RESULT_CHARS = 32_000;

    private final Path file;
    private final Map<String, Entry> entries;

    private record Entry(String tool, int count, String result) {
    }

    private CallLedger(Path file, Map<String, Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    /** 读取（或新建）本任务的台账。工具每次调用都重新 load，实例里不留状态。 */
    public static CallLedger load(Path taskDir) {
        Path file = taskDir.resolve(FILE);
        return new CallLedger(file, read(file));
    }

    /** 台账 key：工具名 + 归一化后的参数，取 SHA-256 前 16 位。 */
    public static String key(String tool, String... parts) {
        StringBuilder sb = new StringBuilder(tool);
        for (String part : parts) {
            sb.append('\n').append(part == null ? "" : part.trim());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /** 多来源参数归一化：拆开、去空白、排序，「A B」与「B A」得到同一个 key。 */
    public static String normalizeSources(String raw) {
        List<String> refs = new ArrayList<>(MediaInputs.parse(raw));
        refs.sort(String::compareTo);
        return String.join("\n", refs);
    }

    /** 同样参数的上一次返回；{@code null} = 没跑过，或上次没存结果。 */
    public String cached(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.result();
    }

    /** 该工具在本次任务里已真实执行的次数（含没存结果的调用）。 */
    public int toolCalls(String tool) {
        int total = 0;
        for (Entry entry : entries.values()) {
            if (entry.tool().equals(tool)) {
                total += entry.count();
            }
        }
        return total;
    }

    /** 记一次成功调用（结果可复用）。 */
    public void record(String key, String tool, String result) {
        record(key, tool, result, true);
    }

    /**
     * 记一次调用。写前重读磁盘再合并：工具是 concurrencySafe 的，多个工具共用同一份台账。
     *
     * <p>计数与存结果是两件事：失败也计数，否则模型对着一个一直失败的来源可以无限重试
     * （次数闸永远拦不住）；但失败不存结果，否则接口抖动一次就被固化成永久结论。
     */
    public void record(String key, String tool, String result, boolean store) {
        store = store && result != null && !result.startsWith("Error:")
                && result.length() <= MAX_RESULT_CHARS;
        Entry updated;
        synchronized (WRITE_LOCK) {
            Map<String, Entry> current = read(file);
            Entry previous = current.get(key);
            updated = new Entry(tool, previous == null ? 1 : previous.count() + 1,
                    store ? result : (previous == null ? null : previous.result()));
            current.put(key, updated);
            try {
                Files.createDirectories(file.getParent());
                ObjectNode root = MAPPER.createObjectNode();
                current.forEach((k, v) -> {
                    ObjectNode node = root.putObject(k);
                    node.put("tool", v.tool());
                    node.put("count", v.count());
                    if (v.result() != null) {
                        node.put("result", v.result());
                    }
                });
                Files.writeString(file, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("could not write call ledger {}: {}", file, e.toString());
            }
        }
        entries.put(key, updated);
    }

    private static Map<String, Entry> read(Path file) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(file)) {
                return entries;
            }
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            root.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (!value.isObject()) {
                    return;
                }
                JsonNode result = value.path("result");
                entries.put(field.getKey(), new Entry(
                        value.path("tool").asText(""),
                        value.path("count").asInt(0),
                        result.isTextual() ? result.asText() : null));
            });
        } catch (Exception e) {
            log.warn("call ledger {} unreadable, starting empty: {}", file, e.toString());
        }
        return entries;
    }
}

package com.jtzj.agent.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 视觉模型结构化输出的宽松解析：把 VL 可能夹带解释/代码围栏的返回文本，抠出第一段平衡的
 * {@code {..}} 或 {@code [..]}，解析成 hits（{@code field,value,pages}）。图片抽取忽略 pages。
 *
 * <p>图片与文档两个抽取工具共用，避免重复实现。解析失败返回 {@code null}，由调用方按批失败处理。
 */
public final class Extraction {

    private Extraction() {
    }

    /** 一个字段命中：字段名、值（可能 null）、命中的页码（图片抽取可空）。 */
    public record Hit(String field, String value, List<Integer> pages) {
    }

    /** 从文本里抠出第一段平衡的 JSON 对象/数组；没有返回 null。 */
    public static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int obj = text.indexOf('{');
        int arr = text.indexOf('[');
        int begin;
        char open, close;
        if (obj < 0 && arr < 0) {
            return null;
        }
        if (arr < 0 || (obj >= 0 && obj < arr)) {
            begin = obj;
            open = '{';
            close = '}';
        } else {
            begin = arr;
            open = '[';
            close = ']';
        }
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = begin; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(begin, i + 1);
                }
            }
        }
        return null;
    }

    /** 解析成 hits（{@code {\"hits\":[...]}} 或顶层数组）；失败返回 null。 */
    public static List<Hit> parseHits(ObjectMapper mapper, String text) {
        String json = extractJson(text);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode hits = root.isArray() ? root : root.path("hits");
            if (!hits.isArray()) {
                return null;
            }
            List<Hit> list = new ArrayList<>();
            for (JsonNode h : hits) {
                String field = textOrNull(h.path("field"));
                if (field == null || field.isBlank()) {
                    continue;
                }
                String value = textOrNull(h.path("value"));
                List<Integer> pages = new ArrayList<>();
                JsonNode p = h.path("pages");
                if (p.isArray()) {
                    for (JsonNode pn : p) {
                        if (pn.isNumber()) {
                            pages.add(pn.asInt());
                        } else if (pn.isTextual()) {
                            try {
                                pages.add(Integer.parseInt(pn.asText().trim()));
                            } catch (NumberFormatException ignored) {
                                // 忽略非数字页码
                            }
                        }
                    }
                } else if (p.isNumber()) {
                    pages.add(p.asInt());
                }
                list.add(new Hit(field.trim(), value, pages));
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    /**
     * 解析字段列表：按 ; ； , ， 、 换行 分割，去空去重，保持顺序。
     */
    public static List<String> parseFields(String raw) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[;；,，、\\n\\r]+")) {
                String f = part.trim();
                if (!f.isEmpty()) set.add(f);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 归并成最终契约。
     *
     * <p><b>每个请求字段恰好一条 {@code value}</b>：多个候选值时取权威值，其余进
     * {@code alternates}（带 units）。之前同名字段直接摊成多条互斥的 results，模型分不清
     * 哪条权威，只能再调别的工具自查（实测 72 页那份 {@code 甲方/乙方/合同金额} 各出现两次，
     * 其中一条还是印章 OCR 碎片）。抽取器自己补出来的、未被请求的字段进 {@code extra}，
     * 不与 {@code results} 混在一起。
     *
     * <p>权威值排序：非占位符优先 → 出现次数多优先 → 首次出现更早优先。占位符（{@code --}、
     * {@code 无}、{@code /} 这类）不会压过真实值，但文档里真的只有占位符时仍会原样返回。
     *
     * @param acc        字段 -&gt; (值 -&gt; 命中单元集合)
     * @param failedUnits 失败的块/来源序号
     * @param totalUnits  总块数/来源数
     */
    public static String merge(ObjectMapper mapper,
                               List<String> fields,
                               LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                               TreeSet<Integer> failedUnits,
                               int totalUnits) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("status", failedUnits == null || failedUnits.isEmpty() ? "complete" : "partial");
        root.put("total_units", totalUnits);

        ArrayNode results = root.putArray("results");
        for (String field : fields) {
            ObjectNode r = results.addObject();
            r.put("field", field);
            LinkedHashMap<String, TreeSet<Integer>> byValue = acc.get(field);
            if (byValue == null || byValue.isEmpty()) {
                r.putNull("value");
                r.putArray("units");
                continue;
            }
            List<Map.Entry<String, TreeSet<Integer>>> ranked = rank(byValue);
            Map.Entry<String, TreeSet<Integer>> top = ranked.get(0);
            r.put("value", top.getKey());
            addUnits(r.putArray("units"), top.getValue());
            if (ranked.size() > 1) {
                ArrayNode alternates = r.putArray("alternates");
                for (int i = 1; i < ranked.size(); i++) {
                    ObjectNode a = alternates.addObject();
                    a.put("value", ranked.get(i).getKey());
                    addUnits(a.putArray("units"), ranked.get(i).getValue());
                }
            }
        }

        ArrayNode extra = null;
        for (Map.Entry<String, LinkedHashMap<String, TreeSet<Integer>>> e : acc.entrySet()) {
            if (fields.contains(e.getKey())) {
                continue;
            }
            if (extra == null) {
                extra = root.putArray("extra");
            }
            for (Map.Entry<String, TreeSet<Integer>> ve : e.getValue().entrySet()) {
                ObjectNode o = extra.addObject();
                o.put("field", e.getKey());
                o.put("value", ve.getKey());
                addUnits(o.putArray("units"), ve.getValue());
            }
        }

        ArrayNode failed = root.putArray("failed_units");
        if (failedUnits != null) {
            for (int u : failedUnits) {
                failed.add(u);
            }
        }
        return mapper.writeValueAsString(root);
    }

    /** 权威值排序：非占位符优先，再按出现次数、首次出现位置。 */
    private static List<Map.Entry<String, TreeSet<Integer>>> rank(
            LinkedHashMap<String, TreeSet<Integer>> byValue) {
        List<Map.Entry<String, TreeSet<Integer>>> list = new ArrayList<>(byValue.entrySet());
        list.sort(Comparator
                .comparing((Map.Entry<String, TreeSet<Integer>> e) -> isPlaceholder(e.getKey()))
                .thenComparing(e -> -e.getValue().size())
                .thenComparing(e -> e.getValue().isEmpty() ? Integer.MAX_VALUE : e.getValue().first()));
        return list;
    }

    /** 只有标点/占位词的值：不能当权威值，但也不能凭空丢掉。 */
    private static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return true;
        }
        if (PLACEHOLDER_WORDS.contains(s)) {
            return true;
        }
        return s.replaceAll("[\\s\\-—–_/·.、，,()（）\\[\\]]", "").isEmpty();
    }

    private static final Set<String> PLACEHOLDER_WORDS = Set.of(
            "无", "空", "未知", "待定", "略", "不适用", "n/a", "N/A", "na", "null");

    private static void addUnits(ArrayNode units, TreeSet<Integer> values) {
        for (int u : values) {
            units.add(u);
        }
    }
}

package com.example.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

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
}

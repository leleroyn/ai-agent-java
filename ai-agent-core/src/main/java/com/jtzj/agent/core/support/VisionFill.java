package com.jtzj.agent.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jtzj.agent.core.service.VisionClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽取结果的<b>图像视觉复核补漏</b>：文本通道（OCR → 分块 → 文本模型）没取到的字段，
 * 拿原始图像再问一次视觉模型。
 *
 * <p><b>为什么收在抽取工具内部</b>：主模型侧的契约是「抽取调一次即终局，禁止用
 * understand_document 补 null 字段」（sys-prompt 硬约束）。补漏放进工具里，模型行为零变化，
 * 也不会开出「模型自己去调理解工具补字段」那条重试循环——那正是当初禁掉它的原因。
 *
 * <p><b>单向补 null，绝不覆盖文本通道已取到的值</b>：视觉模型读长数字串不可靠（实测把 18 位
 * 统一社会信用代码读丢一位，commit 5e52db4 当初正是为此删掉抽取的视觉/OCR 双通道回退）。
 * 所以文本通道有值就以它为准，视觉的冲突候选只进该字段的 {@code alternates}；补出的值标
 * {@code from=vision}，{@code units} 留空（证据在图像上，不在文本块里）。
 *
 * <p><b>只在 status=complete 时跑</b>：partial（OCR 缺页）时缺的是「整页没换成文本」，
 * 这时对着整份图像补，会把缺页里根本没有的字段补成假阳性。
 *
 * <p>图片按 {@code agent.vision.max-images} 分批，各批独立问、批间先到先得（同字段先答为准）；
 * 单批失败不影响其余批，全部批都没跑成才在结果里写 {@code vision_note} 说明补漏没执行。
 */
public final class VisionFill {

    private static final Logger log = LoggerFactory.getLogger(VisionFill.class);

    private VisionFill() {
    }

    /** 一次视觉复核的产出：字段 -&gt; 值（只留非空）、没跑成的批、真正跑成的批数。 */
    private record Pass(Map<String, String> values, List<String> failures, int batches) {
    }

    /**
     * 对抽取结果补漏。任何一步不满足条件就原样返回入参 json，不改动结论。
     *
     * @param maxImages {@code agent.vision.max-images}，单批图数上限
     * @param imageRefs 本次抽取里的图片来源（PDF/TXT 不在里面：它们走文本通道，不补）
     */
    public static String fill(ObjectMapper mapper, VisionClient vision, int maxImages,
                              String json, List<String> imageRefs, Path taskDir) {
        if (vision == null || imageRefs == null || imageRefs.isEmpty()) {
            return json;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!(node instanceof ObjectNode root)) {
                return json;
            }
            List<String> nullFields = nullFields(root);
            if (nullFields == null || nullFields.isEmpty()) {
                return json;
            }
            long started = System.currentTimeMillis();
            Pass pass = ask(mapper, vision, maxImages, imageRefs, nullFields, taskDir);
            int[] applied = apply(root, pass.values());
            int filled = applied[0], conflicts = applied[1], remaining = applied[2];
            if (pass.batches() == 0) {
                root.put("vision_note", "图像视觉复核未执行（" + String.join(" | ", pass.failures())
                        + "），以下结论只来自 OCR 文本通道。");
            } else {
                StringBuilder note = new StringBuilder("已对图像做视觉复核（")
                        .append(pass.batches()).append(" 批 / 共 ").append(imageRefs.size())
                        .append(" 张图）：补出 ").append(filled)
                        .append(" 个原为 null 的字段，这些值来自图像、标 from=vision 且 units 为空；")
                        .append("文本通道已取到的值一律未被覆盖，视觉的冲突候选只进该字段的 alternates（")
                        .append(conflicts).append(" 条）。");
                if (!pass.failures().isEmpty()) {
                    note.append("部分批未跑成：").append(String.join(" | ", pass.failures())).append("。");
                }
                root.put("vision_note", note.toString());
                if (remaining > 0) {
                    root.put("null_note", "value=null 表示 OCR 文本通道已遍历全文全部文本块、并按语义匹配过别名，"
                            + "图像视觉复核也已看过原始图像，两条通道都没出现该字段，"
                            + "这就是终局结论：不要再抽取、不要换字段名重试、不要用 understand_document 补这个字段。");
                }
            }
            log.info("vision fill images={} batches={} null_fields={} filled={} conflicts={} "
                            + "remaining={} ms={} failures={}",
                    imageRefs.size(), pass.batches(), nullFields.size(), filled, conflicts,
                    remaining, System.currentTimeMillis() - started, pass.failures().size());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("vision fill skipped, keep text-channel result: {}", e.toString());
            return json;
        }
    }

    /**
     * 需要补的字段。返回 null 表示本次不补：结果不是 complete（OCR 缺页时补会出假阳性），
     * 或没有 results 数组。
     */
    private static List<String> nullFields(ObjectNode root) {
        if (!"complete".equals(root.path("status").asText())) {
            return null;
        }
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        for (JsonNode r : results) {
            JsonNode value = r.path("value");
            if (value.isMissingNode() || value.isNull()) {
                String field = textOrNull(r.path("field"));
                if (field != null && !field.isBlank()) {
                    fields.add(field.trim());
                }
            }
        }
        return fields;
    }

    /** 逐批把图像送到视觉端点，汇总成 字段 -&gt; 值（同字段先答为准，不覆盖）。 */
    private static Pass ask(ObjectMapper mapper, VisionClient vision, int maxImages,
                            List<String> imageRefs, List<String> fields, Path taskDir) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int max = Math.max(1, maxImages);
        int batches = 0;
        for (int i = 0; i < imageRefs.size(); i += max) {
            List<String> batch = imageRefs.subList(i, Math.min(imageRefs.size(), i + max));
            MediaInputs.Result resolved = MediaInputs.resolveAsDataUrls(vision, taskDir, batch, max);
            if (!resolved.ok()) {
                failures.add(batch + " -> " + resolved.error());
                continue;
            }
            String out = vision.extractFromDataUrls(resolved.dataUrls(), prompt(fields));
            if (out.startsWith("Error:")) {
                failures.add(batch + " -> " + out);
                continue;
            }
            List<Extraction.Hit> hits = Extraction.parseHits(mapper, out);
            if (hits == null) {
                failures.add(batch + " -> 视觉返回无法解析成 hits JSON");
                continue;
            }
            batches++;
            for (Extraction.Hit hit : hits) {
                if (hit.field() == null || hit.field().isBlank()
                        || hit.value() == null || hit.value().isBlank()) {
                    continue;
                }
                values.putIfAbsent(hit.field().trim(), hit.value().trim());
            }
        }
        return new Pass(values, failures, batches);
    }

    /**
     * 灌回结果：只填 null 槽位。已有值的字段一律不动，视觉值与之不一致时只进 alternates。
     *
     * @return {补出数, 冲突进 alternates 的条数, 仍为 null 的字段数}
     */
    private static int[] apply(ObjectNode root, Map<String, String> values) {
        int filled = 0, conflicts = 0, remaining = 0;
        for (JsonNode rn : root.path("results")) {
            if (!(rn instanceof ObjectNode r)) {
                continue;
            }
            String field = textOrNull(r.path("field"));
            if (field == null) {
                continue;
            }
            JsonNode current = r.path("value");
            String candidate = values.get(field.trim());
            if (current.isMissingNode() || current.isNull()) {
                if (candidate != null) {
                    r.put("value", candidate);
                    r.put("from", "vision");
                    filled++;
                } else {
                    remaining++;
                }
                continue;
            }
            if (candidate != null && !compatible(current.asText(), candidate)) {
                JsonNode existing = r.get("alternates");
                ArrayNode alternates = existing instanceof ArrayNode a
                        ? a : r.putArray("alternates");
                boolean seen = false;
                for (JsonNode alt : alternates) {
                    if (candidate.equals(textOrNull(alt.path("value")))) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    ObjectNode alt = alternates.addObject();
                    alt.put("value", candidate);
                    alt.putArray("units");
                    alt.put("from", "vision");
                    conflicts++;
                }
            }
        }
        return new int[]{filled, conflicts, remaining};
    }

    /** 视觉值与文本通道值算不算同一结论：完全相等，或一方去掉空白后是另一方去掉空白的子串。 */
    private static boolean compatible(String textValue, String visionValue) {
        if (textValue.equals(visionValue)) {
            return true;
        }
        String a = textValue.replaceAll("\\s", "");
        String b = visionValue.replaceAll("\\s", "");
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static String prompt(List<String> fields) {
        return "下面这些字段在文档的 OCR 文本里没能取到值，请你直接看原始图像逐个核对。\n"
                + "字段：" + String.join("、", fields) + "\n"
                + "要求：\n"
                + "- 只输出一个 JSON 对象，不要解释、不要 Markdown 代码围栏："
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值\"}]}\n"
                + "- value 逐字符照抄图像上出现的原文：不改名、不翻译、不补位、不去空格、"
                + "不加千分位、不做单位换算、不推断。\n"
                + "- 图像上确实没有的字段：显式写 \"value\":null，不要猜、不要用近似值凑。\n"
                + "- 只输出上面列出的字段，不要输出别的字段。\n"
                + "- 多张图时逐张看完，取图像上最清晰的那一处。";
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}

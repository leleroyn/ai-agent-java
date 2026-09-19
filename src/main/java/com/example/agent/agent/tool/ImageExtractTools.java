package com.example.agent.agent.tool;

import com.example.agent.agent.Extraction;
import com.example.agent.agent.MediaInputs;
import com.example.agent.agent.VisionClient;
import com.example.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 图片<b>字段抽取</b>工具 {@code extract_image_fields}：从一张/多张图里结构化抽出指定字段，
 * 返回 {@code [{field, value}]}。每个请求字段都会出现（抽不到 {@code value:null}），召回可控。
 *
 * <p>与 {@code understand_image}（自由问答）分工：要具体字段值→本工具；要理解/描述/对比→understand_image。
 * 图片来源同 understand_image（URL 或沙箱本地路径，可混用），图片字节只进视觉模型。每任务 new。
 */
public class ImageExtractTools {

    private final VisionClient visionClient;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;

    public ImageExtractTools(VisionClient visionClient, AgentProperties props,
                             ObjectMapper mapper, Path taskDir) {
        this.visionClient = visionClient;
        this.props = props;
        this.mapper = mapper;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "extract_image_fields",
            description = "从图片里【结构化抽取】指定字段，返回 {field,value} 列表（每个请求字段都会出现，抽不到为 null）。"
                    + "当你要的是图片里若干具体字段的值（如发票号、金额、税额、日期、姓名、编号等）时用本工具，比自由问答更可靠、防漏。"
                    + "传入：图片来源(image_urls，http/https URL 或本任务工作目录下的本地图片路径，多个用空格/换行分隔，可混用) "
                    + "+ 要抽取的字段(fields，多个用 ; 逗号 、 或换行分隔)。"
                    + "如果只是要理解/描述/对比图片，改用 understand_image。"
                    + "图片字节只进视觉模型、不进你的上下文。【防循环】对同一组图最多调用一次，第一次返回即权威。",
            readOnly = true,
            concurrencySafe = true)
    public String extractImageFields(
            @ToolParam(
                    name = "image_urls",
                    description = "图片来源，一个或多个，空格或换行分隔。每项是 http/https URL 或本任务工作目录下的本地图片文件路径（可混用）。",
                    required = true)
            String imageRefs,
            @ToolParam(
                    name = "fields",
                    description = "要抽取的字段，多个用分号 ; 逗号 顿号或换行分隔，例如“发票代码;发票号码;金额;开票日期”。",
                    required = true)
            String fieldsRaw) {

        List<String> fields = parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请指定要抽取的字段（多个用 ; 逗号 、 或换行分隔）。";
        }
        MediaInputs.Result res = MediaInputs.resolveAsDataUrls(
                visionClient, taskDir, MediaInputs.parse(imageRefs), props.getVision().getMaxImages());
        if (!res.ok()) {
            return res.error();
        }
        String answer = visionClient.extractFromDataUrls(res.dataUrls(), buildPrompt(fields));
        if (answer.startsWith("Error:")) {
            return answer;
        }
        List<Extraction.Hit> hits = Extraction.parseHits(mapper, answer);
        if (hits == null) {
            return "Error: 视觉模型未返回可解析的抽取结果，可换一种 fields 写法或改用 understand_image。";
        }
        // 每个字段可能多值：合并去重后按请求顺序输出。
        LinkedHashMap<String, LinkedHashSet<String>> byField = new LinkedHashMap<>();
        for (Extraction.Hit h : hits) {
            if (h.value() == null || h.value().isBlank()) {
                continue;
            }
            byField.computeIfAbsent(h.field(), x -> new LinkedHashSet<>()).add(h.value().trim());
        }
        try {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode results = root.putArray("results");
            for (String field : fields) {
                LinkedHashSet<String> vals = byField.get(field);
                ObjectNode r = results.addObject();
                r.put("field", field);
                if (vals == null || vals.isEmpty()) {
                    r.putNull("value");
                } else {
                    r.put("value", String.join(" | ", vals));
                }
            }
            // 模型多抽的未请求字段也附上。
            for (var e : byField.entrySet()) {
                boolean requested = fields.stream().anyMatch(f -> f.equals(e.getKey()));
                if (requested) {
                    continue;
                }
                ObjectNode r = results.addObject();
                r.put("field", e.getKey());
                r.put("value", String.join(" | ", e.getValue()));
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "Error: 生成结果失败：" + e.getMessage();
        }
    }

    private String buildPrompt(List<String> fields) {
        return "只依据这些图片内容，为下面每个字段抽出它的值。图中确实没有的字段，value 用 null，不要编造。"
                + "只输出一个 JSON 对象，不要解释、不要代码围栏。字段：\n"
                + String.join("\n", fields) + "\n\n输出格式（严格）：\n"
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值或null\"}]}";
    }

    private static List<String> parseFields(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[;；,，、\\n\\r]+")) {
                String f = part.trim();
                if (!f.isEmpty()) {
                    set.add(f);
                }
            }
        }
        return new ArrayList<>(set);
    }
}

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片<b>字段抽取</b>工具 {@code extract_image_fields}：从一张/多张图里结构化抽出指定字段。
 *
 * <p><b>多图 = 每图一条记录</b>。服务端对每张图<b>单独</b>调一次视觉模型（单图提示词），并发跑，
 * 再<b>确定性合并</b>成 {@code records:[{image, 字段:值, ...}, ...]}。这样：
 * <ul>
 *   <li>逐图隔离，不会把 A 票的值串到 B 票（召回率最高，正对批量抽取需求）；</li>
 *   <li>每条 prompt 只有 1 张图，上下文小，绕开弱 VL 端点的 {@code n_ctx} 截断。</li>
 * </ul>
 * 单图统一为数组长度 1，主模型无需分支判断返回结构。每条记录里每个请求字段都会出现（抽不到 {@code null}）；
 * 某张图调用失败则该 record 带 {@code error}（不静默丢）。图片来源同 understand_image（URL 或沙箱本地路径，
 * 可混用），图片字节只进视觉模型。每任务 new。
 */
public class ImageExtractTools {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractTools.class);

    /** 逐图并行度上限（图数受 vision.max-images 约束，这里再兜一个上限）。 */
    private static final int MAX_CONCURRENCY = 8;

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
            description = "从图片里【结构化抽取】指定字段。多图时【每张图返回一条记录】，返回 "
                    + "{records:[{image, 字段:值, ...}, ...]}（image=第几张图，按传入顺序；单图即数组长度 1），"
                    + "每条记录里每个请求字段都会出现，抽不到为 null。当你要的是若干张图里具体字段的值"
                    + "（如多张发票的发票号/金额/税额/日期/购销方名称）时用本工具，比自由问答更可靠、防漏、不混票。"
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
        List<String> dataUrls = res.dataUrls();
        int n = dataUrls.size();
        log.info("image extract fields images={} fields={}", n, fields.size());

        // 逐图并行：每张图单独一次 VL（单图提示词），再按传入顺序确定性合并成 per-image records。
        int pool = Math.max(1, Math.min(n, MAX_CONCURRENCY));
        ExecutorService exec = Executors.newFixedThreadPool(pool);
        List<Future<ImgOut>> futures = new ArrayList<>();
        try {
            for (String du : dataUrls) {
                futures.add(exec.submit(() -> extractOne(du, fields)));
            }
            ObjectNode root = mapper.createObjectNode();
            ArrayNode records = root.putArray("records");
            int failed = 0;
            for (int i = 0; i < n; i++) {
                ObjectNode rec = records.addObject();
                rec.put("image", i + 1);
                ImgOut out;
                try {
                    out = futures.get(i).get();
                } catch (Exception e) {
                    out = ImgOut.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                if (out.error != null) {
                    failed++;
                    rec.put("error", out.error);
                    for (String f : fields) {
                        rec.putNull(f);
                    }
                    continue;
                }
                // 按请求字段顺序填值（缺失=null），再附该图多抽出的未请求字段，避免丢信息。
                for (String f : fields) {
                    String v = out.values.get(f);
                    if (v == null || v.isBlank()) {
                        rec.putNull(f);
                    } else {
                        rec.put(f, v);
                    }
                }
                for (var e : out.values.entrySet()) {
                    if (!fields.contains(e.getKey())) {
                        rec.put(e.getKey(), e.getValue());
                    }
                }
            }
            if (failed > 0) {
                root.put("note", failed + " 张图抽取失败（见对应 record 的 error）；如需可逐张单独重试。");
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("image extract failed", e);
            return "Error: 生成结果失败：" + e.getMessage();
        } finally {
            exec.shutdownNow();
        }
    }

    /** 单张图的抽取：一次 VL 调用 → 该图的字段→值映射；失败走 {@link ImgOut#error}。 */
    private ImgOut extractOne(String dataUrl, List<String> fields) {
        String answer = visionClient.extractFromDataUrls(List.of(dataUrl), buildPrompt(fields));
        if (answer.startsWith("Error:")) {
            return ImgOut.fail(answer);
        }
        List<Extraction.Hit> hits = Extraction.parseHits(mapper, answer);
        if (hits == null) {
            return ImgOut.fail("视觉模型未返回可解析的抽取结果");
        }
        LinkedHashMap<String, String> vals = new LinkedHashMap<>();
        for (Extraction.Hit h : hits) {
            if (h.value() == null || h.value().isBlank()) {
                continue;
            }
            // 同一张图里同字段出现多个值：合并，不丢。
            vals.merge(h.field(), h.value().trim(), (a, b) -> a + " | " + b);
        }
        return ImgOut.ok(vals);
    }

    /** 单图抽取提示：严格输出一个 JSON 对象（一张图对应一组字段值）。 */
    private String buildPrompt(List<String> fields) {
        return "只依据这张图片内容，为下面每个字段抽出它的值。图中确实没有的字段，value 用 null，不要编造。"
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

    /** 单图抽取结果：字段→值（同字段多值已用 " | " 合并）；或 error。 */
    private static final class ImgOut {
        final LinkedHashMap<String, String> values;
        final String error;

        private ImgOut(LinkedHashMap<String, String> values, String error) {
            this.values = values;
            this.error = error;
        }

        static ImgOut ok(LinkedHashMap<String, String> v) {
            return new ImgOut(v, null);
        }

        static ImgOut fail(String e) {
            return new ImgOut(new LinkedHashMap<>(), e);
        }
    }
}

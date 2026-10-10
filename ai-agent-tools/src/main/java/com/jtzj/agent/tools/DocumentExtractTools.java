package com.jtzj.agent.tools;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.PdfService;
import com.jtzj.agent.core.service.TextService;
import com.jtzj.agent.core.service.VisionClient;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.MediaInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文档<b>字段抽取</b>工具 {@code extract_document_fields}：支持图片、PDF、TXT。
 * 根据文件扩展名自动路由到对应管线。
 *
 * <ul>
 *   <li>图片：逐图并行调视觉模型，返回 {@code records:[{image, field: value, ...}]}。</li>
 *   <li>PDF：服务端遍历整篇，分批并发，返回 {@code results:[{field, value, pages}]}。</li>
 *   <li>TXT：分块并发，返回 {@code results:[{field, value, pages}]}（pages=段号）。</li>
 * </ul>
 *
 * <p>混合类型时分别处理各类型，结果合并到统一 JSON 对象（{@code images}/{@code pdf}/{@code txt} 子节点）。
 */
public class DocumentExtractTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractTools.class);

    private final VisionClient visionClient;
    private final PdfService pdfService;
    private final TextService textService;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;

    public DocumentExtractTools(VisionClient visionClient, PdfService pdfService,
                                TextService textService, AgentProperties props,
                                ObjectMapper mapper, Path taskDir) {
        this.visionClient = visionClient;
        this.pdfService = pdfService;
        this.textService = textService;
        this.props = props;
        this.mapper = mapper;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "extract_document_fields",
            description = "文档结构化字段抽取。支持图片（jpg/png/webp等）、PDF、TXT。"
                    + "用于从文档中提取指定字段的值（如发票号、金额、日期、合同条款等）。"
                    + "自动遍历整篇（无需手动分页），每个请求字段都会出现在结果里（抽不到 value=null）。"
                    + "返回 {status, total_units, results:[{field, value, units}], failed_units}。"
                    + "units: 图片=图序号, PDF=页码, TXT=段号。",
            readOnly = true,
            concurrencySafe = true)
    public String extractFields(
            @ToolParam(
                    name = "source",
                    description = "文档来源，一个或多个，空格或换行分隔。每项是 http/https URL 或本任务工作目录下的本地文件路径（可混用）。"
                            + "按扩展名判定类型：图片(.jpg/.png/.webp/.gif/.bmp)、PDF(.pdf)、TXT(.txt)。",
                    required = true)
            String source,
            @ToolParam(
                    name = "fields",
                    description = "要抽取的字段，多个用分号 ; 逗号 顿号或换行分隔，例如\"发票代码;发票号码;金额;开票日期\"。",
                    required = true)
            String fieldsRaw,
            @ToolParam(
                    name = "page_range",
                    description = "可选，仅对 PDF 有效：\"a-b\"、\"a\"、\"a-\"。留空=遍历整篇。",
                    required = false)
            String pageRange) {

        List<String> fields = Extraction.parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请指定要抽取的字段（多个用 ; 逗号 、 或换行分隔）。";
        }
        List<String> refs = MediaInputs.parse(source);
        if (refs.isEmpty()) {
            return "Error: 未提供任何文档来源。";
        }

        List<String> images = new ArrayList<>();
        List<String> pdfs = new ArrayList<>();
        List<String> txts = new ArrayList<>();
        for (String ref : refs) {
            String ext = MediaInputs.extension(ref);
            if (MediaInputs.IMAGE_EXTS.contains(ext)) {
                images.add(ref);
            } else if (".pdf".equals(ext)) {
                pdfs.add(ref);
            } else if (".txt".equals(ext)) {
                txts.add(ref);
            } else {
                images.add(ref);
            }
        }

        // 纯图片
        if (!images.isEmpty() && pdfs.isEmpty() && txts.isEmpty()) {
            return extractImages(images, fields);
        }
        // 纯 PDF（单个）
        if (images.isEmpty() && pdfs.size() == 1 && txts.isEmpty()) {
            return pdfService.extractFields(pdfs.get(0), fieldsRaw, pageRange, taskDir);
        }
        // 纯 TXT（单个）
        if (images.isEmpty() && pdfs.isEmpty() && txts.size() == 1) {
            return textService.extractFields(txts.get(0), fieldsRaw, taskDir);
        }

        // 混合类型：分别处理，合并为结构化 JSON
        ObjectNode root = mapper.createObjectNode();
        if (!images.isEmpty()) {
            String imgJson = extractImages(images, fields);
            JsonNode parsed = tryParse(imgJson);
            if (parsed != null) root.set("images", parsed);
            else root.put("images", imgJson);
        }
        for (String pdf : pdfs) {
            String pdfJson = pdfService.extractFields(pdf, fieldsRaw, pageRange, taskDir);
            JsonNode parsed = tryParse(pdfJson);
            if (parsed != null) root.set("pdf", parsed);
            else root.put("pdf", pdfJson);
        }
        for (String txt : txts) {
            String txtJson = textService.extractFields(txt, fieldsRaw, taskDir);
            JsonNode parsed = tryParse(txtJson);
            if (parsed != null) root.set("txt", parsed);
            else root.put("txt", txtJson);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "Error: 合并结果失败：" + e.getMessage();
        }
    }

    // ---- 图片抽取（逐图并行） ----

    private String extractImages(List<String> imageRefs, List<String> fields) {
        MediaInputs.Result res = MediaInputs.resolveAsDataUrls(
                visionClient, taskDir, imageRefs, props.getVision().getMaxImages());
        if (!res.ok()) {
            return res.error();
        }
        List<String> dataUrls = res.dataUrls();
        int n = dataUrls.size();
        log.info("doc extract images={} fields={}", n, fields.size());

        int pool = Math.max(1, Math.min(n, props.getPdfs().getConcurrency()));
        ExecutorService exec = Executors.newFixedThreadPool(pool);
        List<Future<ImgOut>> futures = new ArrayList<>();
        try {
            for (String du : dataUrls) {
                futures.add(exec.submit(() -> extractOneImage(du, fields)));
            }
            // 统一为 results:[{field, value, units}] 格式
            LinkedHashMap<String, LinkedHashMap<String, java.util.TreeSet<Integer>>> acc = new LinkedHashMap<>();
            java.util.TreeSet<Integer> failedUnits = new java.util.TreeSet<>();
            for (int i = 0; i < n; i++) {
                ImgOut out;
                try {
                    out = futures.get(i).get();
                } catch (Exception e) {
                    out = ImgOut.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                if (out.error != null) {
                    failedUnits.add(i + 1);
                    continue;
                }
                for (var e : out.values.entrySet()) {
                    acc.computeIfAbsent(e.getKey(), x -> new LinkedHashMap<>())
                            .computeIfAbsent(e.getValue(), x -> new java.util.TreeSet<>())
                            .add(i + 1);
                }
            }
            ObjectNode root = mapper.createObjectNode();
            root.put("status", failedUnits.isEmpty() ? "complete" : "partial");
            root.put("total_units", n);
            ArrayNode results = root.putArray("results");
            java.util.LinkedHashSet<String> emitted = new java.util.LinkedHashSet<>();
            for (String field : fields) {
                emitted.add(field);
                LinkedHashMap<String, java.util.TreeSet<Integer>> byValue = acc.get(field);
                if (byValue == null || byValue.isEmpty()) {
                    ObjectNode r = results.addObject();
                    r.put("field", field);
                    r.putNull("value");
                    r.putArray("units");
                    continue;
                }
                for (var e : byValue.entrySet()) {
                    ObjectNode r = results.addObject();
                    r.put("field", field);
                    r.put("value", e.getKey());
                    ArrayNode units = r.putArray("units");
                    for (int u : e.getValue()) units.add(u);
                }
            }
            for (var e : acc.entrySet()) {
                if (emitted.contains(e.getKey())) continue;
                for (var ve : e.getValue().entrySet()) {
                    ObjectNode r = results.addObject();
                    r.put("field", e.getKey());
                    r.put("value", ve.getKey());
                    ArrayNode units = r.putArray("units");
                    for (int u : ve.getValue()) units.add(u);
                }
            }
            ArrayNode failed = root.putArray("failed_units");
            for (int u : failedUnits) failed.add(u);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("image extract failed", e);
            return "Error: 生成结果失败：" + e.getMessage();
        } finally {
            exec.shutdownNow();
        }
    }

    private ImgOut extractOneImage(String dataUrl, List<String> fields) {
        String answer = visionClient.extractFromDataUrls(List.of(dataUrl), buildImagePrompt(fields));
        if (answer.startsWith("Error:")) {
            return ImgOut.fail(answer);
        }
        List<Extraction.Hit> hits = Extraction.parseHits(mapper, answer);
        if (hits == null) {
            return ImgOut.fail("视觉模型未返回可解析的抽取结果");
        }
        LinkedHashMap<String, String> vals = new LinkedHashMap<>();
        for (Extraction.Hit h : hits) {
            if (h.value() == null || h.value().isBlank()) continue;
            vals.merge(h.field(), h.value().trim(), (a, b) -> a + " | " + b);
        }
        return ImgOut.ok(vals);
    }

    private static String buildImagePrompt(List<String> fields) {
        return "只依据这张图片内容，为下面每个字段抽出它的值。图中确实没有的字段，value 用 null，不要编造。"
                + "只输出一个 JSON 对象，不要解释、不要代码围栏。字段：\n"
                + String.join("\n", fields) + "\n\n输出格式（严格）：\n"
                + "{\"hits\":[{\"field\":\"字段名\",\"value\":\"值或null\"}]}";
    }

    private JsonNode tryParse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class ImgOut {
        final LinkedHashMap<String, String> values;
        final String error;

        private ImgOut(LinkedHashMap<String, String> values, String error) {
            this.values = values;
            this.error = error;
        }

        static ImgOut ok(LinkedHashMap<String, String> v) { return new ImgOut(v, null); }
        static ImgOut fail(String e) { return new ImgOut(new LinkedHashMap<>(), e); }
    }
}

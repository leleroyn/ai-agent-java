package com.jtzj.agent.tools;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.OcrTextService;
import com.jtzj.agent.core.service.TextService;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.MediaInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文档<b>字段抽取</b>工具 {@code extract_document_fields}：图片、PDF、TXT 走同一条管线。
 *
 * <p>图片与 PDF 先<b>OCR 换成文本</b>（同名 {@code .txt} 落在任务目录的 {@code .ocr/} 子目录，见
 * {@link OcrTextService}：图片一次 OCR，PDF 逐页识别后按页序追加），TXT 直接读，然后统一交给
 * {@link TextService} 按块抽取、确定性归并。只有一条通道、一套失败语义，没有页型探测、
 * 没有视觉/OCR 双通道回退。
 *
 * <p><b>一次任务内同一份内容只 OCR 一次</b>：换一批字段再抽时，直接复用磁盘上已识别好的那份 txt
 * （实测模型常先抽 5 个字段、再补抽 1 个，不复用就会把 72 页重新识别一遍）。txt 不进主模型上下文——
 * 返回值里只有归并后的字段值，且部署侧关掉 read-file、shell 只留 curl；整次任务跑完由
 * {@code AgentTaskRunner} 删掉 {@code .ocr/}。
 *
 * <p><b>不做内存缓存</b>：去重靠磁盘上那份 txt（{@link OcrTextService} 按内容哈希命名，文件已存在
 * 就直接复用），工具实例里不留任何全文或结果副本——几十 MB 的扫描件文本不该压在堆上。
 *
 * <p><b>重复抽取由次数闸拦住</b>：{@link Attempts} 在 {@code .ocr/extracted.json} 里只记「某个来源被抽了
 * 几次」。拦的是「同一来源再抽一遍」本身，不是字段名——抽取子模型每次都遍历全部文本块并按语义
 * 匹配别名，所以换字段名重抽必然同解。实测模型为一个 {@code value=null} 的字段把同一来源抽 14 遍
 * （乙方电子证书号→电子证书号→出让人→证书号→乙方…），2 页扫描件白跑 188s。
 * 计数随任务目录删除，工具实例里没有副本；并发调用最坏是两条都放行，不影响正确性。
 * {@code units} = 文本块序号（不是页码——PDF 的 txt 每页前有页标记，但分块按字符切）。
*/
public class DocumentExtractTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractTools.class);

    /** 同一来源在一次任务里最多抽几次：1 次正常抽取 + 2 次余量（OCR 部分失败、字段确实要补）。 */
    private static final int MAX_EXTRACT_CALLS_PER_SOURCE = 3;

    private final OcrTextService ocrTextService;
    private final TextService textService;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;

    public DocumentExtractTools(OcrTextService ocrTextService, TextService textService,
    AgentProperties props, ObjectMapper mapper, Path taskDir) {
        this.ocrTextService = ocrTextService;
        this.textService = textService;
        this.props = props;
        this.mapper = mapper;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "extract_document_fields",
            description = "文档结构化字段抽取。支持图片（jpg/png/webp等）、PDF、TXT。"
              + "图片与 PDF 会先 OCR 成文本（PDF 逐页识别后按页追加），再按文本块抽取，"
                + "整篇自动遍历，无需指定页范围。每个请求字段都会出现在结果里（抽不到 value=null）。"
                  + "同一份内容在一次任务里只 OCR 一次：第二次调用直接复用已识别好的文本，换一批新字段再抽才有效。"
                  + "返回值就是权威结果：value=null 表示全文已完整遍历、并按语义匹配过别名仍未出现该字段，"
                  + "这就是终局结论——不得换个字段名再抽一遍，也不得改用 understand_document 补这个字段。"
                  + "抽取类任务调本工具一次就够了；同一来源超过抽取次数上限会被直接拒绝（不重跑）。"
                    + "返回 {status, total_units, results:[{field, value, units, alternates?}], extra?, failed_units}。"
                    + "units: 文本块序号（单个来源时）或来源序号（多个来源时）。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock extractFields(
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
            String fieldsRaw) {
        return ToolResults.text(extractFieldsImpl(source, fieldsRaw));
    }

    private String extractFieldsImpl(String source, String fieldsRaw) {

        List<String> refs = MediaInputs.parse(source);
        if (refs.isEmpty()) {
            return "Error: 未提供任何文档来源。";
        }
        List<String> fields = Extraction.parseFields(fieldsRaw);
        if (fields.isEmpty()) {
            return "Error: fields 为空，请指定要抽取的字段（多个用 ; 逗号 、 或换行分隔）。";
        }

        // 1) 每个来源先换成一份文本文件（TXT 直接用，图片/PDF 走 OCR，txt 落在 .ocr/ 下，本次任务内按来源复用）。
        //    txtOfRef 与 refs 下标对齐，转换失败的来源留 null；同一来源在本次运行内只转一次。
        List<String> txtOfRef = new ArrayList<>(refs.size());
        List<String> failures = new ArrayList<>();
        int usable = 0;
        for (String ref : refs) {
            if (".txt".equals(MediaInputs.extension(ref))) {
                txtOfRef.add(ref);
                usable++;
                continue;
            }
            // 去重在 OcrTextService 里按内容做：同一份内容第二次调用直接复用磁盘上已有的 txt，不重 OCR。
            String txt = ocrTextService.toTextFile(ref, taskDir);
            if (txt.startsWith("Error:")) {
                failures.add(ref + " -> " + txt);
                txtOfRef.add(null);
            } else {
                txtOfRef.add(txt);
                usable++;
            }
        }
        if (usable == 0) {
            return "Error: 所有来源都无法转成文本：" + String.join(" | ", failures);
        }

        // 次数闸：所有可用来源都已到上限 → 结论不会变，直接拒绝，不跑模型。
        Attempts attempts = Attempts.load(taskDir, mapper);
        int over = 0;
        for (String txt : txtOfRef) {
            if (txt != null && attempts.reached(txt, MAX_EXTRACT_CALLS_PER_SOURCE)) {
                over++;
            }
        }
        if (over == usable) {
            log.warn("doc extract refused (every source already extracted {} times) sources={}",
                    MAX_EXTRACT_CALLS_PER_SOURCE, refs);
            return "Error: 这些来源在本次任务里已经抽取过 " + MAX_EXTRACT_CALLS_PER_SOURCE
                    + " 次，再抽一遍结论不会变。value=null 就是终局结论：不要再抽取、不要换字段名重试、"
                    + "也不要用 understand_document 补这个字段。请用已有结论作答。";
        }

        log.info("doc extract sources={} usable={} fields={} ocr_failures={}",
                refs.size(), usable, fields.size(), failures.size());
        attempts.bump(txtOfRef);

        // 2) 单来源：直接返回分块抽取结果（units=块号）
        if (refs.size() == 1 && usable == 1) {
            String single = textService.extractFields(txtOfRef.get(0), fieldsRaw, taskDir);
            return single;
        }

        // 3) 多来源：逐个抽取后按 (字段,值)->来源序号 归并，保持与单来源相同的返回形状
        LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc = new LinkedHashMap<>();
        TreeSet<Integer> failedUnits = new TreeSet<>();
        for (int i = 0; i < refs.size(); i++) {
            String txt = txtOfRef.get(i);
            if (txt == null) {
                failedUnits.add(i + 1);
                continue;
            }
            String json = textService.extractFields(txt, fieldsRaw, taskDir);
            JsonNode parsed = tryParse(json);
            if (parsed == null) {
                log.warn("source {} failed: {}", refs.get(i), json);
                failedUnits.add(i + 1);
                continue;
            }
            for (JsonNode r : parsed.path("results")) {
                String field = textOrNull(r.path("field"));
                if (field == null || field.isBlank()) {
                    continue;
                }
                JsonNode value = r.path("value");
                if (value.isMissingNode() || value.isNull()) {
                    acc.computeIfAbsent(field.trim(), x -> new LinkedHashMap<>());
                    continue;
                }
                acc.computeIfAbsent(field.trim(), x -> new LinkedHashMap<>())
                .computeIfAbsent(value.asText(), x -> new TreeSet<>())
                .add(i + 1);
            }
        }
        String merged = buildResult(fields, acc, failedUnits, refs.size());
        return merged;
    }

    /** 归并成统一契约：每个请求字段恰好一条权威 value，其余候选进 alternates。 */
    private String buildResult(List<String> fields,
                               LinkedHashMap<String, LinkedHashMap<String, TreeSet<Integer>>> acc,
                               TreeSet<Integer> failedUnits, int totalUnits) {
        try {
            return Extraction.merge(mapper, fields, acc, failedUnits, totalUnits);
        } catch (Exception e) {
            log.warn("build result failed", e);
            return "Error: 生成结果失败：" + e.getMessage();
        }
    }

    private JsonNode tryParse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has("results") ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /**
     * 「某个来源在本次任务里被抽了几次」的计数，落在 {@code .ocr/extracted.json}。
     *
     * <p>不是缓存：只存计数，不存正文也不存抽取结果。随任务目录一起删，工具实例里没有副本。
     */
    private static final class Attempts {

        private static final String FILE = ".ocr/extracted.json";

        private final Path file;
        private final ObjectMapper mapper;
        private final Map<String, Integer> counts;

        private Attempts(Path file, ObjectMapper mapper, Map<String, Integer> counts) {
            this.file = file;
            this.mapper = mapper;
            this.counts = counts;
        }

        static Attempts load(Path taskDir, ObjectMapper mapper) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Path file = taskDir.resolve(FILE);
            try {
                if (Files.isRegularFile(file)) {
                    JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
                    root.fields().forEachRemaining(e -> {
                        if (e.getValue().isInt()) {
                            counts.put(e.getKey(), e.getValue().asInt());
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("extract attempts {} unreadable, starting empty: {}", file, e.toString());
            }
            return new Attempts(file, mapper, counts);
        }

        /** 该来源是否已经抽到上限。 */
        boolean reached(String key, int cap) {
            return counts.getOrDefault(key, 0) >= cap;
        }

        void bump(List<String> keys) {
            boolean changed = false;
            for (String key : keys) {
                if (key != null) {
                    counts.merge(key, 1, Integer::sum);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                ObjectNode root = mapper.createObjectNode();
                counts.forEach(root::put);
                Files.writeString(file, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("could not write extract attempts {}: {}", file, e.toString());
            }
        }
    }
}

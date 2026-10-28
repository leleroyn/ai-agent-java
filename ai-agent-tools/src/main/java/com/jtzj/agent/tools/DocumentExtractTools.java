package com.jtzj.agent.tools;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.OcrTextService;
import com.jtzj.agent.core.service.TextService;
import com.jtzj.agent.core.service.VisionClient;
import com.jtzj.agent.core.support.CallLedger;
import com.jtzj.agent.core.support.Extraction;
import com.jtzj.agent.core.support.MediaInputs;
import com.jtzj.agent.core.support.VisionFill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * {@link TextService} 按块抽取、确定性归并。文本通道是主通道，没有页型探测；
 * 图片来源若有字段没取到（{@code value=null}，也包括「未提及」这类占位文案——线上指令常写
 * 「未提及填未提及」，只看 null 的话补漏闸永远不触发），再走一次 {@link VisionFill} 图像视觉
 * 复核<b>只填空槽</b>，不覆盖文本通道已取到的真实值；PDF 与 TXT 不补。
 *
 * <p><b>一次任务内同一份内容只 OCR 一次</b>：换一批字段再抽时，直接复用磁盘上已识别好的那份 txt
 * （实测模型常先抽 5 个字段、再补抽 1 个，不复用就会把 72 页重新识别一遍）。txt 不进主模型上下文——
 * 返回值里只有归并后的字段值，且部署侧关掉 read-file 和 shell。跑完只在
 * {@code agent.tools.delete-workspace-on-finish=true} 时随整个任务目录一起清掉。
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
    private static final String TOOL = "extract_document_fields";

    private final OcrTextService ocrTextService;
    private final TextService textService;
    private final VisionClient visionClient;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;

    public DocumentExtractTools(OcrTextService ocrTextService, TextService textService,
    VisionClient visionClient, AgentProperties props, ObjectMapper mapper, Path taskDir) {
        this.ocrTextService = ocrTextService;
        this.textService = textService;
        this.visionClient = visionClient;
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
                  + "图片来源若有字段在文本通道没取到，本工具内部已经对这些字段做过一次图像视觉复核补漏"
                  + "（补出的值来自图像，标 from=vision，units 为空）：你不需要、也不得自己再补一遍。"
                  + "本工具自带幂等：同一组（来源+字段）在一次任务里只真跑一次，重复调用直接返回上次结果（不重 OCR、不重跑模型）。"
                    + "返回 {status, total_units, results:[{field, value, units, alternates?}], extra?, failed_units}。"
                    + "units: 文本块序号（单个来源时）或来源序号（多个来源时）。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock extractFields(
            @ToolParam(
                    name = "source",
                    description = "文档来源，一个或多个，空格或换行分隔。每项必须是本任务工作目录下的文件路径"
                            + "（指令里点名的文件已在任务启动前就绪在 src/ 下，直接用指令里给的路径）。不接受 URL。"
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
        List<String> orderedFields = new ArrayList<>(fields);
        orderedFields.sort(String::compareTo);
        String key = CallLedger.key(TOOL, CallLedger.normalizeSources(source),
                String.join("\n", orderedFields));
        CallLedger ledger = CallLedger.load(taskDir);
        String cached = ledger.cached(key);
        if (cached != null) {
            log.info("doc extract idempotent hit key={} already_run={}", key, ledger.toolCalls(TOOL));
            return cached;
        }

        // 1) 每个来源先换成一份文本文件（TXT 直接用，图片/PDF 走 OCR，txt 落在 .ocr/ 下，本次任务内按来源复用）。
        //    txtOfRef 与 refs 下标对齐，转换失败的来源留 null；同一来源在本次运行内只转一次。
        List<String> txtOfRef = new ArrayList<>(refs.size());
        List<String> failures = new ArrayList<>();
        List<String> partialRefs = new ArrayList<>();
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
                if (txt.endsWith(OcrTextService.PARTIAL_SUFFIX + ".txt")) {
                    partialRefs.add(ref);
                }
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
            if (!partialRefs.isEmpty()) {
                return "Error: 这些来源已抽取过 " + MAX_EXTRACT_CALLS_PER_SOURCE
                        + " 次，且其中有来源 OCR 不完整（" + String.join("、", partialRefs)
                        + "）。请基于已识别的内容作答，缺页导致的 null 已在结果里标注，"
                        + "不要换字段名重试。";
            }
            return "Error: 这些来源在本次任务里已经抽取过 " + MAX_EXTRACT_CALLS_PER_SOURCE
                    + " 次，再抽一遍结论不会变。value=null 就是终局结论：不要再抽取、不要换字段名重试、"
                    + "也不要用 understand_document 补这个字段。请用已有结论作答。";
        }

        // source_failures 数的是「整个来源换不成文本」，与页级缺页不是一回事，别叫 ocr_failures。
        log.info("doc extract sources={} usable={} fields={} source_failures={}",
                refs.size(), usable, fields.size(), failures.size());
        attempts.bump(txtOfRef);

        // 2) 单来源：直接返回分块抽取结果（units=块号）
        if (refs.size() == 1 && usable == 1) {
            String single = visionFill(annotatePartial(
                    textService.extractFields(txtOfRef.get(0), fieldsRaw, taskDir), partialRefs), refs);
            ledger.record(key, TOOL, single);
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
        String merged = visionFill(annotatePartial(
                buildResult(fields, acc, failedUnits, refs.size()), partialRefs), refs);
        ledger.record(key, TOOL, merged);
        return merged;
    }

    /**
     * 图片来源 + 文本通道没取全 → 交给 {@link VisionFill} 做一次图像视觉复核补漏。
     *
     * <p>「没取全」包括 {@code value=null} 和「未提及」这类占位文案（见 {@code VisionFill.MISSING_ANSWERS}）。
     *
     * <p>闸门（任一命中就不补，直接返回原结果）：看图开关关掉；本次来源里没有图片
     * （PDF/TXT 只有文本通道）；{@link VisionFill} 自己还要看 status 与有没有 null。
     * PDF 不补是刻意的：图片是一两张图直接看像素，最便宜；PDF 要补漏得整份栅格化，
     * 成本与「OCR 缺页导致的假阳性」都不划算。
     */
    private String visionFill(String json, List<String> refs) {
        if (!props.getTools().isDocumentUnderstandVision()) {
            return json;
        }
        List<String> images = new ArrayList<>();
        for (String ref : refs) {
            if (MediaInputs.IMAGE_EXTS.contains(MediaInputs.extension(ref))) {
                images.add(ref);
            }
        }
        if (images.isEmpty()) {
            return json;
        }
        return VisionFill.fill(mapper, visionClient, props.getVision().getMaxImages(),
                json, images, taskDir);
    }

    /**
     * OCR 不完整时改写返回契约：status=partial、去掉「终局结论」的 null_note、
     * 加 partial_note 说明缺了哪些来源。否则一次瞬时的 OCR 失败会被说成
     * 「全文都没有这个字段」，且接下来的重试机会被数量闸挡掉。
     */
    private String annotatePartial(String json, List<String> partialRefs) {
        if (partialRefs.isEmpty()) {
            return json;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!(node instanceof ObjectNode root)) {
                return json;
            }
            root.put("status", "partial");
            root.remove("null_note");
            root.put("partial_note", "部分来源 OCR 不完整（" + String.join("、", partialRefs)
                    + "），文本有缺口。value=null 只说明已识别的部分里没有这个字段，"
                    + "不代表文档里没有；允许对同一来源重试一次。");
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("annotate partial failed, keep raw result", e);
            return json;
        }
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

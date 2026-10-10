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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>一次任务内同一来源只 OCR 一次</b>：txt 路径按来源缓存，换一批字段再抽直接复用那份文本
 * （实测模型常先抽 5 个字段、再补抽 1 个，不缓存就会把 72 页重新识别一遍）。txt 不进主模型上下文——
 * 返回值里只有归并后的字段值，且部署侧关掉 read-file、shell 只留 curl；整次任务跑完由
 * {@code AgentTaskRunner} 删掉 {@code .ocr/}。
 * {@code units} = 文本块序号（不是页码——PDF 的 txt 每页前有页标记，但分块按字符切）。
*/
public class DocumentExtractTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractTools.class);

    private final OcrTextService ocrTextService;
    private final TextService textService;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;
    /** 本次运行内同一来源只 OCR 一次：重复调用直接复用已生成的 txt。 */
    private final Map<String, String> txtCache = new ConcurrentHashMap<>();
    /** 本次运行内同样的「来源 + 字段」只抽一次：重复调用返回同一份结果，不再跑分块抽取。 */
    private final Map<String, String> resultCache = new ConcurrentHashMap<>();

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
                  + "同一来源在一次任务里只 OCR 一次，换一批字段再调用会复用已识别的文本。"
                  + "返回值就是权威结果：同样的来源+字段重复调用只会拿到同一份结果，不要用它做二次确认。"
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
            String txt = txtCache.get(ref);
            if (txt == null) {
                txt = ocrTextService.toTextFile(ref, taskDir);
                if (!txt.startsWith("Error:")) {
                    txtCache.put(ref, txt);
                }
            } else {
                log.info("reuse txt {} for source {} (already converted in this run)", txt, ref);
            }
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

        log.info("doc extract sources={} usable={} fields={} ocr_failures={}",
                refs.size(), usable, fields.size(), failures.size());

        // 2) 同样的来源 + 字段只抽一次：模型重复调用时直接回同一份结果。
        String cacheKey = String.join("|", refs) + "#" + String.join("|", fields);
        String cached = resultCache.get(cacheKey);
        if (cached != null) {
            log.info("doc extract cache hit sources={} fields={}", refs.size(), fields.size());
            return cached;
        }

        // 3) 单来源：直接返回分块抽取结果（units=块号）
        if (refs.size() == 1 && usable == 1) {
            String single = textService.extractFields(txtOfRef.get(0), fieldsRaw, taskDir);
            remember(cacheKey, single);
            return single;
        }

        // 4) 多来源：逐个抽取后按 (字段,值)->来源序号 归并，保持与单来源相同的返回形状
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
        remember(cacheKey, merged);
        return merged;
    }

    /** 只缓存成功结果：同样的来源 + 字段再问一次，直接回同一份。 */
    private void remember(String cacheKey, String result) {
        if (result == null || result.startsWith("Error:")) {
            return;
        }
        resultCache.put(cacheKey, result);
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
}
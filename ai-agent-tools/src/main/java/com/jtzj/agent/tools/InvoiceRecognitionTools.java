package com.jtzj.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.IpsClient;
import com.jtzj.agent.core.service.OcrTextService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置<b>发票识别</b>工具 {@code recognize_invoice}：直接吃 URL 或任务目录内路径，内部调
 * 综合服务平台 {@code /api/v3/invoice/preprocess}。
 *
 * <p>与 {@link SealRecognitionTools} 同理：接口只吃 multipart 文件，技能里的 curl 示例会逼模型先把
 * 文件下载进工作目录，从而出现「URL / 本地副本」两个身份并击穿按来源做的缓存。
 *
 * <p>额外做掉的一件事：接口的 {@code ocr_text} 是<b>一个 JSON 字符串</b>，模型经常当对象直接用或再解一层
 * 失败。这里服务端侧就解开成 {@code fields} 对象，模型拿到即可用；解不开才退回 {@code fields_raw}。
 * {@code corp_image_base64} / {@code source_text} 恒为 null，直接丢掉不占上下文。
 */
public class InvoiceRecognitionTools {

    private static final Logger log = LoggerFactory.getLogger(InvoiceRecognitionTools.class);

    private final IpsClient ips;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;

    public InvoiceRecognitionTools(IpsClient ips, AgentProperties props,
                                   ObjectMapper mapper, Path taskDir) {
        this.ips = ips;
        this.props = props;
        this.mapper = mapper;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "recognize_invoice",
            description = "发票识别（专用接口，识别发票只用本工具，不要用 extract_document_fields 或 understand_document 猜发票字段）。"
                    + "输入发票图片或 PDF，来源可以是 http(s) URL 或本任务工作目录内的路径（可多个，空格/换行分隔）；"
                    + "URL 由工具自己下载，禁止先用 shell 下载成本地文件。注意 PDF 只处理最后两页。"
                    + "字段已由接口解析好，直接放在 fields 里（接口口径的字段名，不要改名）："
                    + "发票号码/发票代码/开票日期/购货方公司名称(=购买方)/销售方公司名称(=销售方)/"
                    + "发票金额(不含税)/发票总金额(=价税合计)/校验码/是否抵扣联/是否含有国税局印章。"
                    + "同一份内容在一次任务里只调一次接口，重复调用只会拿到同一份结果。"
                    + "每个来源调用一次就够了，返回的就是权威结果；重复调用只会重新识别一遍，更慢且不会更准。"
                    + "返回 {status, results:[{source, confidence, fields}]}；检测不到有效发票时该来源返回 error（附接口给的原因）。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock recognizeInvoice(
            @ToolParam(
                    name = "source",
                    description = "发票文件，一个或多个，空格或换行分隔。每项是 http/https URL 或本任务工作目录下的本地文件路径（可混用）。",
                    required = true)
            String source,
            @ToolParam(
                    name = "return_ocr_text",
                    description = "可选，是否返回发票内容字段。默认 true。图片输入时字段恒有值，此参数只对文字版 PDF 生效。",
                    required = false)
            Boolean returnOcrText,
            @ToolParam(
                    name = "init_confidence",
                    description = "可选，检测阈值 0~1，默认 0.5。漏检调低（如 0.3），误检调高（如 0.7）。",
                    required = false)
            Double initConfidence) {
        return ToolResults.text(recognize(source, returnOcrText, initConfidence));
    }

    private String recognize(String source, Boolean returnOcrText, Double initConfidence) {
        if (!ips.isEnabled()) {
            return "Error: 发票识别通道未启用（agent.ips.enabled=false，或 agent.ips.base-url 与 agent.ocr.base-url 都没配）。";
        }
        boolean ocrText = returnOcrText == null || returnOcrText;
        List<IpsRecognitionSupport.Outcome> outcomes = IpsRecognitionSupport.callAll(
                source, taskDir, taskDir.resolve(OcrTextService.SCRATCH_DIR), props,
                (bytes, filename, ocr, conf) -> ips.invoice(bytes, filename, ocr, conf),
                ocrText, initConfidence);
        if (outcomes.isEmpty()) {
            return "Error: 未提供任何来源（URL 或本任务目录下的文件路径）。";
        }

        int ok = 0;
        ObjectNode env = mapper.createObjectNode();
        ArrayNode results = env.putArray("results");
        for (IpsRecognitionSupport.Outcome outcome : outcomes) {
            ObjectNode item = results.addObject();
            item.put("source", outcome.source());
            if (!outcome.ok()) {
                item.put("error", outcome.error());
                continue;
            }
            JsonNode array = tryParseArray(outcome.raw());
            if (array == null) {
                item.put("error", "Error: 发票服务返回的不是 JSON 数组："
                        + IpsRecognitionSupport.snippet(outcome.raw()));
                continue;
            }
            if (array.isEmpty()) {
                item.put("error", "未检测到有效发票（接口返回空结果）。这就是本工具的权威结论，"
                        + "不要改用 extract_document_fields / understand_document 猜发票字段。");
                continue;
            }
            JsonNode first = array.get(0);
            item.set("confidence", first.path("confidence"));
            String ocrTextRaw = first.path("ocr_text").asText(null);
            if (ocrTextRaw == null || ocrTextRaw.isBlank()) {
                item.put("note", "接口未返回发票内容字段（文字版 PDF 需要 return_ocr_text=true）。");
            } else {
                JsonNode fields = tryParseObject(ocrTextRaw);
                if (fields != null) {
                    item.set("fields", fields);
                } else {
                    item.put("fields_raw", ocrTextRaw);
                }
            }
            ok++;
        }
        env.put("status", ok == 0 ? "error" : (ok < outcomes.size() ? "partial" : "ok"));
        log.info("recognize_invoice sources={} ok={} return_ocr_text={}",
                outcomes.size(), ok, ocrText);
        return env.toString();
    }

    private JsonNode tryParseArray(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code ocr_text} 是 JSON 字符串，需再解一层；不是对象就返回 null，退回原文。 */
    private JsonNode tryParseObject(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}

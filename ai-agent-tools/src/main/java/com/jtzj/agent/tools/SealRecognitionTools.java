package com.jtzj.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.IpsClient;
import com.jtzj.agent.core.service.OcrTextService;
import com.jtzj.agent.core.support.CallLedger;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置<b>印章识别</b>工具 {@code recognize_seal}：直接吃 URL 或任务目录内路径，内部调
 * 综合服务平台 {@code /api/v3/seal/preprocess}，返回每枚印章的置信度、类型与印章上的公司名。
 *
 * <p>取代原来 {@code seal-recognition} 技能里的 curl 示例。技能只能教模型手搓 multipart，模型就必须
 * 先把文件下载进工作目录（接口不吃 URL），于是同一份内容出现两个身份、缓存必然 miss，
 * 还会顺手把印章字段丢回 {@code extract_document_fields} 去补——为 1 个字段把 72 页重新 OCR 一遍。
 * 工具化之后下载、调用、解析、缓存都在工具里，模型只给来源。
 *
 * <p>返回值只含结构化结论（不含印章 base64），主模型上下文里不会出现图片字节。
 */
public class SealRecognitionTools {

    private static final Logger log = LoggerFactory.getLogger(SealRecognitionTools.class);

    private static final String TOOL = "recognize_seal";
    private static final int MAX_CALLS_PER_TOOL = 4;

    private final IpsClient ips;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Path taskDir;

    public SealRecognitionTools(IpsClient ips, AgentProperties props,
                                ObjectMapper mapper, Path taskDir) {
        this.ips = ips;
        this.props = props;
        this.mapper = mapper;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "recognize_seal",
            description = "印章识别（专用接口，识别印章只用本工具，不要用 extract_document_fields 或 understand_document 找印章）。"
                    + "输入图片或 PDF，来源可以是 http(s) URL 或本任务工作目录内的路径（可多个，空格/换行分隔）；"
                    + "URL 由工具自己下载，禁止先用 shell 下载成本地文件。"
                    + "返回每枚印章的置信度、类型和印章上的公司名；PDF 会逐页检测。"
                    + "本工具自带幂等：同一组参数在一次任务里只真跑一次，重复调用直接返回上次结果（不重复打接口）；"
                    + "一次任务最多真跑 " + MAX_CALLS_PER_TOOL + " 次，超了直接拒绝。"
                    + "每个来源调用一次就够了，返回的就是权威结果，不要用它做二次确认。"
                    + "返回 {status, results:[{source, seals:[{confidence, seal_type, company}]}]}；"
                    + "seal_type 1=圆形红色章、2=圆形灰色章；seals=[] 表示未检出印章（不是错误，不要换工具再查）。"
                    + "company 只读出局部字样时属正常现象，以已读到的部分为准，不要改用视觉工具重猜。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock recognizeSeal(
            @ToolParam(
                    name = "source",
                    description = "印章所在文件，一个或多个，空格或换行分隔。每项是 http/https URL 或本任务工作目录下的本地文件路径（可混用）。",
                    required = true)
            String source,
            @ToolParam(
                    name = "return_ocr_text",
                    description = "可选，是否返回印章上的公司名（company）。默认 true；传 false 明显更快但只有置信度和类型，拿不到公司名。",
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
            return "Error: 印章识别通道未启用（agent.ips.enabled=false 或未配 agent.ips.base-url）。";
        }
        boolean ocrText = returnOcrText == null || returnOcrText;
        String key = CallLedger.key(TOOL, CallLedger.normalizeSources(source),
                String.valueOf(ocrText),
                initConfidence == null ? "" : String.valueOf(initConfidence));
        CallLedger ledger = CallLedger.load(taskDir);
        String cached = ledger.cached(key);
        if (cached != null) {
            log.info("recognize_seal idempotent hit key={} already_run={}", key, ledger.toolCalls(TOOL));
            return cached;
        }
        int used = ledger.toolCalls(TOOL);
        if (used >= MAX_CALLS_PER_TOOL) {
            log.warn("recognize_seal refused after {} calls in this task", used);
            return "Error: recognize_seal 在本次任务里已真跑 " + used
                    + " 次，同一份内容再识别一遍结论不会变。seals=[] 就是权威结论，"
                    + "请用已有结果作答，不要再换阈值、换来源或换工具重试。";
        }
        List<IpsRecognitionSupport.Outcome> outcomes = IpsRecognitionSupport.callAll(
                source, taskDir, taskDir.resolve(OcrTextService.SCRATCH_DIR), props,
                (file, filename, ocr, conf) -> ips.seal(file, filename, ocr, conf),
                ocrText, initConfidence);
        if (outcomes.isEmpty()) {
            return "Error: 未提供任何来源（URL 或本任务目录下的文件路径）。";
        }

        int ok = 0;
        int seals = 0;
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
                item.put("error", "Error: 印章服务返回的不是 JSON 数组："
                        + IpsRecognitionSupport.snippet(outcome.raw()));
                continue;
            }
            ArrayNode sealNodes = item.putArray("seals");
            for (JsonNode s : array) {
                ObjectNode node = sealNodes.addObject();
                node.set("confidence", s.path("confidence"));
                node.set("seal_type", s.path("seal_type"));
                if (ocrText) {
                    node.put("company", s.path("ocr_result").asText(""));
                }
                seals++;
            }
            if (array.isEmpty()) {
                item.put("note", "未检出印章：可能是阈值过高或该文件确实没有章。这就是本工具的权威结论，"
                        + "不要改用 extract_document_fields / understand_document 补找印章。");
            }
            ok++;
        }
        env.put("status", ok == 0 ? "error" : (ok < outcomes.size() ? "partial" : "ok"));
        env.put("seal_count", seals);
        log.info("recognize_seal sources={} ok={} seals={} return_ocr_text={}",
                outcomes.size(), ok, seals, ocrText);
        ledger.record(key, TOOL, env.toString(), ok > 0);
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
}

package com.jtzj.agent.tools;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.PdfService;
import com.jtzj.agent.core.service.TextService;
import com.jtzj.agent.core.service.VisionClient;
import com.jtzj.agent.core.support.CallLedger;
import com.jtzj.agent.core.support.QaMerge;
import com.jtzj.agent.core.support.MediaInputs;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文档<b>自由理解</b>工具 {@code understand_document}：支持图片、PDF、TXT。
 * 根据文件扩展名自动路由到对应管线（图片→视觉模型、PDF→栅格化→视觉模型、TXT→分块→视觉模型）。
 *
 * <p>混合类型时分别处理各类型，结果拼接；不跨类型对比。
 */
public class DocumentUnderstandTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentUnderstandTools.class);

    private static final String TOOL = "understand_document";
    private static final int MAX_CALLS_PER_TOOL = 4;

    private final VisionClient visionClient;
    private final PdfService pdfService;
    private final TextService textService;
    private final AgentProperties props;
    private final Path taskDir;

    public DocumentUnderstandTools(VisionClient visionClient, PdfService pdfService,
                                   TextService textService, AgentProperties props, Path taskDir) {
        this.visionClient = visionClient;
        this.pdfService = pdfService;
        this.textService = textService;
        this.props = props;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "understand_document",
            description = "文档自由理解/问答。支持图片（jpg/png/webp等）、PDF、TXT。"
                    + "用于描述、对比、总结、转录或回答关于文档内容的开放性问题。"
                    + "不得用它补 extract_document_fields 返回 null 的字段——null 是终局结论，"
                    + "抽取类任务调一次抽取工具就结束了。"
                    + "PDF/TXT 会服务端分批遍历全部内容并归并成一段回答，图片过多也会自动分批归并："
                    + "不需要、也不要指定页范围，工具自己覆盖全文。"
                    + "图片来源在同一批内可跨图对比（一批最多 agent.vision.max-images 张）；"
                    + "超出会自动分批、各批独立识别后归并，跨批不对比。"
                    + "混合类型时分别处理各类型结果拼接，不跨类型对比。返回自然语言文本。"
                    + "本工具自带幂等：同一组（来源+问题）在一次任务里只真跑一次，重复调用直接返回上次结果；"
                    + "一次任务最多真跑 " + MAX_CALLS_PER_TOOL + " 次，超了直接拒绝。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock understand(
            @ToolParam(
                    name = "source",
                    description = "文档来源，一个或多个，空格或换行分隔。每项必须是本任务工作目录下的文件路径"
                            + "（指令里点名的文件已在任务启动前就绪在 src/ 下，直接用指令里给的路径）。不接受 URL。"
                            + "按扩展名判定类型：图片(.jpg/.png/.webp/.gif/.bmp)、PDF(.pdf)、TXT(.txt)。",
                    required = true)
            String source,
            @ToolParam(
                    name = "question",
                    description = "要回答的问题或理解要求，例如\"描述这张图\"\"总结这份文档\"\"这两张图金额是否一致\"。",
                    required = true)
            String question) {
        return ToolResults.text(understandImpl(source, question));
    }

    private String understandImpl(String source, String question) {

        if (question == null || question.isBlank()) {
            return "Error: question 不能为空，请说明要对文档做什么。";
        }
        List<String> refs = MediaInputs.parse(source);
        if (refs.isEmpty()) {
            return "Error: 未提供任何文档来源。";
        }
        String key = CallLedger.key(TOOL, CallLedger.normalizeSources(source), question);
        CallLedger ledger = CallLedger.load(taskDir);
        String cached = ledger.cached(key);
        if (cached != null) {
            log.info("understand_document idempotent hit key={} already_run={}", key, ledger.toolCalls(TOOL));
            return cached;
        }
        int used = ledger.toolCalls(TOOL);
        if (used >= MAX_CALLS_PER_TOOL) {
            log.warn("understand_document refused after {} calls in this task", used);
            return "Error: understand_document 在本次任务里已真跑 " + used
                    + " 次，同一组来源和问题再理解一遍结论不会变。抽取类任务的 null 是终局结论，"
                    + "不要用本工具补。请用已有结论作答。";
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
                images.add(ref); // 无扩展名或未知：当图片尝试
            }
        }

        StringBuilder result = new StringBuilder();

        if (!images.isEmpty()) {
            int max = props.getVision().getMaxImages();
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < images.size(); i += max) {
                int to = Math.min(images.size(), i + max);
                MediaInputs.Result r = MediaInputs.resolveAsDataUrls(
                        visionClient, taskDir, images.subList(i, to), max);
                if (!r.ok()) {
                    return r.error();
                }
                String out = visionClient.extractFromDataUrls(r.dataUrls(), question);
                if (out.startsWith("Error:")) {
                    return out;
                }
                String label = images.size() > max ? "【第 " + (i + 1) + "-" + to + " 张图】\n" : "";
                parts.add(label + out.trim());
            }
            if (result.length() > 0) result.append('\n');
            result.append(QaMerge.merge(visionClient, parts, question));
        }
        for (String pdf : pdfs) {
            String out = pdfService.understand(pdf, question, null, taskDir);
            if (result.length() > 0) result.append('\n');
            result.append(out);
        }
        for (String txt : txts) {
            String out = textService.understand(txt, question, taskDir);
            if (result.length() > 0) result.append('\n');
            result.append(out);
        }

        String answer = result.length() == 0 ? "Error: 无法识别文档类型。" : result.toString();
        ledger.record(key, TOOL, answer);
        return answer;
    }
}

package com.jtzj.agent.tools;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.service.PdfService;
import com.jtzj.agent.core.service.TextService;
import com.jtzj.agent.core.service.VisionClient;
import com.jtzj.agent.core.support.MediaInputs;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档<b>自由理解</b>工具 {@code understand_document}：支持图片、PDF、TXT。
 * 根据文件扩展名自动路由到对应管线（图片→视觉模型、PDF→栅格化→视觉模型、TXT→分块→视觉模型）。
 */
public class DocumentUnderstandTools {

    private static final List<String> IMAGE_EXTS = List.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

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
                    + "多个图片来源在一次调用中一起分析，可跨图对比。返回自然语言文本。",
            readOnly = true,
            concurrencySafe = true)
    public String understand(
            @ToolParam(
                    name = "source",
                    description = "文档来源，一个或多个，空格或换行分隔。每项是 http/https URL 或本任务工作目录下的本地文件路径（可混用）。"
                            + "按扩展名判定类型：图片(.jpg/.png/.webp/.gif/.bmp)、PDF(.pdf)、TXT(.txt)。",
                    required = true)
            String source,
            @ToolParam(
                    name = "question",
                    description = "要回答的问题或理解要求，例如\"描述这张图\"\"总结这份文档\"\"这两张图金额是否一致\"。",
                    required = true)
            String question,
            @ToolParam(
                    name = "page_range",
                    description = "可选，仅对 PDF 有效：\"a-b\"、\"a\"、\"a-\"。留空=处理全部。",
                    required = false)
            String pageRange) {

        if (question == null || question.isBlank()) {
            return "Error: question 不能为空，请说明要对文档做什么。";
        }
        List<String> refs = MediaInputs.parse(source);
        if (refs.isEmpty()) {
            return "Error: 未提供任何文档来源。";
        }

        // 按类型分组
        List<String> images = new ArrayList<>();
        List<String> pdfs = new ArrayList<>();
        List<String> txts = new ArrayList<>();
        for (String ref : refs) {
            String ext = getExtension(ref);
            if (IMAGE_EXTS.contains(ext)) {
                images.add(ref);
            } else if (".pdf".equals(ext)) {
                pdfs.add(ref);
            } else if (".txt".equals(ext)) {
                txts.add(ref);
            } else {
                // 无扩展名或未知：当图片尝试
                images.add(ref);
            }
        }

        StringBuilder result = new StringBuilder();

        if (!images.isEmpty()) {
            MediaInputs.Result r = MediaInputs.resolveAsDataUrls(
                    visionClient, taskDir, images, props.getVision().getMaxImages());
            if (!r.ok()) {
                return r.error();
            }
            String out = visionClient.extractFromDataUrls(r.dataUrls(), question);
            if (result.length() > 0) result.append('\n');
            result.append(out);
        }
        for (String pdf : pdfs) {
            String out = pdfService.understand(pdf, question, pageRange, taskDir);
            if (result.length() > 0) result.append('\n');
            result.append(out);
        }
        for (String txt : txts) {
            String out = textService.understand(txt, question, taskDir);
            if (result.length() > 0) result.append('\n');
            result.append(out);
        }

        return result.length() == 0 ? "Error: 无法识别文档类型。" : result.toString();
    }

    private static String getExtension(String ref) {
        String s = ref;
        // 去掉 URL query/fragment
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h > 0) s = s.substring(0, h);
        int dot = s.lastIndexOf('.');
        if (dot < 0) return "";
        return s.substring(dot).toLowerCase(Locale.ROOT);
    }
}

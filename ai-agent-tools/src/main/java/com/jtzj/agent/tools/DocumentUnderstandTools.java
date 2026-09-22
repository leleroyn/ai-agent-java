package com.jtzj.agent.tools;

import com.jtzj.agent.core.service.PdfService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;

/**
 * PDF <b>自由理解</b>工具 {@code understand_pdf}：把一批页栅格化后连同自由问题交给视觉模型，
 * 返回自然语言（带绝对页码）。用于对某几页/小文档做问答、总结、转录。
 *
 * <p>要<b>抽全部关键信息</b>请改用 {@code extract_pdf_fields}（服务端遍历整篇、高召回）。
 * 文档来源可为 http/https URL 或本任务沙箱内本地路径。图片字节只进视觉模型。每任务 new。
 */
public class DocumentUnderstandTools {

    private final PdfService pdfService;
    private final Path taskDir;

    public DocumentUnderstandTools(PdfService pdfService, Path taskDir) {
        this.pdfService = pdfService;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "understand_pdf",
            description = "对 PDF 做自由理解/问答。当你需要理解、总结、转录、或回答关于某几页内容的开放性问题时用。"
                    + "传入：文档来源(pdf_url，http/https URL 或本任务工作目录下的本地 PDF 路径) + 一个问题(question，"
                    + "留空则逐页转录说明) + 可选页范围(page_range，如 \"1-5\")。服务端把这些页栅格化后交给视觉模型，"
                    + "返回自然语言（引用内容带绝对页码）。一次处理的页数有上限，超限会提示你用 page_range 分批。"
                    + "如果你要的是【从整篇文档抽取指定字段并定位页码】，请改用 extract_pdf_fields（服务端遍历整篇，召回更高）。"
                    + "不要为了理解 PDF 而把内容打印到 stdout 或用 read-file 读进对话。"
                    + "【防循环】对同一批页最多调用一次，第一次返回即权威。",
            readOnly = true,
            concurrencySafe = true)
    public String understandPdf(
            @ToolParam(
                    name = "pdf_url",
                    description = "PDF：http/https URL，或本任务工作目录下的本地 PDF 文件路径。",
                    required = true)
            String pdfUrl,
            @ToolParam(
                    name = "question",
                    description = "要对文档做的自由问题/指令，例如“总结这份合同主要条款”“第2页讲了什么”。留空则逐页转录说明。",
                    required = false)
            String question,
            @ToolParam(
                    name = "page_range",
                    description = "可选，处理的页范围：\"a-b\"、单页 \"a\"、或 \"a-\"。留空=从第1页起（受单次页数上限约束）。",
                    required = false)
            String pageRange) {

        return pdfService.understand(pdfUrl, question, pageRange, taskDir);
    }
}

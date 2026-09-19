package com.example.agent.agent.tool;

import com.example.agent.agent.PdfService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;

/**
 * 文档理解工具 {@code understand_document}，定位是「PDF 版的图片理解」。
 *
 * <p>像 {@code understand_image} 一样灵活调用：给一个<b>自由问题</b>（要文档回答什么/抽什么/总结什么）
 * + PDF 来源（URL 或本任务沙箱本地路径）+ 可选页范围，服务端把这一批页栅格化后交给视觉模型
 * （{@code agent.vision}），直接返回<b>自然语言</b>（引用内容时带绝对页码）。页面图像只进视觉模型，
 * 不进主模型上下文。
 *
 * <p>不返回固定 JSON、不在服务端做汇总——需要处理超过单次上限的页数时，用 {@code page_range} 分批调用，
 * 并在你自己的回答里汇总。
 *
 * <p>每任务 new 一个（携带任务沙箱目录），在 {@code ToolkitFactory#build(Path taskDir)} 注册。
 */
public class DocumentUnderstandTools {

    private final PdfService pdfService;
    private final Path taskDir;

    public DocumentUnderstandTools(PdfService pdfService, Path taskDir) {
        this.pdfService = pdfService;
        this.taskDir = taskDir;
    }

    /**
     * @param pdfUrl    PDF 的 http/https 地址，或任务沙箱内的本地文件路径
     * @param question  要对文档做的自由问题/指令（抽取/总结/对比/转录等）；留空=逐页转录说明
     * @param pageRange 可选页范围 {@code "a-b"} / {@code "a"} / {@code "a-"}；留空=全部页（受单次上限约束）
     * @return 视觉模型的自然语言回答（含页码），或 {@code "Error: ..."} / 分批提示
     */
    @Tool(
            name = "understand_document",
            description = "理解 PDF 文档的灵活工具（PDF 版的图片理解）。当任务涉及 PDF，或 instruction "
                    + "里出现 PDF（http(s)://....pdf）时用它。传入：文档来源(pdf_url，可以是 http/https URL "
                    + "或本任务工作目录下的本地 PDF 路径) + 一个自由问题(question，写清你要从文档里得到什么，"
                    + "例如“提取发票号、金额、税额并给出各自页码”“总结这份合同的主要条款和风险”“第2、3页金额是否一致”，"
                    + "留空则逐页转录说明内容) + 可选页范围(page_range，如 \"1-5\")。服务端会把相应页栅格化后"
                    + "交给视觉模型，返回自然语言回答（引用内容会标页码）；图片只进视觉模型、不进你的上下文。"
                    + "一次处理页数有上限；若文档较大或你指定范围超限，工具会提示你用 page_range 分批调用，"
                    + "你再在最终回答里自行汇总。"
                    + "不要为了理解 PDF 而把文档内容打印到 stdout 或用 read-file 读进对话——交给本工具即可。"
                    + "【防循环】同一份文档的同一批页最多调用一次，第一次返回即权威，据此作答。",
            readOnly = true,
            concurrencySafe = true)
    public String understandDocument(
            @ToolParam(
                    name = "pdf_url",
                    description = "PDF：http/https URL，或本任务工作目录下的本地 PDF 文件路径"
                            + "（例如你刚用 shell 下载到当前目录的 PDF，或调用方预置的文件）。",
                    required = true)
            String pdfUrl,
            @ToolParam(
                    name = "question",
                    description = "要对文档做的自由问题/指令，例如“提取发票号、金额、税额及其页码”"
                            + "“总结主要条款与风险”“第2页和第3页金额是否一致”。留空则逐页转录说明内容。",
                    required = false)
            String question,
            @ToolParam(
                    name = "page_range",
                    description = "可选，处理的页范围：\"a-b\"、单页 \"a\"、或 \"a-\"（从第a页到最后）。"
                            + "留空=全部页；若页数超过单次上限，工具会提示你用本参数分批调用。",
                    required = false)
            String pageRange) {

        return pdfService.understand(pdfUrl, question, pageRange, taskDir);
    }
}

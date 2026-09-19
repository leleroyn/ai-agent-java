package com.example.agent.agent.tool;

import com.example.agent.agent.PdfService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;

/**
 * 文档理解工具 {@code understand_document}，针对大/多页扫描 PDF。
 *
 * <p><b>为什么单独有它</b>：一张超大扫描 PDF（几十上百页）整份塞给视觉模型会超 token；
 * 让主模型自己「一页一页读」又会把每一页的图片在 ReAct 各轮反复带上主模型上下文——既贵又慢，
 * 还泄露给主模型。本工具把整份 PDF 的「逐页看 + 抽取」收在服务端内部完成：下载 → 逐页栅格化 →
 * 每几页一批发给视觉模型（{@code agent.vision}）→ 合并，主模型只收到一次紧凑 JSON（字段值 + 页码）。
 * 图片字节始终不进主模型上下文，与 {@code understand_image} 同一原则。
 *
 * <p>每任务 new 一个（携带该任务沙箱目录下载与临时栅格化文件），因此在
 * {@code ToolkitFactory#build(Path taskDir)} 里注册，而非常规 Spring 单例。
 */
public class DocumentUnderstandTools {

    private final PdfService pdfService;
    private final Path taskDir;

    public DocumentUnderstandTools(PdfService pdfService, Path taskDir) {
        this.pdfService = pdfService;
        this.taskDir = taskDir;
    }

    /**
     * @param pdfUrl    文档（PDF）的 http/https 地址
     * @param fields    要抽取的关键信息，多个用分号/逗号/顿号/换行分隔
     * @param pageRange 可选页范围，如 "1-20"、"5"、"30-"；留空=全部页
     * @return 紧凑结果 JSON（status / results[field,value,pages] / failed_pages），或 {@code "Error: ..."}
     */
    @Tool(
            name = "understand_document",
            description = "理解文档（尤其是大文件、多页扫描版 PDF）的正确方式。当任务涉及 PDF，"
                    + "或 instruction 里出现 PDF 的 URL（如 http(s)://....pdf），需要从中找出关键信息"
                    + "并定位到页码时，必须调用本工具——把文档来源、要抽取的字段（fields）、可选页范围传进来，"
                    + "服务端会逐页交给视觉模型抽取，返回每个字段的值和它出现的页码。"
                    + "文档来源(pdf_url)可以是 http/https URL，也可以是本任务工作目录下的本地 PDF 路径"
                    + "（若文件只能经 shell 拿到，先把它下载到当前工作目录存成文件，再传该路径；本地路径限本任务沙箱内）。"
                    + "但绝不要把文档内容打印到 stdout、用 read-file 读进对话、或用 pdftotext/pdftoppm/pdfimages 把内容抽出来直接看"
                    + "——那样会把大文档原文成百上千行灌进上下文撑爆预算，且对扫描版 PDF 根本抽不出文字。"
                    + "把文档交给本工具（传 URL 或本地路径）才是唯一正确方式。"
                    + "fields 写清楚要知道哪些信息（例如“合同甲方；合同金额;签署日期”）。"
                    + "page_range 可留空表示整份文档，也可只处理某几页（如 \"1-20\"）。"
                    + "【重要·防循环】对同一份文档最多只调用本工具一次：它内部已处理全部所需页，"
                    + "第一次返回就是权威结果，直接据此作答；严禁反复调用（不会更准，只会耗尽时间预算）。",
            readOnly = true,
            concurrencySafe = true)
    public String understandDocument(
            @ToolParam(
                    name = "pdf_url",
                    description = "文档（PDF）：http/https URL，或本任务工作目录下的本地 PDF 文件路径"
                            + "（例如你刚用 shell 下载到当前目录的 PDF，或调用方预置的文件）。",
                    required = true)
            String pdfUrl,
            @ToolParam(
                    name = "fields",
                    description = "要抽取的关键信息，多个用分号 ; 逗号 顿号或换行分隔，"
                            + "例如“发票代码;发票号码;金额;开票日期”。",
                    required = true)
            String fields,
            @ToolParam(
                    name = "page_range",
                    description = "可选，处理的页范围：\"a-b\"、单页 \"a\"、或 \"a-\"（从第a页到最后）。"
                            + "留空表示处理整份文档。",
                    required = false)
            String pageRange) {

        return pdfService.extract(pdfUrl, fields, pageRange, taskDir);
    }
}

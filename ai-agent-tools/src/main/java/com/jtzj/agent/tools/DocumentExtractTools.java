package com.jtzj.agent.tools;

import com.jtzj.agent.core.service.PdfService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;

/**
 * PDF <b>字段抽取</b>工具 {@code extract_pdf_fields}：服务端<b>遍历整篇</b>、分批并发地按 {@code fields}
 * 结构化抽取、确定性归并，返回 {@code results:[{field,value,pages}]} + {@code pages_scanned} +
 * {@code incomplete} + {@code failed_pages}。
 *
 * <p>召回由服务端遍历整篇兜底——主模型<b>不需要自己分页</b>，只要给 fields + 文档。语义总结/取舍交给主模型。
 * 与 {@code understand_pdf}（自由问答，局部理解）分工明确。文档来源可为 URL 或沙箱本地路径。每任务 new。
 */
public class DocumentExtractTools {

    private final PdfService pdfService;
    private final Path taskDir;

    public DocumentExtractTools(PdfService pdfService, Path taskDir) {
        this.pdfService = pdfService;
        this.taskDir = taskDir;
    }

    @Tool(
            name = "extract_pdf_fields",
            description = "从 PDF 文档【整篇抽取指定关键信息并定位页码】，返回 {field,value,pages} 列表。"
                    + "这是抽取的关键信息、追求不漏的首选：服务端会自动遍历整篇（你不用管分页），每个请求字段都会出现在 results 里"
                    + "（抽不到 value=null），并给出出现的页码。传入：文档来源(pdf_url，http/https URL 或本任务工作目录下的本地 PDF 路径) "
                    + "+ 要抽取的字段(fields，多个用 ; 逗号 、 或换行分隔，写全你要的每一项) + 可选页范围(page_range，默认整篇)。"
                    + "返回里若 incomplete=true 表示因上限只扫了部分页，按 hint 用 page_range 续扫再自行合并。"
                    + "如果你只是要理解/总结/问答，改用 understand_pdf。不要自己用 shell 转 PDF 或把内容读进对话。"
                    + "【防循环】对同一份文档最多调用一次即可（它已遍历整篇），据此作答。",
            readOnly = true,
            concurrencySafe = true)
    public String extractPdfFields(
            @ToolParam(
                    name = "pdf_url",
                    description = "PDF：http/https URL，或本任务工作目录下的本地 PDF 文件路径。",
                    required = true)
            String pdfUrl,
            @ToolParam(
                    name = "fields",
                    description = "要抽取的关键信息，多个用分号 ; 逗号 顿号或换行分隔，写全你要的每一项，"
                            + "例如“合同甲方;合同金额;签署日期;违约条款”。",
                    required = true)
            String fields,
            @ToolParam(
                    name = "page_range",
                    description = "可选，仅扫描某页范围：\"a-b\"、\"a\"、\"a-\"。留空=遍历整篇。",
                    required = false)
            String pageRange) {

        return pdfService.extractFields(pdfUrl, fields, pageRange, taskDir);
    }
}

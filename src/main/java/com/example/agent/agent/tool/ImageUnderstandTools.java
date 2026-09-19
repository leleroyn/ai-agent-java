package com.example.agent.agent.tool;

import com.example.agent.agent.SandboxPaths;
import com.example.agent.agent.VisionClient;
import com.example.agent.config.AgentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 图片理解工具，默认全局启用（{@code agent.tools.image-understand=true}）。
 *
 * <p><b>为什么必须有它，以及为什么图片不能直接塞给主模型</b>：本服务的主模型只处理文本。
 * 如果把图片以多模态消息塞进 agent 的上下文，图片字节会在 ReAct 的每一轮里被反复携带——
 * token 暴涨、成本高，而且把图片泄露给了主模型。正确做法是把「看图」做成一次独立的、
 * 无状态的工具调用，发给专用视觉模型（{@code agent.vision}），主模型只拿到文字结论。
 *
 * <p><b>图片来源：URL 或沙箱本地文件</b>：{@code image_urls} 每一项可以是 http(s) URL，也可以是
 * 本任务沙箱目录（{@code <working-dir>/<taskId>}）下的本地文件路径，两者可在同一次调用里混用。
 * 本地文件用于「模型用 shell 下载、或调用方预置到沙箱里的图片」——模型不必再把文件挂成 HTTP URL
 * 才能被理解。出于安全，本地路径被严格限制在该任务沙箱内（见 {@link SandboxPaths}），越界一律拒绝。
 *
 * <p><b>支持一次多图</b>：多个来源会被放进同一条视觉请求里，视觉模型能跨图对比、关联、判断异同。
 *
 * <p>因为需要携带每任务的沙箱目录，本工具按任务 new，在 {@code ToolkitFactory#build(Path taskDir)}
 * 注册（与文件工具同模式），不再是 Spring 单例。图片字节始终只进视觉模型，不进主模型上下文。
 */
public class ImageUnderstandTools {

    private final VisionClient visionClient;
    private final AgentProperties props;
    private final Path taskDir;

    public ImageUnderstandTools(VisionClient visionClient, AgentProperties props, Path taskDir) {
        this.visionClient = visionClient;
        this.props = props;
        this.taskDir = taskDir;
    }

    /**
     * @param imageRefs 一个或多个图片来源：http/https URL，或任务沙箱内的本地文件路径；空白/换行分隔，可混用
     * @param question  要视觉模型回答的理解性问题（描述、抽取、对比、判断等）
     * @return 视觉模型的文字结论，或 {@code "Error: ..."} 说明
     */
    @Tool(
            name = "understand_image",
            description = "理解图片的唯一正确方式。当任务涉及任何图片（发票、证件、截图、照片、图表等），"
                    + "或 instruction 里出现了图片 URL（如 http(s)://....png/.jpg/.jpeg/.gif/.webp），"
                    + "必须调用本工具，把图片来源传进来由专用视觉模型理解。"
                    + "图片来源(image_urls)可以是 http/https 的 URL，也可以是本任务工作目录下的本地图片文件路径"
                    + "（例如你刚用 shell 下载/生成到当前目录的图，或调用方预置的文件）；两者可在一次调用里混用，"
                    + "用空格或换行分隔。本地路径只能在本任务沙箱内，越界会被拒绝。"
                    + "对于小型 PDF，可先用 shell 的 pdftoppm 把它栅格化成图片文件（如 pdftoppm -png -f 1 -l 3 doc.pdf page 生成 page-1.png…），"
                    + "再把这些图片本地路径传进来理解（整份带页码的结构化抽取请改用 understand_document）。"
                    + "不要用 read-file 读图片文件（图片进不了模型），要用本工具传路径。"
                    + "支持一次理解多张图：把多个来源用空格或换行放进 image_urls，它们会在同一次调用里"
                    + "一起被分析，可用于跨图对比、找差异、判断是否同一张。"
                    + "question 写清楚你要知道什么（例如“提取这张发票的全部要素并逐项列出”或"
                    + "“这两张图金额是否一致”）。返回的是图片的文字理解结果。"
                    + "【重要·防循环】对同一张图最多只调用本工具一次：第一次返回的内容就是权威结果，直接据此作答即可。严禁为了核对／二次确认／逐字确认而再次调用本工具——重复调用不会更准确，只会把时间预算耗尽导致任务超时。",
            readOnly = true,
            concurrencySafe = true)
    public String understandImage(
            @ToolParam(
                    name = "image_urls",
                    description = "图片来源，一个或多个，用空格或换行分隔。每项可以是 http/https URL，"
                            + "或本任务工作目录下的本地图片文件路径（可混用）。",
                    required = true)
            String imageRefs,
            @ToolParam(
                    name = "question",
                    description = "要视觉模型对图片回答的问题或理解要求，例如“提取这张发票的完整信息并逐项列出”。",
                    required = true)
            String question) {

        List<String> refs = parseRefs(imageRefs);
        if (refs.isEmpty()) {
            return "Error: image_urls 为空。请传入至少一个 http/https 图片地址，或任务目录下的图片文件路径。";
        }
        if (question == null || question.isBlank()) {
            return "Error: question 不能为空，请说明要对图片做什么。";
        }
        int max = props.getVision().getMaxImages();
        if (refs.size() > max) {
            return "Error: 一次最多理解 " + max + " 张图，当前 " + refs.size()
                    + " 张。请拆成多次调用，每次不超过 " + max + " 张。";
        }

        // URL 与本地文件统一转成 data URL，再一次性交给视觉模型（可跨图推理）。
        List<String> dataUrls = new ArrayList<>(refs.size());
        for (String ref : refs) {
            String dataUrl;
            if (isUrl(ref)) {
                dataUrl = visionClient.dataUrlFromUrl(ref);
            } else {
                Path local = SandboxPaths.resolveWithin(taskDir, ref);
                if (local == null) {
                    return "Error: 本地图片路径必须在任务工作目录内，已拒绝越界访问：" + ref;
                }
                if (!Files.isRegularFile(local)) {
                    return "Error: 本地图片文件不存在：" + ref + "（相对本任务工作目录）。";
                }
                dataUrl = visionClient.dataUrlFromLocalFile(local);
            }
            if (dataUrl.startsWith("Error:")) {
                return dataUrl;
            }
            dataUrls.add(dataUrl);
        }
        return visionClient.extractFromDataUrls(dataUrls, question);
    }

    private static boolean isUrl(String s) {
        String t = s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /** 按空白（空格/制表/换行）切分，去空、按出现顺序去重。URL 与本地路径本身不含空白，切分无歧义。 */
    private static List<String> parseRefs(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.trim().split("\\s+")) {
                String u = part.trim();
                if (!u.isEmpty()) {
                    set.add(u);
                }
            }
        }
        return new ArrayList<>(set);
    }
}

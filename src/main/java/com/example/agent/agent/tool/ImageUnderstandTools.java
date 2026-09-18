package com.example.agent.agent.tool;

import com.example.agent.agent.VisionClient;
import com.example.agent.config.AgentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

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
 * 用户明确不想让某些图片经过主模型，这个工具正是为满足这一点而存在。
 *
 * <p><b>支持一次多图</b>：{@code image_urls} 可传多个 URL（空白/换行分隔），它们会被放进
 * 同一条视觉请求里，视觉模型能跨图对比、关联、判断异同（实测有效），而不是逐张孤立地看。
 *
 * <p>注册点见 {@code ToolkitFactory#build}。图片 URL 由调用方写在 instruction 文本里，模型
 * 从中取出并传进来——请求体本身不含 {@code images} 字段，图片与主模型彻底解耦。
 */
@Component
public class ImageUnderstandTools {

    private final VisionClient visionClient;
    private final AgentProperties props;

    public ImageUnderstandTools(VisionClient visionClient, AgentProperties props) {
        this.visionClient = visionClient;
        this.props = props;
    }

    /**
     * @param imageUrls 一个或多个图片 URL，用空格或换行分隔；需要跨图推理时把它们放进同一次调用
     * @param question  要视觉模型回答的理解性问题（描述、抽取、对比、判断等）
     * @return 视觉模型的文字结论，或 {@code "Error: ..."} 说明
     */
    @Tool(
            name = "understand_image",
            description = "理解图片的唯一正确方式。当任务涉及任何图片（发票、证件、截图、照片、图表等），"
                    + "或 instruction 里出现了图片 URL（如 http(s)://....png/.jpg/.jpeg/.gif/.webp），"
                    + "必须调用本工具，把图片 URL 传进来由专用视觉模型理解。"
                    + "严禁用 shell 的 curl/wget 下载图片、也不要用 read-file 去读图片文件——"
                    + "那些方式图片进不了任何模型，只会让你空转直到任务超时。"
                    + "支持一次理解多张图：把多个 URL 用空格或换行放进 image_urls，它们会在同一次调用里"
                    + "一起被分析，可用于跨图对比、找差异、判断是否同一张。"
                    + "question 写清楚你要知道什么（例如“提取这张发票的全部要素并逐项列出”或"
                    + "“这两张图金额是否一致”）。返回的是图片的文字理解结果。"
                    + "【重要·防循环】对同一张图最多只调用本工具一次：第一次返回的内容就是权威结果，直接据此作答即可。严禁为了核对／二次确认／逐字确认而再次调用本工具——重复调用不会更准确，只会把时间预算耗尽导致任务超时。",
            readOnly = true,
            concurrencySafe = true)
    public String understandImage(
            @ToolParam(
                    name = "image_urls",
                    description = "图片地址，一个或多个，用空格或换行分隔。必须是 http/https。",
                    required = true)
            String imageUrls,
            @ToolParam(
                    name = "question",
                    description = "要视觉模型对图片回答的问题或理解要求，例如“提取这张发票的完整信息并逐项列出”。",
                    required = true)
            String question) {

        List<String> urls = parseUrls(imageUrls);
        if (urls.isEmpty()) {
            return "Error: image_urls 为空。请传入至少一个 http/https 图片地址。";
        }
        int max = props.getVision().getMaxImages();
        if (urls.size() > max) {
            return "Error: 一次最多理解 " + max + " 张图，当前 " + urls.size()
                    + " 张。请拆成多次调用，每次不超过 " + max + " 张。";
        }
        return visionClient.understand(urls, question);
    }

    /** 按空白（空格/制表/换行）切分 URL，去空、按出现顺序去重。URL 本身不含空白，切分无歧义。 */
    private static List<String> parseUrls(String raw) {
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

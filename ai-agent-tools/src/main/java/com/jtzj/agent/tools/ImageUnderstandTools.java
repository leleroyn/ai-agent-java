package com.jtzj.agent.tools;

import com.jtzj.agent.core.support.MediaInputs;
import com.jtzj.agent.core.service.VisionClient;
import com.jtzj.agent.core.config.AgentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.List;

/**
 * 图片<b>自由理解</b>工具 {@code understand_image}：给图片来源 + 一个自由问题，交给独立视觉模型
 * （{@code agent.vision}）返回自然语言。用于看图/描述/对比/判断等开放问题。
 *
 * <p>要<b>抽具体字段</b>请改用 {@code extract_image_fields}。图片字节只进视觉模型，不进主模型上下文。
 *
 * <p>图片来源可以是 http/https URL，也可以是本任务沙箱目录下的本地文件路径（可混用），越界一律拒绝。
 * 因需携带任务沙箱目录，每任务 new，在 {@code ToolkitFactory#build(Path taskDir)} 注册。
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

    @Tool(
            name = "understand_image",
            description = "对图片做自由理解/问答的唯一正确方式。当需要看图、描述图、对比多张图、判断两张图是否同一张、"
                    + "或回答关于图的一般性问题时用它。传入：图片来源(image_urls，http/https URL 或本任务工作目录下的本地图片路径，"
                    + "多个用空格或换行分隔，可混用) + 一个问题(question，写清你要知道什么)。服务端把图交给视觉模型，"
                    + "返回自然语言。多图会在同一次调用里一起分析，可跨图对比。"
                    + "如果你要的是若干【具体字段的值】（如发票号、金额、日期），请改用 extract_image_fields（结构化抽取，更可靠）。"
                    + "不要用 read-file 读图片、也不要把图片内容打印到 stdout——那样图片进不了模型。"
                    + "【防循环】对同一组图最多调用一次，第一次返回即权威，据此作答。",
            readOnly = true,
            concurrencySafe = true)
    public String understandImage(
            @ToolParam(
                    name = "image_urls",
                    description = "图片来源，一个或多个，空格或换行分隔。每项是 http/https URL 或本任务工作目录下的本地图片文件路径（可混用）。",
                    required = true)
            String imageRefs,
            @ToolParam(
                    name = "question",
                    description = "要视觉模型回答的问题或理解要求，例如“描述这张图”“这两张图金额是否一致”。",
                    required = true)
            String question) {

        if (question == null || question.isBlank()) {
            return "Error: question 不能为空，请说明要对图片做什么。";
        }
        MediaInputs.Result r = MediaInputs.resolveAsDataUrls(
                visionClient, taskDir, MediaInputs.parse(imageRefs), props.getVision().getMaxImages());
        if (!r.ok()) {
            return r.error();
        }
        List<String> urls = r.dataUrls();
        return visionClient.extractFromDataUrls(urls, question);
    }
}

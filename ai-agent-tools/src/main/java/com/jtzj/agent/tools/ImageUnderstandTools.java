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
            description = "图片自由理解/问答。用于描述图片、对比多图、判断是否同一张、回答关于图片的开放性问题。"
                    + "多图在一次调用中一起分析，可跨图对比。返回自然语言文本。",
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

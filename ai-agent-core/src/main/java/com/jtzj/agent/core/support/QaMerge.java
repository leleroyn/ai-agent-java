package com.jtzj.agent.core.support;

import com.jtzj.agent.core.service.VisionClient;
import java.util.List;

/**
 * 分批理解的归并：把"同一份文档分批问同一个问题"得到的多段回答，合成一段连贯回答。
 *
 * <p>存在的理由：单次视觉请求能带的图片数有限（{@code agent.vision.max-images}），长 PDF 必须
 * 分批问。但分批的中间结果不该丢给主模型自己拼——那等于让 agent 去分配页、还要它做汇总。
 * 所以工具内部问完所有批次，在这里归并成一段，主模型只看得到最终一段。
 *
 * <p>归并是纯文本任务，走 {@code agent.text-model}。归并失败时退化为按页顺序拼接，
 * 绝不把已经拿到的结果吞掉。
 */
public final class QaMerge {

    private QaMerge() {
    }

    /**
     * @param parts    按文档顺序排列的分批回答（每段自带页码/图片范围标题）
     * @param question 原始问题，归并时原样带上
     */
    public static String merge(VisionClient vision, List<String> parts, String question) {
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String prompt = "下面是同一份文档按页顺序分批理解的结果，每段前面标了它对应的页码或图片序号范围。\n"
                + "请把它们合成一段连贯的回答，直接回答这个问题：\n" + question + "\n"
                + "要求：只依据下面这些分批结果，不要编造；分批结果里给出的页码标注要保留；"
                + "同一结论重复出现只写一次；某一批没看清就跳过，不要因此断言全文没有该内容。"
                + "输出纯文本，不要 JSON、不要代码围栏、不要逐批复述。\n\n=== 分批结果 ===\n"
                + String.join("\n\n", parts);
        String out = vision.askTextOnly(prompt);
        if (out.startsWith("Error:")) {
            return String.join("\n\n", parts);
        }
        return out.trim();
    }
}

package com.example.agent.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 图片来源解析：把调用方给的 {@code image_urls}（http/https URL 或任务沙箱本地路径，可混用）
 * 统一解析成视觉模型用的 data URL 列表。
 *
 * <p>安全：本地路径被严格限制在该任务沙箱内（{@link SandboxPaths}），越界一律拒绝；本地文件的
 * 源文件不会被删除。URL 与本地文件都走 {@link VisionClient} 的下载/读取 + 魔数判类型。
 */
public final class MediaInputs {

    private MediaInputs() {
    }

    /** 解析结果：成功带 {@code dataUrls}，失败带 {@code error}（二者其一）。 */
    public record Result(List<String> dataUrls, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 按空白切分来源，去空、按出现顺序去重。URL 与本地路径本身不含空白，切分无歧义。 */
    public static List<String> parse(String raw) {
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

    public static boolean isUrl(String s) {
        String t = s == null ? "" : s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /**
     * @param refs     已 {@link #parse} 的来源列表
     * @param max      单次最多张数
     * @return 成功为 data URL 列表；任一来源非法/越界/下载失败则返回可读错误。
     */
    public static Result resolveAsDataUrls(VisionClient visionClient, Path taskDir,
                                           List<String> refs, int max) {
        if (refs.isEmpty()) {
            return new Result(null, "Error: 未提供任何图片来源（URL 或本任务目录下的文件路径）。");
        }
        if (refs.size() > max) {
            return new Result(null, "Error: 一次最多理解 " + max + " 张图，当前 " + refs.size()
                    + " 张。请拆分，每次不超过 " + max + " 张。");
        }
        List<String> urls = new ArrayList<>(refs.size());
        for (String ref : refs) {
            String dataUrl;
            if (isUrl(ref)) {
                dataUrl = visionClient.dataUrlFromUrl(ref);
            } else {
                Path local = SandboxPaths.resolveWithin(taskDir, ref);
                if (local == null) {
                    return new Result(null, "Error: 本地图片路径必须在任务工作目录内，已拒绝越界访问：" + ref);
                }
                if (!Files.isRegularFile(local)) {
                    return new Result(null, "Error: 本地图片文件不存在：" + ref + "（相对本任务工作目录）。");
                }
                dataUrl = visionClient.dataUrlFromLocalFile(local);
            }
            if (dataUrl.startsWith("Error:")) {
                return new Result(null, dataUrl);
            }
            urls.add(dataUrl);
        }
        return new Result(urls, null);
    }
}

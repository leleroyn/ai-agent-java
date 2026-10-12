package com.jtzj.agent.core.support;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/**
 * 「文档来源」= 任务沙箱内的本地文件。工具层<b>不再下载任何东西</b>。
 *
 * <p>下载只发生一次，在 agent 启动之前：{@code SourceIngestor} 把指令里的文件 URL 抓成
 * {@code <taskDir>/src/} 下的本地文件，并把指令里的 URL 原地换成相对路径。之前每条工具路径
 * （OCR 转文本、印章、发票、PDF 理解、TXT 抽取）各自 {@code http.send}，同一份 30MB 扫描件在
 * 一个混合任务里被下载 3-4 遍，光下载就 90s+；模型自己 {@code curl -o} 落盘还会让同一内容出现
 * 「URL」和「本地副本」两个身份，按来源字符串做的复用必然 miss。
 *
 * <p>安全：本地路径经 {@link SandboxPaths} 严格限制在 {@code taskDir} 内，越界一律拒绝；
 * URL 一律先过 {@link #validateUrl} 的 SSRF 主机白名单，下载有体积上限（防 DoS）。
 * {@link #download} 是全工程唯一的下载实现，只给 {@code SourceIngestor} 用。
 */
public final class SourceFiles {

    private SourceFiles() {
    }

    /** 工具收到 URL 时的统一错误：告诉模型下一步该传什么，而不是只说「不支持」。 */
    public static final String URL_REJECTED = "工具不接受 URL。指令里的文件已在任务启动前下载到本任务工作目录的"
            + " src/ 下，请把来源改成本地相对路径（形如 src/1-文件名.pdf）。";

    /**
     * 解析结果：成功带 {@code file}（一定是任务沙箱内已存在的文件，调用方不得删除），
     * 失败带 {@code error}。
     */
    public record Fetched(Path file, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param ref     任务沙箱内的本地路径（相对 {@code taskDir} 或 {@code taskDir} 内的绝对路径）
     * @param taskDir 任务沙箱目录，本地路径的边界
     */
    public static Fetched fetch(String ref, Path taskDir) {
        if (ref == null || ref.isBlank()) {
            return new Fetched(null, "Error: 未提供来源。");
        }
        String s = ref.trim();
        if (MediaInputs.isUrl(s)) {
            return new Fetched(null, "Error: " + URL_REJECTED);
        }
        try {
            Path local = SandboxPaths.resolveWithin(taskDir, s);
            if (local == null) {
                return new Fetched(null, "Error: 本地文件必须在任务工作目录内，已拒绝越界访问：" + s);
            }
            if (!Files.isRegularFile(local)) {
                return new Fetched(null, "Error: 本地文件不存在：" + s
                        + "（相对本任务工作目录）。指令里点名的文件应已就绪在 src/ 下。");
            }
            return new Fetched(local, null);
        } catch (Exception e) {
            return new Fetched(null,
                    "Error: 读取来源失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * URL 合法性 + SSRF 主机白名单（后缀匹配）。白名单为空时只校验 scheme。
     *
     * <p>所有文档抓取都走这一个判定：OCR 转文本、印章、发票、PDF 理解。之前只有
     * {@code PdfService.validateUrl} 查白名单，另外两条通道直接 {@code URI.create}，
     * 运维配了也拦不住。
     */
    public static URI validateUrl(String url, List<String> allow) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("文档来源 URL 为空");
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("文档来源必须是 http/https，收到：" + url);
        }
        if (allow == null || allow.isEmpty()) {
            return uri;
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("无法解析主机名：" + url);
        }
        String h = host.toLowerCase();
        for (String entry : allow) {
            String e = entry == null ? "" : entry.trim().toLowerCase();
            if (!e.isEmpty() && (h.equals(e) || h.endsWith("." + e))) {
                return uri;
            }
        }
        throw new IllegalArgumentException("主机不在允许的白名单内：" + host);
    }

    /** 文件内容的 SHA-256（十六进制）。用来把「同一份内容」认出来，与来源字符串无关。 */
    public static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 全工程唯一的下载实现：流式落盘 + 体积上限，只由 {@code SourceIngestor} 在任务启动前调用。 */
    public static Path download(URI uri, Path target, int maxBytes) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(180)).GET().build();
        HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("下载文件失败 HTTP " + resp.statusCode());
        }
        long total = 0;
        try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("文件超过体积上限 " + maxBytes + " 字节");
                }
                os.write(buf, 0, n);
            }
        }
        if (total == 0) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("下载到的文件为空");
        }
        return target;
    }
}
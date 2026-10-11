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
 * 「文档来源」统一落地成本地可读文件：http(s) URL 下载到指定临时目录，本地路径必须落在任务沙箱内。
 *
 * <p>存在的理由是<b>把下载这件事从模型手里拿走</b>。之前印章/发票只有技能里的 curl 示例，模型只能先
 * {@code curl -o <taskDir>/contract.pdf} 落盘，再把本地路径喂给后续工具——同一份内容于是同时存在
 * 「URL」和「本地副本」两个身份，按来源字符串做的缓存必然 miss，72 页 PDF 被重新 OCR 一遍。
 * 工具自己吃 URL 之后，本地副本根本不会出现。
 *
 * <p>安全：本地路径经 {@link SandboxPaths} 严格限制在 {@code taskDir} 内，越界一律拒绝；
 * URL 一律先过 {@link #validateUrl} 的 SSRF 主机白名单，下载有体积上限（防 DoS），
 * 落点是调用方给的临时目录，由调用方用完删除。
 */
public final class SourceFiles {

    private SourceFiles() {
    }

    /**
     * 解析结果：成功带 {@code file}（{@code temp=true} 表示是下载来的临时文件，调用方须删除），
     * 失败带 {@code error}。
     */
    public record Fetched(Path file, boolean temp, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param ref      http/https URL，或任务沙箱内的本地路径
     * @param taskDir  任务沙箱目录，本地路径的边界
     * @param tempDir  URL 下载的落点；{@code null} 表示落在 {@code taskDir}
     * @param maxBytes 下载体积上限
     * @param ssrfAllowlist SSRF 主机白名单（后缀匹配），空=不限制
     */
    public static Fetched fetch(String ref, Path taskDir, Path tempDir,
                                int maxBytes, List<String> ssrfAllowlist) {
        if (ref == null || ref.isBlank()) {
            return new Fetched(null, false, "Error: 未提供来源。");
        }
        String s = ref.trim();
        try {
            if (MediaInputs.isUrl(s)) {
                Path dir = tempDir != null ? tempDir : taskDir;
                Files.createDirectories(dir);
                Path target = dir.resolve("src-" + System.nanoTime());
                return new Fetched(download(validateUrl(s, ssrfAllowlist), target, maxBytes), true, null);
            }
            Path local = SandboxPaths.resolveWithin(taskDir, s);
            if (local == null) {
                return new Fetched(null, false, "Error: 本地文件必须在任务工作目录内，已拒绝越界访问：" + s);
            }
            if (!Files.isRegularFile(local)) {
                return new Fetched(null, false, "Error: 本地文件不存在：" + s + "（相对本任务工作目录）。");
            }
            return new Fetched(local, false, null);
        } catch (Exception e) {
            return new Fetched(null, false,
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

    private static Path download(URI uri, Path target, int maxBytes) throws Exception {
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
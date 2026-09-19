package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文档（PDF）理解流水线，定位是 <b>「PDF 版的图片理解」</b>：调用方给一个<b>自由问题</b>
 * （要 VL 对文档做什么）+ 一个 PDF 来源（URL 或任务沙箱本地路径）+ 可选页范围，服务端把这一批页
 * 栅格化后连同问题一次性交给视觉模型（{@code agent.vision}），直接返回视觉模型的<b>自然语言回答</b>。
 *
 * <p><b>与 {@link VisionClient} 解耦同一原则</b>：页面图像只发到 VL 端点，主模型只拿到文字。
 *
 * <p><b>刻意保持薄</b>：不在服务端做结构化、字段合并、reduce 汇总、格式化——这些交给主模型。
 * 因此本工具不做分批并发：一次只处理一批页（上限 {@code agent.pdf.pages-per-call}，且不超过
 * {@code agent.vision.max-images}）。需要处理更多页时，调用方用 {@code page_range} 分批调用，
 * 并在主模型自己的上下文里汇总。这与 {@code understand_image} 一次若干图、多轮自由问答的用法一致。
 *
 * <p>栅格化用 {@code pdftoppm}（镜像内 poppler-utils），产物写任务沙箱、用完即删；本地来源的
 * 源文件绝不删除。
 */
@Component
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);
    private static final Pattern PAGES = Pattern.compile("^Pages:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern TRAIL_NUM = Pattern.compile("-(\\d+)\\.[A-Za-z0-9]+$");

    private final AgentProperties props;
    private final VisionClient visionClient;
    private final HttpClient http;

    public PdfService(AgentProperties props, VisionClient visionClient) {
        this.props = props;
        this.visionClient = visionClient;
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @param pdfSource PDF 的 http/https 地址，或任务沙箱内的本地文件路径
     * @param question  要对文档做的自由问题/指令；留空则默认逐页转录/说明内容
     * @param pageRange 可选页范围 {@code "a-b"} / {@code "a"} / {@code "a-"}；null=全部页
     * @param taskDir   任务沙箱
     * @return 视觉模型的自然语言回答（已要求引用内容时标注绝对页码）；出错或超页时返回可读说明
     */
    public String understand(String pdfSource, String question, String pageRange, Path taskDir) {
        AgentProperties.Pdfs cfg = props.getPdfs();
        if (!props.getVision().isEnabled()) {
            return "Error: 视觉功能未启用（agent.vision.enabled=false），无法理解 PDF。";
        }
        Path pdf = null;
        boolean downloaded = false;
        try {
            if (isHttpUrl(pdfSource)) {
                URI uri = validateUrl(pdfSource, cfg);
                pdf = download(uri, taskDir, cfg.getMaxDownloadBytes());
                downloaded = true;
            } else {
                // 本地文档：只允许任务沙箱内的文件，绝不删除源文件（可能是调用方预置的）。
                Path local = SandboxPaths.resolveWithin(taskDir, pdfSource);
                if (local == null) {
                    return "Error: 本地文档路径必须在任务工作目录内，已拒绝越界访问：" + pdfSource;
                }
                if (!Files.isRegularFile(local)) {
                    return "Error: 本地文档文件不存在：" + pdfSource + "（相对本任务工作目录）。";
                }
                pdf = local;
            }
            int total = pageCount(pdf);
            int[] range = resolveRange(pageRange, total);
            int start = range[0];
            int end = range[1];
            int pages = end - start + 1;
            int cap = Math.min(cfg.getPagesPerCall(), props.getVision().getMaxImages());
            if (cap < 1) {
                cap = 1;
            }
            log.info("pdf understand src={} total={} range={}-{} pages={} cap={}",
                    pdfSource, total, start, end, pages, cap);

            // 不在服务端分批/reduce：超上限就让主模型自己分批调、自己汇总。
            if (pages > cap) {
                int nextEnd = start + cap - 1;
                return "本文档共 " + total + " 页，本次请求第 " + start + "-" + end + " 页（" + pages
                        + " 页）超过单次上限 " + cap + " 页。请用 page_range 分批调用本工具，例如先 "
                        + "\"" + start + "-" + nextEnd + "\"、再 \"" + (nextEnd + 1) + "-…\"，"
                        + "把各批返回的自然语言结果在你自己的最终回答里汇总。";
            }

            List<String> urls = rasterize(pdf, start, end, 0, taskDir);
            if (urls.isEmpty()) {
                return "Error: 栅格化第 " + start + "-" + end + " 页失败（可能不是有效 PDF）。";
            }
            String q = (question == null || question.isBlank())
                    ? "请逐页转录并说明这一页的标题与主要内容。" : question.trim();
            return visionClient.extractFromDataUrls(urls, buildQaPrompt(start, end, total, q));
        } catch (Exception e) {
            log.error("pdf understand failed src={}", pdfSource, e);
            return "Error: 处理 PDF 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            cleanupTempImages(taskDir);
            if (downloaded && pdf != null) {
                try {
                    Files.deleteIfExists(pdf);
                } catch (Exception ignored) {
                    // 源 PDF 是下载来的临时文件，删失败无妨（taskDir 会随任务清理）
                }
            }
        }
    }

    /** 自由问答提示词：标注绝对页码，要求引用内容时带页码、不编造。 */
    private String buildQaPrompt(int start, int end, int total, String question) {
        return "这是同一份文档的第 " + start + " 到第 " + end + " 页（全文共 " + total
                + " 页中的这一段）。按顺序：第 1 张图=第 " + start + " 页，第 2 张图=第 " + (start + 1)
                + " 页，依此类推。\n只依据这些图片内容作答，不要编造；凡是引用了文档中的具体内容，"
                + "请在后面标注它出现的绝对页码（用上面标好的页码）。\n要求：" + question;
    }

    /** 栅格化 [a..b] 页为 JPEG data URL（按页升序），用完即删临时文件。 */
    private List<String> rasterize(Path pdf, int a, int b, int batchId, Path taskDir) throws Exception {
        AgentProperties.Pdfs cfg = props.getPdfs();
        String prefix = taskDir.resolve("pg" + batchId).toString();
        // 参数顺序必须正确：pdftoppm 对用法错误会直接打 usage 并以退出码 99 退出。
        // -scale-to / -jpegopt 都是带值的选项，不能把它们和自己的值拆开，也不能插到别的选项中间。
        List<String> cmd = new ArrayList<>(List.of("pdftoppm"));
        if (cfg.getMaxImageDimension() > 0) {
            cmd.add("-scale-to");
            cmd.add(String.valueOf(cfg.getMaxImageDimension()));
        }
        cmd.add("-jpeg");
        cmd.add("-jpegopt");
        cmd.add("quality=" + cfg.getJpegQuality());
        cmd.add("-f");
        cmd.add(String.valueOf(a));
        cmd.add("-l");
        cmd.add(String.valueOf(b));
        cmd.add(pdf.toString());
        cmd.add(prefix);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroy();
            throw new IllegalStateException("pdftoppm 超时 pages=" + a + "-" + b);
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException("pdftoppm 退出码 " + proc.exitValue() + ": " + snippet(out));
        }
        final String stem = "pg" + batchId + "-";
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(taskDir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith(stem) && n.toLowerCase().endsWith(".jpg")) {
                    files.add(p);
                }
            });
        }
        files.sort(Comparator.comparingInt(PdfService::trailingNumber));
        List<String> urls = new ArrayList<>(files.size());
        for (Path f : files) {
            byte[] bytes = Files.readAllBytes(f);
            urls.add("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes));
            Files.deleteIfExists(f);
        }
        return urls;
    }

    /** 从 pdftoppm 文件名末尾 -NNN.jpg 取页序号，用于排序。 */
    private static int trailingNumber(Path p) {
        Matcher m = TRAIL_NUM.matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** pdfinfo 取总页数。 */
    private int pageCount(Path pdf) throws Exception {
        Process proc = new ProcessBuilder("pdfinfo", pdf.toString())
                .redirectErrorStream(true).start();
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroy();
            throw new IllegalStateException("pdfinfo 超时");
        }
        Matcher m = PAGES.matcher(out);
        if (!m.find()) {
            throw new IllegalStateException("pdfinfo 未给出页数（可能不是有效 PDF）：" + snippet(out));
        }
        return Integer.parseInt(m.group(1));
    }

    /** 下载 PDF 到沙箱，超过体积上限即中止。 */
    private Path download(URI uri, Path taskDir, int maxBytes) throws Exception {
        Path target = taskDir.resolve("source.pdf");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(120))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("下载 PDF 失败 HTTP " + resp.statusCode());
        }
        long total = 0;
        try (InputStream in = resp.body(); OutputStream os = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("PDF 超过体积上限 " + maxBytes + " 字节");
                }
                os.write(buf, 0, n);
            }
        }
        if (total == 0) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("下载到的 PDF 为空");
        }
        return target;
    }

    /** 校验 URL 协议与 SSRF 白名单。 */
    private URI validateUrl(String url, AgentProperties.Pdfs cfg) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("pdf_url 为空");
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("pdf_url 必须是 http/https，收到：" + url);
        }
        List<String> allow = cfg.getSsrfAllowlist();
        if (allow != null && !allow.isEmpty()) {
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalArgumentException("无法解析主机名：" + url);
            }
            String h = host.toLowerCase();
            boolean ok = false;
            for (String entry : allow) {
                String e = entry == null ? "" : entry.trim().toLowerCase();
                if (!e.isEmpty() && (h.equals(e) || h.endsWith("." + e))) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("主机不在允许的白名单内：" + host);
            }
        }
        return uri;
    }

    private static int[] resolveRange(String pageRange, int total) {
        if (pageRange == null || pageRange.isBlank()) {
            return new int[]{1, total};
        }
        String s = pageRange.trim().replace('－', '-');
        int dash = s.indexOf('-');
        int start;
        int end;
        if (dash < 0) {
            start = Integer.parseInt(s.trim());
            end = start;
        } else {
            String l = s.substring(0, dash).trim();
            String r = s.substring(dash + 1).trim();
            start = l.isEmpty() ? 1 : Integer.parseInt(l);
            end = r.isEmpty() ? total : Integer.parseInt(r);
        }
        start = Math.max(1, start);
        end = Math.min(end, total);
        if (start > end) {
            throw new IllegalArgumentException("页范围无效：" + pageRange + "（总页数 " + total + "）");
        }
        return new int[]{start, end};
    }

    /** 是否 http/https URL（否则视为沙箱本地路径）。 */
    private static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /** 删除残留的页面临时图（只删我们生成的 pg*.jpg；下载来的源 PDF 由调用处按模式决定删不删）。 */
    private void cleanupTempImages(Path taskDir) {
        try {
            if (taskDir != null && Files.isDirectory(taskDir)) {
                try (var stream = Files.list(taskDir)) {
                    stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("pg") && n.toLowerCase().endsWith(".jpg");
                    }).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // 尽力而为
                        }
                    });
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}

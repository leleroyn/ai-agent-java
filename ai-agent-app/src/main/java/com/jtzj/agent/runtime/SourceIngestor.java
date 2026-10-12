package com.jtzj.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.error.AgentException;
import com.jtzj.agent.core.error.ErrorCodes;
import com.jtzj.agent.core.support.MediaInputs;
import com.jtzj.agent.core.support.SourceFiles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务启动前把指令里的文件 URL 变成任务目录里的本地文件，并把指令里的 URL 原地换成相对路径。
 *
 * <p><b>为什么要这一步</b>：指令只给 URL，而每条工具路径（OCR 转文本、印章、发票、PDF 理解、
 * TXT 抽取）都要先把来源落地成文件，同一份 30MB 扫描件在一个混合任务里被下载 3-4 遍，光下载
 * 就 90s+。模型自己 {@code curl -o} 落盘更糟：同一份内容出现「URL」和「本地副本」两个身份，
 * 按来源做的复用全部 miss。这里下载一次、改写一次，工具层就只看本地路径，URL 支持整个删掉。
 *
 * <p>只处理指令文本里的 URL，且只认扩展名白名单（{@link #FILE_EXTS}）——普通网页链接、
 * 接口地址原样留着。下载失败直接失败整个任务（{@link ErrorCodes#SOURCE_UNAVAILABLE}），
 * 不带着坏来源去烧模型的钱。
 */
@Component
public class SourceIngestor {

    private static final Logger log = LoggerFactory.getLogger(SourceIngestor.class);

    /** 下载落点：{@code <taskDir>/src}，工具传来源时就写这个相对路径。 */
    public static final String SRC_DIR = "src";
    /** 清单目录：{@code <taskDir>/.ingest/manifest.json}，事后排查「这个文件是从哪个 URL 来的」。 */
    public static final String MANIFEST_DIR = ".ingest";

    /** 只有这些扩展名的 URL 才当文件抓，其它 URL（网页、接口）不动。 */
    private static final Set<String> FILE_EXTS = Set.of(
            ".pdf", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".txt");

    /**
     * 指令里的 URL。空白和引号之外，还把中文标点/CJK 排除在 URL 之外——指令是中文的，
     * 「…pdf，抽取字段」这种写法必须在这里断开，否则逗号进了 URL，下载必然 404。
     */
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s\"'<>()\\[\\]{}|\\\\^`\u3000-\u303F\u4E00-\u9FFF\uFF00-\uFFFF]+");

    /** 匹配尾部可能粘上的英文标点：{@code see http://x/a.pdf.} 里的句号。 */
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[.,;:!?]+$");

    /** 文件名里不允许的字符（Windows/POSIX 取并集）；中文保留。 */
    private static final Pattern ILLEGAL_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}\\s]+");

    private static final int MAX_STEM = 60;

    private final AgentProperties props;
    private final ObjectMapper mapper;

    public SourceIngestor(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 一个已就绪的来源。{@code path} 是相对任务目录的路径，直接喂给工具。 */
    public record Entry(String url, String path, long bytes, String sha256) {
    }

    /** 改写结果：{@code instruction} 里 URL 已换成本地路径；{@code entries} 为空表示没动过。 */
    public record Ingested(String instruction, List<Entry> entries) {
    }

    /**
     * @throws AgentException SOURCE_UNAVAILABLE 当某个文件 URL 抓不下来（4xx/超限不重试，
     *                        5xx/超时/网络错误可重试）
     */
    public Ingested ingest(String instruction, Path taskDir) {
        if (instruction == null || instruction.isBlank()) {
            return new Ingested(instruction, List.of());
        }
        List<String> urls = fileUrls(instruction);
        if (urls.isEmpty()) {
            return new Ingested(instruction, List.of());
        }
        Path srcDir = taskDir.resolve(SRC_DIR);
        List<Entry> entries = new ArrayList<>(urls.size());
        String rewritten = instruction;
        int index = 0;
        for (String url : urls) {
            index++;
            String name = index + "-" + safeName(url);
            Path target = srcDir.resolve(name);
            long bytes;
            String sha;
            try {
                Files.createDirectories(srcDir);
                // 抓取通道只有一条，SSRF 主机白名单也必须在这条上生效
                URI uri = SourceFiles.validateUrl(url, props.getPdfs().getSsrfAllowlist());
                SourceFiles.download(uri, target, props.getPdfs().getMaxDownloadBytes());
                bytes = Files.size(target);
                sha = SourceFiles.sha256(target);
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(target);
                } catch (Exception ignored) {
                    // 半截文件删不掉也不影响：任务目录整体由 delete-workspace-on-finish 处理
                }
                log.warn("ingest failed task={} url={} target={}: {}",
                        taskDir.getFileName(), url, target, e.toString());
                throw new AgentException(ErrorCodes.SOURCE_UNAVAILABLE,
                        "指令里的文件取不到：" + url + "（" + e.getClass().getSimpleName()
                                + ": " + e.getMessage() + "）", retryable(e), e);
            }
            String relative = SRC_DIR + "/" + name;
            entries.add(new Entry(url, relative, bytes, sha));
            log.info("ingest ok task={} url={} -> {} bytes={} sha256={}",
                    taskDir.getFileName(), url, relative, bytes, sha.substring(0, 12));
        }
        // 改写放在下载之后，且长 URL 先替换：短 URL 可能是长 URL 的前缀
        // （http://h/a.pdf 与 http://h/a.pdf?v=1），先替换短的会把长的那条截断。
        List<Entry> longestFirst = new ArrayList<>(entries);
        longestFirst.sort(Comparator.comparingInt((Entry e) -> e.url().length()).reversed());
        for (Entry e : longestFirst) {
            rewritten = rewritten.replace(e.url(), e.path());
        }
        writeManifest(taskDir, entries);
        return new Ingested(rewritten + note(entries), entries);
    }

    /** 指令里要抓的文件 URL：按出现顺序去重，只留扩展名白名单内的。 */
    static List<String> fileUrls(String instruction) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = URL.matcher(instruction);
        while (m.find()) {
            String url = TRAILING_PUNCT.matcher(m.group()).replaceAll("");
            if (FILE_EXTS.contains(MediaInputs.extension(url))) {
                found.add(url);
            }
        }
        return new ArrayList<>(found);
    }

    /** URL 末段→安全文件名：去 query/fragment，过滤非法字符，主干截 {@value #MAX_STEM}，扩展名小写。 */
    static String safeName(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        String ext = MediaInputs.extension(s);
        String stem = ext.isEmpty() ? s : s.substring(0, s.length() - ext.length());
        stem = ILLEGAL_NAME_CHARS.matcher(stem).replaceAll("_")
                .replaceAll("^\\.+", "").replaceAll("[ .]+", "").trim();
        if (stem.isEmpty()) {
            stem = "file";
        }
        if (stem.length() > MAX_STEM) {
            stem = stem.substring(0, MAX_STEM);
        }
        return (stem + ext).toLowerCase(Locale.ROOT);
    }

    private static String note(List<Entry> entries) {
        StringBuilder sb = new StringBuilder("\n\n（系统说明：指令里的文件已下载到本任务工作目录，来源直接传这些路径：");
        for (Entry e : entries) {
            sb.append(' ').append(e.path()).append("（").append(e.bytes()).append(" 字节）;");
        }
        sb.append(" 工具不接受 URL，也不得用 shell 下载。）");
        return sb.toString();
    }

    private void writeManifest(Path taskDir, List<Entry> entries) {
        try {
            Path dir = taskDir.resolve(MANIFEST_DIR);
            Files.createDirectories(dir);
            ArrayNode array = mapper.createArrayNode();
            for (Entry e : entries) {
                ObjectNode node = array.addObject();
                node.put("url", e.url());
                node.put("path", e.path());
                node.put("bytes", e.bytes());
                node.put("sha256", e.sha256());
            }
            Files.writeString(dir.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(array));
        } catch (Exception e) {
            // 清单只是排查用的旁路信息，写失败不能影响任务
            log.warn("cannot write ingest manifest: {}", e.toString());
        }
    }

    /** 4xx、体积超限、空文件、非法 URL 重试没意义；5xx、超时、网络错误值得重试。 */
    private static boolean retryable(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return false;
        }
        String message = String.valueOf(e.getMessage());
        if (message.contains("HTTP 4") || message.contains("体积上限") || message.contains("为空")) {
            return false;
        }
        return true;
    }
}

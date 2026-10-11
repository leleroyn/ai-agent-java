package com.jtzj.agent.tools;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.core.support.MediaInputs;
import com.jtzj.agent.core.support.SourceFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code recognize_seal} / {@code recognize_invoice} 共用的「来源 -&gt; 字节 -&gt; 调一次接口」管线。
 *
 * <p>三件事：
 * <ul>
 *   <li><b>来源直接吃 URL</b>，下载在工具内部完成。模型不必也不该先 {@code curl -o} 落盘——
 *       一旦落盘，同一份内容就有了「URL」和「本地副本」两个身份，后续调用全变成本地路径。</li>
 *   <li><b>不在内存里留副本</b>：识别响应只走一遍，工具实例不缓存任何东西。这两个接口都不便宜，
 *       但把响应压在堆上换一次复用不值得，省时间靠提示模型「每个来源只调一次」。</li>
 *   <li><b>单个来源失败只影响它自己</b>，其余来源照常返回，错误原因按来源带出。</li>
 * </ul>
 */
final class IpsRecognitionSupport {

    private static final Logger log = LoggerFactory.getLogger(IpsRecognitionSupport.class);

    private IpsRecognitionSupport() {
    }

    /** 拿到文件字节后调具体接口（印章或发票）。 */
    interface Call {
        String invoke(byte[] fileBytes, String filename, boolean returnOcrText, Double initConfidence);
    }

    /** 单个来源的结果：{@code error} 与 {@code raw}（接口响应原文）二者其一。 */
    record Outcome(String source, String error, String raw) {
        boolean ok() {
            return error == null;
        }
    }

    static List<Outcome> callAll(String sourceRaw, Path taskDir, Path tempDir,
                                 AgentProperties props, Call call, boolean returnOcrText,
                                 Double initConfidence) {
        List<String> refs = MediaInputs.parse(sourceRaw);
        List<Outcome> outcomes = new ArrayList<>(refs.size());
        int maxBytes = props.getPdfs().getMaxDownloadBytes();
        for (String ref : refs) {
            SourceFiles.Fetched fetched = SourceFiles.fetch(ref, taskDir, tempDir, maxBytes);
            if (!fetched.ok()) {
                outcomes.add(new Outcome(ref, fetched.error(), null));
                continue;
            }
            try {
                String raw = call.invoke(Files.readAllBytes(fetched.file()),
                        filenameOf(ref), returnOcrText, initConfidence);
                if (raw.startsWith("Error:")) {
                    outcomes.add(new Outcome(ref, raw, null));
                } else {
                    outcomes.add(new Outcome(ref, null, raw));
                }
            } catch (Exception e) {
                outcomes.add(new Outcome(ref,
                        "Error: 读取文件失败：" + e.getClass().getSimpleName() + ": " + e.getMessage(), null));
            } finally {
                if (fetched.temp()) {
                    try {
                        Files.deleteIfExists(fetched.file());
                    } catch (Exception ignored) {
                        // 临时下载文件删不掉不影响结果，任务结束时随 .ocr/ 一起清理
                    }
                }
            }
        }
        return outcomes;
    }

    /** multipart 里的文件名：服务端按魔数判类型，这里只为可读性。取来源末段，去 query。 */
    static String filenameOf(String ref) {
        String s = ref.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        return s.isBlank() ? "document" : s;
    }

    static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
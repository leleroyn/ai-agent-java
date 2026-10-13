package com.jtzj.agent.core.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步调用的 PDF 页窗口：调用方正阻塞等结果，长 PDF 只取首尾各 {@value #SYNC_EDGE_PAGES} 页。
 *
 * <p><b>裁剪发生在文件落盘那一刻</b>：{@code SourceIngestor} 下载完就地改写 {@code src/} 下的文件。
 * 工具是大模型调的，来源身份只有「任务目录里的这个文件」一种——文件本身只剩 10 页，
 * OCR、PDF 理解、{@code ips} CLI 全部自动只看这 10 页，不需要把「是不是同步」透传进每条工具路径，
 * 也不会出现「工具 A 看了全文、工具 B 只看了首尾」这种同一任务内不一致。
 *
 * <p>页码一律 1-based 闭区间。用镜像里已有的 poppler（{@code pdfinfo}/{@code pdfseparate}/
 * {@code pdfunite}）做，不引 PDF 库：这三条命令本来就是 PDF 光栅化那条路径在用的。
 */
public final class PageWindow {

    private static final Logger log = LoggerFactory.getLogger(PageWindow.class);

    /** 首、尾各保留的页数。 */
    public static final int SYNC_EDGE_PAGES = 5;

    private static final Pattern PAGES = Pattern.compile("^Pages:\\s*(\\d+)", Pattern.MULTILINE);
    /** {@code pdfseparate} 用页号填 {@code %d}，按页号排序才是文档顺序。 */
    private static final Pattern SEP = Pattern.compile("^(.*)-(\\d+)\\.pdf$");

    private PageWindow() {
    }

    /** 页数超过首尾之和才需要裁；相等时首尾正好覆盖全文，裁了没意义。 */
    public static boolean applies(int total) {
        return total > SYNC_EDGE_PAGES * 2;
    }

    /** 要保留的页区间；不需要裁时返回空表。 */
    public static List<int[]> edgeSegments(int total) {
        if (!applies(total)) {
            return List.of();
        }
        return List.of(new int[]{1, SYNC_EDGE_PAGES},
                new int[]{total - SYNC_EDGE_PAGES + 1, total});
    }
    /** 给人看的一句话，例如「前 5 页与后 5 页（第 6-67 页未处理）」。 */
    public static String describe(int total) {
        List<int[]> segments = edgeSegments(total);
        if (segments.isEmpty()) {
            return "全部 " + total + " 页";
        }
        int gapFrom = segments.get(0)[1] + 1;
        int gapTo = segments.get(segments.size() - 1)[0] - 1;
        return "前 " + SYNC_EDGE_PAGES + " 页与后 " + SYNC_EDGE_PAGES + " 页（第 "
                + gapFrom + "-" + gapTo + " 页未处理）";
    }

    /**
     * 就地改写 {@code pdf}：只留首尾各 {@value #SYNC_EDGE_PAGES} 页，中间页从文件里消失。
     * 分片写在 {@code workDir} 下（任务目录内，不落到沙箱外）。
     *
     * @return 原页数；不需要裁或裁不动时返回 -1，调用方按「没裁」继续——一个可选优化不该判死整个任务
     */
    public static int truncateToEdges(Path pdf, Path workDir) {
        int total;
        try {
            total = pageCount(pdf);
        } catch (Exception e) {
            log.warn("cannot read pdf page count {}: {}", pdf, e.toString());
            return -1;
        }
        if (edgeSegments(total).isEmpty()) {
            return -1;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory(workDir, "win-");
            List<Path> parts = new ArrayList<>();
            for (int[] segment : edgeSegments(total)) {
                String prefix = "s" + segment[0] + "-";
                exec("pdfseparate", "-f", String.valueOf(segment[0]),
                        "-l", String.valueOf(segment[1]), pdf.toString(),
                        tmp.resolve(prefix + "%d.pdf").toString());
                parts.addAll(orderedParts(tmp, prefix));
            }
            List<String> command = new ArrayList<>();
            command.add("pdfunite");
            for (Path part : parts) {
                command.add(part.toString());
            }
            command.add(tmp.resolve("windowed.pdf").toString());
            exec(command.toArray(String[]::new));
            Files.move(tmp.resolve("windowed.pdf"), pdf, StandardCopyOption.REPLACE_EXISTING);
            log.info("sync window pdf={} total={} kept={}", pdf, total, describe(total));
            return total;
        } catch (Exception e) {
            log.warn("cannot window pdf {}, keep full file: {}", pdf, e.toString());
            return -1;
        } finally {
            deleteTree(tmp);
        }
    }

    /** {@code pdfinfo} 的 {@code Pages:} 行。 */
    public static int pageCount(Path pdf) throws Exception {
        String out = exec("pdfinfo", pdf.toString());
        Matcher m = PAGES.matcher(out);
        if (!m.find()) {
            throw new IllegalStateException("pdfinfo 没有 Pages 行：" + out.trim());
        }
        return Integer.parseInt(m.group(1));
    }

    /** {@code pdfseparate} 的 %d 是页号，按页号排序才是文档顺序。 */
    private static List<Path> orderedParts(Path dir, String prefix) throws Exception {
        List<Path> found = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                if (SEP.matcher(file.getFileName().toString()).matches()
                        && file.getFileName().toString().startsWith(prefix)) {
                    found.add(file);
                }
            }
        }
        found.sort(Comparator.comparingInt(PageWindow::pageNumber));
        if (found.isEmpty()) {
            throw new IllegalStateException("pdfseparate 没有产出 " + prefix + " 分片");
        }
        return found;
    }

    private static int pageNumber(Path file) {
        Matcher m = SEP.matcher(file.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /** 输出（含 stderr）一律读干：不读会在管道满时卡死；失败时把它带进异常信息，方便定位坏 PDF。 */
    private static String exec(String... command) throws Exception {
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException(command[0] + " 超时");
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException(command[0] + " 退出码 " + proc.exitValue() + "：" + out.trim());
        }
        return out;
    }

    /** 分片是中间产物，用完就删；任务目录整体的清理开关不管这一步。 */
    private static void deleteTree(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // 删不掉也不影响结果
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}

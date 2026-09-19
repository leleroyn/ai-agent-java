package com.example.agent.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把调用方给的「本地文件」参数解析到任务沙箱目录内，拒绝任何越权访问。
 *
 * <p>工具（{@code understand_image} / {@code understand_document}）除了 URL，也接受任务沙箱里的
 * 本地路径（例如模型用 shell 下载、或调用方预置到 {@code <working-dir>/<taskId>} 下的文件）。
 * 但绝不能让模型借此读取沙箱外的宿主文件，所以所有本地路径都必须在 {@code taskDir} 之内。
 *
 * <p>相对路径按 {@code taskDir} 解析；绝对路径也允许，但归一化后必须仍在 {@code taskDir} 之下，
 * 否则返回 {@code null}。归一化会消解 {@code ..}，因此 {@code ../} 逃逸同样被拒。
 */
public final class SandboxPaths {

    private SandboxPaths() {
    }

    /**
     * @return 归一化后确在 {@code taskDir} 内的绝对路径；越界或非法返回 {@code null}。
     */
    public static Path resolveWithin(Path taskDir, String raw) {
        if (taskDir == null || raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path base = taskDir.toAbsolutePath().normalize();
            Path p = Paths.get(raw.trim());
            if (!p.isAbsolute()) {
                p = base.resolve(p);
            }
            p = p.normalize();
            return p.startsWith(base) ? p : null;
        } catch (Exception e) {
            // 非法路径字符等，一律当作不允许
            return null;
        }
    }
}

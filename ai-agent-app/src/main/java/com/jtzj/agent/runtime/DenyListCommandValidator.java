package com.jtzj.agent.runtime;

import io.agentscope.core.tool.coding.CommandValidator;
import io.agentscope.core.tool.coding.UnixCommandValidator;
import io.agentscope.core.tool.coding.WindowsCommandValidator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 框架默认命令校验 + 可配置的命令黑名单，以及一份<b>逐段</b>命令白名单。
 *
 * <p>为什么不直接用框架白名单：{@code UnixCommandValidator} 在白名单非空时，会把任何含
 * {@code & && || | ; 换行} 的命令一律拒掉（它只比对整条命令的第一个 token，判不到分段），
 * 混合任务里「跑一条命令再 grep 结果」这类用法就全废了。所以白名单非空时改由本类自己判：
 * 按 {@code ; | & 换行} 拆段，<b>每段</b>的首个 token 都必须在白名单内，管道与组合命令照常可用。
 *
 * <p>黑名单先判，白名单后判，两条规则都按段取首个 token：去引号、转小写。黑名单条目允许两种写法：
 * <ul>
 *   <li>裸名（{@code curl}）——按 token 的 basename 命中，所以 {@code curl}、{@code /usr/bin/curl}、
 *       {@code "curl" -o x} 都能掐掉；</li>
 *   <li>带路径（{@code native/ips}）——按 token 路径<b>后缀</b>命中，用来堵「白名单只写了
 *       {@code ips}，模型却用绝对路径直调 {@code /opt/agent-cli/native/ips} 绕过包装脚本」这条路。</li>
 * </ul>
 *
 * <p>黑名单默认掐 {@code curl} / {@code wget}：指令里的文件 URL 已在任务启动前由
 * {@link SourceIngestor} 下载到 {@code src/}，工具也只吃本地路径。模型再自己下一遍，同一份
 * 内容就会出现两个身份（URL 与本地路径），去重失效——实测代价是把 72 页扫描件重新 OCR 一次。
 *
 * <p>白名单默认空（不限可执行文件，行为与改动前一致）。运维要「shell 除 ips 外不能读本地文件」就配
 * {@code AGENT_SHELL_ALLOWED=ips}：那时 {@code cat}/{@code grep}/{@code head}/{@code python} 等
 * 一律被拒，只有 {@code ips} 可跑。注意这只掐 shell——{@code view_text_file} 等文件工具仍读本任务
 * 目录内的文件，要一并关就设 {@code AGENT_TOOL_READ=false}。
 *
 * <p>被拒的命令不会执行：{@code ShellCommandTool} 拿到 rejected 后走审批回调，本服务没有配
 * 审批回调，于是直接返回 {@code SecurityError: ...} 给模型，模型能据此改用工具。
 */
public class DenyListCommandValidator implements CommandValidator {

    private static final Logger log = LoggerFactory.getLogger(DenyListCommandValidator.class);

    /** 段分隔符：单字符类同时覆盖 {@code &&}、{@code ||}（拆出空段，忽略即可）。 */
    private static final String SEGMENT_SPLIT = "[;&|\\n]";

    /** 与框架 {@code extractExecutable} 保持一致：这些后缀不属于可执行名的一部分。 */
    private static final List<String> SCRIPT_EXTENSIONS =
            List.of(".sh", ".py", ".rb", ".pl", ".bash", ".zsh");

    private final CommandValidator delegate;
    private final Set<String> denied;

    public DenyListCommandValidator(List<String> deniedCommands) {
        this.delegate = isWindows() ? new WindowsCommandValidator() : new UnixCommandValidator();
        Set<String> normalized = new LinkedHashSet<>();
        if (deniedCommands != null) {
            for (String command : deniedCommands) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                normalized.add(command.trim().replace('\\', '/').toLowerCase(Locale.ROOT));
            }
        }
        this.denied = normalized;
    }

    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        if (command == null || command.isBlank()) {
            return delegate.validate(command, allowedCommands);
        }
        List<String> tokens = executableTokens(command);

        for (String token : tokens) {
            String rule = deniedMatch(token);
            if (rule != null) {
                log.info("shell command denied by blacklist: '{}' (token '{}', rule '{}')",
                        command, token, rule);
                return ValidationResult.rejected(
                        "Command '" + rule + "' is denied by agent.tools.shell-denied-commands", token);
            }
        }

        Set<String> allowed = allowedNames(allowedCommands);
        if (allowed.isEmpty()) {
            // 白名单为空：完全交给框架判（框架此时一律放行），行为与改动前一致。
            return delegate.validate(command, allowedCommands);
        }

        for (String token : tokens) {
            String reason = whitelistRejection(token, allowed);
            if (reason != null) {
                log.info("shell command denied by whitelist: '{}' (token '{}', allowed {})",
                        command, token, allowed);
                return ValidationResult.rejected(reason, token);
            }
        }
        return ValidationResult.allowed(tokens.isEmpty() ? "" : executableName(tokens.get(0)));
    }

    @Override
    public String extractExecutable(String command) {
        return delegate.extractExecutable(command);
    }

    /**
     * 委托给平台校验器。框架只在白名单非空时用它拒绝含 {@code & | ; 换行} 的命令，本类在白名单
     * 非空时自己逐段判、不走这条，所以这里保持委托只为接口完整。
     */
    @Override
    public boolean containsMultipleCommands(String command) {
        return delegate.containsMultipleCommands(command);
    }

    /** 白名单非空时：段内首个 token 不合法的原因，合法返回 {@code null}。 */
    private String whitelistRejection(String token, Set<String> allowed) {
        String path = token.replace('\\', '/');
        if (path.startsWith("../")) {
            return "Command '" + token + "' escapes current directory";
        }
        if (path.startsWith("./") && !delegate.isPathWithinCurrentDirectory(path)) {
            return "Command '" + token + "' escapes current directory";
        }
        String name = executableName(token);
        if (allowed.contains(name)) {
            return null;
        }
        return "Command '" + name + "' is not in the allowed whitelist";
    }

    /** 黑名单命中则返回命中的条目，否则 {@code null}。 */
    private String deniedMatch(String token) {
        if (denied.isEmpty()) {
            return null;
        }
        String path = token.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = basename(path);
        if (denied.contains(base)) {
            return base;
        }
        for (String entry : denied) {
            if (!entry.contains("/")) {
                continue;
            }
            if (path.equals(entry) || path.endsWith("/" + entry)) {
                return entry;
            }
        }
        return null;
    }

    /** 整条命令按段取首个 token（保留路径，去引号）。 */
    private static List<String> executableTokens(String command) {
        List<String> out = new ArrayList<>();
        for (String segment : command.split(SEGMENT_SPLIT)) {
            String token = firstToken(segment);
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private static Set<String> allowedNames(Set<String> allowedCommands) {
        Set<String> out = new LinkedHashSet<>();
        if (allowedCommands != null) {
            for (String entry : allowedCommands) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                out.add(executableName(entry.trim()));
            }
        }
        return out;
    }

    /** 段内第一个 token → 去引号（保留路径）。 */
    private static String firstToken(String segment) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first == '"' || first == '\'') {
            int endQuote = trimmed.indexOf(first, 1);
            if (endQuote > 0) {
                return trimmed.substring(1, endQuote);
            }
        }
        int space = indexOfWhitespace(trimmed);
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    /** token → 去路径 → 去脚本后缀 → 小写，即可执行名。 */
    private static String executableName(String token) {
        String name = basename(token.replace('\\', '/'));
        for (String extension : SCRIPT_EXTENSIONS) {
            if (name.endsWith(extension) && name.length() > extension.length()) {
                return name.substring(0, name.length() - extension.length());
            }
        }
        return name;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
package com.example.agent.agent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 获取系统当前时间的工具，默认全局启用（{@code agent.tools.system-time=true}）。
 *
 * <p><b>为什么需要它</b>：模型不知道现在是何年何月。它的知识有截止日期，任何涉及
 * 「今天」「最近三天」「是否已过期」「距离发货还有几天」的判断，只要靠猜就会出错，而且错得
 * 很自信。给它一个确定的时间基准，是这类任务能做对的前提。
 *
 * <p>返回值同时给出本地时间、ISO-8601 带偏移、星期和 Unix 秒级时间戳：前者便于模型引用与
 * 写进输出，后两者便于它做日期差计算而不容易算错。
 *
 * <p>这个类也是自定义工具的标准写法样板：
 * <ul>
 *   <li>{@link Tool} 标在方法上，框架反射生成 schema；{@code name} 用 snake_case。</li>
 *   <li>{@link ToolParam} 的 {@code name} 必填（Java 运行时不保留形参名）。</li>
 *   <li>返回 {@code String}，作为工具结果直接进入对话上下文。</li>
 *   <li>失败返回 {@code "Error: ..."} 文本而不是抛异常——只有拿到可读的错误文本，模型才会
 *       换参数重试；抛异常只会让这次工具调用变成失败记录。</li>
 *   <li>{@code readOnly=true}：权限模式收紧到 {@code EXPLORE} 时只放行只读工具，漏标会导致
 *       工具在那种模式下静默失效。</li>
 *   <li>无状态，注册为 Spring 单例、复用到每个任务的 Toolkit 是安全的。</li>
 * </ul>
 *
 * <p>注册点见 {@code ToolkitFactory#build}。
 */
@Component
public class SystemTimeTools {

    private static final DateTimeFormatter LOCAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private static final DateTimeFormatter ISO_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * @param timezone 可选的 IANA 时区名；省略则用服务器默认时区
     * @return 当前时间的多种表示，或参数错误说明
     */
    @Tool(
            name = "get_system_time",
            description = "获取服务器当前日期和时间，并且可以直接算出「N 天后／前天」的目标日期。"
                    + "凡是任务涉及「今天」「昨天」「最近几天」「是否已过期」"
                    + "「距离某个日期还有多久」，或者需要给输出盖上当前时间，都必须先调用本工具，"
                    + "禁止凭记忆或猜测推断现在几点。"
                    + "需要偏移时直接传 offset_days / offset_hours（例如问“3 天后是哪天”就传"
                    + "offset_days=3），一次调用就能拿到目标日期；不要用 shell 的 date 命令做日期运算。"
                    + "返回内容包含：本地时间、ISO-8601（带时区偏移）、星期、Unix 秒级时间戳，"
                    + "以及偏移时的目标日期。可选参数 timezone 用于切换时区。",
            readOnly = true,
            concurrencySafe = true)
    public String getSystemTime(
            @ToolParam(
                    name = "timezone",
                    description = "IANA 时区名，例如 Asia/Shanghai、UTC、America/New_York；"
                            + "省略则返回服务器默认时区的时间",
                    required = false)
            String timezone,
            @ToolParam(
                    name = "offset_days",
                    description = "须要结果里附带“若干天后／前天”的目标日期时传入：相对今天的偏移"
                            + "天数，可为负（-1 表示昨天，3 表示 3 天后）。省略则只返回当前时间。"
                            + "日期计算一律用本参数，不要调 shell 的 date 命令。",
                    required = false)
            Integer offsetDays,
            @ToolParam(
                    name = "offset_hours",
                    description = "相对当前时刻的偏移小时数，可为负；与 offset_days 可叠加。省略记为 0",
                    required = false)
            Integer offsetHours) {

        ZoneId zone;
        try {
            zone = (timezone == null || timezone.isBlank())
                    ? ZoneId.systemDefault()
                    : ZoneId.of(timezone.trim());
        } catch (Exception e) {
            // 告诉模型合法取值，它才有机会换个写法重试。
            return "Error: 无法识别的时区 '" + timezone + "'。请使用 IANA 时区名，"
                    + "例如 Asia/Shanghai、UTC、America/New_York；或省略该参数使用服务器默认时区。";
        }

        ZonedDateTime now = ZonedDateTime.now(zone);
        DayOfWeek day = now.getDayOfWeek();

        StringBuilder sb = new StringBuilder();
        sb.append("当前时间: ").append(now.format(LOCAL_FORMAT)).append('\n');
        sb.append("ISO-8601: ").append(now.format(ISO_OFFSET)).append('\n');
        sb.append("时区: ").append(zone.getId()).append('\n');
        sb.append("星期: ").append(day.getDisplayName(TextStyle.FULL, Locale.CHINA))
                .append(" (").append(day.getValue()).append("/7)").append('\n');
        sb.append("Unix 时间戳(秒): ").append(now.toEpochSecond());

        // 把日期运算也在工具里做完：模型拿到确切的目标日期就不需要再转一轮推理，
        // 也不会去调 shell 的 date（那是个在 Windows 上会挂死的坑）。
        int days = offsetDays == null ? 0 : offsetDays;
        int hours = offsetHours == null ? 0 : offsetHours;
        if (days != 0 || hours != 0) {
            ZonedDateTime target = now.plusDays(days).plusHours(hours);
            sb.append('\n');
            sb.append("偏移量: ").append(days).append(" 天 ").append(hours).append(" 小时\n");
            sb.append("目标时间: ").append(target.format(LOCAL_FORMAT)).append('\n');
            sb.append("目标日期: ").append(target.toLocalDate())
                    .append(" ")
                    .append(target.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
        }
        return sb.toString();
    }
}

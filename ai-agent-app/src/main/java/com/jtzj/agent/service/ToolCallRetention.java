package com.jtzj.agent.service;

import com.jtzj.agent.core.config.AgentProperties;
import com.jtzj.agent.store.TaskStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * agent_tool_call 的保留期清理。那是一张只写的行存表：每个任务多 N 行，只涨不缩。
 * 每天 {@link #RUN_AT} 删一次 {@code created_at} 早于 {@code agent.trace.retention-days}
 * （默认 30 天）的行；置 0 就是不清理，留给要做长期统计的部署。
 *
 * <p>与 {@link TaskPoller} 同款：自带一根 daemon 线程，不引 Spring 的 {@code @EnableScheduling}。
 * 删除按挂钟排在凌晨：那是一天里任务量最低的窗口，批量 DELETE 的行锁最难和任务撞车。
 *
 * <p>多实例部署时每个实例各删一遍——DELETE 按条件写，重复执行没有副作用，只是多几次扫描
 * （索引由 {@code db/migration-008-tool-call-retention.sql} 建）。重启当天可能多跑一次，同理无害。
 *
 * <p>清理失败不影响任何任务：子表缺失时 store 返回 0，SQL 异常在这里吞掉只留一条 WARN。
 * 链本身也从来不在任务链路上（写它在终态之后、整段 try/catch，见 §14）。
 */
@Component
public class ToolCallRetention implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ToolCallRetention.class);
    /** 每天几点删，容器本地时区。分钟取 30 是为了避开整点那一堆定时任务。 */
    private static final LocalTime RUN_AT = LocalTime.of(3, 30);
    private static final long PERIOD_MILLIS = Duration.ofDays(1).toMillis();

    private final TaskStore store;
    private final AgentProperties props;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "agent-tool-call-retention");
                thread.setDaemon(true);
                return thread;
            });

    public ToolCallRetention(TaskStore store, AgentProperties props) {
        this.store = store;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int days = props.getTrace().getRetentionDays();
        if (days <= 0) {
            log.info("tool-call retention off (agent.trace.retention-days={})", days);
            return;
        }
        long firstDelayMillis = millisUntilNextRun();
        scheduler.scheduleAtFixedRate(this::purgeQuietly, firstDelayMillis, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        log.info("tool-call retention scheduled: daily at {}, keep {} days of agent_tool_call, first run in {} min",
                RUN_AT, days, Duration.ofMillis(firstDelayMillis).toMinutes());
    }

    private void purgeQuietly() {
        int days = props.getTrace().getRetentionDays();
        if (days <= 0) {
            return;
        }
        try {
            int rows = store.deleteToolCallsOlderThan(Instant.now().minus(Duration.ofDays(days)));
            if (rows > 0) {
                log.info("agent_tool_call purged rows={} olderThanDays={}", rows, days);
            } else {
                log.debug("agent_tool_call purge: nothing older than {} days", days);
            }
        } catch (RuntimeException e) {
            log.warn("agent_tool_call purge failed: {}", e.toString());
        }
    }

    /**
     * 到下一个 {@link #RUN_AT} 还有多少毫秒。
     *
     * <p>必须自己算成毫秒：{@code scheduleAtFixedRate} 的延迟和周期共用同一个 TimeUnit，
     * 直接传 {@code (5, 24, HOURS)} 排的是「5 小时后开始、每 24 小时一次」，不是 5 分钟。
     */
    private static long millisUntilNextRun() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.toLocalDate().atTime(RUN_AT).atZone(now.getZone());
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
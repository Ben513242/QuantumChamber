package dev.quantumchamber.collapse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * collapse package 的 JUnit 共用的 log capture（F4a fix round 2，m-8：取代 {@code CollapseAuthoritiesTest}、{@code CollapseServiceTest}、
 * {@code DestinationResolverTest} 各自的 log4j appender）。只收 {@code quantumchamber} logger 的 log，每一則記下等級、格式化之後的訊息、附帶的例外，
 * 以及是否帶 {@link CollapseService#LOG_MARKER}（含子 marker）與子 marker {@link CollapseService#TICK_LOG_MARKER}。
 * <p>建立時先以 {@code LOG_MARKER} 送一則 INFO 探針，確認攔得到 log 與 marker，之後清空；攔不到時關閉並丟 {@link IllegalStateException}。
 * INFO 是三個 test 斷言的最低等級，所以探針同時證明 WARN 與 ERROR 也攔得到。以 try-with-resources 使用，關閉時移除 appender。
 */
final class CollapseLogCapture extends AbstractAppender implements AutoCloseable {
    /** 一則 log：{@code marked} 是帶 {@code LOG_MARKER}（含子 marker），{@code tick} 是帶子 marker {@code TICK_LOG_MARKER}。 */
    record Entry(Level level, String message, Throwable thrown, boolean marked, boolean tick) { }

    private final LoggerContext context;
    private final List<Entry> events = Collections.synchronizedList(new ArrayList<>());

    CollapseLogCapture() {
        super("collapse-log-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
        context = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger("quantumchamber")).getContext();
        start();
        context.getConfiguration().getRootLogger().addAppender(this, null, null);
        context.updateLoggers();
        var probe = "COLLAPSE_LOG_CAPTURE_PROBE " + UUID.randomUUID();
        org.slf4j.LoggerFactory.getLogger("quantumchamber").info(CollapseService.LOG_MARKER, probe);
        if (entries().stream().noneMatch(entry -> entry.message().equals(probe) && entry.marked())) {
            close();
            throw new IllegalStateException("log capture 無法觀察 quantumchamber 的 log 與 LOG_MARKER");
        }
        events.clear();
    }

    @Override public void append(LogEvent event) {
        if (!"quantumchamber".equals(event.getLoggerName())) return;
        var marker = event.getMarker();
        events.add(new Entry(event.getLevel(), event.getMessage().getFormattedMessage(), event.getThrown(),
                marker != null && marker.isInstanceOf(CollapseService.LOG_MARKER.getName()),
                marker != null && marker.isInstanceOf(CollapseService.TICK_LOG_MARKER.getName())));
    }

    /** 目前攔到的全部 log（依發生順序）。 */
    List<Entry> entries() { synchronized (events) { return List.copyOf(events); } }

    /** 這個等級的 log 的訊息（依發生順序）。 */
    List<String> messages(Level level) { return entries().stream().filter(entry -> entry.level() == level).map(Entry::message).toList(); }

    /** 這個等級的 log 數。 */
    int count(Level level) { return (int) entries().stream().filter(entry -> entry.level() == level).count(); }

    @Override public void close() {
        context.getConfiguration().getRootLogger().removeAppender(getName());
        context.updateLoggers();
        stop();
    }
}

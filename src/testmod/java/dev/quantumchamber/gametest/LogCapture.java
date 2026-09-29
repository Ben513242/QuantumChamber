package dev.quantumchamber.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * 僅 testmod：在 root LoggerConfig 旁掛一個只收指定 logger 名稱的 appender，觀察 production 的 WARN／INFO 分流、cause 與去重。
 * 不替 production logger 建立獨立 LoggerConfig，因此既有 console／latest.log 輸出不受影響；release JAR 不含此類。
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {
    record Entry(Level level,String message,Throwable thrown) {}
    private final String loggerName;
    private final org.apache.logging.log4j.core.LoggerContext loggerContext;
    private final List<Entry> entries=Collections.synchronizedList(new ArrayList<>());

    LogCapture(String name) {
        super("quantumchamber-ifix1-capture-"+UUID.randomUUID(),null,null,true,Property.EMPTY_ARRAY);
        loggerName=name;
        loggerContext=((org.apache.logging.log4j.core.Logger)LogManager.getLogger(name)).getContext();
        start();
        loggerContext.getConfiguration().getRootLogger().addAppender(this,null,null);
        loggerContext.updateLoggers();
        // 以同一個 slf4j logger 名稱送出探針，證明 production 呼叫路徑確實進入本 appender，不能以空結果冒充「沒有 WARN」。
        String probe="IFIX1_LOG_CAPTURE_PROBE "+UUID.randomUUID();
        org.slf4j.LoggerFactory.getLogger(name).info(probe);
        if(entries().stream().noneMatch(entry -> entry.message().equals(probe))) {
            close(); throw new IllegalStateException("log capture 無法觀察 production logger："+name);
        }
    }

    @Override public void append(LogEvent event) {
        if(!loggerName.equals(event.getLoggerName())) return;
        entries.add(new Entry(event.getLevel(),event.getMessage().getFormattedMessage(),event.getThrown()));
    }
    List<Entry> entries() { synchronized(entries) { return List.copyOf(entries); } }
    List<Entry> matching(Level level,String... fragments) {
        return entries().stream().filter(entry -> entry.level()==level)
                .filter(entry -> java.util.Arrays.stream(fragments).allMatch(entry.message()::contains)).toList();
    }
    @Override public void close() {
        loggerContext.getConfiguration().getRootLogger().removeAppender(getName());
        loggerContext.updateLoggers();
        stop();
    }
}

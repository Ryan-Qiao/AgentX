package com.kama.jchatmind.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;

@Component
@DependsOn("agentTraceSchemaInitializer")
public class AgentTraceLogCollector {
    private final java.util.concurrent.Executor taskExecutor;
    private final AgentTraceIngestionService ingestionService;
    private AgentTraceLogAppender appender;

    public AgentTraceLogCollector(@Qualifier("taskExecutor") java.util.concurrent.Executor taskExecutor,
                                  AgentTraceIngestionService ingestionService) {
        this.taskExecutor = taskExecutor;
        this.ingestionService = ingestionService;
    }

    @PostConstruct
    void register() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger("AGENT_TRACE");
        appender = new AgentTraceLogAppender(json -> taskExecutor.execute(() -> ingestionService.ingest(json)));
        appender.setContext(context);
        appender.setName("AGENT_TRACE_COLLECTOR");
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        // 保留向根日志输出的 JSON，同时由专用 Appender 完成采集。
        logger.setAdditive(true);
    }

    @PreDestroy
    void unregister() {
        if (appender != null) appender.stop();
    }
}

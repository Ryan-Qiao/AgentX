package com.kama.jchatmind.trace;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.Appender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingAgentTraceRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void emitsSingleLineVersionedJsonAndTruncatesLargePayload() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("AGENT_TRACE");
        Appender<ILoggingEvent> collector = logger.getAppender("AGENT_TRACE_COLLECTOR");
        if (collector != null) logger.detachAppender(collector);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AgentTraceContext context = new AgentTraceContext("trace-1", "agent-1", "session-1", "message-1");
            LoggingAgentTraceRecorder recorder = new LoggingAgentTraceRecorder(objectMapper, 1000);

            recorder.record(context, TraceEventType.MODEL_CALL_COMPLETED,
                    TraceEventStatus.COMPLETED, 1, "model", Instant.now(),
                    Map.of("content", "x".repeat(1200)), null);

            assertThat(appender.list).hasSize(1);
            String json = appender.list.get(0).getFormattedMessage();
            assertThat(json).doesNotContain("\n");
            JsonNode root = objectMapper.readTree(json);
            assertThat(root.path("schemaVersion").asText()).isEqualTo("1.0");
            assertThat(root.path("category").asText()).isEqualTo("agent_trace");
            assertThat(root.path("traceId").asText()).isEqualTo("trace-1");
            assertThat(root.path("sequenceNo").asInt()).isEqualTo(1);
            assertThat(root.path("payload").path("truncated").asBoolean()).isTrue();
        } finally {
            logger.detachAppender(appender);
            if (collector != null) logger.addAppender(collector);
        }
    }

    @Test
    void contextSequenceIsMonotonic() {
        AgentTraceContext context = new AgentTraceContext("trace", "agent", "session", "message");
        assertThat(context.nextSequence()).isEqualTo(1);
        assertThat(context.nextSequence()).isEqualTo(2);
        assertThat(context.nextSequence()).isEqualTo(3);
    }
}

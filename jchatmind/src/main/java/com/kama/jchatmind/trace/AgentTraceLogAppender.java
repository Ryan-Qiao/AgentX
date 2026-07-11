package com.kama.jchatmind.trace;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.function.Consumer;

public class AgentTraceLogAppender extends AppenderBase<ILoggingEvent> {
    private final Consumer<String> consumer;

    public AgentTraceLogAppender(Consumer<String> consumer) {
        this.consumer = consumer;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        consumer.accept(eventObject.getFormattedMessage());
    }
}

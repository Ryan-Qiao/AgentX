package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        return executor("trace-", 2, 4, 500);
    }

    @Bean("agentExecutor")
    public Executor agentExecutor() {
        return executor("agent-", 4, 10, 100);
    }

    @Bean("memoryExecutor")
    public Executor memoryExecutor() {
        return executor("memory-", 1, 2, 50);
    }

    @Bean("documentExecutor")
    public Executor documentExecutor() {
        return executor("document-", 1, 3, 20);
    }

    private Executor executor(String prefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

package com.agentflow.execution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.concurrent.Executor;

@Configuration @EnableScheduling
public class ExecutionInfrastructure {
    @Bean("executionExecutor") Executor executionExecutor() {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(8); executor.setQueueCapacity(100); executor.setThreadNamePrefix("agentflow-execution-"); return executor;
    }

    @Bean("aiProviderExecutor") Executor aiProviderExecutor() {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(8); executor.setQueueCapacity(100); executor.setThreadNamePrefix("agentflow-ai-"); return executor;
    }
}

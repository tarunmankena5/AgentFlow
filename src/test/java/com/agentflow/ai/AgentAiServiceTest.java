package com.agentflow.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentflow.shared.config.AgentFlowProperties;
import com.agentflow.tool.CalculatorTool;
import com.agentflow.tool.DateTimeTool;
import com.agentflow.tool.StructuredExtractTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AgentAiServiceTest {
    @Test
    void failsClearlyWhenConfiguredProviderIsUnavailable() {
        JdbcTemplate jdbc = jdbc(30);
        ObjectProvider<ChatModel> models = new DefaultListableBeanFactory().getBeanProvider(ChatModel.class);
        AgentAiService service = service(jdbc, models, Runnable::run);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.invoke(UUID.randomUUID(), UUID.randomUUID(), "calculate 2 + 2"));
        assertTrue(error.getMessage().contains("Configure AI_CHAT_MODEL=openai"));
    }

    @Test
    void propagatesProviderFailuresWithoutInventingAnAnswer() {
        ChatModel model = provider((proxy, method, args) -> {
            if ("call".equals(method.getName())) throw new IllegalStateException("provider unavailable");
            throw new UnsupportedOperationException(method.getName());
        });
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chatModel", model);
        AgentAiService service = service(jdbc(30), beans.getBeanProvider(ChatModel.class), Runnable::run);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.invoke(UUID.randomUUID(), UUID.randomUUID(), "test provider failure"));
        assertEquals("provider unavailable", error.getMessage());
    }

    @Test
    void enforcesTheAgentDeadlineOnProviderCalls() {
        ChatModel model = provider((proxy, method, args) -> {
            if ("call".equals(method.getName())) {
                Thread.sleep(2_000);
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chatModel", model);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ai-timeout-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            AgentAiService service = service(jdbc(1), beans.getBeanProvider(ChatModel.class), executor);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.invoke(UUID.randomUUID(), UUID.randomUUID(), "test provider timeout"));
            assertTrue(error.getMessage().contains("exceeded agent timeout"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static JdbcTemplate jdbc(int timeoutSeconds) {
        return new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (!sql.contains("SELECT av.system_prompt")) return List.of();
                return List.of((T) new Object[] {
                    "Be concise", "openai", "gpt-4o-mini", BigDecimal.ZERO, 3, 1000, timeoutSeconds, "PUBLISHED"
                });
            }
        };
    }

    private static AgentAiService service(JdbcTemplate jdbc, ObjectProvider<ChatModel> models,
                                          java.util.concurrent.Executor executor) {
        AgentFlowProperties properties = new AgentFlowProperties(
                new AgentFlowProperties.Security("s".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7), "agentflow"),
                new AgentFlowProperties.Execution(50, Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(1)),
                new AgentFlowProperties.Kafka("jobs", "dead", "test"),
                new AgentFlowProperties.Tools("", 1024),
                new AgentFlowProperties.Ai(BigDecimal.ZERO, BigDecimal.ZERO));
        return new AgentAiService(jdbc, models, ToolCallingManager.builder().build(), executor,
                new CalculatorTool(), new DateTimeTool(), new StructuredExtractTool(), properties, new ObjectMapper());
    }

    private static ChatModel provider(java.lang.reflect.InvocationHandler handler) {
        return (ChatModel) Proxy.newProxyInstance(ChatModel.class.getClassLoader(), new Class<?>[] {ChatModel.class}, handler);
    }
}

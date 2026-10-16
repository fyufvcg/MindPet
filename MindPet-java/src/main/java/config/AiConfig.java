package config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import util.Logger;

import java.util.List;
import tool.impl.DateCalcTool;
import tool.impl.EmailTools;
import tool.impl.NumericCalcTool;
import tool.impl.TimezoneConvertTool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

@Configuration
public class AiConfig {

    private final AtomicReference<ToolCallback[]> cachedCallbacks = new AtomicReference<>();

    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel, Logger logger) {
        return ChatClient.builder(chatModel);
    }

    @Bean(name = "memoryCuratorExecutor")
    public Executor memoryCuratorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("memory-curator-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    @Bean(name = "memoryReflectionExecutor")
    public Executor memoryReflectionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("memory-reflection-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    @Bean(name = "knowledgeGraphExecutor")
    public Executor knowledgeGraphExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("knowledge-graph-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    /** 返回空工具集占位。ApplicationReadyEvent 后注册通用工具，避免提前 getBean() 触发循环依赖。 */
    @Bean
    public ToolCallbackProvider toolCallbackProvider() {
        return () -> {
            ToolCallback[] cb = cachedCallbacks.get();
            return cb != null ? cb : new ToolCallback[0];
        };
    }

    /** 只注册通用办公工具，避免扫描 @Tool 时重新暴露历史垂直业务适配器。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        ApplicationContext appCtx = event.getApplicationContext();
        Logger logger = appCtx.getBean(Logger.class);
        List<Object> toolBeans = List.of(
            appCtx.getBean(NumericCalcTool.class), appCtx.getBean(DateCalcTool.class),
            appCtx.getBean(TimezoneConvertTool.class), appCtx.getBean(EmailTools.class));
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(toolBeans.toArray())
            .build()
            .getToolCallbacks();
        cachedCallbacks.set(callbacks);
        logger.log("INFO", "注册本地工具数: " + callbacks.length);
    }

}

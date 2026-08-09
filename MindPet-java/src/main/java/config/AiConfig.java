package config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import util.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

@Configuration
public class AiConfig {

    private final AtomicReference<ToolCallback[]> cachedCallbacks = new AtomicReference<>();

    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel, Logger logger) {
        return ChatClient.builder(chatModel)
            .defaultAdvisors(new SimpleLoggerAdvisor(), new ToolCallLimitAdvisor(logger));
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

    /** 返回空工具集占位。ApplicationReadyEvent 后才真正扫描，避免 getBean() 触发循环依赖。 */
    @Bean
    public ToolCallbackProvider toolCallbackProvider() {
        return () -> {
            ToolCallback[] cb = cachedCallbacks.get();
            return cb != null ? cb : new ToolCallback[0];
        };
    }

    /** 容器完全就绪后再扫描 @Tool Bean，此时所有 Bean 已创建完毕，无循环依赖风险。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        ApplicationContext appCtx = event.getApplicationContext();
        Logger logger = appCtx.getBean(Logger.class);
        List<Object> toolBeans = new ArrayList<>();
        for (String beanName : appCtx.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = appCtx.getBean(beanName);
            } catch (Exception e) {
                logger.log("WARN", "跳过Bean初始化: " + beanName + " → " + e.toString());
                continue;
            }
            if (hasToolAnnotatedMethod(bean)) {
                toolBeans.add(bean);
            }
        }
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(toolBeans.toArray())
            .build()
            .getToolCallbacks();
        cachedCallbacks.set(callbacks);
        logger.log("INFO", "注册本地工具数: " + callbacks.length);
    }

    private boolean hasToolAnnotatedMethod(Object bean) {
        for (Method method : bean.getClass().getMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }
}

package com.healthcare.navigator.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Spring AI and concurrency configuration.
 *
 * <p>Exposes:
 * <ul>
 *   <li>A {@link ChatClient} bean wired from Spring AI's auto-configured
 *       {@link ChatClient.Builder} — used by every agent to make LLM calls.</li>
 *   <li>A {@code virtualThreadExecutor} {@link Executor} bean backed by
 *       {@link Executors#newVirtualThreadPerTaskExecutor()} — used by the
 *       orchestrator to execute sub-agents in parallel with low overhead
 *       (Java 21 virtual threads).</li>
 * </ul>
 */
@Configuration
public class SpringAiConfig {

    /**
     * Provides a {@link ChatClient} pre-configured with Spring AI's default
     * auto-configured builder. Agents inject this bean to make LLM calls.
     *
     * @param builder Spring AI's auto-configured builder (provided by the
     *                {@code spring-ai-starter-model-openai} starter)
     * @return a ready-to-use {@link ChatClient}
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Provides a virtual-thread-per-task executor for parallel agent execution.
     *
     * <p>Java 21 virtual threads are extremely lightweight and well-suited for
     * the I/O-bound LLM and RAG calls made by each sub-agent. The orchestrator
     * submits each agent as a {@link java.util.concurrent.CompletableFuture} on
     * this executor, then waits with a 10-second timeout.
     *
     * @return an {@link Executor} that spawns a new virtual thread for every task
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

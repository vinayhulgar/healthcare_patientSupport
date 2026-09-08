package com.healthcare.navigator.observability;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Utility class that propagates the SLF4J MDC context from a parent thread into
 * child tasks that run on Java virtual threads (or any other executor).
 *
 * <p>Virtual threads do not inherit the MDC context of their parent because SLF4J's
 * MDC is backed by a {@link ThreadLocal} (or {@link InheritableThreadLocal} depending
 * on the implementation).  This utility captures the MDC map <em>before</em> the task
 * is submitted and restores it inside the child task, then cleans up after the task
 * completes or throws.
 *
 * <p>Usage example:
 * <pre>{@code
 * CompletableFuture.supplyAsync(
 *     MdcPropagator.wrap(() -> agent.execute(context)),
 *     virtualThreadExecutor
 * );
 * }</pre>
 *
 * <p>Requirements: 11.2 | Design: §2.10
 */
public final class MdcPropagator {

    private MdcPropagator() {
        // utility class — no instances
    }

    /**
     * Wraps a {@link Callable} so that the caller's MDC context is restored inside the
     * callable's execution.
     *
     * <p>The MDC context is captured at wrap-time (in the parent thread) and applied
     * inside the callable (in the child thread). The original context of the child
     * thread (if any) is restored after the callable completes.
     *
     * @param <T>      the return type of the callable
     * @param callable the task to wrap
     * @return a new {@link Callable} that runs with the parent's MDC context
     */
    public static <T> Callable<T> wrapCallable(Callable<T> callable) {
        Map<String, String> parentContext = captureContext();
        return () -> {
            Map<String, String> childContext = captureContext();
            try {
                applyContext(parentContext);
                return callable.call();
            } finally {
                restoreContext(childContext);
            }
        };
    }

    /**
     * Wraps a {@link Supplier} so that the caller's MDC context is restored inside the
     * supplier's execution.
     *
     * <p>The MDC context is captured at wrap-time (in the parent thread) and applied
     * inside the supplier (in the child thread). The original context of the child
     * thread (if any) is restored after the supplier completes.
     *
     * @param <T>      the return type of the supplier
     * @param supplier the task to wrap
     * @return a new {@link Supplier} that runs with the parent's MDC context
     */
    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        Map<String, String> parentContext = captureContext();
        return () -> {
            Map<String, String> childContext = captureContext();
            try {
                applyContext(parentContext);
                return supplier.get();
            } finally {
                restoreContext(childContext);
            }
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Captures a defensive copy of the current thread's MDC context map.
     * Returns an empty map when MDC is not set.
     */
    private static Map<String, String> captureContext() {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return (ctx != null) ? ctx : Collections.emptyMap();
    }

    /**
     * Sets all entries in {@code context} into the current thread's MDC.
     * Clears the MDC first to avoid stale entries from a recycled thread.
     */
    private static void applyContext(Map<String, String> context) {
        MDC.clear();
        if (!context.isEmpty()) {
            MDC.setContextMap(context);
        }
    }

    /**
     * Restores the current thread's MDC to a previous state.
     * If {@code context} is empty, the MDC is cleared entirely.
     */
    private static void restoreContext(Map<String, String> context) {
        MDC.clear();
        if (!context.isEmpty()) {
            MDC.setContextMap(context);
        }
    }
}

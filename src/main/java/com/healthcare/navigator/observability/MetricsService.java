package com.healthcare.navigator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Centralises observability metrics for the Healthcare Care Navigator.
 *
 * <p>Currently exposed metric:
 * <ul>
 *   <li>{@code logging.fault.count} — incremented whenever the logging infrastructure
 *       fails to accept a log entry (e.g. MDC put failure, appender error).  The counter
 *       allows operators to detect degraded logging without failing the request.</li>
 * </ul>
 *
 * <p>This service is designed to be used in conjunction with safe MDC helpers:
 * {@link #safeMdcPut(String, String)} wraps {@link MDC#put} in a try-catch and
 * increments the fault counter on failure so that logging errors never propagate to
 * the request processing thread.
 *
 * <p>Requirements: 11.6 | Design: §2.10, §Logging Infrastructure Failure
 */
@Component
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    /** Micrometer counter metric name used by operators and alerting rules. */
    public static final String LOGGING_FAULT_COUNTER_NAME = "logging.fault.count";

    private final Counter loggingFaultCounter;

    public MetricsService(MeterRegistry meterRegistry) {
        this.loggingFaultCounter = Counter.builder(LOGGING_FAULT_COUNTER_NAME)
                .description("Number of times the logging infrastructure failed to record an entry")
                .register(meterRegistry);
    }

    /**
     * Increments the {@code logging.fault.count} Micrometer counter.
     *
     * <p>Called whenever an MDC operation or log appender call fails so that the failure
     * is surfaced as a metric rather than an exception.
     */
    public void incrementLoggingFaultCounter() {
        try {
            loggingFaultCounter.increment();
        } catch (Exception e) {
            // Last-resort: even the counter increment failed — swallow silently so the
            // request is never affected by observability infrastructure failures.
        }
    }

    /**
     * Safely puts a key-value pair into the SLF4J MDC.
     *
     * <p>On any failure (e.g. unsupported MDC implementation, thread-local overflow),
     * the fault counter is incremented and request processing continues normally.
     *
     * @param key   the MDC key
     * @param value the MDC value
     */
    public void safeMdcPut(String key, String value) {
        try {
            MDC.put(key, value);
        } catch (Exception e) {
            incrementLoggingFaultCounter();
            // Do not rethrow — logging failure must never affect the request
        }
    }

    /**
     * Safely removes a key from the SLF4J MDC.
     *
     * <p>On any failure the fault counter is incremented and processing continues.
     *
     * @param key the MDC key to remove
     */
    public void safeMdcRemove(String key) {
        try {
            MDC.remove(key);
        } catch (Exception e) {
            incrementLoggingFaultCounter();
        }
    }
}

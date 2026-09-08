package com.healthcare.navigator.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that assigns a unique correlation ID to every incoming HTTP request.
 *
 * <p>On each request:
 * <ol>
 *   <li>Generates a UUID correlation ID (or reads {@code X-Correlation-ID} from the request
 *       header if one was supplied by a trusted upstream caller).</li>
 *   <li>Stores the ID in the SLF4J MDC under the key {@code correlationId} so that it
 *       appears automatically in every log line emitted during request processing.</li>
 *   <li>Adds the ID to the {@code X-Correlation-ID} response header so callers can
 *       correlate requests end-to-end.</li>
 * </ol>
 *
 * <p>After the response is committed the MDC entry is removed to prevent context leakage
 * across thread-pool threads.
 *
 * <p>Requirements: 1.6, 11.1 | Design: §2.10
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** MDC key used throughout the application. */
    public static final String MDC_KEY = "correlationId";

    /** HTTP response (and optional request) header name. */
    public static final String HEADER_NAME = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String correlationId = resolveCorrelationId(request);
        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(HEADER_NAME, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            // Always clear MDC to prevent leakage across pooled/virtual threads
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Returns the correlation ID from the incoming {@code X-Correlation-ID} header when
     * present; otherwise generates a fresh random UUID.
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_NAME);
        if (incoming != null && !incoming.isBlank()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}

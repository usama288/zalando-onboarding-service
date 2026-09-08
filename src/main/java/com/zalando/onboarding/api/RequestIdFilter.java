package com.zalando.onboarding.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, puts it in the MDC for the whole call, and echoes it back.
 *
 * <p>This is what makes support possible without logging any personal data (A13): an
 * applicant quotes the id from the error they saw, and every log line for that request —
 * and the problem body they were shown — carries it.
 *
 * <p>An inbound id is honoured so a trace survives across a gateway, but only after being
 * checked against a conservative charset. An unfiltered header goes into log lines and into
 * a response header, which is a log-forging and header-splitting hole; anything that does
 * not match is replaced rather than sanitised, because a half-cleaned id is not the caller's
 * id anyway.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** The current request's id, or a placeholder outside a request. Never null. */
    public static String current() {
        String requestId = MDC.get(MDC_KEY);
        return requestId == null ? "no-request" : requestId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = accept(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        // Set before the chain runs, so it is present even on a response committed by an
        // error path that never reaches the controller advice.
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String accept(String inbound) {
        return inbound != null && ACCEPTABLE.matcher(inbound).matches()
                ? inbound
                : UUID.randomUUID().toString();
    }
}

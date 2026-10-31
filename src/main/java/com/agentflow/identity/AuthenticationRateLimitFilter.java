package com.agentflow.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rate limits public credential endpoints by the socket peer address. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {
    private final AuthenticationRateLimiter limiter;
    public AuthenticationRateLimitFilter(AuthenticationRateLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        int limit = switch (path) {
            case "/api/v1/auth/login" -> 15;
            case "/api/v1/auth/register" -> 5;
            case "/api/v1/auth/refresh" -> 20;
            default -> 0;
        };
        if (limit > 0 && "POST".equalsIgnoreCase(request.getMethod())) {
            String key = path + ':' + request.getRemoteAddr();
            if (!limiter.allow(key, limit, System.nanoTime())) {
                response.setHeader("Retry-After", "60");
                response.sendError(429, "Too many authentication requests");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

package com.tesla.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Tesla-API-Key";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String[] PUBLIC_GET_PATHS = {
            "/", "/index.html", "/app.css", "/app.js", "/favicon.ico",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"
    };
    private final byte[] expectedApiKey;

    public ApiKeyFilter(@Value("${tesla.api.key}") String apiKey) {
        this.expectedApiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        for (String publicPath : PUBLIC_GET_PATHS) {
            if (PATH_MATCHER.match(publicPath, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String providedApiKey = request.getHeader(API_KEY_HEADER);
        if (providedApiKey == null || !MessageDigest.isEqual(
                expectedApiKey, providedApiKey.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "A valid Tesla API key is required.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

package com.chh.autosense.common;

import com.chh.autosense.utils.LogContextUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class RequestLogFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "autosense.requestId";

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = (String) request.getAttribute(REQUEST_ID);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID, id);
        }
        try (var ignored = LogContextUtils.install(Map.of("requestId", id))) {
            chain.doFilter(request, response);
        }
    }
}

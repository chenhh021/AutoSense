package com.chh.autosense.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer 令牌校验过滤器:解析成功则写入 SecurityContext,失败不拦截
 * (由 SecurityConfig 统一返回 401)。
 */
@Component
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "autosense.authUser";

    private final UserTokenResolver tokenResolver;

    public BearerTokenAuthFilter(UserTokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuthUser user = tokenResolver.resolve(request.getHeader("Authorization"));
        if (user != null) {
            // 角色转 Spring Security authority,供 hasRole("ADMIN") 判定(FR-027)
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(user, null,
                            List.of(new SimpleGrantedAuthority(
                                    "ROLE_" + user.role().toUpperCase())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.setAttribute(ATTR_USER, user);
        }
        chain.doFilter(request, response);
    }
}

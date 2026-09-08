package com.chh.autosense.core.security;

import com.chh.autosense.common.BaseResponse;
import com.chh.autosense.common.ErrorResponse;
import com.chh.autosense.utils.ResultUtils;
import com.chh.autosense.config.CorsProperties;
import com.chh.autosense.common.RequestLogFilter;
import com.chh.autosense.constant.UserRoleConstants;
import com.chh.autosense.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 资源服务端安全配置(FR-015/027):注册/登录匿名放行;/api/v1/admin/**
 * 仅 admin 角色(否则 403);其余 /api/** 需认证,无状态会话。
 */
@Configuration
@Slf4j
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private final CorsProperties corsProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(CorsProperties corsProperties, ObjectMapper objectMapper) {
        this.corsProperties = corsProperties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   BearerTokenAuthFilter authFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                // CORS 必须在认证前处理，否则跨域预检 OPTIONS 会被 /api/** 的认证规则拦截
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 异步分发/错误分发:认证已在初始 REQUEST 完成(FR-021)
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,
                                jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // 注册/登录匿名可达(FR-022/023)
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/users/register", "/api/v1/users/login").permitAll()
                        // 管理端点仅 admin(FR-027)
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole(UserRoleConstants.ADMIN.toUpperCase(java.util.Locale.ROOT))
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RequestLogFilter(), BearerTokenAuthFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> { log.warn("Authentication rejected: errorCode=UNAUTHORIZED"); writeError(res,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                ErrorCode.UNAUTHORIZED, "未登录或令牌无效"); })
                        .accessDeniedHandler((req, res, e) -> { log.warn("Access denied: errorCode=FORBIDDEN"); writeError(res,
                                HttpServletResponse.SC_FORBIDDEN,
                                ErrorCode.FORBIDDEN, "无权限访问该资源"); }))
                .build();
    }

    @Bean
    public FilterRegistrationBean<BearerTokenAuthFilter> authFilterRegistration(BearerTokenAuthFilter filter) {
        FilterRegistrationBean<BearerTokenAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * API 跨域白名单。Bearer 令牌由前端显式写入 Authorization 请求头，
     * 不使用跨域 Cookie，因此关闭 credentials。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Cache-Control",
                "Last-Event-ID",
                "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(corsProperties.maxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** 过滤器层的认证/授权失败与 GlobalExceptionHandler 保持一致:BaseResponse&lt;ErrorResponse&gt;。 */
    private void writeError(HttpServletResponse res, int status, ErrorCode errorCode, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        BaseResponse<ErrorResponse> body = ResultUtils.error(errorCode,
                new ErrorResponse(errorCode.name(), message, null), message);
        res.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

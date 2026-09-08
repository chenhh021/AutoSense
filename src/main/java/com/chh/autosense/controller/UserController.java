package com.chh.autosense.controller;

import com.chh.autosense.common.BaseResponse;
import com.chh.autosense.utils.ResultUtils;
import com.chh.autosense.domain.dto.LoginRequest;
import com.chh.autosense.domain.dto.LoginResponse;
import com.chh.autosense.domain.dto.RegisterRequest;
import com.chh.autosense.domain.vo.UserView;
import com.chh.autosense.domain.entity.User;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户账号 API(FR-022~025,contracts/user-api.md §1~§4)。
 * 注册/登录匿名可达(SecurityConfig 放行);me/logout 需 Bearer 令牌。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 注册普通用户账号。
     *
     * @param request 注册账号、密码和确认密码
     * @return 包装响应，业务数据为已创建的脱敏用户信息，不包含密码
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<UserView> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResultUtils.success(UserView.of(userService.register(
                request.userAccount(), request.userPassword(), request.confirmPassword())));
    }

    /**
     * 使用账号和密码登录。
     *
     * @param request 登录账号和密码
     * @return 包装响应，业务数据为 Bearer 访问令牌及当前用户的脱敏信息
     */
    @PostMapping("/login")
    public BaseResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        UserService.LoginResult result = userService.login(
                request.userAccount(), request.userPassword());
        return ResultUtils.success(LoginResponse.of(result.token(), result.user()));
    }

    /**
     * 获取当前登录用户的脱敏信息。
     *
     * @param authUser Spring Security 注入的当前认证用户
     * @return 当前登录用户的脱敏信息，不包含密码
     */
    @GetMapping("/me")
    public UserView me(
            @Parameter(hidden = true)
            @AuthenticationPrincipal AuthUser authUser) {
        User user = userService.currentUser(authUser.userId());
        return UserView.of(user);
    }

    /**
     * 注销当前会话，使访问令牌立即失效。
     *
     * @param authorization Bearer 认证请求头
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Parameter(
                    name = "Authorization",
                    description = "Bearer 认证请求头，格式：Bearer {登录接口返回的 token}",
                    required = true,
                    example = "Bearer Bx8J5mK2pQ7wV4yN9cR6tL1sH3dF0aZg",
                    in = ParameterIn.HEADER
            )
            @RequestHeader("Authorization") String authorization) {
        String token = authorization.substring("Bearer ".length()).trim();
        userService.logout(token);
    }
}

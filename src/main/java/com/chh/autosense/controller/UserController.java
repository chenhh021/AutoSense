package com.chh.autosense.controller;

import com.chh.autosense.api.ErrorResponse;
import com.chh.autosense.api.dto.LoginRequest;
import com.chh.autosense.api.dto.LoginResponse;
import com.chh.autosense.api.dto.RegisterRequest;
import com.chh.autosense.api.dto.UserView;
import com.chh.autosense.domain.model.User;
import com.chh.autosense.repository.UserMapper;
import com.chh.autosense.security.AuthUser;
import com.chh.autosense.user.UserService;
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
@Tag(name = "UserController", description = "提供用户注册、登录、查询当前登录用户和注销能力")
@SecurityScheme(
        name = "bearerAuth",
        description = "登录接口返回的不透明访问令牌，调用时使用 Authorization: Bearer {token}",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "Opaque Token"
)
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    /**
     * 注册普通用户账号。
     *
     * @param request 注册账号、密码和确认密码
     * @return 已创建的脱敏用户信息，不包含密码
     */
    @Operation(
            summary = "注册用户",
            description = "创建普通用户账号。账号须为 4~32 位字母、数字或下划线；"
                    + "密码须为 8~64 位且同时包含字母和数字；注册角色固定为 user。"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "注册成功",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserView.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数缺失、格式不合法或两次密码不一致",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "账号已存在（错误码：ACCOUNT_EXISTS）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务内部错误",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(
            @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "用户注册信息",
                    required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RegisterRequest.class))
            )
            @RequestBody RegisterRequest request) {
        return UserView.of(userService.register(
                request.userAccount(), request.userPassword(), request.confirmPassword()));
    }

    /**
     * 使用账号和密码登录。
     *
     * @param request 登录账号和密码
     * @return Bearer 访问令牌及当前用户的脱敏信息
     */
    @Operation(
            summary = "用户登录",
            description = "校验账号和密码并签发不透明 Bearer 访问令牌。账号不存在、密码错误或账号已禁用时，"
                    + "统一返回 401，避免泄露具体失败原因。"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "登录成功",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LoginResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "账号或密码字段为空",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "账号或密码错误，或账号已禁用（错误码：UNAUTHORIZED）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务内部错误",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/login")
    public LoginResponse login(
            @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "用户登录凭据",
                    required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LoginRequest.class))
            )
            @RequestBody LoginRequest request) {
        UserService.LoginResult result = userService.login(
                request.userAccount(), request.userPassword());
        return LoginResponse.of(result.token(), result.user());
    }

    /**
     * 获取当前登录用户的脱敏信息。
     *
     * @param authUser Spring Security 注入的当前认证用户
     * @return 当前登录用户的脱敏信息，不包含密码
     */
    @Operation(
            summary = "获取当前用户",
            description = "根据 Bearer 访问令牌查询当前登录用户，返回结果不包含密码。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserView.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未提供令牌、令牌格式错误、令牌无效或令牌已过期（错误码：UNAUTHORIZED）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务内部错误",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/me")
    public UserView me(
            @Parameter(hidden = true)
            @AuthenticationPrincipal AuthUser authUser) {
        User user = userMapper.selectOneById(authUser.userId());
        return UserView.of(user);
    }

    /**
     * 注销当前会话，使访问令牌立即失效。
     *
     * @param authorization Bearer 认证请求头
     */
    @Operation(
            summary = "用户注销",
            description = "删除当前会话的访问令牌。注销成功后，该令牌立即失效，接口不返回响应体。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注销成功，响应体为空"),
            @ApiResponse(
                    responseCode = "401",
                    description = "未提供令牌、令牌格式错误、令牌无效或令牌已过期（错误码：UNAUTHORIZED）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务内部错误",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
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

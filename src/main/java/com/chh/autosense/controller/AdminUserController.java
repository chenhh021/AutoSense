package com.chh.autosense.controller;

import com.chh.autosense.common.ErrorResponse;
import com.chh.autosense.domain.vo.AdminUserPageView;
import com.chh.autosense.domain.dto.SetUserStatusRequest;
import com.chh.autosense.domain.vo.UserView;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理 API(仅 admin,FR-027,contracts/user-api.md §5~§6)。
 * 角色检查由 SecurityConfig 的 hasRole("ADMIN") 承担,非 admin 一律 403。
 */
@Tag(name = "adminUserController", description = "仅管理员可用，提供用户分页查询及用户禁用、启用能力")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 分页查询用户列表，可按账号或昵称模糊搜索。
     *
     * @param page 页码，从 1 开始
     * @param size 每页记录数，最大为 100
     * @param keyword 可选的账号或昵称搜索关键字
     * @param includeDisabled 是否包含已禁用用户
     * @return 用户分页数据，用户记录均不包含密码
     */
    @Operation(
            summary = "分页查询用户",
            description = "按用户 ID 升序分页查询用户，可使用 keyword 对账号或昵称进行模糊搜索。"
                    + "默认不返回已禁用用户；includeDisabled=true 时同时返回已禁用用户。"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "查询成功",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AdminUserPageView.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "查询参数类型不正确",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未提供令牌、令牌格式错误、令牌无效或令牌已过期（错误码：UNAUTHORIZED）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "当前登录用户不是管理员（错误码：FORBIDDEN）",
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
    @GetMapping
    public AdminUserPageView list(
            @Parameter(description = "页码，从 1 开始；小于 1 时按 1 处理", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页记录数，取值会被限制在 1~100", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "账号或昵称模糊搜索关键字；不传或空白时查询全部",
                    example = "zhang")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "是否包含已禁用用户", example = "false")
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return AdminUserPageView.of(userService.listUsers(page, size, keyword, includeDisabled));
    }

    /**
     * 禁用或启用指定用户。
     *
     * @param operator Spring Security 注入的当前管理员
     * @param id 目标用户 ID
     * @param request 目标禁用状态
     * @return 状态变更后的脱敏用户信息
     */
    @Operation(
            summary = "禁用或启用用户",
            description = "disabled=true 时禁用目标用户并使其全部访问令牌立即失效；"
                    + "disabled=false 时恢复用户，但历史令牌不会恢复，用户需要重新登录。"
                    + "管理员不能禁用或启用自己的账号。"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "用户状态更新成功",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserView.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "disabled 缺失，或尝试修改当前管理员自己的状态（错误码：BAD_REQUEST）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未提供令牌、令牌格式错误、令牌无效或令牌已过期（错误码：UNAUTHORIZED）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "当前登录用户不是管理员（错误码：FORBIDDEN）",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "目标用户不存在（错误码：USER_NOT_FOUND）",
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
    @PutMapping("/{id}/status")
    public UserView setStatus(
            @Parameter(hidden = true)
            @AuthenticationPrincipal AuthUser operator,
            @Parameter(description = "需要禁用或启用的目标用户 ID", required = true, example = "12")
            @PathVariable long id,
            @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "用户目标状态",
                    required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SetUserStatusRequest.class))
            )
            @RequestBody SetUserStatusRequest request) {
        return UserView.of(userService.setStatus(
                operator.userId(), id, Boolean.TRUE.equals(request.disabled())));
    }
}

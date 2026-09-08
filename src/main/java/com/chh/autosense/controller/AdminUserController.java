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
    @GetMapping
    public AdminUserPageView list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
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
    @PutMapping("/{id}/status")
    public UserView setStatus(
            @AuthenticationPrincipal AuthUser operator,
            @PathVariable long id,
            @Valid  @RequestBody SetUserStatusRequest request) {
        return UserView.of(userService.setStatus(
                operator.userId(), id, Boolean.TRUE.equals(request.disabled())));
    }
}

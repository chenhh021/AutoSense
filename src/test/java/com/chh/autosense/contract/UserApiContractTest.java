package com.chh.autosense.contract;

import com.chh.autosense.api.ApiException;
import com.chh.autosense.api.ErrorCode;
import com.chh.autosense.controller.AdminUserController;
import com.chh.autosense.controller.UserController;
import com.chh.autosense.domain.model.User;
import com.chh.autosense.repository.UserMapper;
import com.chh.autosense.security.AuthUser;
import com.chh.autosense.security.BearerTokenAuthFilter;
import com.chh.autosense.security.SecurityConfig;
import com.chh.autosense.security.UserTokenResolver;
import com.chh.autosense.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T042:用户管理 API 契约测试(contracts/user-api.md)——注册/登录匿名可达;
 * me/logout 需认证;管理端点非 admin 403;密码不出响应;注册忽略 role 字段。
 */
@WebMvcTest({UserController.class, AdminUserController.class})
@Import({SecurityConfig.class, BearerTokenAuthFilter.class})
class UserApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private UserTokenResolver tokenResolver;

    private User sampleUser(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setUserAccount("zhangsan");
        u.setUserPassword("$2a$10$xxxx");
        u.setUserName("zhangsan");
        u.setUserRole(role);
        return u;
    }

    @BeforeEach
    void setUp() {
        when(tokenResolver.resolve(anyString())).thenAnswer(inv -> {
            String header = inv.getArgument(0);
            if (header.contains("admin-token")) {
                return new AuthUser(1L, "admin");
            }
            if (header.contains("user-token")) {
                return new AuthUser(12L, "user");
            }
            return null;
        });
    }

    // ---------- 跨域 ----------

    @Test
    void 允许来源的登录预检请求无需认证() throws Exception {
        mockMvc.perform(options("/api/v1/users/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString(HttpMethod.POST.name())))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsString("authorization")));
    }

    @Test
    void 允许来源的管理接口预检请求无需Bearer令牌() throws Exception {
        mockMvc.perform(options("/api/v1/admin/users")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:3000"));
    }

    @Test
    void 拒绝非白名单来源的预检请求() throws Exception {
        mockMvc.perform(options("/api/v1/users/register")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ---------- 注册 ----------

    @Test
    void 注册匿名可达_201且不含密码() throws Exception {
        when(userService.register(eq("zhangsan"), eq("pass1234"), eq("pass1234")))
                .thenReturn(sampleUser(12L, "user"));

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\",\"userPassword\":\"pass1234\","
                                + "\"confirmPassword\":\"pass1234\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.userAccount").value("zhangsan"))
                .andExpect(jsonPath("$.userRole").value("user"))
                .andExpect(jsonPath("$.userPassword").doesNotExist());
    }

    @Test
    void 注册夹带role字段被忽略() throws Exception {
        when(userService.register(anyString(), anyString(), anyString()))
                .thenReturn(sampleUser(12L, "user"));

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\",\"userPassword\":\"pass1234\","
                                + "\"confirmPassword\":\"pass1234\",\"userRole\":\"admin\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userRole").value("user"));
    }

    @Test
    void 注册校验失败_400() throws Exception {
        when(userService.register(anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致"));

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\",\"userPassword\":\"pass1234\","
                                + "\"confirmPassword\":\"pass9999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void 注册账号重复_409() throws Exception {
        when(userService.register(anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.ACCOUNT_EXISTS, "账号已存在"));

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\",\"userPassword\":\"pass1234\","
                                + "\"confirmPassword\":\"pass1234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
    }

    @Test
    void 注册缺字段_400() throws Exception {
        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---------- 登录 ----------

    @Test
    void 登录成功返回token() throws Exception {
        when(userService.login("zhangsan", "pass1234"))
                .thenReturn(new UserService.LoginResult("tok-abc", sampleUser(12L, "user")));

        mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\",\"userPassword\":\"pass1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok-abc"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.userAccount").value("zhangsan"))
                .andExpect(jsonPath("$.user.userPassword").doesNotExist());
    }

    @Test
    void 登录密码错误_401不区分原因() throws Exception {
        when(userService.login(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));

        mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"zhangsan\",\"userPassword\":\"wrong123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    // ---------- me / logout ----------

    @Test
    void 获取当前用户_200脱敏() throws Exception {
        when(userMapper.selectOneById(12L)).thenReturn(sampleUser(12L, "user"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.userAccount").value("zhangsan"))
                .andExpect(jsonPath("$.userPassword").doesNotExist());
    }

    @Test
    void 未带令牌访问me_401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 注销_204并删除令牌() throws Exception {
        mockMvc.perform(post("/api/v1/users/logout")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isNoContent());
        verify(userService).logout("user-token");
    }

    // ---------- 管理端点 ----------

    @Test
    void 普通用户访问管理端点_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 未认证访问管理端点_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 管理员分页列表_200() throws Exception {
        when(userService.listUsers(anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new UserService.PageResult(1, 1, 20, List.of(sampleUser(12L, "user"))));

        mockMvc.perform(get("/api/v1/admin/users?keyword=zhang")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].userAccount").value("zhangsan"))
                .andExpect(jsonPath("$.records[0].userPassword").doesNotExist());
    }

    @Test
    void 管理员禁用用户_200() throws Exception {
        when(userService.setStatus(eq(1L), eq(12L), eq(true)))
                .thenReturn(sampleUser(12L, "user"));

        mockMvc.perform(put("/api/v1/admin/users/12/status")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12));
    }

    @Test
    void 禁用自身_400() throws Exception {
        when(userService.setStatus(anyLong(), anyLong(), anyBoolean()))
                .thenThrow(new ApiException(ErrorCode.BAD_REQUEST, "不能禁用/启用自己的账号"));

        mockMvc.perform(put("/api/v1/admin/users/1/status")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void 禁用目标不存在_404() throws Exception {
        when(userService.setStatus(anyLong(), anyLong(), anyBoolean()))
                .thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND, "用户不存在"));

        mockMvc.perform(put("/api/v1/admin/users/999/status")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 普通用户禁用他人_403且不触达服务层() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/12/status")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isForbidden());
        verify(userService, never()).setStatus(anyLong(), anyLong(), anyBoolean());
    }
}

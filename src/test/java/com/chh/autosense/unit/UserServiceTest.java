package com.chh.autosense.unit;

import com.chh.autosense.api.ApiException;
import com.chh.autosense.api.ErrorCode;
import com.chh.autosense.domain.model.User;
import com.chh.autosense.repository.UserMapper;
import com.chh.autosense.user.AuthTokenService;
import com.chh.autosense.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T043:UserService 单测——校验规则、BCrypt、昵称缺省、登录失败不区分原因、
 * 禁用即令牌失效(Mockito,不落库)。
 */
class UserServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuthTokenService tokenService = mock(AuthTokenService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UserService service = new UserService(userMapper, passwordEncoder, tokenService);

    // ---------- 注册校验 ----------

    @Test
    void 账号格式校验() {
        assertRegisterRejected("ab", "pass1234");          // 太短
        assertRegisterRejected("a".repeat(33), "pass1234"); // 太长
        assertRegisterRejected("张三账号ok", "pass1234");      // 非法字符
        assertRegisterRejected("has space", "pass1234");    // 空格
    }

    @Test
    void 密码强度校验() {
        assertRegisterRejected("zhangsan", "short1");            // 太短
        assertRegisterRejected("zhangsan", "onlyletters");       // 无数字
        assertRegisterRejected("zhangsan", "12345678");          // 无字母
        assertRegisterRejected("zhangsan", "a".repeat(65) + "1"); // 太长
    }

    @Test
    void 确认密码不一致拒绝() {
        assertThatThrownBy(() -> service.register("zhangsan", "pass1234", "pass9999"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void 注册成功_昵称缺省等于账号_角色写死user_密码BCrypt() {
        User user = service.register("zhangsan", "pass1234", "pass1234");

        assertThat(user.getUserName()).isEqualTo("zhangsan");
        assertThat(user.getUserRole()).isEqualTo("user");
        assertThat(user.getUserPassword()).isNotEqualTo("pass1234");
        assertThat(passwordEncoder.matches("pass1234", user.getUserPassword())).isTrue();
        verify(userMapper).insert(any(User.class));
    }

    // ---------- 登录 ----------

    @Test
    void 登录成功颁发令牌() {
        User user = new User();
        user.setId(12L);
        user.setUserAccount("zhangsan");
        user.setUserPassword(passwordEncoder.encode("pass1234"));
        user.setUserRole("user");
        when(userMapper.selectOneByQuery(any())).thenReturn(user);
        when(tokenService.issue(12L, "user")).thenReturn("tok-abc");

        UserService.LoginResult result = service.login("zhangsan", "pass1234");

        assertThat(result.token()).isEqualTo("tok-abc");
        assertThat(result.user().getId()).isEqualTo(12L);
    }

    @Test
    void 登录失败_账号不存在与密码错误同为401() {
        when(userMapper.selectOneByQuery(any())).thenReturn(null);
        assertLoginRejected("nobody", "pass1234");

        User user = new User();
        user.setUserAccount("zhangsan");
        user.setUserPassword(passwordEncoder.encode("pass1234"));
        when(userMapper.selectOneByQuery(any())).thenReturn(user);
        assertLoginRejected("zhangsan", "wrong123");
    }

    // ---------- 禁用/启用 ----------

    @Test
    void 禁用即逻辑删除并清除全部令牌() {
        User target = new User();
        target.setId(12L);
        when(userMapper.selectByIdIncludingDeleted(12L)).thenReturn(target);

        service.setStatus(1L, 12L, true);

        verify(userMapper).deleteById(12L);
        verify(tokenService).invalidateAll(12L);
    }

    @Test
    void 启用经原生SQL恢复() {
        User target = new User();
        target.setId(12L);
        when(userMapper.selectByIdIncludingDeleted(12L)).thenReturn(target);

        service.setStatus(1L, 12L, false);

        verify(userMapper).restoreById(12L);
    }

    @Test
    void 禁止操作自身() {
        assertThatThrownBy(() -> service.setStatus(1L, 1L, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void 目标不存在_404() {
        when(userMapper.selectByIdIncludingDeleted(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.setStatus(1L, 999L, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ---------- 辅助 ----------

    private void assertRegisterRejected(String account, String password) {
        assertThatThrownBy(() -> service.register(account, password, password))
                .as("account=%s password=%s 应被拒绝", account, password)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private void assertLoginRejected(String account, String password) {
        assertThatThrownBy(() -> service.login(account, password))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}

package com.chh.autosense.service.user;

import com.chh.autosense.constant.UserRoleConstants;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.domain.entity.User;
import com.chh.autosense.mapper.UserMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户账号服务(FR-022~027,R20/R22/R23):注册/登录/注销/管理。
 * 密码 BCrypt 入库,编码前校验 UTF-8 字节长度不超过 72;注册角色写死 user(防提权);
 * 禁用走逻辑删除(isDelete)并使全部令牌失效。
 * 登录签发与禁用/启用按同用户行锁串行,查询包含已禁用行并在锁内复核状态;
 * 关键 Redis 失败向上传播并回滚数据库;启用先撤销旧索引再恢复行。
 * 结果日志仅英文参数化字段,不含账号/密码/令牌。
 */
@Service
@Slf4j
public class UserService {

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{4,32}$");
    private static final Pattern LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");

    /** BCrypt 输入上限:UTF-8 编码 72 字节。 */
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService tokenService;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       AuthTokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /** 注册(FR-022):昵称缺省=账号;角色写死 user;唯一冲突 409。 */
    public User register(String account, String password, String confirmPassword) {
        if (account == null || !ACCOUNT_PATTERN.matcher(account).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "账号须为 4~32 位字母、数字或下划线");
        }
        if (password == null || password.length() < 8 || password.length() > 64
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "密码须为 8~64 位且同时包含字母和数字");
        }
        // BCrypt 只处理前 72 字节,编码前显式拒绝超限输入,不截断或 trim(R20)
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_UTF8_BYTES) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "密码的 UTF-8 编码长度不能超过 72 字节");
        }
        if (!password.equals(confirmPassword)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致");
        }
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(passwordEncoder.encode(password));
        user.setUserName(account);
        user.setUserRole(UserRoleConstants.USER); // 注册不可自选角色(R23 防提权)
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "账号已存在");
        }
        log.info("User operation completed: operation=register, result=CREATED");
        return user;
    }

    /**
     * 登录(FR-023):账号不存在/密码错误/已禁用统一 401,不区分原因。
     * 行锁覆盖含已禁用行,同账号登录与禁用操作串行;锁内复核删除标记。
     */
    @Transactional
    public LoginResult login(String account, String password) {
        User user = account == null ? null : userMapper.selectByAccountForUpdate(account);
        boolean deleted = user != null && user.getIsDelete() != null && user.getIsDelete() != 0;
        if (user == null || deleted || !passwordEncoder.matches(password, user.getUserPassword())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        String token = tokenService.issue(user.getId(), user.getUserRole());
        log.info("User operation completed: operation=login, result=TOKEN_ISSUED");
        return new LoginResult(token, user);
    }

    public void logout(String token) {
        tokenService.invalidate(token);
        log.info("User operation completed: operation=logout, result=TOKEN_REVOKED");
    }

    /** 当前登录用户(FR-025):经服务层访问,禁用/缺失视为令牌无效。 */
    public User currentUser(long userId) {
        User user = userMapper.selectOneById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "未登录或令牌无效");
        }
        return user;
    }

    /** 管理员:用户列表(FR-027),分页 + 账号/昵称模糊搜索。 */
    public PageResult listUsers(int page, int size, String keyword, boolean includeDisabled) {
        int pageNo = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 100);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword;
        if (includeDisabled) {
            // 逻辑删除自动条件仅作用于 MyBatis-Flex 查询构造器,原生 SQL 显式放开
            long total = userMapper.countAllIncludingDeleted(kw);
            List<User> records = userMapper.selectAllIncludingDeleted(
                    kw, (long) (pageNo - 1) * pageSize, pageSize);
            return new PageResult(total, pageNo, pageSize, records);
        }
        QueryWrapper query = QueryWrapper.create().orderBy("id", true);
        if (kw != null) {
            query.and("(userAccount LIKE ? OR userName LIKE ?)", "%" + kw + "%", "%" + kw + "%");
        }
        Page<User> p = userMapper.paginate(Page.of(pageNo, pageSize), query);
        return new PageResult(p.getTotalRow(), pageNo, pageSize, p.getRecords());
    }

    /**
     * 管理员:禁用/启用(FR-027)。行锁串行并在锁内复核状态;禁止操作自身。
     * 禁用:逻辑删除 + 全部令牌失效。启用:先撤销旧令牌索引再恢复行,
     * 撤销失败传播并回滚,旧令牌绝不复活。
     */
    @Transactional
    public User setStatus(long operatorId, long targetId, boolean disabled) {
        if (operatorId == targetId) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "不能禁用/启用自己的账号");
        }
        User target = userMapper.selectByIdForUpdate(targetId);
        if (target == null) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        boolean currentlyDisabled = target.getIsDelete() != null && target.getIsDelete() != 0;
        if (disabled) {
            if (!currentlyDisabled) {
                userMapper.deleteById(targetId); // 逻辑删除(isDelete=1)
            }
            tokenService.invalidateAll(targetId);
            log.info("User operation completed: operation=disable, result=DISABLED");
        } else {
            // 启用:先撤销旧索引(Redis 失败传播并回滚),再恢复行
            tokenService.invalidateAll(targetId);
            if (currentlyDisabled) {
                userMapper.restoreById(targetId); // 原生 SQL 恢复(框架逻辑删除条件会拦截常规 update)
            }
            log.info("User operation completed: operation=enable, result=ENABLED");
        }
        return target;
    }

    /** 登录响应载荷。 */
    public record LoginResult(String token, User user) {
    }

    /** 管理端分页结果。 */
    public record PageResult(long total, int page, int size, List<User> records) {
    }
}

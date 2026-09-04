package com.chh.autosense.user;

import com.chh.autosense.api.ApiException;
import com.chh.autosense.api.ErrorCode;
import com.chh.autosense.domain.model.User;
import com.chh.autosense.repository.UserMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户账号服务(FR-022~027,R20/R22/R23):注册/登录/注销/管理。
 * 密码 BCrypt 入库;注册角色写死 user(防提权);禁用走逻辑删除(isDelete)
 * 并使全部令牌失效。
 */
@Service
public class UserService {

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{4,32}$");
    private static final Pattern LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");

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
        if (!password.equals(confirmPassword)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致");
        }
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(passwordEncoder.encode(password));
        user.setUserName(account);
        user.setUserRole("user"); // 注册不可自选角色(R23 防提权)
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "账号已存在");
        }
        return user;
    }

    /**
     * 登录(FR-023):账号不存在/密码错误/已禁用统一 401,不区分原因。
     * isDelete 逻辑删除由 MyBatis-Flex 自动过滤,禁用用户查不到即视为错误。
     */
    public LoginResult login(String account, String password) {
        User user = account == null ? null : userMapper.selectOneByQuery(
                QueryWrapper.create().where("userAccount = ?", account));
        if (user == null || !passwordEncoder.matches(password, user.getUserPassword())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        return new LoginResult(tokenService.issue(user.getId(), user.getUserRole()), user);
    }

    public void logout(String token) {
        tokenService.invalidate(token);
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

    /** 管理员:禁用/启用(FR-027)。禁用即令牌全失效;禁止操作自身。 */
    public User setStatus(long operatorId, long targetId, boolean disabled) {
        if (operatorId == targetId) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "不能禁用/启用自己的账号");
        }
        User target = userMapper.selectByIdIncludingDeleted(targetId);
        if (target == null) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (disabled) {
            userMapper.deleteById(targetId); // 逻辑删除(isDelete=1)
            tokenService.invalidateAll(targetId);
        } else {
            userMapper.restoreById(targetId); // 原生 SQL 恢复(框架逻辑删除条件会拦截常规 update)
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

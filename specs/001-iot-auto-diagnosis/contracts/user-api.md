# API Contract: 用户管理（v1)

> **2026-09-07 规格拆分说明**：下文保留拆分前的设计/契约/验证指南作为参考，尚未按五个 feature 的新边界重新规划；其中工作区状态、需求编号和流程描述均属于编制时上下文。当前需求以[feature 总览](../../README.md)及各自 spec 为准；原需求可查[拆分前规格](../history/20260907-before-feature-split.md)。复用适用部分时须核对新职责，本文不代表新 feature 已实现或验收通过。

**Date**: 2026-09-07 | **Spec**: [../spec.md](../spec.md)（US3,FR-022~FR-027） | **Constitution**: 2.0.0

纯 REST + JSON。Base path: `/api/v1`。
认证：除 §1 注册、§2 登录外，所有端点要求请求头 `Authorization: Bearer <token>`;
未认证一律 `401`。§5/§6 管理端点仅 `admin` 角色可用，否则 `403`。
令牌由登录颁发（不透明随机串，Redis 存储 + 7 天滚动 TTL,R19);dev 模式
（`AUTH_DEV_MODE=true`）另接受 `Bearer user-{id}`，真实令牌优先，生产必须关闭（R21)。

接口路径、字段及普通用户/管理员角色沿用当前实现。依据 [research.md](../research.md)
R19–R23、R27/R28，目标补齐令牌索引失效和并发保障，并收敛 Controller/Mapper 与 DTO/VO
分层；这些内部修订待后续实施，不代表本次已运行或通过验收。

## 通用约定

- 错误响应统一结构与 [diagnosis-api.md](./diagnosis-api.md) 相同（`code`/`message` + 可选上下文字段）。
- 用户对象**永不包含** `userPassword`（散列也不输出，FR-022)。
- 本文端点全部为普通 JSON/空响应；错误使用实际 HTTP 状态，不使用 SSE error。
- 管理员仅增加用户管理权限，对本人设备/会话的范围与普通用户一致。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 参数校验失败（账号/密码格式、两次密码不一致） |
| 401 | `UNAUTHORIZED` | 未登录/令牌失效/账号或密码错误 |
| 403 | `FORBIDDEN` | 非管理员访问管理端点（FR-027) |
| 404 | `USER_NOT_FOUND` | 目标用户不存在（管理端点） |
| 409 | `ACCOUNT_EXISTS` | 注册账号已存在（FR-022) |
| 500 | `INTERNAL_ERROR` | 未预期或持久化/令牌存储失败；不得返回内部堆栈和凭据 |

## 1. 注册（匿名）

`POST /users/register`

```json
{ "userAccount": "zhangsan", "userPassword": "pass1234", "confirmPassword": "pass1234" }
```

校验（FR-022/R20):`userAccount` 4~32 位 `^[a-zA-Z0-9_]+$`，唯一；
`userPassword` 8~64 位且含字母+数字；`confirmPassword` 必须一致。
**请求体不接受自选角色**（夹带 role/userRole 即忽略，R23 防提权），新用户始终为 user。
被禁用账号仍受同一账号唯一约束，不能通过重新注册绕过禁用。

响应 `201`（昵称缺省=账号；密码 BCrypt 入库，不回显）:

```json
{ "id": 12, "userAccount": "zhangsan", "userName": "zhangsan",
  "userAvatar": null, "userProfile": null, "userRole": "user" }
```

失败：400（格式/确认不一致）,409 `ACCOUNT_EXISTS`。

## 2. 登录（匿名）

`POST /users/login`

```json
{ "userAccount": "zhangsan", "userPassword": "pass1234" }
```

响应 `200`（令牌即 Bearer 凭证，Redis 7 天滚动 TTL,R19):

```json
{ "token": "x9f…(32字节随机Base64URL)", "tokenType": "Bearer",
  "user": { "id": 12, "userAccount": "zhangsan", "userName": "zhangsan",
            "userAvatar": null, "userProfile": null, "userRole": "user" } }
```

失败：空账号/密码为 `400 BAD_REQUEST`；账号不存在/密码错误/账号被禁用统一
`401 UNAUTHORIZED`，不区分原因。token 是 32 字节安全随机值编码为无填充 Base64URL，
客户端视为不透明字符串；不从 token 内容推断角色或用户 ID。

目标签发需在用户短事务行锁内再次确认用户启用，原子写 token、用户 Set 成员和双 TTL，
事务提交后才返回 200；不能在与禁用并发时返回仍可使用的新令牌（R19）。

## 3. 获取当前登录用户

`GET /users/me` → `200` 返回当前用户对象（字段同 §1 响应，脱敏）。

目标用户已不存在/禁用、token 或成员关系无效时为 `401 UNAUTHORIZED`，不返回空用户或 500。
`UserController.me` 的 `UserMapper.selectOneById` 查询迁至 `UserService` 的当前用户查询方法，
Controller 保留 UserView 响应转换；不改变 URL、字段或成功状态码（R27）。

## 4. 注销

`POST /users/logout` → `204`，无响应体。删除当前 Redis token 及其 Set 成员，**立即失效**
（FR-025）；后续持该真实 token 的新请求一律 401，不影响其他仍有效的登录 token。
目标撤销失败不返回 204。dev 形式 user-{id} 是本地替代身份，不是可注销的真实 token。

## 5. 用户列表（仅 admin,FR-027)

`GET /admin/users?page=1&size=20&keyword=zhang`

- `keyword` 可选，按账号/昵称模糊搜索；默认按 id 升序；不含已禁用（isDelete=1）用户，
  除非 `includeDisabled=true`。
- 默认 page=1、size=20、includeDisabled=false；沿用当前规范化规则：page 小于 1 时取 1，
  size 限定到 1~100，响应回传规范化后的 page/size。keyword 为 null/空白时不按关键词过滤。
- 响应 `200`:

```json
{ "total": 57, "page": 1, "size": 20,
  "records": [ { "id": 12, "userAccount": "zhangsan", "userName": "zhangsan",
                 "userAvatar": null, "userProfile": null, "userRole": "user" } ] }
```

## 6. 禁用/启用用户（仅 admin,FR-027)

`PUT /admin/users/{id}/status`

```json
{ "disabled": true }
```

- disabled 必填且为布尔值，缺失/null 返回 `400 BAD_REQUEST`。
- `disabled=true`:`isDelete=1` 并撤销该用户全部真实 token（R19）；
  被禁用用户无法再登录，后续持旧 token 的新请求为 401。
- `disabled=false`:`isDelete=0` 恢复，恢复前再次撤销全部旧 token 成员（历史令牌不复活，需重新登录）。
- 响应 `200` 返回更新后用户对象；404 用户不存在；403 非管理员。
- 沿用当前实现：管理员不得对自身执行禁用或启用，均为 `400 BAD_REQUEST`。
- UserView 字段仍与 §1 相同，不新增 isDelete、权限等级或角色修改入口。已认证且正在处理的
  会话无需追溯取消；后续新请求须执行新的鉴权校验（规格 Edge Cases）。

### 目标失效保障（待实施，R19）

真实 token 有效性同时要求：用户存在且启用、token 键存在、token 属于
`autosense:usertokens:{userId}` Set。解析时原子校验 Redis token/成员关系并同时续期
token 和 Set（默认 7 天）；Set 缺失或不含该 token 时拒绝，不能由解析请求补回。

登录签发、禁用、启用针对同一用户通过短数据库行锁串行（锁查询包含已禁用行）。
签发在锁内复核启用态并原子写 token/成员/双 TTL；禁用更新 isDelete 后删除 token 及整个 Set；
启用前再次删除整个 Set。必须删整个 Set，不能只按枚举结果删单 token：即使存在未枚举到的
孤立 token，缺少 Set 成员也会使其失效；重新登录的新 Set 不包含旧 token，因此不会复活。

数据库事务提交后才返回成功。Redis 失败返回错误并回滚数据库事务，已经撤销的 Redis 凭证
无需恢复；客户端可能需要重新登录。无需新增 authVersion 列、JWT 或新接口。
当前实现仅续 token 而不续 Set，解析也未复核用户启用态，因此上述竞态与索引过期保障
属于实施缺口，不能由现有短时禁用测试推断已经满足。

## 鉴权变更（对既有端点）

[diagnosis-api.md](./diagnosis-api.md) 全部端点沿用 Bearer 真实令牌或显式 dev 身份。
真实 token 始终优先，dev 身份固定普通用户，生产必须关闭；`AuthUser` 携带 role，但资源
所有权仍由服务端检查（FR-015/026）。角色和权限常量归 `constant/`，保留外部 user/admin 字符串。

## 类型迁移与目标验收

- `UserView`、`AdminUserPageView` 从 domain/dto 移至 domain/vo，保持类名、字段和 JSON 不变。
  `RegisterRequest/LoginRequest/SetUserStatusRequest/LoginResponse` 留在 domain/dto；
  登录嵌套 user 引用调整后的 UserView，不直接返回 User 实体或密码。
- UserController 移除 UserMapper 注入；当前用户查询、禁用状态校验由 UserService 完成。
  `UserApiContractTest` 的直接 Mapper mock 收敛到 Service mock，服务层另验证真实业务规则。
- 验证注册/登录/查看当前用户/注销闭环；账号唯一、夹带角色无效、普通用户管理接口 403；
  管理员同样不能查询他人设备/会话。
- 验证分页规范化、includeDisabled 查询、禁止自身启用/禁用，以及任何用户响应都不含密码散列。
- 目标验证活跃 token 与 Set 同时续期、Set 缺失/缺成员立即拒绝、禁用后全端失效、
  启用后旧 token 不复活、登录与禁用/启用竞争无漏撤销，以及 Redis 失败不返回成功。

本段为后续实施验收要求；本次规划未运行认证服务、数据迁移或上述测试。

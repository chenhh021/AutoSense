# API Contract: 用户管理（v1)

**Date**: 2026-08-29 | **Spec**: [../spec.md](../spec.md)（US3,FR-022~FR-027)

纯 REST + JSON。Base path: `/api/v1`。
认证：除 §1 注册、§2 登录外，所有端点要求请求头 `Authorization: Bearer <token>`;
未认证一律 `401`。§5/§6 管理端点仅 `admin` 角色可用，否则 `403`。
令牌由登录颁发（不透明随机串，Redis 存储 + 7 天滚动 TTL,R19);dev 模式
（`AUTH_DEV_MODE=true`）另接受 `Bearer user-{id}`，真实令牌优先，生产必须关闭（R21)。

## 通用约定

- 错误响应统一结构与 diagnosis-api.md 相同（`code`/`message` + 可选上下文字段）。
- 用户对象**永不包含** `userPassword`（散列也不输出，FR-022)。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 参数校验失败（账号/密码格式、两次密码不一致） |
| 401 | `UNAUTHORIZED` | 未登录/令牌失效/账号或密码错误 |
| 403 | `FORBIDDEN` | 非管理员访问管理端点（FR-027) |
| 404 | `USER_NOT_FOUND` | 目标用户不存在（管理端点） |
| 409 | `ACCOUNT_EXISTS` | 注册账号已存在（FR-022) |

## 1. 注册（匿名）

`POST /users/register`

```json
{ "userAccount": "zhangsan", "userPassword": "pass1234", "confirmPassword": "pass1234" }
```

校验（FR-022/R20):`userAccount` 4~32 位 `^[a-zA-Z0-9_]+$`，唯一；
`userPassword` 8~64 位且含字母+数字；`confirmPassword` 必须一致。
**请求体不接受 role 字段**（夹带即忽略，R23 防提权）。

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

失败：401（账号不存在/密码错误/账号被禁用，统一 `UNAUTHORIZED`，不区分原因）。

## 3. 获取当前登录用户

`GET /users/me` → `200` 返回当前用户对象（字段同 §1 响应，脱敏）。

## 4. 注销

`POST /users/logout` → `204`。删除 Redis 令牌键，**立即失效**(FR-025);
后续持该令牌请求一律 401。

## 5. 用户列表（仅 admin,FR-027)

`GET /admin/users?page=1&size=20&keyword=zhang`

- `keyword` 可选，按账号/昵称模糊搜索；默认按 id 升序；不含已禁用（isDelete=1）用户，
  除非 `includeDisabled=true`。
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

- `disabled=true`:`isDelete=1` + 删除该用户全部 Redis 令牌（R19,**立即踢下线**);
  被禁用用户无法再登录（401)。
- `disabled=false`:`isDelete=0` 恢复（历史令牌不复活，需重新登录）。
- 响应 `200` 返回更新后用户对象；404 用户不存在；403 非管理员。
- 管理员不得禁用自身（400 `BAD_REQUEST`)，防止最后一个管理员自锁。

## 鉴权变更（对既有端点）

diagnosis-api.md 全部端点行为不变；鉴权语义更新为：Bearer 真实令牌或
dev 模式 `user-{id}` 均可，`AuthUser` 现携带 `role`（归属过滤 FR-015/026 不变——
每用户仅见本人设备与对话）。

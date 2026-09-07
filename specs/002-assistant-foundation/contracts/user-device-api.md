# API Contract: 用户、权限与设备管理兼容

**Date**: 2026-09-07
**Feature**: [002](../spec.md)
**Status**: 以当前代码为兼容基线，令牌一致性和分层迁移为待实施目标；不在此实现004的自然语言实时状态查询。

## 1. 用户与管理员接口

路径均包含 /api/v1：

| 方法及路径 | 输入 | 成功响应 |
| --- | --- | --- |
| POST /users/register | userAccount、userPassword、confirmPassword | 201 UserView |
| POST /users/login | userAccount、userPassword | 200 LoginResponse |
| GET /users/me | Bearer | 200 UserView |
| POST /users/logout | Bearer | 204，无响应体 |
| GET /admin/users | page、size、keyword、includeDisabled | 200 AdminUserPageView |
| PUT /admin/users/{id}/status | disabled:boolean | 200 UserView |

注册账号为4–32位字母、数字或下划线且唯一。注册密码长度沿用现有Java String.length()校验口径，为8–64个UTF-16代码单元；至少包含一个英文字母和一个数字，并且UTF-8编码后不超过72字节，确认密码必须一致。

校验须在BCrypt编码及数据库写入前完成。超过72字节返回HTTP 400、BAD_REQUEST，提示“密码的 UTF-8 编码长度不能超过 72 字节”；不得截断、自动trim密码或依赖编码器异常产生内部错误。

新用户固定user角色，昵称缺省为账号，不允许注册请求指定管理员角色。继续使用BCrypt存储，保持既有密码哈希与登录验证兼容；密码不在响应或日志中出现。

**注册密码边界样例**（其余注册条件有效）：

| 输入构造 | 字符数 | UTF-8字节数 | 预期 |
| --- | ---: | ---: | --- |
| `a1` + 23个“汉” + `b` | 26 | 72 | 满足其他条件时注册成功 |
| `a1` + 23个“汉” + `bc` | 27 | 73 | 400 / BAD_REQUEST |

UserView：

```json
{
  "id": 11,
  "userAccount": "demo_user",
  "userName": "demo_user",
  "userAvatar": null,
  "userProfile": null,
  "userRole": "user"
}
```

LoginResponse字段为token、tokenType、user；tokenType为Bearer，token为不透明随机串，不新增expiresIn或在客户端解析角色。注销立即撤销当前真实令牌。

AdminUserPageView字段为total、page、size、records，records为UserView列表。保留默认page=1、size=20，规范化page至少1、size在1–100；空白keyword不筛选，按id升序，默认不包含禁用用户。管理员不可对自身执行禁用或启用；不提供角色修改和重置他人密码。

## 2. 认证与授权目标

- 保持随机Redis token；真实身份要求token本体、用户token索引成员资格、账号存在且启用同时满足。
- 缺索引成员或账号被禁用时拒绝，即使token本体仍在；启用账号不恢复旧token，新登录产生新凭证。
- 登录签发与禁用/启用在同用户行锁中串行，Redis token/index原子修改并统一续期；具体失败边界见[data-model.md](../data-model.md)。
- AUTH_DEV_MODE仅显式本地/测试启用，生产关闭；开发身份固定user角色，真实token优先解析。dev token不用于证明真实注销和禁用。
- 普通用户访问管理端点拒绝；管理员仍只能访问本人设备/会话，不具有跨用户设备控制权。
- UserController.me经UserService查询，不直接访问UserMapper；账号不存在/被禁用为UNAUTHORIZED，不因空实体转VO而返回内部错误。

| HTTP / code | 用途 |
| --- | --- |
| 400 BAD_REQUEST | 输入不合法、密码不一致、管理员自身状态变更等 |
| 401 UNAUTHORIZED | 密码错误、未登录、失效/撤销/过期凭证 |
| 403 FORBIDDEN | 普通用户管理请求 |
| 404 USER_NOT_FOUND | 管理目标用户不存在 |
| 409 ACCOUNT_EXISTS | 账号已占用 |

普通异常响应保持code、message、可空sessionId；安全过滤器401/403现只含code/message，两种形状均保留兼容。消息不泄露密码、token、数据库异常或用户私密字段。

## 3. 设备绑定与列表

| 方法及路径 | 输入 | 成功响应 |
| --- | --- | --- |
| POST /devices | sn、name | 201 DeviceView |
| GET /devices | Bearer | 200 {devices:DeviceView[]} |

绑定请求：

```json
{
  "sn": "LITE123456789",
  "name": "客厅灯"
}
```

sn原样匹配 `^[A-Z0-9]{4}[0-9]{9}$`，不自动trim或转大写；name非空白，最多64字符。示例SN不保证存在，实际验证必须使用外部服务提供的有效SN。

- 平台按SN查询外部已存在设备，不调用创建设备接口。
- 仅exists:true且返回稳定字段完整、SN身份一致时写绑定。
- SN全局唯一，无论已属于本人或他人均拒绝重复绑定，错误不透露所有者。
- 保留数据库uk_device_sn作为并发裁决；查询失败、格式错误与冲突不新增或覆盖记录。
- 已存在但未支持诊断的类型/型号仍允许绑定。

DeviceView：

```json
{
  "id": 21,
  "name": "客厅灯",
  "simulatorName": "simulator-bulb",
  "sn": "LITE123456789",
  "deviceTypeCode": "LITE",
  "deviceTypeId": 1,
  "deviceModelCode": "LA001",
  "deviceModelId": 1,
  "supported": true,
  "online": true
}
```

示例ID仅示意，真实值来自平台/外部服务。保持字段列表，不增加运行状态、外部时间戳或simulatorDeviceId等当前未公开字段。

## 4. supported 与 online 的准确语义

- supported是当前诊断注册表的支持投影，**不代表安全控制已交付或用户已授权操作**。
- 绑定成功时online为true；列表按SN查询，exists:true为true；未发现、空结果或异常为false。
- false仅表示本次未确认在线，不能解释为确定离线。online不是实时诊断或控制前置条件的权威证据。
- supported/online均不持久化；稳定元数据按绑定记录返回，运行状态由004/001/005按需读取。
- 可将当前DeviceController中的支持性/可发现性查询编排收敛至core/device业务入口，保持JSON不变。

## 5. 设备错误语义

| HTTP / code | 场景 / 用户说明 |
| --- | --- |
| 400 BAD_REQUEST | SN格式、名称或请求结构不合法 |
| 404 DEVICE_NOT_FOUND | SN查询exists:false，说明“设备不存在或不在线” |
| 409 DEVICE_ALREADY_BOUND | 全局重复SN，说明“该 SN 已绑定” |
| 503 DEVICE_SERVICE_UNAVAILABLE | 外部超时、网络错误、5xx、畸形/缺失必需数据，说明稍后重试 |
| 401 UNAUTHORIZED | 未登录或身份无效 |

这些是普通JSON设备接口的HTTP状态。若设备相关错误在会话流接受后发生，则遵循[会话契约](assistant-api.md)的error事件语义。

## 6. 复用与验证

UserView、AdminUserPageView、DeviceView迁至domain/vo，保持同名与字段；Request和LoginResponse仍在domain/dto。既有用户、设备和数据库ID可继续使用。

验收包括注册/登录/me/注销、管理员分页/筛选/自身限制、普通用户拒绝、禁用再启用旧token仍失效、真实token索引失效与并发撤销、SN格式/全局并发唯一性/未知型号/服务故障以及旧数据继续可用。数据库新建与迁移不能清空已有账号/绑定，运行状态不得因本次拆分新增持久化列。


## 7. 对象与日志兼容

User/Device 使用 Lombok @Getter + @Setter 和必要无参构造；保留映射、逻辑删除、主键回填、原有字段与数据。现有 Request、LoginResponse 和迁入 domain/vo 的三个 View 均保持 record、校验注解及组件形状；不把实体直接用于接口传输。

[公共日志契约](logging-contract.md)覆盖注册/登录/注销、管理员状态变更、认证/归属拒绝、绑定与外部查找结果。英文事件只包含已验证标识、操作、结果和耗时；不记录密码、token、完整User/LoginResponse、原始SN或请求正文。客户端可见消息语言和原HTTP错误体不因此改变。

账户/绑定成功日志在事务提交后记录；失败和冲突不能出现成功事件。列表查询聚合记录次数、结果和耗时，不逐设备输出INFO；online:false的日志仍只表示未确认在线，不推断可靠离线。

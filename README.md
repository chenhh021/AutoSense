# AutoSense — IoT 设备自动诊断与修复

基于自然语言对话的 IoT/网络设备自动诊断与修复服务(API-only,SSE 流式推送)。
用户描述问题 → 意图路由(常识直答/设备诊断/售后查询)→ 定位设备 → 采集诊断信息 →
规则引擎判定故障 → 用户确认后自动修复并复检 → 输出结论;无法自动修复时给出
人工分步指引或附近售后网点。

- 技术栈:Java 21 · Spring Boot 3.5.3 · MySQL + MyBatis-Flex · Redis · LangChain4j
- 设计文档:`specs/001-iot-auto-diagnosis/`(spec / plan / research / data-model / contracts / tasks)

## 快速开始

已有数据库升级前，请先备份并对 `MYSQL_URL` 指向的数据库执行
[`20260907-assistant-processing.sql`](scripts/migration/20260907-assistant-processing.sql)。
脚本幂等地补齐会话处理字段；`SQL_INIT_MODE=always` / `schema.sql` 仅初始化缺失的表，
不会升级旧表。漏迁移会造成对话创建和会话列表查询失败，后端会在启动时检查并提示。
全新数据库才使用 `SQL_INIT_MODE=always` 显式初始化，完成后恢复为 `never`。

```bash
# 1. 启动依赖(MySQL 8 + Redis + deviceSimulator 模拟设备服务,端口 8081)
docker compose up -d

# 2. 启动服务(默认 dev:LLM 走内置 mock 桩,设备交互连本地模拟器)
./mvnw spring-boot:run

# 3. 先在模拟器创建测试设备，并从响应中复制 sn
curl -X POST localhost:8081/api/v1/devices \
  -H "Content-Type: application/json" \
  -d '{"device_type_code":"LITE","device_model_code":"LA001","quantity":1,"name":"sim-la001"}'

# 4. AutoSense 仅按 SN 发现并绑定，用户名称与模拟器名称分别保存
curl -X POST localhost:8180/api/v1/devices \
  -H "Authorization: Bearer user-1" -H "Content-Type: application/json" \
  -d '{"sn":"LITE123456789","name":"客厅灯"}'

# 5. 发起诊断会话(SSE 流;dev 令牌格式 user-{id})
curl -N -X POST localhost:8180/api/v1/sessions \
  -H "Authorization: Bearer user-1" -H "Content-Type: application/json" \
  -d '{"problem": "客厅的灯太暗了,几乎看不见"}'
```

本期仅支持智能灯泡(模拟器 LITE:LA001 单色 / LB001 彩灯);路由器/空调待模拟器
扩展型号后接入。故障判定规则在 `application.yaml` 的 `device-types.*.fault-rules`
中配置(如 `brightness < 5` → 自动调亮;`color_temperature >= 6400` → 人工指引)。

## 环境变量(章程原则 V:外部服务全部配置外置)

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | MySQL 连接 | 本地 compose |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis | localhost:6379 |
| `CORS_ALLOWED_ORIGINS` | 浏览器前端来源白名单，逗号分隔 | 常用本地开发端口 |
| `CORS_MAX_AGE_SECONDS` | 浏览器跨域预检缓存时间（秒） | 3600 |
| `LLM_MODE` | `mock`(内置桩)/ `real`(LangChain4j) | real |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL_NAME` | 模型服务(mode=real 必填) | — |
| `DEVICE_SERVICE_BASE_URL` | deviceSimulator 地址(无认证) | http://localhost:8081 |
| `DEVICE_SERVICE_TIMEOUT_SECONDS` | 模拟器调用超时(秒) | 10 |
| `AFTERSALES_MOCK_ENABLED` | 售后查询使用项目内固定 mock 数据 | true |
| `AFTERSALES_BASE_URL` / `AFTERSALES_API_KEY` | 售后查询服务(mock 关闭时必填) | — |
| `AUTH_DEV_MODE` | dev 令牌 `user-{id}`(真实令牌优先);⚠️ 生产必须 false | true |
| `AUTH_TOKEN_TTL_DAYS` | 登录令牌滚动有效期(天,Redis 存储) | 7 |
| `CHAT_MEMORY_WINDOW` | LLM 对话记忆窗口(条数) | 20 |

## 用户管理(US3,2026-08-29;详见 contracts/user-api.md)

- `POST /api/v1/users/register` — 注册(匿名):`{userAccount, userPassword, confirmPassword}`;
  账号 4~32 位字母/数字/下划线唯一,密码 8~64 位须含字母+数字,BCrypt 入库
- `POST /api/v1/users/login` — 登录(匿名):返回 `token`(不透明随机串,Redis 7 天滚动),
  之后携 `Authorization: Bearer <token>` 访问
- `GET /api/v1/users/me` — 当前登录用户(脱敏,不含密码)
- `POST /api/v1/users/logout` — 注销,令牌立即失效
- `GET /api/v1/admin/users?page=&size=&keyword=&includeDisabled=` — 用户列表(仅 admin)
- `PUT /api/v1/admin/users/{id}/status` — 禁用/启用(仅 admin,`{disabled: true|false}`;
  禁用即令牌全失效;不可操作自身)

初始管理员:data.sql 种子,账号 `admin` / 密码 `admin123`(BCrypt 散列入库),
**上线后请立即改密**。角色分 `user`/`admin`;每用户仅见本人设备与对话。
dev 模式(`AUTH_DEV_MODE=true`)下 `Bearer user-{id}` 仍可用于本地调试,真实令牌优先。

## API 摘要(详见 `specs/001-iot-auto-diagnosis/contracts/diagnosis-api.md`)

写入端点为 SSE 流(`text/event-stream`),五类事件:`token`(LLM 增量文本)、
`status`(状态迁移)、`awaiting`(等待用户输入,随后关流)、`conclusion`(终态结论,
随后关流)、`error`(业务错误,随后关流)。断线不补发,补查走 GET。

- `POST /api/v1/sessions` — 发起会话(SSE)`{ "problem": "..." }`
- `POST /api/v1/sessions/{id}/messages` — 澄清/设备确认/修复确认/补充位置(SSE)
  `{ "content": "...", "confirmRepair": true }`
- `GET /api/v1/sessions` — 历史对话列表(仅本人,含 preview)
- `GET /api/v1/sessions/{id}` — 查询状态与结论(断线补查)
- `GET /api/v1/sessions/{id}/messages` — 对话记录(长期保留)
- `POST /api/v1/devices` — 按 SN 绑定已存在且运行中的模拟器设备，入参
  `{sn,name}`；SN 全平台唯一，不支持诊断的类型仍可绑定并返回 `supported=false`
- `GET /api/v1/devices` — 我的设备列表

错误处理:认证/校验类(401/400/403)在流建立前以 JSON 错误体返回;流程内错误
(409 DEVICE_BUSY、422 UNSUPPORTED_DEVICE_TYPE / DEVICE_UNREACHABLE)
以 SSE `error` 事件传达。

设备绑定错误使用普通 JSON：非法 SN/名称为 `400 BAD_REQUEST`，设备不存在或不在线为
`404 DEVICE_NOT_FOUND`，SN 已由任一用户绑定为 `409 DEVICE_ALREADY_BOUND`，
模拟器 5xx、超时、连接失败或畸形响应为 `503 DEVICE_SERVICE_UNAVAILABLE`。

## 测试

```bash
./mvnw verify          # 单元 + 契约测试(无需 Docker)
./mvnw verify -Pit     # 端到端集成:Testcontainers MySQL/Redis + 真实 deviceSimulator
                       # 需先 `docker compose up -d device-simulator`
                       # (或用 DEVICE_SERVICE_BASE_URL 指向其他就绪实例)
```

## 既有数据库迁移

新建数据库直接使用 `src/main/resources/schema.sql`。从旧版设备表升级时，先备份数据库，
再执行 `scripts/migration/20260904-device-sn-binding.sql`。脚本只回填旧版允许创建的
`smart_bulb + LA001/LB001` 数据；发现非法 SN 或无法可靠映射类型/型号 ID 的行时会在
修改表结构前中止并报告数量，不会删除设备记录。修正报告的数据后再重新执行。

## 架构要点

- **确定性状态机编排**(session/statemachine):意图路由(FR-019:常识直答 / 设备操作 /
  售后查询 / 澄清)后进入诊断修复主流程;LLM 仅做意图分类/语义分析/诊断推理,
  修复动作只能经状态机调用适配器白名单(FR-008/017,失败即停不重试)
- **规则化故障判定**(device/rule):fault-rules 按序匹配设备 state,输出
  AUTO_REPAIRABLE / MANUAL_ONLY / AFTERSALES / NORMAL,规则引用知识库优先于自由检索
- **SSE 流内推进**(FR-021):确认修复后执行与复检在同一 SSE 流内完成,无轮询
- **对话记忆**(FR-018):LangChain4j MessageWindowChatMemory(最近 20 条),
  Redis 存储 30min 滚动,冷启动从 MySQL chat_message 重建;终态会话可续聊开新一轮
- **设备扩展**:新增设备类型 = yaml 注册表条目(型号/状态字段/命令白名单/规则)
  + 一个 DeviceAdapter Bean(FR-012)
- **设备互斥**:Redis 分布式锁 `autosense:lock:device:{id}`(FR-016)
- **会话记录长期保留**(FR-013):会话/消息/快照全量落 MySQL,不做定期清理

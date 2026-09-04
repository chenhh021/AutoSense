# Quickstart: 端到端验证指南

**Date**: 2026-08-22(2026-08-27、2026-08-29、2026-09-04 刷新) | 关联：[spec.md](./spec.md) · [data-model.md](./data-model.md) · [contracts/diagnosis-api.md](./contracts/diagnosis-api.md)

## 前置条件

- JDK 21、Docker（用于 Testcontainers、本地 MySQL/Redis 与 deviceSimulator)。
- `application.yaml` 中外部服务均以环境变量占位注入（章程原则 V):
  - `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL_NAME`(LangChain4j)
  - `AFTERSALES_API_KEY` / `AFTERSALES_BASE_URL`（售后查询；本期默认固定 mock 数据）
  - `DEVICE_SERVICE_BASE_URL`(deviceSimulator，默认 `http://localhost:8081`;2026-08-27 起为真实 HTTP 调用）
  - `AUTH_ISSUER_URI`（令牌校验）
  - `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`、`REDIS_HOST` / `REDIS_PORT`
- 本地开发：`docker compose up -d` 启动 MySQL 8、Redis（含 RediSearch）与
  **deviceSimulator**（设备交互唯一通道；SN 查询契约见
  `documents/新增接口说明-按SN查询设备.md`)。
- 以下示例使用 `APP_BASE_URL`（缺省 `http://localhost:8180`）和
  `DEVICE_SERVICE_BASE_URL`（项目缺省 `http://localhost:8081`）。新接口文档示例端口为
  8080；实际环境统一通过变量配置，不把端口写入客户端路径。

## 构建与自动化验证

```bash
./mvnw verify          # 单元 + 契约测试(LLM/售后 WireMock 打桩,设备客户端单测)
./mvnw verify -Pit     # 含集成测试:连真实 deviceSimulator(docker-compose 启动)
```

须全部通过：单元测试（含 SN 查询响应/错误映射、设备绑定零写入与唯一冲突）、契约测试
（含设备添加 400/404/409/503 及未知类型仍可添加）、集成测试（下列场景 0/1/2/4/5/6
的设备路径连真实模拟器，先准备模拟器设备再按 SN 绑定，故障经模拟器命令预置 +
yml 规则判定）。

## 既有数据库迁移

- 新建库由 `src/main/resources/schema.sql` 直接创建目标结构。
- 旧版库先做备份，再执行 `scripts/migration/20260904-device-sn-binding.sql`。
- 脚本会先检查 SN 和旧类型/型号能否可靠映射；存在无法回填的行时以错误中止并报告数量，
  不删除或覆盖旧数据。修正这些行后再重新执行，禁止通过
  `spring.sql.init.continue-on-error` 跳过失败。

## 手动端到端场景（本地启动后)

启动：`LLM_API_KEY=... ./mvnw spring-boot:run`（或 `LLM_MODE=mock` 用内置桩）。
先设置：`APP_BASE_URL=${APP_BASE_URL:-http://localhost:8180}`、
`DEVICE_SERVICE_BASE_URL=${DEVICE_SERVICE_BASE_URL:-http://localhost:8081}`。

### 场景 0：按 SN 添加设备（对应 FR-020、SC-009)

```bash
# 1. 测试夹具只在模拟器侧创建；从响应记录生成的 sn（例如 LITE123456789）
curl -X POST "$DEVICE_SERVICE_BASE_URL/api/v1/devices" \
  -H "Content-Type: application/json" \
  -d '{"device_type_code":"LITE","device_model_code":"LA001","quantity":1,"name":"sim-la001"}'

# 2. AutoSense 只按 SN 发现并绑定，不得再次创建模拟器设备
SN="LITE123456789"
curl -X POST "$APP_BASE_URL/api/v1/devices" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"sn\":\"$SN\",\"name\":\"客厅灯\"}"

curl "$APP_BASE_URL/api/v1/devices" -H "Authorization: Bearer $TOKEN"
```

期望：添加返回 `201`；`name=客厅灯`、`simulatorName=sim-la001`，类型/型号编码与 ID
来自模拟器，`supported=true`；响应和列表均不含 state/running status/模拟器时间戳。
绑定前后模拟器设备数量不变。

失败验证：

- 再用同一或另一用户提交 `$SN` → `409 DEVICE_ALREADY_BOUND`，平台最终仅一条绑定。
- 提交 `lite123456789` → `400 BAD_REQUEST`，不调用模拟器。
- 提交格式正确但未知的 `ZZZZ000000000`，或先停止目标设备再提交 →
  `404 DEVICE_NOT_FOUND`，message 为“设备不存在或不在线”，无绑定。
- 将 `DEVICE_SERVICE_BASE_URL` 指向不可用地址后提交未绑定 SN →
  `503 DEVICE_SERVICE_UNAVAILABLE`，message 为“设备服务暂不可用，请稍后重试”，无绑定。
- “当前不支持但仍可添加”由设备 API 契约测试使用 `exists:true` 未注册类型响应覆盖；真实
  模拟器当前仅有 LITE 型号，不能用 `ZZZZ...` 验证此分支（它按契约返回 `exists:false`）。

### 场景 1:US1 自动修复闭环（对应 SC-001、FR-001~009)

```bash
# 预置故障:模拟器上将灯亮度调到 0(或直接 stop)
curl -X POST "$DEVICE_SERVICE_BASE_URL/api/v1/devices/{simId}/commands" \
  -H "Content-Type: application/json" -d '{"command":"set_brightness","parameters":{"brightness":0}}'

# SSE 流式响应(-N 禁用缓冲)
curl -N -X POST "$APP_BASE_URL/api/v1/sessions" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"problem": "客厅的灯不亮了"}'
```

期望：流内依次出现 `status`(ANALYZING→LOCATING→DIAGNOSING→CONFIRMING_REPAIR)
与 `awaiting`(FR-008 全操作确认门）事件后流关闭 → `POST /messages
{"confirmRepair": true}` 的流内出现 REPAIRING→VERIFYING 的 `status` 与终态
`conclusion`(type=FIXED，含 PRE/POST state 对比）。全程 < 2 分钟。

### 场景 2:US2 人工指引分支（对应 FR-010)

预置命中"不可修复-有人工步骤"规则的设备状态（见 yml `fault-rules`，如硬件故障标志
场景）。期望：终态 `conclusion.type=UNFIXED_MANUAL_GUIDE`，含分步指引。

### 场景 3:US2 售后兜底 + 独立网点查询（对应 FR-011、FR-019 路由 4)

- 预置命中"不可修复-步骤不明"规则 → `conclusion.type=UNFIXED_AFTERSALES`,
  `afterSales` 至少 1 条（固定 mock 数据）；无位置时官方客服兜底。
- 直接输入"附近哪有售后网点？我在杭州西湖区" → 不经设备流程，
  `status=COMPLETED_AFTERSALES` 直接返回网点。

### 场景 4：安全与互斥（对应 FR-004/015/016)

- 他人设备发起会话 → `403 DEVICE_FORBIDDEN`。
- 同设备并行两个会话 → 其一 `409 DEVICE_BUSY`。
- 输入"扫地机器人不工作" → `422 UNSUPPORTED_DEVICE_TYPE`，无任何模拟器调用。

### 场景 5：设备不可达与失败即停（对应 FR-014/FR-017)

- 模拟器上 `stop` 目标设备后发起会话 → 终态 `DEVICE_UNREACHABLE`，提示检查电源/网络。
- 修复命令返回错误（可用越界参数在单测构造；集成环境对不可修复规则场景验证）
  → 如实报告失败，无第二次尝试，转人工引导。

### 场景 6：问题路由、对话记忆与 SSE(FR-018/019/021,2026-08-27 新增）

- 常识问题："智能灯泡一般能用多久？" → SSE 流内含 ≥1 个 `token` 事件 +
  `conclusion.type=ANSWERED`，无模拟器调用。
- 型号问题（本期并入常识）:"LA001 支持调颜色吗？" → 直接回答。
- 意图不明："帮我看看那个" → `awaiting`(CLARIFYING）追问，无模拟器调用。
- `GET /api/v1/sessions` → 返回本人历史对话列表。
- 对终态会话 POST 新问题（如"顺便把卧室灯调暗点")→ 流内先出现回到进行中状态的
  `status` 事件，新一轮诊断可见最近 20 条历史消息。
- SSE 断线：中断连接后 `GET /sessions/{id}` 可补查当前状态/结论（断线不补发）。

### 场景 7：用户注册/登录/权限（FR-022~027,2026-08-29 新增）

前提：`AUTH_DEV_MODE=false`（验证真实令牌链路）；数据库含 data.sql 种子的
初始管理员（账号 `admin`,R23)。

- 注册：`POST /api/v1/users/register` `{userAccount:"u_test", userPassword:"pass1234",
  confirmPassword:"pass1234"}` → `201`，昵称=账号，响应不含密码；
  重复注册同账号 → `409 ACCOUNT_EXISTS`；弱密码（如 `12345678`)→ `400`。
- 登录：`POST /api/v1/users/login` → `200` 返回 `token`;
  错误密码 → `401`（不区分账号不存在/密码错误）。
- 携 `Authorization: Bearer <token>` 调 `GET /api/v1/users/me` → 返回本人信息；
  调既有 `GET /api/v1/sessions` → 仅见本人对话。
- 注销：`POST /api/v1/users/logout` → `204`；再用同一令牌调 `/users/me` → `401`。
- 权限：普通用户令牌调 `GET /api/v1/admin/users` → `403`;
  admin 令牌调 `GET /api/v1/admin/users?keyword=u_test` → 列表含该用户。
- 禁用：admin 调 `PUT /api/v1/admin/users/{id}/status` `{disabled:true}` → `200`;
  该用户原令牌立即失效（`401`)，重新登录也被拒；再 `{disabled:false}` 恢复后可登录。
- dev 模式：`AUTH_DEV_MODE=true` 时 `Bearer user-1` 仍可访问既有端点；
  真实令牌优先于 dev 令牌解析（R21)。

## 验收对照

| 场景 | 验证的规格条目 |
| --- | --- |
| 0 | FR-020、FR-015、SC-009（按 SN 发现、绑定归属、全局唯一、错误分流） |
| 1 | US1 全部验收场景、SC-001、FR-001~009、R12 规则判定 |
| 2/3 | US2 全部验收场景、SC-005、FR-010/011、FR-019 路由 4 |
| 4 | SC-003/SC-004、FR-003/004/015/016 |
| 5 | FR-014/017、Edge Cases |
| 6 | FR-018（列表/续聊/20 条窗口/长期保留）、FR-019（四路路由+歧义追问）、FR-021(SSE 流) |
| 7 | US3 全部验收场景、SC-008、FR-022~FR-027、R19~R23 |

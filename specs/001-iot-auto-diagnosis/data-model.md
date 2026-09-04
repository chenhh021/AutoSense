# Data Model: IoT 设备自动诊断与修复

**Date**: 2026-08-22(2026-08-27、2026-08-29、2026-09-04 刷新) | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

持久化采用 MySQL + MyBatis-Flex（章程原则 III)；进行中会话、锁与 LLM 对话记忆窗口
存 Redis(R6/R15)。所有表含 `created_at` / `updated_at`;`id` 均为 BIGINT 自增主键。
**全部对话数据长期保留，不设清理任务**(FR-013,2026-08-22 修订）。

## 1. device（设备)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | 本平台设备 ID |
| user_id | BIGINT | NOT NULL, INDEX | 归属用户（FR-015 越权校验依据） |
| sn | CHAR(13) CHARACTER SET ascii COLLATE ascii_bin | NOT NULL, UNIQUE `uk_device_sn` | 模拟器 SN；区分大小写，API/领域统一命名为 `sn` |
| name | VARCHAR(128) | NOT NULL | 用户提交的平台显示名称，不得被模拟器名称覆盖 |
| simulator_name | VARCHAR(128) | NOT NULL | SN 查询返回的模拟器原始名称 |
| simulator_device_id | BIGINT | NOT NULL | deviceSimulator 侧设备 ID |
| device_type_code | VARCHAR(32) | NOT NULL | 模拟器类型编码，如 `LITE`；不等同于平台适配器键 `smart_bulb` |
| device_type_id | BIGINT | NOT NULL | 模拟器类型 ID |
| device_model_code | VARCHAR(32) | NOT NULL | 模拟器型号编码，如 `LA001` / `LB001` |
| device_model_id | BIGINT | NOT NULL | 模拟器型号 ID |
| created_at | DATETIME | NOT NULL | 平台绑定创建时间 |
| updated_at | DATETIME | NOT NULL | 平台绑定更新时间 |

校验与派生规则：

- `sn` 必须原样满足 `^[A-Z0-9]{4}[0-9]{9}$`，不得 trim、转大写或移除字符；全局唯一，
  `user_id` 不参与唯一键。同一用户和不同用户重复绑定均失败（FR-020/R25）。
- `name` 是必填平台显示名；公开 API 限制 1~64 字符，数据库保留 128 字符容量。
- 模拟器 ID、类型/型号编码与 ID、`simulator_name` 仅从 `exists:true` 的完整 SN 查询响应写入；
  不接受用户覆盖。
- `state`、`running_status`、模拟器 `created_at`/`updated_at` 不属于 device 持久属性。
  诊断需要的实时 state 仍可作为独立 `diagnostic_snapshot` 保存，不能回写 device 表。
- 设备是否支持诊断由 yaml 注册表基于 `device_type_code + device_model_code` 动态派生，
  不设持久化 `supported` 列；未支持设备仍可绑定和列出（FR-004/FR-020/R26）。

添加生命周期（无外部写副作用）：

```text
请求(sn,name)
  → 本地校验/全局重复预检
  → deviceSimulator 按 SN 查询
  ├─ exists:false / 400 / 5xx / timeout / 畸形响应 → 失败，不写库
  └─ exists:true + 稳定字段完整 → 短事务 INSERT
       ├─ 唯一约束成功 → 已绑定
       └─ uk_device_sn 冲突 → 409，不覆盖既有绑定
```

迁移约束：当前 `schema.sql` 使用 `CREATE TABLE IF NOT EXISTS`，只改建表语句不会升级已有
数据库。实施必须提供显式迁移，将旧 `identifier/device_type/device_model` 数据转换为新列，
删除或停止使用 `status/last_seen_at`；不得依赖 `continue-on-error` 静默跳过迁移失败。

## 2. problem_report（问题报告)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | 所属会话（终态续聊时每轮新建一条，R17) |
| round | INT | NOT NULL DEFAULT 1 | 会话内轮次号（2026-08-22 新增，R17) |
| raw_text | TEXT | NOT NULL | 用户原始描述 |
| intent | VARCHAR(32) | NULL | 路由意图 `COMMON_SENSE` / `DEVICE_ACTION` / `AFTERSALES_QUERY`(FR-019/R13;MODEL_SPECIFIC 并入 COMMON_SENSE) |
| device_type | VARCHAR(32) | NULL | LLM 提取的设备类型（FR-002)，未识别为 NULL |
| symptom | VARCHAR(512) | NULL | 问题表现 |
| reproduction | VARCHAR(512) | NULL | 复现方式（若有） |
| clarifications | TEXT | NULL | 追问澄清记录（JSON 数组） |

## 3. repair_session（修复会话)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | 即对话的 session id(FR-018) |
| user_id | BIGINT | NOT NULL, INDEX | 发起用户（列表查询按此过滤） |
| device_id | BIGINT | NULL, INDEX → device.id | 目标设备（定位成功前为 NULL) |
| status | VARCHAR(32) | NOT NULL | 状态机，见下 |
| conclusion_type | VARCHAR(32) | NULL | `FIXED` / `UNFIXED_MANUAL_GUIDE` / `UNFIXED_AFTERSALES` / `DEVICE_UNREACHABLE` / `ANSWERED`（常识直答） / `AFTERSALES_PROVIDED`（独立网点查询） |
| conclusion | TEXT | NULL | 面向用户的结论文本（FR-009) |
| created_at | DATETIME | NOT NULL, INDEX | 列表排序依据（长期保留，无清理） |
| updated_at | DATETIME | NOT NULL | 最近活动时间（续聊刷新） |

### 状态机（2026-08-22/27 扩展：意图路由 + 终态回路 + 全操作确认门）

```text
CREATED → ROUTING(意图分类,R13)
ROUTING ──常识/型号特异性──→ ANSWERING ──→ COMPLETED_ANSWERED(终)
ROUTING ──网点查询──┬─有位置─→ AFTERSALES_LOOKUP ──→ COMPLETED_AFTERSALES(终)
                    └─缺位置─→ AWAITING_LOCATION ──用户提供位置──→ AFTERSALES_LOOKUP
ROUTING ──设备操作──→ ANALYZING
ROUTING ──意图不明──→ CLARIFYING ──用户补充──→ ROUTING
ANALYZING ──信息不足──→ CLARIFYING ──用户补充──→ ANALYZING
ANALYZING ──不支持──→ REJECTED_UNSUPPORTED(终)
ANALYZING → LOCATING ──多候选──→ DEVICE_CONFIRMING ──用户确认──→ LOCATING
LOCATING ──越权──→ REJECTED_FORBIDDEN(终)
LOCATING ──设备忙──→ REJECTED_BUSY(终,HTTP 409)
LOCATING ──设备 stopped/不可达──→ FAILED_DEVICE_UNREACHABLE(终,FR-014)
LOCATING → DIAGNOSING(读 state,R11)→ PLANNING(规则判定,R12)
PLANNING ──规则:不可修复-步骤不明──→ GUIDED_AFTERSALES
GUIDED_AFTERSALES ──有位置──→ AFTERSALES_LOOKUP ──→ COMPLETED_AFTERSALES(终)
GUIDED_AFTERSALES ──缺位置──→ AWAITING_LOCATION ──用户提供位置──→ AFTERSALES_LOOKUP
PLANNING ──规则:不可修复-有人工步骤──→ GUIDED_MANUAL(终,FR-010)
PLANNING ──规则:可自动修复──→ CONFIRMING_REPAIR(全部操作必经确认,FR-008 扩大)
PLANNING ──规则:无异常──→ COMPLETED_ANSWERED(终,观察建议)
CONFIRMING_REPAIR ──用户确认──→ REPAIRING
CONFIRMING_REPAIR ──用户拒绝──→ COMPLETED_UNFIXED(终,记录原因)
REPAIRING ──成功──→ VERIFYING
REPAIRING ──失败──→ VERIFYING(失败即停,FR-017,结论如实报告)
VERIFYING ──恢复──→ COMPLETED_FIXED(终)
VERIFYING ──未恢复──→ GUIDED_* (按 FR-010/011 分支,终)
任意终态 ──用户新消息──→ ROUTING(新一轮,R17)
```

- 等待用户态：`CLARIFYING` / `DEVICE_CONFIRMING` / `CONFIRMING_REPAIR` /
  `AWAITING_LOCATION`（会话上下文驻留 Redis,R6；进入等待态时 SSE 发 `awaiting`
  事件并关流，FR-021)；其余状态由状态机自动推进。
- 终态续聊：状态迁移写 `repair_action_log`；新一轮在 `problem_report` 新增行
  (round 递增，R17)。
- 所有状态迁移写入 `repair_action_log`，保证 FR-013 可追溯。

## 4. diagnostic_snapshot（诊断快照)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | |
| round | INT | NOT NULL DEFAULT 1 | 所属轮次（R17) |
| phase | VARCHAR(8) | NOT NULL | `PRE`（修复前，FR-005)/ `POST`（复检，FR-009) |
| payload | JSON | NOT NULL | 设备 state 键值 + running_status（来自模拟器 `/devices/{id}/data`,R11) |
| created_at | DATETIME | NOT NULL | |

## 5. repair_action_log（修复动作日志)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | |
| action_code | VARCHAR(64) | NOT NULL | 白名单动作码（对应模拟器命令如 `set_brightness`）或状态迁移标记 |
| params | JSON | NULL | 动作参数（如 `{"brightness": 80}`) |
| result | VARCHAR(16) | NOT NULL | `SUCCESS` / `FAILED` / `SKIPPED` |
| message | VARCHAR(1024) | NULL | 失败原因/返回信息（含模拟器错误码透传） |
| created_at | DATETIME | NOT NULL | |

## 6. repair_knowledge（修复知识)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| device_type | VARCHAR(32) | NOT NULL, INDEX | 适用设备类型（本期 `smart_bulb`) |
| problem_pattern | VARCHAR(512) | NOT NULL | 问题特征描述（用于检索） |
| solution_content | TEXT | NOT NULL | 解决方案正文（嵌入文本来源） |
| auto_executable | TINYINT(1) | NOT NULL | 是否可自动执行 |
| repair_action_code | VARCHAR(64) | NULL | 可自动执行时的白名单动作码（模拟器命令名） |
| disruptive | TINYINT(1) | NOT NULL DEFAULT 0 | 保留字段（确认门已扩为全部操作，FR-008) |
| manual_steps | TEXT | NULL | 人工分步指引（FR-010) |

同步策略：条目变更后异步重建其在 Redis Embedding Store 的向量（R4)。

## 7. chat_message（对话消息)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | |
| role | VARCHAR(16) | NOT NULL | `USER` / `ASSISTANT` / `SYSTEM` |
| content | TEXT | NOT NULL | 消息内容 |
| created_at | DATETIME | NOT NULL, INDEX | 长期保留；LLM 窗口重建取最近 20 条(R15) |

## 8. Redis 键规范（非持久化，章程键命名 + TTL)

| 键 | 类型 | TTL | 用途 |
| --- | --- | --- | --- |
| `autosense:session:{sessionId}` | Hash | 30min 滚动 | 进行中会话上下文（状态、待回答问题） |
| `autosense:lock:device:{deviceId}` | String | 10min（续期） | 设备互斥锁（FR-016)，值=sessionId |
| `autosense:chatmemory:{sessionId}` | List | 30min 滚动 | LLM 对话记忆窗口（最近 20 条，R15;MySQL 为权威源） |
| `autosense:token:{token}` | String(JSON) | 7 天滚动 | 登录令牌→`{userId, role}`(R19,FR-023) |
| `autosense:usertokens:{userId}` | Set | 7 天滚动 | 用户全部令牌索引；禁用/启用时整组清除（R19,FR-027) |
| `autosense:rag:*` | RediSearch 索引 | 无（索引型，显式重建） | 知识向量（R4 注明的 TTL 例外） |

## 9. 非持久化实体

- **Device Support Projection（设备诊断支持投影）**：输入设备持久化的
  `device_type_code + device_model_code`，经 `DeviceTypeRegistryProperties` 反向解析为
  平台适配器键（如 `LITE + LA001 → smart_bulb`）和 `supported`；配置变更后即时生效，
  不落库。解析失败时 `supported=false`，设备仍保留在列表中，但诊断入口在任何探测前拒绝。
- **After-sales Location（售后网点）**：本期为项目内固定 mock 数据（R14)，不落库；
  响应模型为名称/地址/联系电话/距离。无结果时回退配置的品牌官方客服（R8)。
- **Fault Rule（故障规则）**:yaml 注册表配置项，不落库：条件（state 字段比较）+
  结论类型 + 关联动作/人工步骤引用（R12)。规则定结论类型；repair_knowledge 经 RAG
  检索补充方案正文/人工步骤文本（FR-007)，规则引用优先。

## 10. user（用户,2026-08-29 新增,US3)

DDL 按用户给定原样（camelCase 列名，R22);实体每字段显式 `@Column`,
`isDelete` 为逻辑删除标记。

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK AUTO_INCREMENT | |
| userAccount | VARCHAR(256) | NOT NULL, UNIQUE(uk_userAccount) | 账号；应用层校验 4~32 位 `^[a-zA-Z0-9_]+$`(FR-022) |
| userPassword | VARCHAR(512) | NOT NULL | **BCrypt 散列**,应用层校验明文 8~64 位含字母+数字；不出接口/日志 |
| userName | VARCHAR(256) | NULL | 昵称，注册缺省=账号 |
| userAvatar | VARCHAR(1024) | NULL | 头像 URL |
| userProfile | VARCHAR(512) | NULL | 简介 |
| userRole | VARCHAR(256) | NOT NULL DEFAULT 'user' | `user`/`admin`(FR-026)；注册写死 user(R23) |
| editTime | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 资料编辑时间（应用显式赋值） |
| createTime | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updateTime | DATETIME | NOT NULL, ON UPDATE CURRENT_TIMESTAMP | |
| isDelete | TINYINT | NOT NULL DEFAULT 0 | 逻辑删除=禁用（FR-027)；禁用即令牌失效（R19) |

关系：user 1─N device / repair_session（归属过滤 FR-015/026，逻辑外键）。
初始管理员（账号 `admin`）经 data.sql 幂等种子，密码为预计算 BCrypt 散列（R23)。

## 关系总览

```text
user(2026-08-29 起为本平台实体) 1─N device(含 simulator_device_id/sn 与稳定类型/型号元数据)
user 1─N repair_session
device 1─N repair_session 1─N problem_report(每轮一条,R17)
repair_session 1─N diagnostic_snapshot / repair_action_log / chat_message
repair_knowledge 按 device_type 关联(逻辑外键,不建物理外键)
```

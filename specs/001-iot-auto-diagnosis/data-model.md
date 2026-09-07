# Data Model: IoT 设备自动诊断与修复

> **2026-09-07 规格拆分说明**：下文保留拆分前的设计/契约/验证指南作为参考，尚未按五个 feature 的新边界重新规划；其中工作区状态、需求编号和流程描述均属于编制时上下文。当前需求以[feature 总览](../README.md)及各自 spec 为准；原需求可查[拆分前规格](history/20260907-before-feature-split.md)。复用适用部分时须核对新职责，本文不代表新 feature 已实现或验收通过。

**Date**: 2026-09-07 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Constitution**: 2.0.0

持久化采用 MySQL + MyBatis-Flex（章程原则 III)；进行中会话、锁与 LLM 对话记忆窗口
存 Redis(R6/R15)。以下关系表沿用现有实体和 `schema.sql`，`id` 均为 BIGINT 自增主键。
`device`、`problem_report`、`repair_session`、`repair_knowledge` 含 `created_at/updated_at`；
`diagnostic_snapshot`、`repair_action_log`、`chat_message` 只有 `created_at`；用户表使用 camelCase 时间列。
**全部对话数据长期保留，不设清理任务**(FR-013,2026-08-22 修订）。

依据 [research.md](./research.md) R1–R31 刷新。标注“目标”的状态、并发、记忆与鉴权规则
须由后续实施完成，不代表当前代码已经满足；本阶段不执行建表、迁移或运行验证。

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
- 现有响应保留 `online:boolean`：绑定成功为 true，列表按 SN 再发现；未发现或探测异常为 false，
  表示“未确认在线”，不等同于确定离线。该只读投影不落库，也不替代诊断时采集 state。

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
数据库。已有 `scripts/migration/20260904-device-sn-binding.sql` 面向 2026-08-27 旧基线，
转换 `identifier/device_type/device_model` 并删除 `status/last_seen_at`。目标是验证其基线、
唯一性与回填前置条件：现脚本只识别已知灯泡型号，`simulator_name=name` 不能证明任意旧数据
的模拟器原名可还原；无法可靠回填时停止并核实，不生成占位 ID，不删除未知记录。
不得依赖 `continue-on-error` 静默跳过迁移失败。

## 2. problem_report（问题报告)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | 所属会话（终态续聊时每轮新建一条，R17) |
| round | INT | NOT NULL DEFAULT 1 | 会话内轮次号（2026-08-22 新增，R17) |
| raw_text | TEXT | NOT NULL | 用户原始描述 |
| intent | VARCHAR(32) | NULL | 归一化意图 `COMMON_SENSE` / `DEVICE_ACTION` / `AFTERSALES_QUERY` / `UNCLEAR`；`MODEL_SPECIFIC` 写入前并入 `COMMON_SENSE`（FR-019/R13） |
| device_type | VARCHAR(32) | NULL | LLM 提取的设备类型（FR-002)，未识别为 NULL |
| symptom | VARCHAR(512) | NULL | 问题表现 |
| reproduction | VARCHAR(512) | NULL | 复现方式（若有） |
| clarifications | TEXT | NULL | 追问澄清记录（JSON 数组） |
| created_at | DATETIME | NOT NULL | 本轮创建时间 |
| updated_at | DATETIME | NOT NULL | 本轮报告更新时间 |

目标：同一会话的新消息经会话租约串行处理后分配递增 round，每轮只创建一条报告；澄清更新
当前报告。分类无效时按 UNCLEAR 处理，禁止由未知分类进入设备调用。

## 3. repair_session（修复会话)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | 即对话的 session id(FR-018) |
| user_id | BIGINT | NOT NULL, INDEX | 发起用户（列表查询按此过滤） |
| device_id | BIGINT | NULL, INDEX → device.id | 目标设备（定位成功前为 NULL) |
| status | VARCHAR(32) | NOT NULL | 状态机，见下 |
| conclusion_type | VARCHAR(32) | NULL | `FIXED` / `UNFIXED_MANUAL_GUIDE` / `UNFIXED_AFTERSALES` / `DEVICE_UNREACHABLE` / `ANSWERED`（常识直答） / `AFTERSALES_PROVIDED`（独立网点查询） |
| conclusion | TEXT | NULL | 面向用户的结论文本（FR-009) |
| conclusion_extra | JSON | NULL | 已有附加结论数据，如 manualSteps、afterSales、preDiagnostics；实体通过 String 映射 JSON |
| created_at | DATETIME | NOT NULL, INDEX | 会话创建时间（长期保留，无清理） |
| updated_at | DATETIME | NOT NULL | 最近活动时间（续聊刷新） |

### 目标状态机（R6/R17/R29，待实施）

初次创建经 ROUTING 分类；终态续聊按 FR-018 直接进入 ANALYZING，并在该新轮状态内
先重分类。ANALYZING 不表示已经允许探测：仅分类为 DEVICE_ACTION 且完成属性、归属、
支持范围和租约校验后，才能进入设备路径。

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
LOCATING ──类型/型号不支持──→ REJECTED_UNSUPPORTED(终)
LOCATING ──设备忙──→ REJECTED_BUSY(终,SSE error:DEVICE_BUSY)
LOCATING ──设备 stopped/不可达──→ FAILED_DEVICE_UNREACHABLE(终,FR-014)
LOCATING → DIAGNOSING(读 state,R11)→ PLANNING(规则判定,R12)
DIAGNOSING ──不可达──→ FAILED_DEVICE_UNREACHABLE(终)
PLANNING ──规则:不可修复-步骤不明──→ GUIDED_AFTERSALES
GUIDED_AFTERSALES ──有位置──→ AFTERSALES_LOOKUP ──→ COMPLETED_AFTERSALES(终)
GUIDED_AFTERSALES ──缺位置──→ AWAITING_LOCATION ──用户提供位置──→ AFTERSALES_LOOKUP
PLANNING ──规则:不可修复-有人工步骤──→ GUIDED_MANUAL(终,FR-010)
PLANNING ──规则:可自动修复──→ CONFIRMING_REPAIR(全部操作必经确认,FR-008 扩大)
PLANNING ──规则:无异常──→ COMPLETED_ANSWERED(终,观察建议)
CONFIRMING_REPAIR ──有效确认且持有当前租约──→ REPAIRING
CONFIRMING_REPAIR ──用户拒绝──→ COMPLETED_UNFIXED(终,记录原因)
CONFIRMING_REPAIR ──确认过期──→ LOCATING(重查归属/支持范围/获取锁/探测，重新生成方案并确认)
REPAIRING ──成功──→ VERIFYING
REPAIRING ──失败──→ VERIFYING(失败即停,FR-017,结论如实报告)
VERIFYING ──恢复──→ COMPLETED_FIXED(终)
VERIFYING ──未恢复──→ GUIDED_MANUAL(终) 或 GUIDED_AFTERSALES(继续查询/等待位置)
VERIFYING ──不可达──→ FAILED_DEVICE_UNREACHABLE(终)
VERIFYING ──内部失败无法正常收尾──→ COMPLETED_UNFIXED(终，记录已发生事实)
任意终态 ──用户新问题──→ ANALYZING(新一轮，先重分类,R17)
ANALYZING ──新轮常识/型号问题──→ ANSWERING
ANALYZING ──新轮网点查询──→ AFTERSALES_LOOKUP 或 AWAITING_LOCATION
ANALYZING ──新轮意图不明──→ CLARIFYING(不探测设备)
```

- 等待用户态：`CLARIFYING` / `DEVICE_CONFIRMING` / `CONFIRMING_REPAIR` /
  `AWAITING_LOCATION`（会话上下文驻留 Redis,R6；进入等待态时 SSE 发 `awaiting`
  事件并关流，FR-021)；其余状态由状态机自动推进。
- 终态为 `REJECTED_UNSUPPORTED/REJECTED_FORBIDDEN/REJECTED_BUSY/FAILED_DEVICE_UNREACHABLE/`
  `COMPLETED_FIXED/COMPLETED_UNFIXED/GUIDED_MANUAL/COMPLETED_ANSWERED/COMPLETED_AFTERSALES`；
  `GUIDED_AFTERSALES` 不是终态。等待态和终态主动结束当前 SSE。
- 目标在结束上一轮前，将完整用户可见结论（摘要、人工步骤、售后名称/地址/电话等）写为
  一条长期保留的 ASSISTANT 消息，再允许续聊清空当前投影。新轮在短事务中更新状态及审计、
  创建 problem_report，并清空当前结论、附加结论、设备引用与旧待执行方案；历史消息、
  诊断快照与操作日志保留。不得继承旧轮授权，也无需新增归档表。
- 超时、模型/内部失败的收尾边必须按来源明确列入状态表：未开始写操作的
  `ROUTING/ANSWERING/ANALYZING/CLARIFYING/LOCATING/DEVICE_CONFIRMING/DIAGNOSING/PLANNING/`
  `CONFIRMING_REPAIR/AFTERSALES_LOOKUP/AWAITING_LOCATION/GUIDED_AFTERSALES` 可进入
  `COMPLETED_UNFIXED`，记录已知事实和原因。REPAIRING 失败先进入 VERIFYING，能读则复检；
  VERIFYING 无法正常完成时进入有明确原因的失败终态，不重试写操作。
- 所有迁移经 `SessionTransitionLog` 校验，状态更新与 repair_action_log 在同一短事务一致提交；
  事件在提交后发送。禁止异常分支直接 setStatus，也不放行任意状态之间的跳转。
- 现状差距：代码终态续聊仍转 ROUTING；状态表缺少 LOCATING→REJECTED_UNSUPPORTED 和
  VERIFYING→FAILED_DEVICE_UNREACHABLE；部分异常绕过迁移日志。以上不是已完成能力。

## 4. diagnostic_snapshot（诊断快照)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | |
| round | INT | NOT NULL DEFAULT 1 | 所属轮次（R17) |
| phase | VARCHAR(8) | NOT NULL | `PRE`（修复前，FR-005)/ `POST`（复检，FR-009) |
| payload | JSON | NOT NULL | 设备 state 键值 + running_status（来自模拟器 `/devices/{id}/data`,R11) |
| created_at | DATETIME | NOT NULL | |

目标：当前结论仅从 `session_id + round + phase` 选快照，按 `created_at,id` 确定排序取
最新 PRE/POST；本轮缺少的快照为 null，不从上一轮补齐。原始历史保留。

## 5. repair_action_log（修复动作日志)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | |
| action_code | VARCHAR(64) | NOT NULL | 白名单动作码（对应模拟器命令如 `set_brightness`）或状态迁移标记 |
| params | JSON | NULL | 动作参数（如 `{"brightness": 80}`) |
| result | VARCHAR(16) | NOT NULL | `SUCCESS` / `FAILED` / `SKIPPED` |
| message | VARCHAR(1024) | NULL | 经脱敏的动作结果/失败原因；保留必要的模拟器错误码，不记录凭据或将内部堆栈返回用户 |
| created_at | DATETIME | NOT NULL | |

动作参数仅包含业务命令数据；状态迁移使用 `state:FROM->TO` 标记。目标在日志记录中包含
轮次上下文（可由 params 记录 round），保留原表结构；日志上下文不得混入实际设备命令参数。
写操作失败即停，执行过的动作必须记录，不得在异常收尾中声称设备一定未变更。

## 6. repair_knowledge（修复知识)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| device_type | VARCHAR(32) | NOT NULL, INDEX | 适用设备类型（本期 `smart_bulb`) |
| problem_pattern | VARCHAR(512) | NOT NULL | 问题特征描述（用于检索） |
| solution_content | TEXT | NOT NULL | 解决方案正文（检索增强资料来源） |
| auto_executable | TINYINT(1) | NOT NULL | 是否可自动执行 |
| repair_action_code | VARCHAR(64) | NULL | 可自动执行时的白名单动作码（模拟器命令名） |
| disruptive | TINYINT(1) | NOT NULL DEFAULT 0 | 保留字段（确认门已扩为全部操作，FR-008) |
| manual_steps | TEXT | NULL | 人工分步指引（FR-010) |
| created_at | DATETIME | NOT NULL | 知识创建时间 |
| updated_at | DATETIME | NOT NULL | 知识更新时间 |

R4 目标：MySQL 为权威源，规则 knowledgeRef 优先，再按设备类型/问题特征检索 Top-K，
把命中的正文/人工步骤提供给模型。空查询不得任意命中第一条；无命中走有依据的人工或售后引导。
本期不创建向量索引；`embeddings-enabled=false` 为支持模式，true 目标启动报错说明尚未实现，
不能静默降级。未来向量缓存也必须原子写入并有 TTL，不存在无 TTL 的章程例外。

## 7. chat_message（对话消息)

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK | |
| session_id | BIGINT | NOT NULL, INDEX → repair_session.id | |
| role | VARCHAR(16) | NOT NULL | `USER` / `ASSISTANT` / `SYSTEM` |
| content | TEXT | NOT NULL | 消息内容 |
| created_at | DATETIME | NOT NULL, INDEX | 长期保留；LLM 窗口重建取最近 20 条(R15) |

R15 目标：编排层是用户可见历史的唯一写入方；每次模型调用以当前消息 ID 为边界读取此前
最近 20 条历史（按 created_at/id 排序），再仅加入一次当前输入。分类 JSON、内部提示和检索
片段不写共享历史；模型代理使用只读历史快照。消息先落 MySQL，再刷新/失效 Redis 窗口。
终态的一条 ASSISTANT 消息必须包含完整用户可见结论，而不只是摘要；目前人工步骤/售后详情
仅在 conclusion_extra、消息仅存摘要，清空新轮投影会丢失旧轮详情，属于待修复保留缺口。

## 8. Redis 键与确认生命周期（目标，R6/R15/R19）

缓存不是持久化权威源。所有键设置明确 TTL；表中值为设计默认值，需经配置属性绑定。

| 键 | 类型 | TTL | 用途 |
| --- | --- | --- | --- |
| `autosense:session:{sessionId}` | Hash | 30min 滚动 | 会话 ID、userId、round、status、候选设备、待执行方案及租约 owner；原子写入/续期并删除失效字段 |
| `autosense:lock:device:{deviceId}` | String | 10min，持有者受控续期 | owner 含 sessionId、round、随机标识；SET NX + TTL 获取，比较 owner 后原子续期/释放 |
| `autosense:lock:session:{sessionId}` | String | 30s，处理期间续期 | 新增消息处理租约；随机请求 owner，串行覆盖写历史、分类和确认消费，等待/终态/失败时比较 owner 后释放 |
| `autosense:chatmemory:{sessionId}` | String(JSON) | 30min 滚动 | 最近历史窗口及边界信息；单次 SET 同时写值和 TTL，MySQL 为权威源 |
| `autosense:token:{token}` | String(JSON) | 7 天滚动 | 登录令牌→`{userId, role}`(R19,FR-023) |
| `autosense:usertokens:{userId}` | Set | 7 天滚动 | 真实 token 的必要有效性成员集合；与 token 同时原子续期，禁用/启用前整组清除 |

会话租约覆盖整个本次 POST（含异步模型回调），定期在到期前按 owner 续租；同一会话并发消息
拒绝处理，不追加重复历史或再次执行确认。设备租约另行约束同设备跨会话互斥。
任何续期失败或 owner 改变均终止后续推进；迟到回调不得恢复状态或下发命令。

确认判定必须联合检查当前会话/用户/设备归属、round、CONFIRMING_REPAIR 状态、待执行
动作/参数以及当前设备锁 owner。只有当前计划的有效确认可进入 REPAIRING。
`confirmationExpired` 是“锁、上下文或方案已失效”的内部判定，不新增 API 字段：
清除旧授权后重新定位、获取独占锁并探测，生成可用方案后重新发 awaiting；收到的旧
`confirmRepair=true` 不用于授权新方案。上下文缺失不能直接执行 null 动作或旧参数。
执行期间租约失效停止后续命令；已发出的动作记录结果，能读则复检。

记忆现状为 Redis List，目标变更为 String(JSON)；遇旧类型仅淘汰该会话缓存并从 MySQL 重建。
当前锁只存 sessionId、确认前未复核持有者、上下文更新可能残留旧字段、token Set 未随解析续期，
均属于后续实施缺口。本期不写 `autosense:rag:*` 向量数据。

## 9. 非持久化实体

- **Device Support Projection（设备诊断支持投影）**：输入设备持久化的
  `device_type_code + device_model_code`，经 `DeviceTypeRegistryProperties` 反向解析为
  平台适配器键（如 `LITE + LA001 → smart_bulb`）和 `supported`；配置变更后即时生效，
  不落库。解析失败时 `supported=false`，设备仍保留在列表中，但诊断入口在任何探测前拒绝。
- **Device Online Projection（在线发现投影）**：`online:boolean` 由按 SN 只读发现获得；
  false 只表示本次未确认在线，不能据此生成诊断结论；后续诊断重新采集实时状态。
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

### 目标鉴权有效性与并发（R19/R21/R22）

真实 token 有效要求同时满足：数据库用户存在且 `isDelete=0`、token 键存在、token 是该
用户 Set 的成员。Redis 对 token 内容/存在性、成员关系和双 TTL 的验证/续期必须原子完成；
Set 缺失或不含 token 直接失效，不能借解析请求自动补回成员。

签发、禁用、启用以同一用户行的短事务锁串行，查锁包含已禁用行；签发在锁内复核启用态后
原子写 token、Set 成员及双 TTL。禁用先更新 isDelete 再清理 token/整个 Set；启用前再次清理
整个 Set。只枚举删除 token 不足以撤销残留孤立 token，Set 删除使其统一失效。
事务提交后才返回成功；Redis 操作失败回滚数据库事务并返回失败，已撤销的 Redis 凭证无需恢复。
重新启用需重新登录，新 Set 不含旧 token，因此旧 token 不复活。无需新增 authVersion 等表列。

当前解析只续 token、不续 Set，也未复核用户启用态，不能视为已完成失效保障。
`AUTH_DEV_MODE` 仅为显式本地替代身份，生产关闭，不将其行为作为真实 token 的注销验证。
已认证且正在处理的请求不追溯中断，后续新请求执行新的鉴权检查。

## 11. Java 模型归属与迁移（目标，章程 2.0.0）

| 模型职责 | 目标位置与类型 | 兼容边界 |
| --- | --- | --- |
| 持久化实体 | `domain/entity/`，对应上述 8 张表 | 不因包规范更改表名/列名 |
| 请求 DTO | `domain/dto/`：CreateSessionRequest、MessageRequest、RegisterDeviceRequest、RegisterRequest、LoginRequest、SetUserStatusRequest | 保留六个请求类型及现有字段 |
| 传输响应 DTO | `domain/dto/`：LoginResponse、SessionResponse、ConclusionDto | 保留字段和 JSON 格式 |
| 展示 VO | `domain/vo/`：UserView、AdminUserPageView、DeviceView、ChatMessageView、SessionListItemView | 从同名 `domain/dto/` 文件迁移，类名和字段不变 |
| SSE 类型 | `domain/message/`：SseEvent、SseEventStream | 五类事件不因包迁移改变 |
| AI 结构化输出 | `ai/model/`：ProblemAnalysis、DiagnosisConclusion | 从 `core/analysis/` 迁移，仍需业务校验 |
| AI 分类枚举 | `ai/model/enums/Intent` | 从 `domain/enums/Intent` 迁移，归一化后的持久化字符串不变 |
| 业务枚举 | `domain/enums/`：SessionStatus、ConclusionType、ActionResult、MessageRole、SnapshotPhase、DeviceType | 按业务用途保留，不因参与 AI 调用而迁移 |
| AI 创建与全局常量 | `ai/factory/` 创建 AI Service；`constant/` 放 user/admin 等权限常量；`utils/` 放通用工具 | 不新增角色体系，不用工具类编排业务 |

VO 引用同步调整：UserView 涉及 UserController/AdminUserController/LoginResponse/AdminUserPageView；
AdminUserPageView 涉及 AdminUserController；DeviceView 涉及 DeviceController；ChatMessageView
涉及 SessionController；SessionListItemView 涉及 SessionController/SessionOrchestrator。
结论历史写入调整在现有 chat_message 表内完成，保留完整内容；已有历史缺失详情不能凭空补造。
UserController.me 的 UserMapper 查询收敛到 UserService 的当前用户查询方法，由服务处理
不存在/禁用校验；Controller 仅保留请求接收与 UserView 响应转换。设备列表在线和支持性编排
收敛到 core/device 业务入口，Mapper 仅承担持久化，不放 DTO/VO 转换逻辑。

## 关系总览

```text
user(2026-08-29 起为本平台实体) 1─N device(含 simulator_device_id/sn 与稳定类型/型号元数据)
user 1─N repair_session
device 1─N repair_session 1─N problem_report(每轮一条,R17)
repair_session 1─N diagnostic_snapshot / repair_action_log / chat_message
repair_knowledge 按 device_type 关联(逻辑外键,不建物理外键)
```

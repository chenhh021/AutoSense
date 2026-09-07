# API Contract: 诊断修复服务（v1)

> **2026-09-07 规格拆分说明**：下文保留拆分前的设计/契约/验证指南作为参考，尚未按五个 feature 的新边界重新规划；其中工作区状态、需求编号和流程描述均属于编制时上下文。当前需求以[feature 总览](../../README.md)及各自 spec 为准；原需求可查[拆分前规格](../history/20260907-before-feature-split.md)。复用适用部分时须核对新职责，本文不代表新 feature 已实现或验收通过。

**Date**: 2026-09-07 | **Spec**: [../spec.md](../spec.md) | **Constitution**: 2.0.0

本特性提供后端 REST/JSON 与 SSE API；同仓库已有前端作为调用方，不在本轮扩展界面。Base path: `/api/v1`。
认证：所有端点要求请求头 `Authorization: Bearer <token>`（平台签发，R7);
未认证一律 `401`。
**会话写入端点（§1/§2）以 SSE(text/event-stream）流式返回**(FR-021/R18);
查询端点（§3~§5、§7）以及设备添加（§6）返回普通 JSON。

依据 [research.md](../research.md) R1–R31 与当前源码刷新；路径、字段沿用现有实现。
标注“目标”的有效确认、多轮、租约、时限与审计行为待后续实施，不表示本次已通过运行验收。
示例设备 ID/SN 为格式说明，验证时使用真实模拟器返回值。

## 通用约定

- 错误响应统一结构：

```json
{
  "code": "DEVICE_BUSY",
  "message": "该设备正在处理中，请稍后再试",
  "sessionId": 10023
}
```

HTTP 接受前的认证、参数绑定/校验错误，以及普通 JSON 端点错误，用 HTTP 状态表达。
会话 POST 已接受并建立 SSE 流后保持 HTTP `200`，异步业务失败通过 `error` 事件表达并关流，
不能在流中再改成 HTTP 409/422。当前 `SessionController` 的编排在异步任务中执行，
设备忙、不支持、不可达及异步会话校验失败均按流内错误处理。

| JSON 端点/流建立前 HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 请求字段缺失或格式不合法；设备添加时包含 SN 格式错误 |
| 401 | `UNAUTHORIZED` | 未登录或令牌无效 |
| 403 | `DEVICE_FORBIDDEN` | 设备或会话不属于当前用户（FR-015) |
| 404 | `SESSION_NOT_FOUND` / `DEVICE_NOT_FOUND` | 资源不存在；添加设备时后者也表示“设备不存在或不在线” |
| —（SSE error） | `DEVICE_BUSY` | 设备已有进行中会话（FR-016)，会话 POST 已接受时 HTTP 为 200 |
| 409 | `DEVICE_ALREADY_BOUND` | SN 已被任一用户绑定，不暴露原绑定用户（FR-020) |
| —（SSE error） | `UNSUPPORTED_DEVICE_TYPE` | 设备类型/型号不支持（FR-004；本期仅 LITE:LA001/LB001 映射为 smart_bulb） |
| —（SSE error） | `DEVICE_UNREACHABLE` | 设备 stopped/不可达（FR-014) |
| 503 | `DEVICE_SERVICE_UNAVAILABLE` | deviceSimulator 5xx、连接/读取超时或无效响应 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |

上表 JSON 错误码同样可用于流建立后的 error 事件。JSON 错误可包含 sessionId 上下文；
现有 SSE error 始终包含 sessionId，尚无可用会话 ID 时为 `-1`。内部堆栈、凭据及原始上游
响应不能暴露给客户端。管理员身份不绕过设备/会话所有权检查。

## 1. 发起对话/诊断会话

`POST /sessions` —— 响应为 **SSE 流**(`Content-Type: text/event-stream`,FR-021)

请求：

```json
{
  "problem": "客厅的灯不亮了"
}
```

`problem` 必填且非空白；空值在建立流前返回 `400 BAD_REQUEST`。

响应 `200`(SSE 事件序列；意图路由 FR-019/R13 决定事件走向）:

```text
event: status
data: {"sessionId": 10023, "status": "ROUTING"}

event: status
data: {"sessionId": 10023, "status": "ANALYZING"}

event: status
data: {"sessionId": 10023, "status": "LOCATING"}

event: status
data: {"sessionId": 10023, "status": "DIAGNOSING"}

event: status
data: {"sessionId": 10023, "status": "PLANNING"}

event: status
data: {"sessionId": 10023, "status": "CONFIRMING_REPAIR"}

event: awaiting
data: {"sessionId": 10023, "prompt": "检测到客厅灯亮度为 3，是否将其调整为 80？此操作会改变设备状态。"}
```

- **SSE 事件类型**(R18):`token`(LLM 逐 token 文本,data `{"text": "..."}`)、
  `status`（状态机迁移，data `{"sessionId", "status"}`)、`awaiting`（进入等待用户态，
  data `{"sessionId", "prompt"}`)、`conclusion`（终态结论，结构同 §3 conclusion)、
  `error`（错误，data 为通用错误结构）。
- `conclusion` 的 data 直接为 §3 的 conclusion 对象，不额外包裹 sessionId；客户端保存
  status/awaiting 中的 sessionId。等待状态通过 status 事件和 GET 状态确认，awaiting 本身
  只有 sessionId/prompt。结构化分类 JSON、内部提示不作为面向用户的 token 输出。
- 常识/型号问题：`status`(ANSWERING)→ 若干 `token` → `conclusion`(`ANSWERED`);
  网点查询：有位置时 `status`(AFTERSALES_LOOKUP)→ `conclusion`(`AFTERSALES_PROVIDED`);
  缺位置时 `awaiting`(AWAITING_LOCATION,`prompt` 询问位置)，用户经 §2 补充位置后
  继续；意图不明：`awaiting`(CLARIFYING，追问内容在 `prompt`)。
- **流生命周期**：`awaiting`、`conclusion` 或 `error` 发出后服务端主动结束流；
  断线不补发，用 §3 补查（FR-021)。目标中模型生成的用户解释经流式服务发送 token，
  确定性规则文本可直接通过 awaiting/conclusion 提供，不伪造模型 token。
- 修复确认后的 REPAIRING→VERIFYING→终态在同一请求（§2）的流内继续推送，
  无需轮询。

## 2. 会话内追加消息（澄清/设备确认/操作确认/终态续聊）

`POST /sessions/{sessionId}/messages` —— 响应为 **SSE 流**（事件类型同 §1)

请求：

```json
{ "content": "客厅那台" }
```

或确认设备操作（FR-008,**所有改变设备状态的操作都需确认**):

```json
{ "content": "确认执行", "confirmRepair": true }
```

- 对**终态**会话调用本端点需非空白 content。目标按 FR-018 从终态直接进入 `ANALYZING`
  并在新轮内先重分类，再进入常识、售后或设备路径；首个新轮状态事件为 ANALYZING。
  当前实现仍进入 ROUTING，此处是待实施修订（R17），不会修改规格文字。
  目标先将上一轮完整用户可见结论存入长期 ASSISTANT 消息，再创建新轮问题报告、清除当前
  结论投影、待执行方案和授权，不沿用上一轮快照。
- 目标每次模型调用只读取当前输入之前最近 20 条历史，并且仅加入一次当前输入；
  全部历史仍长期可查。内部分类/提示不进入共享消息历史（R15）。
- 操作确认后，REPAIRING → VERIFYING → 终态的 `status`/`conclusion` 事件
  在本响应流内依次推送（FR-021)。
- CONFIRMING_REPAIR 时仅 `confirmRepair:true` 表示确认；false 或未提供时按现有行为取消，
  记录取消原因并进入 COMPLETED_UNFIXED。其他等待状态使用 content 回答澄清、设备候选或位置。
- **目标有效确认**（R6/R29）：服务器验证当前用户和设备归属、轮次、状态、待执行方案及
  设备锁 owner；同一会话的消息串行处理，重复/并发确认不能产生第二次写动作。
  会话正在处理时并发消息以现有 `BAD_REQUEST` 错误码说明“会话正在处理中，请稍后再试”，
  不追加重复历史；SSE 已接受时通过 error 事件表达。
- 锁、上下文或方案失效（内部 `confirmationExpired` 判定）时，旧确认不能执行操作：
  重新定位、获取锁并读取最新诊断，再生成方案、发 awaiting 要求新确认；如果设备已正常、
  忙、不可达或不支持，则返回对应当前事实。此过程不新增请求字段，也不把旧 true 自动用于新方案。
- 设备操作失败即停，不自动重试；已发出动作记录真实结果，能读则复检。取消、超时或租约失效
  禁止新的写操作，迟到模型回调不得继续推进；所有状态迁移和结果需要审计。

## 3. 查询会话状态与结论（JSON，非流式；断线补查/非 SSE 客户端使用，FR-021)

`GET /sessions/{sessionId}`

响应 `200`:

```json
{
  "sessionId": 10023,
  "status": "COMPLETED_FIXED",
  "reply": "已修复：已调整亮度并复检正常。",
  "awaitingInput": false,
  "conclusion": {
    "type": "FIXED",
    "summary": "已修复：检测到亮度为 3，已调整为 80 并复检正常。",
    "preDiagnostics": { "brightness": 3, "color_temperature": 4600 },
    "postDiagnostics": { "brightness": 80, "color_temperature": 4600 },
    "manualSteps": null,
    "afterSales": null
  }
}
```

字段沿用 `SessionResponse`：没有 device 字段。`reply` 为最近的等待提示、处理中提示或结论；
`awaitingInput` 对 CLARIFYING/DEVICE_CONFIRMING/CONFIRMING_REPAIR/AWAITING_LOCATION 为 true。
非终态 conclusion 为 null；拒绝等无结论类型的终态也可能为 null，以 status/reply 为准。
目标 PRE/POST 只取当前 round，缺失为 null，不从旧轮补齐（R29）。

`conclusion.type` ∈ `FIXED` / `UNFIXED_MANUAL_GUIDE` / `UNFIXED_AFTERSALES` /
`DEVICE_UNREACHABLE` / `ANSWERED` / `AFTERSALES_PROVIDED`。
`UNFIXED_MANUAL_GUIDE` 时 `conclusion.manualSteps` 为分步指引数组；
`UNFIXED_AFTERSALES` / `AFTERSALES_PROVIDED` 时 `conclusion.afterSales` 为网点数组：

```json
{
  "type": "UNFIXED_AFTERSALES",
  "summary": "无法确定远程修复步骤,建议联系售后。",
  "afterSales": [
    { "name": "XX 售后服务中心(XX店)", "address": "...", "phone": "...", "distanceMeters": 2300 }
  ]
}
```

## 4. 查询会话对话记录

`GET /sessions/{sessionId}/messages`

响应 `200` 为数组，如 `[{ "role": "USER", "content": "客厅灯太暗了", "createdAt": "2026-09-07T09:00:00" }]`。
role 沿用已存 USER/ASSISTANT/SYSTEM；按时间正序返回完整历史，目标同时间按 ID 稳定排序。
长期保留不受 LLM 最近 20 条历史窗口影响（FR-018/R15）。
目标终态的一条 ASSISTANT 消息包含完整结论（摘要、人工步骤、售后名称/地址/电话等），
确保新轮清除当前 conclusion_extra 后仍能通过本端点阅读旧轮详情，不新增归档接口。
当前实现部分结论消息仅有摘要，此保留缺口待修复；已有缺失历史不能由模型补造。

## 5. 历史对话列表（FR-018,2026-08-22 新增）

`GET /sessions`

响应 `200`:

```json
{
  "sessions": [
    { "sessionId": 10023, "status": "COMPLETED_FIXED", "preview": "客厅的灯不亮了",
      "createdAt": "...", "updatedAt": "..." }
  ]
}
```

仅返回当前用户会话，按 `updatedAt` 倒序。preview 优先最近结论，无结论时回退首条用户问题。
继续对话 = 对返回的 sessionId 调端点 2。

## 6. 按 SN 添加设备（FR-020,2026-09-04 修订）

`POST /devices`

请求：

```json
{ "sn": "LITE123456789", "name": "客厅灯" }
```

- `sn`：必填，必须原样满足 `^[A-Z0-9]{4}[0-9]{9}$`；区分大小写，服务端不得 trim、
  转大写或删除字符。
- `name`：必填、非空，最大 64 字符；作为平台显示名称。
- 请求中的类型/型号字段不再接受；设备身份与分类只信任 SN 查询结果。

响应 `201`:

```json
{
  "id": 55,
  "name": "客厅灯",
  "simulatorName": "LA001-a3f9b2",
  "sn": "LITE123456789",
  "deviceTypeCode": "LITE",
  "deviceTypeId": 1,
  "deviceModelCode": "LA001",
  "deviceModelId": 1,
  "supported": true,
  "online": true
}
```

- 服务端先按 SN 检查平台全局重复，再调用 deviceSimulator
  `GET /api/v1/devices/by-sn/{sn}`。只有 `200 + exists:true` 且稳定字段完整时才写绑定；
  绑定动作不得在模拟器创建新设备。
- `name` 始终来自用户请求；`simulatorName` 来自上游 `name`。响应不得包含 `state`、
  `runningStatus` 或模拟器时间戳；`supported` 由当前诊断注册表动态计算，不落库。
- `online` 保留当前布尔字段，绑定刚按 SN 成功发现即返回 true，不为该响应重复探测。
- 查询到当前不支持诊断的类型/型号仍返回 `201` 和 `supported:false`；之后发起诊断时
  才在 SSE 内返回 `error`/`UNSUPPORTED_DEVICE_TYPE`，且不得读取诊断数据或操作设备。
- 失败映射：SN/名称非法 → `400 BAD_REQUEST`；`exists:false` → `404 DEVICE_NOT_FOUND`
  且 message 固定为“设备不存在或不在线”；已有绑定或并发唯一冲突 →
  `409 DEVICE_ALREADY_BOUND`；上游 5xx/超时/连接失败/畸形响应 →
  `503 DEVICE_SERVICE_UNAVAILABLE` 且 message 固定为“设备服务暂不可用，请稍后重试”。
  以上情况新增设备和绑定记录数均为 0。

## 7. 我的设备列表（FR-020 配套）

`GET /devices`

响应 `200`:

```json
{
  "devices": [
    {
      "id": 55,
      "name": "客厅灯",
      "simulatorName": "LA001-a3f9b2",
      "sn": "LITE123456789",
      "deviceTypeCode": "LITE",
      "deviceTypeId": 1,
      "deviceModelCode": "LA001",
      "deviceModelId": 1,
      "supported": true,
      "online": true
    }
  ]
}
```

仅返回当前用户绑定。沿用当前行为按 SN 做只读发现：exists:true 时 online=true；
exists:false 或探测异常时 online=false，表示“本次未确认在线”，不能区分确定离线和上游失败。
探测异常不使整个列表失败；不持久化 online，也不把响应 state/running_status 存入 device。
诊断时仍重新读取 state/running status 并保存诊断快照，不能使用 online 代替诊断证据。
目标仅将支持性/发现编排收敛到 core/device 业务入口，不改变这些响应字段（R26/R27）。

## 长耗时处理

- SSE 客户端：修复确认后 REPAIRING→VERIFYING→终态在 §2 响应流内推送，无需轮询（FR-021)。
- 非 SSE 客户端/断线补查：可按 5s 间隔轮询 §3；进入等待态时需要提交下一条消息，不能无限等终态。
- 目标单次模型调用上限 30s、重试 0；首次诊断总耗时预算不超过 120s，包含排队、模型和 HTTP
  调用，等待用户输入时间不计入处理预算（R18/SC-001）。上述参数配置化，现有较长默认值待调整。
- 达到时限时停止后续写动作，记录事实并通过 error 或有依据的 conclusion 收尾，GET 可补查。
  不增加独立订阅端点，不支持 Last-Event-ID 事件重放。

## 契约测试要点（`src/test/.../contract/`)

1. 未带令牌 → HTTP 401；GET 他人/不存在会话 → HTTP 403/404，已接受 SSE 的同类错误在流中表达。
2. 同设备并发创建会话 → 第二个已接受请求以 SSE error `DEVICE_BUSY` 关流，HTTP 200。
3. 不支持设备类型/型号 → SSE error `UNSUPPORTED_DEVICE_TYPE`，且没有诊断读取或设备写入。
4. **任何**改变状态的操作未确认 → 状态停在 `CONFIRMING_REPAIR`；确认后进入
   `REPAIRING`(FR-008 2026-08-22 扩大）。
5. 修复失败 → 终态含如实结论，且不存在第二次修复尝试（FR-017 无重试）。
6. 常识问题（如"灯泡一般寿命多久")→ `conclusion.type=ANSWERED`，无任何设备调用（FR-019)。
7. 意图不明输入 → `awaiting`(CLARIFYING)，无任何设备调用。
8. 目标终态续聊首个新轮 status 为 ANALYZING；重分类常识/未知意图不得探测，旧轮快照不进入新结论，
   原完整人工步骤/售后详情仍可由历史消息读取。
9. 设备添加：合法 `{sn,name}` → 201，用户名称不被 `simulatorName` 覆盖，响应不含
    state/running status/模拟器时间戳，online=true，且模拟器设备数量不增加。
10. 设备添加请求缺字段、名称空白或 SN 大小写/长度/后九位格式错误 → 400，且不调用
    设备服务；`exists:false` → 404；5xx/超时 → 503；所有失败均无数据库写入。
11. 同一 SN 被同用户或不同用户再次添加，以及两个请求并发添加 → 409，最终仅一条绑定；
    本地重复预检命中时不调用设备服务。
12. SN 查询返回当前不支持的类型/型号 → 添加仍为 201 且 `supported:false`；发起诊断才
    返回 `UNSUPPORTED_DEVICE_TYPE`，且无设备探测/操作。
13. SSE(FR-021):§1/§2 响应 Content-Type 为 text/event-stream;`awaiting`/`conclusion`/`error`
    事件发出后流关闭；事件序列含状态迁移；常识回答路径含 ≥1 个 `token` 事件。
14. 设备列表保留 online 布尔值；上游未发现或异常为 false，列表仍返回，不保存运行状态。
15. 目标并发确认只允许一次写操作；租约过期、上下文丢失后的旧 true 必须先重探测再要求新确认。
16. 目标超时/租约丢失后的迟到回调无新增写动作；所有失败迁移有审计，修复失败能读则复检。
17. Java 包迁移保持 JSON：DeviceView、ChatMessageView、SessionListItemView 移到 domain/vo；
    CreateSessionRequest/MessageRequest/RegisterDeviceRequest、SessionResponse/ConclusionDto 保留 domain/dto。

这些要点描述目标验收，不是本次测试运行记录；已有契约测试覆盖部分当前字段和事件，
安全租约、实际状态机迁移及多轮隔离还需服务/集成测试验证，不能仅用编排 mock 证明。

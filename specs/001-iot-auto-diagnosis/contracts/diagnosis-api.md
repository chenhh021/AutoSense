# API Contract: 诊断修复服务（v1)

**Date**: 2026-08-22(2026-08-27、2026-08-29、2026-09-04 刷新) | **Spec**: [../spec.md](../spec.md)

纯 REST + JSON，无界面。Base path: `/api/v1`。
认证：所有端点要求请求头 `Authorization: Bearer <token>`（平台签发，R7);
未认证一律 `401`。
**会话写入端点（§1/§2）以 SSE(text/event-stream）流式返回**(FR-021/R18);
查询端点（§3~§7）为普通 JSON。

## 通用约定

- 错误响应统一结构：

```json
{
  "code": "DEVICE_BUSY",
  "message": "该设备正在处理中，请稍后再试",
  "sessionId": 10023
}
```

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 请求字段缺失或格式不合法；设备添加时包含 SN 格式错误 |
| 401 | `UNAUTHORIZED` | 未登录或令牌无效 |
| 403 | `DEVICE_FORBIDDEN` | 设备不属于当前用户（FR-015) |
| 404 | `SESSION_NOT_FOUND` / `DEVICE_NOT_FOUND` | 资源不存在；添加设备时后者也表示“设备不存在或不在线” |
| 409 | `DEVICE_BUSY` | 设备已有进行中会话（FR-016) |
| 409 | `DEVICE_ALREADY_BOUND` | SN 已被任一用户绑定，不暴露原绑定用户（FR-020) |
| 422 | `UNSUPPORTED_DEVICE_TYPE` | 设备类型/型号不支持（FR-004；本期仅 smart_bulb LA001/LB001) |
| 422 | `DEVICE_UNREACHABLE` | 设备 stopped/不可达（FR-014) |
| 503 | `DEVICE_SERVICE_UNAVAILABLE` | deviceSimulator 5xx、连接/读取超时或无效响应 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |

## 1. 发起对话/诊断会话

`POST /sessions` —— 响应为 **SSE 流**(`Content-Type: text/event-stream`,FR-021)

请求：

```json
{
  "problem": "客厅的灯不亮了"
}
```

响应 `200`(SSE 事件序列；意图路由 FR-019/R13 决定事件走向）:

```text
event: status
data: {"sessionId": 10023, "status": "ANALYZING"}

event: status
data: {"sessionId": 10023, "status": "DIAGNOSING"}

event: status
data: {"sessionId": 10023, "status": "CONFIRMING_REPAIR"}

event: awaiting
data: {"sessionId": 10023, "prompt": "检测到客厅灯亮度为 0,是否将其调整为 80?(此操作会改变设备状态)"}
```

- **SSE 事件类型**(R18):`token`(LLM 逐 token 文本,data `{"text": "..."}`)、
  `status`（状态机迁移，data `{"sessionId", "status"}`)、`awaiting`（进入等待用户态，
  data `{"sessionId", "prompt"}`)、`conclusion`（终态结论，结构同 §3 conclusion)、
  `error`（错误，data 为通用错误结构）。
- 常识/型号问题：`status`(ANSWERING)→ 若干 `token` → `conclusion`(`ANSWERED`);
  网点查询：有位置时 `status`(AFTERSALES_LOOKUP)→ `conclusion`(`AFTERSALES_PROVIDED`);
  缺位置时 `awaiting`(AWAITING_LOCATION,`prompt` 询问位置)，用户经 §2 补充位置后
  继续；意图不明：`awaiting`(CLARIFYING，追问内容在 `prompt`)。
- **流生命周期**：进入等待用户态（`awaiting` 发出后）或终态（`conclusion` 发出后）
  服务端主动结束流；断线不补发，用 §3 补查（FR-021)。
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

- 对**终态**会话调用本端点 = 继续对话：状态机从终态回到 `ROUTING`/`ANALYZING`
  开启新一轮（FR-018/R17)，历史消息（最近 20 条）对新一轮分析可见（R15),
  新一轮事件在本响应流内推送。
- 操作确认后，REPAIRING → VERIFYING → 终态的 `status`/`conclusion` 事件
  在本响应流内依次推送（FR-021)。

## 3. 查询会话状态与结论（JSON，非流式；断线补查/非 SSE 客户端使用，FR-021)

`GET /sessions/{sessionId}`

响应 `200`:

```json
{
  "sessionId": 10023,
  "status": "COMPLETED_FIXED",
  "device": { "id": 55, "name": "客厅灯", "type": "smart_bulb", "model": "LA001" },
  "awaitingInput": false,
  "conclusion": {
    "type": "FIXED",
    "summary": "已修复：检测到亮度为 0,已调整为 80 并复检正常。",
    "preDiagnostics": { "brightness": 0, "color_temperature": 4600 },
    "postDiagnostics": { "brightness": 80, "color_temperature": 4600 }
  }
}
```

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

响应 `200`:`[{ "role": "USER|ASSISTANT", "content": "...", "createdAt": "..." }]`
（完整历史，长期保留；LLM 端窗口为最近 20 条，FR-018/R15)

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

仅返回当前用户会话，按 `updatedAt` 倒序。继续对话 = 对返回的 sessionId 调端点 2。

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
  "supported": true
}
```

- 服务端先按 SN 检查平台全局重复，再调用 deviceSimulator
  `GET /api/v1/devices/by-sn/{sn}`。只有 `200 + exists:true` 且稳定字段完整时才写绑定；
  绑定动作不得在模拟器创建新设备。
- `name` 始终来自用户请求；`simulatorName` 来自上游 `name`。响应不得包含 `state`、
  `runningStatus` 或模拟器时间戳；`supported` 由当前诊断注册表动态计算，不落库。
- 查询到当前不支持诊断的类型/型号仍返回 `201` 和 `supported:false`；之后发起诊断时
  才返回 `422 UNSUPPORTED_DEVICE_TYPE`，且不得读取或操作设备。
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
      "supported": true
    }
  ]
}
```

仅返回当前用户绑定；不为列表读取或缓存实时运行状态。诊断时重新向 deviceSimulator
读取 state/running status，并按既有规则保存诊断快照。

## 长耗时处理

- SSE 客户端：修复确认后 REPAIRING→VERIFYING→终态在 §2 响应流内推送，无需轮询（FR-021)。
- 非 SSE 客户端/断线补查：以 ≤5s 间隔轮询 §3 直至终态（轮询端点保留）。
- 单次请求内同步步骤（路由/分析/定位/诊断）总耗时预算 < 120s(SC-001)。

## 契约测试要点（`src/test/.../contract/`)

1. 未带令牌 → 401；他人设备/会话 → 403/404。
2. 同设备并发创建会话 → 第二个请求 409 `DEVICE_BUSY`。
3. 不支持设备类型/型号 → 422 `UNSUPPORTED_DEVICE_TYPE`，且无任何设备调用发生。
4. **任何**改变状态的操作未确认 → 状态停在 `CONFIRMING_REPAIR`；确认后进入
   `REPAIRING`(FR-008 2026-08-22 扩大）。
5. 修复失败 → 终态含如实结论，且不存在第二次修复尝试（FR-017 无重试）。
6. 常识问题（如"灯泡一般寿命多久")→ `conclusion.type=ANSWERED`，无任何设备调用（FR-019)。
7. 意图不明输入 → `awaiting`(CLARIFYING)，无任何设备调用。
8. 终态会话 POST 新消息 → 流内先出现回到进行中状态的 `status` 事件（终态回路，FR-018)。
9. 设备添加：合法 `{sn,name}` → 201，用户名称不被 `simulatorName` 覆盖，响应不含
   state/running status/模拟器时间戳，且模拟器设备数量不增加。
10. 设备添加请求缺字段、名称空白或 SN 大小写/长度/后九位格式错误 → 400，且不调用
    设备服务；`exists:false` → 404；5xx/超时 → 503；所有失败均无数据库写入。
11. 同一 SN 被同用户或不同用户再次添加，以及两个请求并发添加 → 409，最终仅一条绑定；
    本地重复预检命中时不调用设备服务。
12. SN 查询返回当前不支持的类型/型号 → 添加仍为 201 且 `supported:false`；发起诊断才
    返回 `UNSUPPORTED_DEVICE_TYPE`，且无设备探测/操作。
13. SSE(FR-021):§1/§2 响应 Content-Type 为 text/event-stream;`awaiting`/`conclusion`
    事件发出后流关闭；事件序列含状态迁移；常识回答路径含 ≥1 个 `token` 事件。

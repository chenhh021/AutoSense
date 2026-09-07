# API Contract: 公共会话与流式响应

**Date**: 2026-09-07
**Feature**: [002](../spec.md)
**Status**: 保持既有外形的目标契约；标注为新增的状态/错误与一致性行为尚待实施。URL相对应用地址，公共前缀为 /api/v1。

## 1. 接口清单

所有端点要求有效Bearer身份，且只允许访问本人会话。

| 方法及路径 | 请求 | 成功响应 |
| --- | --- | --- |
| POST /sessions | {problem:string}，非空白 | 200 text/event-stream |
| POST /sessions/{sessionId}/messages | {content:string或null,confirmRepair:boolean或null} | 200 text/event-stream |
| GET /sessions/{sessionId} | 无 | 200 SessionResponse |
| GET /sessions | 无 | 200 {sessions:SessionListItemView[]} |
| GET /sessions/{sessionId}/messages | 无 | 200 ChatMessageView[] |

不新增订阅端点、公开路由端点或自动事件重放。公开请求不接受用户自行指定的身份、可信能力或已验证设备归属。

POST追加消息至少包含非空content或显式confirmRepair；空业务输入返回BAD_REQUEST。confirmRepair保留兼容，但仅在005确认上下文有效时可能被解释为确认，不授予直接调用旧修复runner的权限。

## 2. 请求例子

新建会话：

```json
{
  "problem": "我的客厅灯现在亮度多少"
}
```

新问题/澄清回复：

```json
{
  "content": "先查询亮度",
  "confirmRepair": null
}
```

既有确认形状仍可被解析：

```json
{
  "content": null,
  "confirmRepair": true
}
```

最后一个请求不表示002已完成安全控制功能。未接入005、无有效待确认请求、旧上下文失效或操作内容变化时不得执行；返回明确失败或重新澄清。控制确认ID和防重放细节由005后续契约定义。

## 3. 保持现有JSON字段

**SessionResponse**：

```json
{
  "sessionId": 101,
  "status": "CLARIFYING",
  "reply": "你想先查询设备状态，还是提出控制请求？",
  "awaitingInput": true,
  "conclusion": null
}
```

字段固定为sessionId、status、reply、awaitingInput、conclusion，**没有device字段**。reply是当前可见回复/错误说明，不是内部路由JSON。waiting/terminal应与状态机一致。

**ConclusionDto**：

```json
{
  "type": "ANSWERED",
  "summary": "AutoSense可帮助咨询知识、查询本人设备、诊断问题和安全控制设备。",
  "preDiagnostics": null,
  "postDiagnostics": null,
  "manualSteps": null,
  "afterSales": null
}
```

旧type值保持可读：FIXED、UNFIXED_MANUAL_GUIDE、UNFIXED_AFTERSALES、DEVICE_UNREACHABLE、ANSWERED、AFTERSALES_PROVIDED。新增ERROR用于FAILED_REQUEST的GET补查投影，不伪装成ANSWERED；002不会把测试分流结果标记FIXED。

**SessionListItemView**：

```json
{
  "sessions": [
    {
      "sessionId": 101,
      "status": "CLARIFYING",
      "preview": "我的客厅灯现在亮度多少",
      "createdAt": "2026-09-07T10:00:00",
      "updatedAt": "2026-09-07T10:00:02"
    }
  ]
}
```

列表仅本人会话，按更新时间倒序；preview沿用当前结论或初始问题预览。时间字符串延续现有序列化，不借本次拆分重写旧时间数据。

**ChatMessageView[]**：

```json
[
  {
    "role": "USER",
    "content": "我的客厅灯现在亮度多少",
    "createdAt": "2026-09-07T10:00:00"
  }
]
```

字段为role、content、createdAt；内部messageId/round不因此暴露为新公共字段。消息按既有时间顺序并以id稳定打破同时间排序；仅当前用户可见消息。

## 4. 五类SSE事件

| event | data | 结束流 |
| --- | --- | --- |
| token | {text:string} | 否 |
| status | {sessionId:number,status:string} | 否 |
| awaiting | {sessionId:number,prompt:string} | 是 |
| conclusion | 直接为ConclusionDto，无额外sessionId包裹 | 是 |
| error | {code:string,message:string,sessionId:number} | 是 |

sessionId尚不存在时，error事件继续使用-1；普通HTTP错误的可空sessionId保持原语义。不要在用户输出中暴露提示词、原始分类JSON、内部分析或供应商异常正文。

新增公共流程例子：

```text
event: status
data: {"sessionId":101,"status":"ROUTING"}

event: status
data: {"sessionId":101,"status":"DISPATCHING"}

event: status
data: {"sessionId":101,"status":"FAILED_REQUEST"}

event: error
data: {"code":"CAPABILITY_NOT_AVAILABLE","message":"该能力暂未接入，请稍后再试。","sessionId":101}

```

上例说明已完成分流但业务处理器未注册，不能解释为设备查询成功。只有测试接收器会返回明确测试标记，生产不得安装该处理器。

`awaiting` 和成功 `conclusion` MUST 在对应业务收尾事务提交成功后发送。已接纳请求发生模型或能力处理失败时，应先持久化失败说明、状态和追溯，再发送对应 `error`。

未接纳请求的错误，例如 `BAD_REQUEST`、归属校验失败和 `SESSION_BUSY`，MUST NOT 以本请求的持久化成功为发送前提，也不得新增用户消息或修改其他处理中请求。

写入或提交异常导致收尾无法确认时，连接仍可写则至多发送一次脱敏的 `INTERNAL_ERROR`，随后关闭本次流并停止后续输出及普通完成回调。不得发送未经确认的成功结论或状态，不得凭错误通知额外清理处理指针、重放模型调用或执行设备操作。

`error` 表示本次请求发生错误，不自动证明数据库已经保存 `FAILED_REQUEST`。提交结果不确定时，以数据库实际记录为准；后续补查与到期恢复继续核对消息指针和截止条件。连接已经断开时只清理连接资源，不重放事件。

## 5. 错误分层

**接受SSE之前与普通JSON接口**：

- 认证失败：401 UNAUTHORIZED，过滤器错误体保持code/message。
- 普通用户访问管理员功能：403 FORBIDDEN。
- JSON格式/绑定/创建problem非空校验错误：400 BAD_REQUEST。
- GET查询不存在会话：404 SESSION_NOT_FOUND；他人会话：403 DEVICE_FORBIDDEN。
- 公共异常体继续使用code、message、可空sessionId；不为统一格式破坏现有安全过滤器形状。

**SSE已接受之后**：

| code | 来源 | 状态/副作用 |
| --- | --- | --- |
| BAD_REQUEST | 空业务输入或当前状态不能接纳 | 不创建无效消息、不执行设备 |
| DEVICE_FORBIDDEN / SESSION_NOT_FOUND | 异步归属检查 | 不泄露会话内容 |
| SESSION_BUSY（新增） | 同会话已有处理中消息 | 只拒绝本请求，不改另一消息的状态/指针 |
| AI_SERVICE_UNAVAILABLE（新增） | 模型调用传输/服务失败 | 当前处理FAILED_REQUEST，保存脱敏说明 |
| CAPABILITY_NOT_AVAILABLE（新增） | 能力未注册/不可续办 | 当前处理FAILED_REQUEST，无旧流程回退 |
| REQUEST_TIMEOUT（新增） | 公共处理总截止 | 原子结清旧处理，不重新执行 |
| CONTEXT_EXPIRED（新增） | 旧等待上下文无法安全恢复 | 澄清或FAILED_REQUEST，不能恢复控制授权 |
| INTERNAL_ERROR | 其他内部失败/提交失败 | 不虚报成功，不覆盖另一消息 |

这些错误仍是HTTP 200流中的error，不把其业务含义直接写成HTTP 409/422/503。结构非法的路由结果优先走CLARIFYING+awaiting，而非把所有情况当成模型网络错误。

## 6. 多轮、恢复与兼容

- 已结束会话可在同sessionId发起新轮，重新路由，不沿用旧意图或确认。
- 公共入口开始处理前再次校验服务端身份/会话归属；各能力读取/操作设备前重新校验自己的前置条件。
- 同会话一条处理中消息；忙请求不新增USER消息。当前请求有独立messageId用于回调约束。
- 当前结果、完整可见历史和追溯保存后，才清旧投影或释放处理权。部分流文本不等于完整已保存回答。
- 活跃请求到期主动按同一消息ID及已过期条件结清FAILED_REQUEST并关闭流；进程中断后GET或后续POST补偿结清已过期处理。两种恢复均不得覆盖新消息指针，不重发模型请求或设备命令。
- 公开API没有新增通用幂等键；客户端重连先GET补查，不假定再次POST相同文本会自动去重。005另行定义设备控制请求的幂等边界。
- 新增DISPATCHING、FAILED_REQUEST及ERROR结论是枚举值扩展，既有字段及旧状态保持可读。客户端应显示未知状态的通用进行/失败说明。


## 7. 对象与日志兼容

CreateSessionRequest、MessageRequest、SessionResponse、ConclusionDto、SseEvent 保持 record；SessionListItemView/ChatMessageView 迁入 domain/vo 后仍为 record，组件、null、时间和五类 SSE JSON 形状不变。SseEventStream 是有生命周期的流封装，不能为了统一形式改成数据 record。

[公共日志契约](logging-contract.md)只定义服务端英文日志；用户文本、错误说明和澄清语言保持原接口语义。requestId/messageId/round/MDC不新增到公开JSON或SSE字段。异步接受、连接结束与业务提交分别记录，业务结果以已持久化事实为准，不能因Controller返回emitter就输出成功日志。

验证既有JSON/Bean Validation兼容，并在并发与SSE异常测试中核对日志关联、无完整会话内容、无晚到回调冒充新轮或事务回滚后的成功记录。

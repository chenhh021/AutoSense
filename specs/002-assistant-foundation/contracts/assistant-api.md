# API Contract: Graph 输出、确认与恢复

**Date**: 2026-09-15
**Status**: 目标设计，未实施；旧客户端兼容桥与新 WorkflowEvent 同时规划。
**References**: [State](../data-model.md)、[Graph](graph-contract.md)。

## 1. 会话与工作流入口

所有接口要求 Bearer 身份并校验本人会话及 workflow。conversationId 对应既有 sessionId，不引入新会话命名空间。
conversation逻辑复用repair_session，chat_message沿用原表；workflow_execution独立于长期会话，command_execution独立于工作流步骤的展示状态。审计逻辑audit_event复用repair_action_log，对外API不暴露物理表或新增同义端点。
移植完成后SessionController不再注入或调用SessionOrchestrator。执行入口由WorkflowExecutionService校验接纳并返回graph流，GET/列表/消息历史/删除由新会话读写服务承接；保留API外形不等于保留旧编排实现。任何失败或旧confirmRepair兼容处理均不能回退旧runner。

| 方法与路径（前缀 /api/v1） | 输入 / 响应 |
| --- | --- |
| POST /sessions | 保留 {problem} → SSE；服务端生成并持久化 workflow requestId |
| POST /sessions/{sessionId}/messages | 保留 {content,confirmRepair}，新增可选 {inputRequestId,expectedVersion} → SSE；新问题生成新workflow，WAITING_INPUT回复按追问标识续接；旧确认按下述兼容边界处理 |
| GET /sessions、GET /sessions/{sessionId}、GET /sessions/{sessionId}/messages | 保留现有外形和语义，只读补查，不隐式执行或恢复 |
| DELETE /sessions/{sessionId} | 保留归属检查；非终止或结果不确定的 workflow 返回冲突，不悄然丢弃在途操作 |
| GET /sessions/{sessionId}/workflows/{requestId} | WorkflowView，包含安全步骤列表、当前步骤、进度、已保存结果、批准提示和可恢复状态 |
| POST /sessions/{sessionId}/workflows/{requestId}/approval | {stepId,approvalId,approved,expectedVersion} → SSE |
| POST /sessions/{sessionId}/workflows/{requestId}/resume | {expectedVersion} → SSE；必须是本人显式继续 |
| POST /sessions/{sessionId}/workflows/{requestId}/cancel | {expectedVersion} → SSE；记录取消与剩余未执行，不撤回已发生效果 |

用户不得提交 AgentState、PlanContext、permission、operation key、目标覆盖字段或新的可信 userId。requestId 路径只定位服务端已保存的 workflow，不能创建任意 thread。
approvalId 绑定已展示步骤的规范化目标/动作/参数 hash 和有效期。重复相同决定返回已有决定/当前状态，不重复开始执行；不同决定或过期 version 返回明确冲突。
resume 校验尚未终止、待恢复、归属、版本、claim；重复/并发请求至多取得一个执行权，返回已有状态或 WORKFLOW_BUSY，绝不同时重放。已终止请求仅返回终态，不重启。
服务重启后 approval 只能保存决定，不能代替显式 resume；未重启且正常 WAITING_APPROVAL 时批准可直接恢复该步骤。
WAITING_INPUT回复须匹配当前inputRequestId/版本，只保存一次可见消息并恢复对应澄清节点。旧客户端未传inputRequestId时，仅在会话有唯一当前追问且未重启时由服务端映射；存在歧义时拒绝猜测。尚无正式计划时允许据澄清重新规划，已有正式计划时仅补齐运行时输入，不增删步骤。重启后的回答只保存，显式resume后继续。
旧 confirmRepair 仅在服务端能唯一对应一个仍有效的 CONTROL 批准请求时映射，不能用于 Query 批准、条件整体授权或重启恢复；映射不明确时要求使用带 approvalId 的新接口。
既有 SessionResponse 的 sessionId/status/reply/awaitingInput/conclusion 不删除；新增 workflow 可选投影（requestId、status、currentStep、progress、version、canResume）。旧数据 workflow=null。
新工作流状态同时映射到旧会话状态：执行映射 DISPATCHING/ANSWERING，等待映射既有等待语义，成功映射 COMPLETED_ANSWERED，失败映射 FAILED_REQUEST；精确状态在 workflow 中提供，不能把 stub 控制显示为 FIXED。

## 2. OutputContext → WorkflowEvent → SSE

OutputContext 保持 type/code/message/data 四字段。data 是白名单 DTO，包含 eventId、sequence、requestId、conversationId、stepId、stepType、status、progress、version、payload（按 type 定义）；不含 messages、prompt、原始候选计划、审批安全凭证、原始异常。
WorkflowEvent 是不可变公开 envelope，使用同样四字段；Controller 消费 LangGraph4j stream 的中间 state，调用纯投影器转换，不序列化 AgentState 本身。
同一 OutputContext 在多个 node snapshot 重复出现时按 eventId 去重；每次实际更新必须产生新 sequence。终态/等待/步骤成功只在提交后可见。非持久 token 使用本次流临时序列，不能冒充审计提交序列。
持久公开事件的eventId由requestId与审计event_sequence组成，内部审计导致的序列间隙允许存在。WorkflowEvent仅为公开DTO，不对应新增workflow_event表，也不直接序列化repair_action_log。控制步骤结果的payload可包含commandExecutionId、status和resultCertainty供关联补查，不能暴露operationKey、批准凭证或原始命令参数。完整可见文本由chat_message保存，审计以引用关联，重复恢复不新增相同输出。
新增 SSE event=workflow，data=WorkflowEvent；同时保留五类 legacy 事件：
- TEXT → token {text}。
- STATUS → status {sessionId,status}。
- AWAITING → awaiting {sessionId,prompt}，随后关闭。
- CONCLUSION → conclusion（原 ConclusionDto），随后关闭。
- ERROR → error {code,message,sessionId}，随后关闭。
- STEP_RESULT → workflow 事件及简短 token 说明，不关闭流；只有整计划汇总才发送 conclusion。
兼容映射应先发 workflow，最后发会关闭连接的 legacy 事件；不能在一个步骤结束时误关整个计划。
例如：

```json
{
  "type": "STEP_RESULT",
  "code": "STEP_COMPLETED",
  "message": "设备查询已完成。",
  "data": {
    "eventId": "workflow-uuid:7",
    "sequence": 7,
    "requestId": "workflow-uuid",
    "conversationId": 101,
    "stepId": "s1",
    "stepType": "DEVICE_QUERY",
    "status": "RUNNING",
    "progress": {"total": 2, "completed": 1, "skipped": 0, "notExecuted": 0},
    "version": 8,
    "payload": {"brightness": 20, "source": "DEVICE_QUERY"}
  }
}
```

暂停/结束才关闭 SSE；断线不重放事件，GET 补查已持久化状态与结果。SSE 断开不是批准或取消，当前调用可提交结果，但后续任何未确认步骤仍暂停；服务重启后必须显式 resume。
AgentState 中继承的旧输出、框架 START/END 通知及内部流元素不得重复发给前端。graph 技术异常由服务端收敛为安全失败投影；持久化提交不确定时可发送一次 INTERNAL_ERROR 并关闭，但不宣称保存成功。

## 3. 错误与状态

HTTP 接受前：401 身份失效；403 归属拒绝；404 会话/工作流不存在；400 非法 DTO；409 VERSION_CONFLICT / WORKFLOW_BUSY / APPROVAL_SCOPE_MISMATCH / WORKFLOW_NOT_RESUMABLE。
SSE 接受后沿用 HTTP 200 + error，code 可为 PLAN_INVALID、AI_SERVICE_UNAVAILABLE、CAPABILITY_NOT_AVAILABLE、APPROVAL_REJECTED、REQUEST_TIMEOUT、DEVICE_RESULT_UNKNOWN、CHECKPOINT_UNAVAILABLE、INTERNAL_ERROR。
参数/计划歧义输出 WAITING_INPUT 与脱敏澄清（payload含inputRequestId），在AwaitInput中断而非Reject终止；明确计划安全违规拒绝执行。OUT_OF_SCOPE输出范围说明并正常结束，空步骤不是非法计划。设备业务错误与明确 HTTP 4xx/5xx 不是 timeout，不重试。
确认过期输出新的 WAITING_APPROVAL；权限已撤销则拒绝并停止。步骤失败及剩余 NOT_EXECUTED 与已完成结果一并可补查，不能显示为全部成功。

## 4. 骨架与前端验收边界

stub 事件和结果明确 code=STUB_*、payload.simulated=true；真实模式未接入能力应返回 CAPABILITY_NOT_AVAILABLE，不能用 stub 冒充真实设备结果。
旧 ChatPage 只识别五类事件，兼容桥保持基本文本可用；步骤卡片、Query 批准、requestId/version 传递及 resume/cancel 按本契约增量适配。API 骨架可先用 curl 完成验收，不把当前前端视为已支持这些交互。
用户点击一次步骤批准不批准其后控制/复检步骤。重新连接只能补查，必须由用户显式动作调用 resume。


### 2026-09-23 删除会话规则

DELETE /api/v1/sessions/{sessionId} 继续返回 204，校验当前用户归属。DISPATCHING 或其他未终止状态本身不构成拒绝理由；没有有效数据库执行租约且不存在 IN_FLIGHT/UNKNOWN 设备命令时，允许删除及清理关联历史、检查点和批准记录。活跃执行、旧版 processing 标记或未确定命令返回 409 WORKFLOW_BUSY，并提供具体中文原因；前端显示响应 message。GET 列表根据最新工作流状态生成兼容状态字段，无工作流时保留旧会话状态，不触发恢复或执行。

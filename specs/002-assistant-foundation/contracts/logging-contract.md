# Contract: 工作流日志、关联与审计

**Date**: 2026-09-15
**Status**: SLF4J/Log4j2、条件格式、graph日志关联与脱敏已实施，见本期验证记录。
**References**: [data-model](../data-model.md)、[graph](graph-contract.md)、[章程](../../../.specify/memory/constitution.md)。

## 1. 依赖与显示

保留当前SLF4J门面、Log4j2单提供者、@Slf4j及英文参数化文案，不增加第二套日志系统。配置由application.yaml/log4j2-spring.xml及环境变量管理。
关闭 LangChain4j、LangGraph4j 和 async 库可能携带原始模型输出或 SQL 参数的内部异常日志；应用执行边界仍记录脱敏错误类型、原因及关联标识，不关闭应用故障日志。
无对话上下文的启动/普通服务日志不显示空对话字段块；有上下文时仅显示实际适用字段，以方括号和逗号分隔，例如：
`[requestId=transport-uuid, userId=1, sessionId=9, messageId=41, round=3, workflowRequestId=workflow-uuid, stepId=s2]`。
沿用用户已要求的条件格式，不恢复固定一串空session/message/round/device参数。不得使用日志上下文代替身份或流程状态校验。

## 2. requestId与异步关联

RequestLogFilter继续为每次HTTP请求生成transport requestId。首次规划时将该服务器ID持久化为RequestContext.requestId/threadId；之后approval/resume HTTP有新的transport requestId，并通过workflowRequestId关联原计划。
conversationId映射现sessionId；userId仅认证成功后加入；stepId、attemptId、messageId只在对应业务事实建立后加入。
TaskExecutor、SDKcallback、generator、定时任务均显式捕获/安装不可变MDC白名单，finally恢复旧上下文。迟到回调保留旧attempt，不冒充新轮；启动恢复不制造HTTP请求标识。
Controller返回emitter、SSE关闭、节点完成、业务提交是不同事实；成功日志只能在确认相应持久化成功后输出。
删除旧编排时，将事务提交后的日志/审计和异步MDC传播迁至新工作流服务、节点及事件投影边界；不得为保留日志而继续依赖SessionOrchestrator、SessionTransitionLog或旧回调链。

## 3. 关键事件

| 责任边界 | 英文事件示例 | 级别 |
| --- | --- | --- |
| 接纳与计划验证 | Workflow accepted / Plan validated / Plan rejected | INFO，拒绝WARN |
| 路由与步骤 | Step started / Step completed / Step skipped / Workflow stopped | INFO；失败按原因WARN/ERROR |
| 确认 | Approval requested / Approval accepted / Approval rejected / Approval expired | INFO或WARN |
| 外部AI/检索/设备调用 | External call started / External call completed / External call timed out / External call failed | INFO结果及耗时；超时WARN；未预期失败ERROR |
| 重试 | Step retry scheduled / Step retry exhausted / Unsafe retry blocked | WARN；含stepId、attemptId、retryCount、reasonCode |
| 检查点 | Checkpoint saved / Checkpoint restore requested / Workflow resumed / Checkpoint restore failed | INFO或ERROR；不输出payload |
| 并发与迟到回调 | Workflow claim rejected / Stale callback ignored / SSE connection closed | WARN冲突；DEBUG正常重复/断线 |
| 最终异常 | Workflow failed / Workflow finalization failed | ERROR，一次脱敏诊断 |

字段使用节点名、步骤种类、结果码、elapsedMs、状态和标识；不输出完整state、DTO、消息、prompt、模型结果、批准凭证、参数正文或原始供应商异常。
不逐token输出INFO，不高频输出租约续期；日志汇总不得抹掉外部模型失败来源。保留既有LogSanitizer及控制字符清理，异常cause不携带敏感原文。

## 4. 审计不是运行日志

conversation复用repair_session，用户输入和可见系统输出保存在chat_message，计划/步骤/批准/检查点属于workflow_execution，控制命令事实由command_execution保存；audit_event复用并扩展repair_action_log，追加步骤尝试、操作种类/结果、批准及恢复事实。运行日志只保留排障摘要，不替代上述五类业务数据。
业务结果、命令账本、audit_event及投影版本在短事务中一致提交，不新建workflow_event或平行audit_event表。审计事件和公开WorkflowEvent不是同一个对象，后者只取白名单字段；旧日志值不改写。审计result=SUCCESS仅说明对应事件成功，不能将“批准成功/意图保存成功”解释为命令效果成功。checkpoint是恢复快照，不能单凭“checkpoint保存成功”宣称设备效果成功。
验收捕获日志验证跨线程/跨HTTP恢复关联、非对话日志无空字段块、英文固定文案、秘密/完整消息泄漏为零、同一异常不过度重复。

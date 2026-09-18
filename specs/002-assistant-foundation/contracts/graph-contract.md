# Contract: MainGraph、子图、Checkpoint 与 Stream

**Date**: 2026-09-15
**Status**: 设计契约，固定 LangGraph4j core 1.8.27；具体API依据见[research](../research.md)。
**References**: [State](../data-model.md)、[API](assistant-api.md)、[计划路由](routing-contract.md)。

最终所有流程选择、等待续接与步骤推进由图承担。SessionOrchestrator、旧SessionStateMachine、Redis SessionContext恢复、CapabilityDispatcher/旧handler与RepairExecutionRunner在迁移完成时删除，详见plan删除矩阵；WorkflowExecutionService不得复制旧编排分支，也不得把旧Orchestrator包装为graph节点。

## 1. Factory 与编译

MainGraphFactory 创建 StateGraph<AssistantState>，三个子图工厂返回未编译 StateGraph<AssistantState>，以 addNode(name, subGraph) 接入；由主图一次 compile，共享 state schema、serializer 和 BaseCheckpointSaver。
生产/本地应用使用 MyBatisCheckpointSaver，MemorySaver仅限隔离骨架测试。CompileConfig 使用 releaseThread(false)，明确配置图迭代上限，保留历史检查点；不能在SSE关流时release业务thread。
正常边按用户主图：START → IntentPlanner → PlanValidator → PlanRouter → 四能力之一 → CompleteStep → PlanRouter/ResponseAggregator → END；计划无效经Reject → END。
补充必要边：条件false → CompleteStep(SKIPPED)；任一步骤明确失败/拒绝/耗尽 → Reject；CLARIFY经PrepareInput → AwaitInput中断，收到回答后回IntentPlanner；OUT_OF_SCOPE经ResponseAggregator说明范围并正常结束。等待输入/批准在具体节点interrupt，不进入CompleteStep或END。错误guard和澄清节点不是另一个业务步骤。
对子图不得使用普通方法invoke绕过graph stream；不编译为独立CompiledGraph以免派生不同threadId。框架编译后的内部节点ID用 SubGraphNode.formatId 获取，不手拼前缀。

## 2. 节点职责与子图

| 节点 | 输入/输出约束 |
| --- | --- |
| IntentPlanner | RequestContext + messages → 候选PlanContext，AI不能写可信身份或批准 |
| PlanValidator | 结构/安全约束、白名单条件、步骤引用校验；输出正式计划或拒绝/澄清 |
| PlanRouter | 仅按游标、类型和既定条件路由，不调用LLM；新步骤入口清除上一执行的临时device/diagnosis/control/retry输出投影 |
| KnowledgeConsult | 常识直答或003检索增强；结果、引用及OutputContext；不访问设备 |
| CompleteStep | 只接受成功/正常跳过，原子保存结果、推进游标与progress，发布STEP_RESULT |
| ResponseAggregator | 汇总已保存步骤事实；初期使用确定性模板，避免为了汇总增加模型调用和昂贵恢复点 |
| Reject | 记录失败步骤及计划原因、剩余NOT_EXECUTED；输出安全错误，保留已完成事实；不承担非终止澄清 |

PrepareInput保存WAITING_INPUT、inputRequestId、追问及返回节点，然后在AwaitInput前中断。主图处理未形成计划的澄清；Query/Diagnosis/Control可在内部以同样模式补充未决目标/症状/参数，再回原校验节点。只能填充运行时输入绑定，不能修改已经发布的步骤列表或扩大已批准范围。

QuerySubGraph：
`ResolveTarget → ValidateQuery → PrepareApproval → AwaitApproval → Revalidate → ReadDevice → SaveQueryResult → END`。
已经有匹配有效批准时分支绕过 PrepareApproval/AwaitApproval，进入Revalidate；本地绑定解析用于最小候选/批准展示，不读取实时设备。所有业务查询（包括本地设备列表）、诊断取证和控制后复检均需用户确认。
一次查询可有显式批准的设备/字段集合，不能在重试中扩大目标范围。PrepareApproval发布的范围必须与真正出站请求匹配。

DiagnosisSubGraph：
`PrepareEvidence → RetrieveDiagnosisKnowledge → AnalyzeDiagnosis → SaveDiagnosisResult → END`。
复用LangChain4j检索、诊断AI Service及现有规则，消费用户症状和已保存前序查询证据；检索按设备类型，优先排故资料，仍明确来源/型号局限。不调用DeviceServiceClient，不直接调用控制子图。repairProposal仅作候选结果，后续已规划Control可引用；未规划则提示重新规划。

ControlSubGraph：
`ResolveCommand → ValidatePermissionAndRisk → PrepareApproval → AwaitApproval → Revalidate → PersistCommandIntent → ExecuteCommand → SaveControlResult → END`。
PersistCommandIntent创建或加载同一步骤的command_execution，并与audit_event（物理repair_action_log）准备事件同事务提交；恢复/重试沿用commandId和operationKey。ExecuteCommand在写前checkpoint成功后持久化attempt/IN_FLIGHT再发送，SaveControlResult同事务保存命令结果、步骤投影与审计。设备查询只记录workflow_step及审计，不创建控制命令。已有RepairExecutor的调用后日志需迁入统一事务，不能同时保留旧格式双写。
没有隐藏状态探测或复检；所需状态来自前序已确认Query且执行前检查证据适用性。过期/不足证据则不能继续控制；需要新增Query时重新规划，不暗中补读。
拒绝、非法参数或明确设备错误立即返回失败guard；超时只有安全重发条件成立时按同一operation key重试。复检另由PlanRouter调度后续已规划Query，用户可拒绝该Query，此时停止计划并保留控制已执行事实。

## 3. 人工中断

Query/Control 的 PrepareApproval 在事务中保存待批准记录及WAITING_APPROVAL输出，返回state delta；graph保存其checkpoint后，在真实内部节点 AwaitApproval 前中断。
CompileConfig.interruptBefore 使用 SubGraphNode.formatId("QuerySubGraph","AwaitApproval") / ControlSubGraph 对应名称；不能配置 interruptAfter("ControlSubGraph") 这种逻辑容器节点。
Controller不能看到PrepareApproval输出就立即取消generator：先确认框架已完成checkpoint及中断，再发送终结本次连接的awaiting，释放工作线程/执行租约。等待期间不保持数据库事务或设备锁。
批准入口校验当前用户、requestId、stepId、approvalId、scope hash、期限及version，短事务持久化决定。只允许服务端计算出的controlContext/workflowContext delta进入 updateState；使用其返回的RunnableConfig，再 stream(GraphInput.resume(), config)。
AwaitApproval节点验证批准事实后继续；静态中断位置的恢复语义必须由固定版本测试证明，避免每次resume重复卡在同一个门。拒绝信号经同一位置进入失败guard，不执行外部操作。
重启场景先保持WAITING_RESUME；外部批准可以保存，但只有显式resume才驱动图。resume不会自动批准，未批准的原步骤继续等待。确认过期走新的PrepareApproval；同一步骤未变化且确认有效可复用。

WAITING_INPUT使用POST /messages的inputRequestId与expectedVersion关联当前追问，保存新的可见USER消息并更新messages/运行时输入，再恢复AwaitInput及其返回节点；不把澄清回复当作新计划或批准。重启后澄清回答可保存但不自动恢复，仍需显式resume。若用户表达新目标，则先显式取消原计划；不自行将其解释为当前追问答案。

## 4. Checkpoint 与并发恢复

根RunnableConfig.threadId严格等于持久requestId；批准、重试、恢复不能创建新thread。保存以下边界：
1. 外部等待/人工中断之前；
2. 非重复副作用的意图提交后、真正发送前；
3. 外部结果保存后；
4. 昂贵阶段结果形成后及CompleteStep之后。
框架会在节点边界保存；业务节点拆分必须对应这些边界，不能把意图、设备调用、结果全部藏在一个不可恢复节点中。

MyBatisCheckpointSaver 实现 BaseCheckpointSaver.list/get/put/release，经WorkflowCheckpointService与mapper读写。get支持config的指定checkpointId或最新项，list按最新优先，put返回包含已保存checkpointId的RunnableConfig。只允许当前版本/执行fence提交；release不在等待、断线或普通完成后自动清除审计。
JSON serializer以固定schemaVersion/graphVersion和类型白名单处理纯数据；不使用任意Java对象反序列化。不可识别版本明确失败，不从START重放。
一次resume取得数据库claim并恢复已保存nextNode；完整state不得由客户端上传。同一步骤已成功时直接复用结果补齐state；重试次数不重置，迟到attempt回调不能覆盖新版本。
进程重启的启动整理仅将遗留运行状态标成WAITING_RESUME，绝不执行业务节点。等待批准也可恢复其提示状态，但不得因GET读取自动批准/执行。
结果未知的控制请求无远端幂等支持时以DEVICE_RESULT_UNKNOWN结束，不能因本地checkpoint存在而重发。读取当前状态也不能普遍证明非幂等命令是否只执行一次。
命令是否发送及效果以command_execution为权威，state/checkpoint只保存其引用/投影。恢复先核对持久命令及步骤结果；结果事务成功而checkpoint失败时补齐state，不能重新发送。repair_action_log的历史SUCCESS动作行没有稳定命令身份，不能据此创建可恢复命令；追加审计也不能替代命令claim。

## 5. Stream 与 TokenStream 桥接

Controller调用应用入口获得授权后的graph执行流，在TaskExecutor上消费 AsyncGenerator.Cancellable<NodeOutput<AssistantState>>。graph stream是唯一业务输出源；Controller只从每个NodeOutput.state().outputContext映射WorkflowEvent，不取messages等内部字段。
普通可观察节点返回OutputContext更新。传输层以eventId去重，旧output出现在下一snapshot不重复发送；需要多条输出时逐条形成中间snapshot，不能在单次Map更新中覆盖未发布消息。

正式逐token路径使用core已支持的“节点Map内嵌AsyncGenerator”机制，是本项目适配方案：
- LangChain4j AI Service 的TokenStream callback只写有界队列，容量默认256条，不接触SSE。
- adapter generator按顺序构造 NodeOutput.of(nodeId, 独立stateSnapshot)，每个snapshot有新的TEXT OutputContext。
- 队列满不能静默丢字；停止该attempt并报告受控输出失败。取消/失败/完成通过同一原子终结控制，晚到callback丢弃。
- token eventId使用requestId/stepId/attemptId/tokenIndex；data.sequence在该attempt内递增，data持有attemptId，不能与audit_event的持久序列混淆（物理repair_action_log.event_sequence）。不再新建workflow_event表；公开WorkflowEvent由可公开审计事实白名单投影，内部事件造成序列间隙合法。
- Data.done(finalDelta)才将最终结果合并到主state；中间token snapshot不是自动持久化检查点。完整回答事务提交成功后再发STEP_RESULT/最终CONCLUSION。
- 若已推送部分token的attempt超时，自动重试前发TEXT_RESET状态并撤销该attempt的临时文本；只保存最终成功回答。旧客户端无撤销能力时，兼容token桥对该步骤缓冲到尝试成功再输出，避免重复答案。
- 同步增强回答无需伪造token；节点结果也从OutputContext进入同一stream。
- 框架/模型内部异常正文、raw AgentState、提示词和思维链不得进入输出。

骨架阶段先验证节点级state stream，不要求真实模型token；后续适配必须验证generator结束最终Map、去重、失败、超时、取消和晚到回调。不能以回调直接写SSE作为简化替代。

## 6. Stub边界

stub planner对测试语料生成固定计划，stub能力有确定成功、条件false、一次超时后成功、明确错误、拒绝与昂贵结果场景。参数来自测试配置/专用fixture，生产请求不能通过自然语言启用错误注入。
stub不调用LLM、embedding或设备网络；模拟命令以稳定operation key保存一次效果，重复尝试返回同一结果。只在显式开发/测试模式装配，真实模式缺失能力直接报错。
G1可用MemorySaver跑单进程图；G2必须使用MySQL saver验证进程重启。两阶段输出均标明simulated，不把stub查询/控制结果当真实设备数据。

# Data Model: LangGraph4j 对话计划与恢复

**Date**: 2026-09-15
**Status**: 目标设计，尚未实施；替换旧单一意图运行模型，历史数据继续可读。
**References**: [spec](spec.md)、[plan](plan.md)、[执行契约](contracts/graph-contract.md)。

## 1. State 与字段权威

`graph/state/AssistantState extends AgentState`。保留用户指定的十个顶层键；上下文使用不可变 record，集合防御性复制，节点返回局部更新 Map，不直接修改共享嵌套对象。上下文默认替换而非隐式深合并；缺省值通过初始状态显式建立，清空当前步骤上下文也必须显式更新。

| 顶层字段 | 内容及目的 | 写入权威 |
| --- | --- | --- |
| requestContext | requestId、conversationId、userId、userMessage；身份和原始输入 | 接纳服务一次写入；恢复不得覆盖 |
| planContext | executionPlan、currentStep；经过验证的有序步骤及当前游标；runtimeInputs存未决输入的绑定 | Planner 提供候选，Validator 发布正式计划；CompleteStep 推进 |
| workflowContext | status、currentStep、progress；增加 version、graphVersion、schemaVersion、lastOutputSequence；澄清时保存inputRequestId/prompt/returnNode | 生命周期节点；currentStep 是 PlanContext 的只读投影 |
| deviceContext | 当前目标的已验证本地绑定元数据、设备类型及按 stepId 关联的快照引用/采集时间 | 确定性解析与独立 Query 节点 |
| diagnosisContext | diagnosticInput、evidenceRefs、result、repairProposal | Diagnosis 节点；不包含设备执行器 |
| controlContext | command、commandExecutionId、permissionDecision、risk、approvalRef、idempotencyKey、executionResult | Query/Control的确认与执行节点；查询command/commandExecutionId为空，approval按stepId绑定；命令状态是command_execution的投影 |
| retryContext | 按 stepId/callId 的 attempts、retriesUsed、maxRetries、nextRetryAt、latestFailure、resultCertainty | 统一请求尝试边界 |
| auditContext | workflowId（等于 requestId）、reportId、messageId、round、commandExecutionRefs、auditEventRefs | 持久化服务；只放引用，不复制整份审计历史 |
| outputContext | type、code、message、data；当前面向客户端的输出 | 可观察节点生成，提交后发布 |
| messages | LLM 节点使用的有界消息上下文 | 会话记忆适配器按持久消息 ID 构造 |

`requestId` 是服务端首次接纳计划时的 UUID，持久化并作为 LangGraph4j `threadId`。后续 HTTP 请求自己的 requestId 仅作 transport 日志标识，以 `workflowRequestId` 关联原计划。用户不能提交任意 State 或改写原 userId。
`conversationId` 映射现有 `repair_session.id/sessionId`，不新增平行会话 ID。一轮新计划使用新 requestId；重试、批准、重启恢复保持原 requestId。
`PlanContext.currentStep` 为零起始索引：0 ≤ index ≤ steps.size；末尾为完成哨兵。执行时类型读取等价于 `executionPlan.steps[currentStep].type`。WorkflowContext.currentStep 只提供对应 stepId/index/type 投影，不能反向决定路由。
`progress` 含 total/completed/skipped/notExecuted，拒绝或失败不冒充 completed。CompleteStep 在同一事务内更新游标、步骤结果和投影，避免双 currentStep 分歧。

State 禁止保存 Bean、AI Service、ChatMemory 实例、TokenStream、AsyncGenerator、SseEmitter、Future、锁对象、凭据或数据库连接。检查点只保存有明确 schema/version 的普通数据；反序列化使用类型白名单，不开启任意多态类型加载。

## 2. 计划与步骤

`ai/model/ExecutionPlanCandidate` 是模型候选；`graph/state/ExecutionPlan` 是服务端验证后发布的不可变计划。AI 步骤分类枚举位于 ai/model/enums，执行状态/业务步骤枚举位于 domain/enums，两者显式映射。

| PlanStep 字段 | 约束 |
| --- | --- |
| stepId | 模型局部引用标识经格式、唯一性校验；持久身份为 (requestId, stepId) |
| type | KNOWLEDGE_CONSULT / DEVICE_QUERY / FAULT_DIAGNOSIS / DEVICE_CONTROL |
| instruction、targetHint、parameters | 候选任务与线索，不能包含可信身份或任意接口地址 |
| dependsOn、inputBindings | 仅能引用更早步骤的公开类型化结果字段；禁止循环、向前引用 |
| condition | 可选受限条件表达式；只允许白名单比较、AND/OR、字段引用；禁止脚本/SpEL/任意代码 |
| requiresKnowledgeBase | 仅知识步骤必填 boolean，常识 false，资料依赖或不确定 true |
| diagnosisMode | 仅诊断步骤可指定 DEFAULT/AFTERSALES |
| timeout、retryPolicy | 由服务端按类型配置，AI 不可修改 |
| status、resultRef、failureCode | 服务端执行状态；模型无写入权 |

计划最多 8 步（可配置），条件引用只允许已成功前序结果；条件 false 为 SKIPPED，引用缺失/类型不匹配则明确失败并停止计划。新需求需要新步骤时结束原计划并请求重新规划，不在当前计划中自动增删。图的步骤调度回边和有界重试不等于允许模型生成业务循环。

## 3. 生命周期

| 对象 | 状态与转换 |
| --- | --- |
| Workflow | CREATED → PLANNING → VALIDATING → RUNNING；RUNNING 可进入 WAITING_APPROVAL / WAITING_INPUT / RETRYING；重启后未终止计划进入 WAITING_RESUME；最终 COMPLETED / FAILED / REJECTED / CANCELLED |
| PlanStep | PENDING → RUNNING → COMPLETED；条件不满足 → SKIPPED；等待确认 → WAITING_APPROVAL；超时 → RETRYING；明确错误/拒绝/耗尽 → FAILED/REJECTED；剩余步骤 → NOT_EXECUTED |
| Approval | PENDING → APPROVED / REJECTED / EXPIRED / INVALIDATED；绑定用户、计划、步骤、目标、动作与规范化参数 hash |
| Execution certainty | NOT_SENT / IN_FLIGHT / SUCCEEDED / FAILED / UNKNOWN；UNKNOWN 不等于未执行 |
| Checkpoint | 保存 state、nextNode、checkpointId、parentId、版本；不是业务成功的证明 |

失败、拒绝、重试耗尽后不进入成功 CompleteStep，不递增 completed，不继续无依赖步骤。正常 SKIPPED 由 CompleteStep 记账并推进。
重启的 WAITING_RESUME 保留原 suspendedStatus/nextNode 和重试预算；显式 resume 后回到原等待/执行位置。已终止计划不能以 resume 复活。
读写前重查身份、归属、参数与权限；过期批准重新询问而不复用，拒绝立即终止。恢复过程中不能因旧批准而跳过这些校验。
同会话有未结束计划时，新普通消息不能隐式批准或恢复；用户显式取消旧计划后才接纳新目标，取消不能撤回已发送的外部效果。
唯一例外是与当前inputRequestId匹配的WAITING_INPUT回复：属于原计划澄清，保存消息后续接AwaitInput。正式计划发布前可回Planner；发布后仅补充runtimeInputs，已确认scope若变化则失效并重新确认。OUT_OF_SCOPE没有执行步骤，正常结束；CLARIFY没有执行步骤，持久化为等待而非失败。

## 4. 持久化设计

### 4.1 五类逻辑数据与既有表映射

对话业务持久化分为conversation、chat_message、workflow_execution、command_execution、audit_event五类。逻辑名称不要求重命名旧表，也不要求只有五张物理表；步骤、批准和检查点属于workflow_execution的内部持久结构。保留user、device及已有诊断领域数据；实体仍放domain/entity，MyBatis-Flex mapper放mapper，事务由core/session及相应业务服务承担。

| 逻辑种类 | 物理落点 | 职责、关系及复用方式 |
| --- | --- | --- |
| conversation | **复用repair_session** | 长期会话容器，保留id/user_id与历史展示字段，增加nullable active_workflow_request_id。conversationId=sessionId=现有id；一会话多消息、多workflow，不新建conversation表或第二套ID |
| chat_message | **复用chat_message** | 用户输入与可见系统输出的正文权威。保留id/session_id/role/content/created_at；增nullable workflow_request_id、step_id、output_key及唯一(workflow_request_id,output_key)防止恢复后重复保存输出；旧行保持原ID与正文 |
| workflow_execution | **新增workflow_execution**及下述内部辅助表 | 一次用户目标的计划、步骤推进、等待、重试及恢复。request_id同时为workflowId/threadId；一会话多次执行，批准/澄清/恢复属于原执行；历史problem_report不是可恢复工作流 |
| command_execution | **新增command_execution** | 单个设备控制命令的持久幂等账本，保存目标、动作、批准引用、执行尝试和结果确定性；一workflow可有多命令，同一Control步骤至多一条逻辑命令，重试更新同一条而不是新增命令 |
| audit_event | **扩展repair_action_log** | 追加的事实记录，覆盖接纳、计划/步骤、查询、批准、拒绝、重试、恢复及命令尝试/结果；关联workflow/command/message。保留旧动作和状态日志，取消上版新增workflow_event表方案，不另建audit_event副本 |

`problem_report`保留已有轮次、原始问题和诊断描述的兼容用途，`diagnostic_snapshot`保留设备证据；它们是领域附属数据，不扩成另一套workflow或命令账本。旧raw_text保留，新流程以chat_message引用定位输入；不得把一个复合计划压成problem_report.intent中的单一执行意图。repair_session.device_id仅为历史/展示关联，当前每步目标以步骤与command记录为准。

新增物理表共五张：workflow_execution、command_execution、workflow_step、workflow_approval、workflow_checkpoint；扩展三张已有表repair_session、chat_message、repair_action_log。后面三张新增表是workflow内部结构，不是额外业务数据种类：步骤需要独立结果/claim，批准需要独立版本/有效期，checkpoint需要按框架SPI保留最新及历史快照；不把这些塞进消息正文、审计JSON或已有诊断快照来伪装复用。

### 4.2 工作流及其内部结构

| 表 / 主要键 | 主要字段及约束 |
| --- | --- |
| workflow_execution / request_id PK | user_id、session_id、report_id、origin_message_id、latest_input_message_id、graph_version、schema_version、status、suspended_status、current_step_index、plan_json、version、lease_owner/fence、lease_until、last_event_sequence、时间；同会话只允许一个非终止计划 |
| workflow_step / (request_id,step_id) PK | ordinal、type、status、input_json/hash、result_json、failure_code、certainty、retries_used、max_retries、active_attempt_id、command_execution_id、开始/完成时间；唯一(request_id,ordinal)。非控制步骤自身管理尝试；控制步骤的命令状态/尝试以command_execution为权威，步骤仅保存结果引用/投影 |
| workflow_approval / approval_id PK | request_id、step_id、user_id、scope_hash、operation_kind、device_refs、status、expires_at、decision_at、version；批准只能来自认证入口；每步至多一个当前有效批准，旧批准保留 |
| workflow_checkpoint / (thread_id,checkpoint_id) PK | parent_checkpoint_id、next_node、state_payload、schema_version、graph_version、version、created_at；按thread查最新与历史，存完整可恢复checkpoint，不作为命令执行成功依据 |

接纳事务锁定本人repair_session行，检查active_workflow_request_id指向的状态并原子设置新执行，不能只用无锁count检查实现单活跃计划。workflow与message/report/step/approval/command的会话、用户、步骤关联必须一致；终止时条件清空活动指针，旧执行不能清掉后来执行的指针。

### 4.3 CommandExecution

新表以command_id为主键，唯一(request_id,step_id)及唯一operation_key；关联session_id、user_id、device_id、action_code、canonical_params、params_hash、approval_id、scope_hash。另存status、certainty、result_json、failure_code、active_attempt_id、attempt_count、retries_used、max_retries、version/fence、started_at/finished_at/updated_at和可选remote_operation_id。operation_key由服务端生成并在所有重试/恢复中保持稳定；模型及客户端不能指定。

仅DEVICE_CONTROL产生命令记录，DEVICE_QUERY的只读调用记在workflow_step和audit_event；知识/诊断不产生设备命令。拒绝或权限校验未通过时仅记审计，不生成可发送命令。有效批准且重查权限/参数后，在出站前创建PREPARED记录。状态为PREPARED → IN_FLIGHT → SUCCEEDED / FAILED / UNKNOWN；安全超时重试可进入RETRYING再开始新attempt，沿用同command_id/operation_key，已成功或明确失败不可再执行。取消尚未发送命令可记CANCELLED；取消计划不能把已发送命令改成“未执行”。

命令记录是设备效果与重试判断的权威；workflow_step/controlContext/checkpoint仅引用或投影，不能相互独立修改执行结果。每次attempt的开始、超时、结果与拒绝另追加audit_event，不能用命令行覆盖后的最新状态代替完整尝试历史。当前外部client不支持幂等键或按操作ID核查结果，因此发送后超时/崩溃留下的不确定命令必须停止并报告DEVICE_RESULT_UNKNOWN；本地唯一键不能证明外部恰好执行一次。

### 4.4 AuditEvent复用与公开事件投影

repair_action_log原有id/session_id/action_code/params/result/message/created_at继续保留，不改写旧动作或state日志。新增nullable request_id、step_id、command_execution_id、attempt_id、actor_user_id、event_type、operation_kind、event_sequence、event_key、result_code、chat_message_ref和schema_version；新graph事件必须由服务端完整填写其适用关联，operation_kind保存查询/控制及具体操作种类。params复用为按event_type/schema_version定义的白名单元数据JSON，message仍为至多1024字符的安全摘要，完整正文通过chat_message_ref引用。

保留result原有短结果分类和历史值；详细业务错误放新增result_code，不能把长失败码塞进现有VARCHAR(16)。action_code保持64字符内的动作/事件标识，旧值原样可读。历史行新增字段为null，不伪造workflow、command、批准或事件序列。唯一(request_id,event_sequence)和唯一(request_id,event_key)只约束新关联事件；event_key在同一业务事实的重复提交中稳定，重试的新attempt使用新事件身份。事件序列从workflow行原子分配，内部审计导致的公开序列间隙合法。

AuditEvent是内部持久事实，WorkflowEvent是对外白名单DTO，两者不等同。只有可公开的状态/等待/步骤结果/终态事件才投影到OutputContext → WorkflowEvent → SSE；内部权限、参数、checkpoint和审计字段不得直接序列化给前端。逐token输出仍是临时序列，不逐token写repair_action_log。已有RepairActionLog/Mapper可扩展复用，不为逻辑名相同再建一套表或同时双写新旧审计。

### 4.5 事务、恢复与权威

首次接纳在短事务内保存用户消息、关联report并创建workflow。结果事务原子保存步骤/工作流投影、command结果（控制时）、最终chat_message及审计事件/公开输出序列，网络调用全部在事务外。重复结果提交先检查稳定输出/事件键；不能多写消息、审计或重发设备操作。
副作用前提交command意图和准备审计，再保存写前checkpoint；实际请求前以version/fence取得attempt、记IN_FLIGHT和尝试开始审计，之后才出站。写前任何持久化失败都禁止发送。出站与数据库提交不是同一事务；崩溃遗留IN_FLIGHT必须按不确定结果处理，不猜测请求未发送。
成功结果先提交，checkpoint随后保存；checkpoint保存失败时，恢复从workflow_step读取已成功知识/查询结果，从command_execution读取命令结果并补齐步骤/state，不重做昂贵工作或成功命令。成功提交前不向SSE报成功；checkpoint不能覆盖较新的命令事实。
MySQL乐观版本/claim及执行fence是并发权威；Redis带TTL租约仅加速互斥。迟到回调只按attempt/fence条件提交，不覆盖新结果；租约丢失时不能让第二执行器重发未知命令。checkpoint saver经持久化服务与mapper工作，不另建DAO框架或Redis持久源。
用户输入/输出正文、命令账本、追加审计与checkpoint各有职责；不记录内部思维链、提示词或秘密。删除旧SessionTransitionLog/RepairExecutionRunner不删除repair_action_log表及历史记录，新命令审计由统一结果事务追加，复用的RepairExecutor不能再绕过该事务重复写旧格式日志。

## 5. Memory 与缓存

复用 ConversationHistoryService 的本人会话、messageId 边界与 20 条历史窗口，创建每次调用独享的 LangChain4j ChatMemory 视图（20 条历史 + 当前输入一次），其内容投影到 messages。messages 使用替换或按稳定消息 ID 去重并限窗的 reducer，不用无界 append。
同用户不同 conversation/request 的 memory 实例及消息集合隔离。AI Service 缓存仍只以 userId 缓存无状态代理，由专用工厂创建；不能将可变 ChatMemory 挂到共享用户代理或放到检查点。
多个 LLM 节点消费当前有效消息窗口和显式步骤输入；规划 JSON、RAG 原文、内部推理不成为用户历史。首次规划按原始消息边界；接纳澄清回复后以最新回复为当前输入，之前可见消息进入最近20条历史窗口。RequestContext.userMessage仍保留最初原文作追溯，不再次追加到模型输入。当前输入不同时通过 history 与 text 重复注入。
恢复加载持久 snapshot 的 messages，必要时按该workflow最后接纳的原始/澄清消息ID边界从历史重建，不能带入另一个会话或新轮的消息。origin_message_id始终保留最初输入，另存latest_input_message_id定位最后澄清输入。批准/恢复意向单独审计，不替换原始 userMessage。

## 6. 迁移与保留

新增前向迁移 `scripts/migration/20260915-langgraph-workflow.sql` 并同步新建库 DDL（均在 implement 阶段生成）。旧迁移不改写，已有数据不重置。
按4.1创建五张必要新表并扩展三张既有表；旧ID、消息正文和审计记录保持不变。新关联列对旧行允许null，索引须兼容存量数据。现有旧命令日志没有可靠幂等键/批准/尝试身份，禁止回填成可恢复command_execution；旧report也不能批量伪造checkpoint。旧记录只通过历史投影读取。
repair_session 增加 nullable active_workflow_request_id；现 processing_message_id/deadline 仅代表一次活跃执行区间，人工等待和重启恢复不受旧绝对截止误终止。
升级先排空旧处理：无 checkpoint 的旧在途请求留存明确无法恢复说明，不能从原消息重放；旧终态/消息无需转成新图。
新图 workflow 由新恢复逻辑处理，旧 recoverOwned/expire 随SessionProcessingService移除，不再参与GET或重启恢复。ConversationHistoryService改依赖独立AcceptedWorkflow记录或显式userId/sessionId/messageId，不能继续引用旧内部Accepted。
SessionContext/SessionContextStore及旧Redis等待快照退出最终数据模型，只有持久checkpoint能恢复新任务。SessionStatus历史值及既有processing字段可以为数据/API兼容保留，但不再承载第二套状态机或旧到期裁决逻辑；本次不要求为删除类而破坏性删除历史列。
原接纳/结果提交/审计及afterCommit日志职责迁入新事务服务，旧SessionTransitionLog/SessionStateMachine删除。共享历史、快照、账号、设备、知识索引和userId代理缓存保留。
既有显式会话删除继续校验归属并事务处理关联记录；持有有效执行租约或设备命令为 IN_FLIGHT/UNKNOWN 时拒绝删除；无有效执行租约且不存在未确定命令时允许删除未终止的工作流，包括中断后残留 DISPATCHING、等待确认及 WAITING_RESUME。删除持有工作流与会话行锁，并复查新请求，已删除的工作流不能再被旧执行者写回或续接。

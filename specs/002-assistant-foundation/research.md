# Research: LangGraph4j 对话工作流

**Date**: 2026-09-15
**Scope**: 固定版本官方文档/源码和当前仓库的只读研究；没有运行新图或新增依赖编译实验。旧版研究由Git历史保留，原验收见validation.md。

## R1 — 版本与最小依赖

**Decision**: 固定 org.bsc.langgraph4j:langgraph4j-core:1.8.27，不引LangChain4j集成模块，保留已有LangChain4j1.0.1。
**Rationale**: 官方1.8.x为LTS，1.8.27于2026-09-05发布，Java17+满足Java21；core不需要替换模型框架。新增依赖的Jackson/Gson等必须在实施阶段核查完整依赖树并编译/序列化验证。
**Alternatives considered**: 不采用1.9预发行，不因为新框架升级Boot或AI SDK，不仅替换原SessionStateMachine枚举。
**Evidence**: [发行记录](https://github.com/langgraph4j/langgraph4j/releases/tag/v1.8.27)、[core POM](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/pom.xml)。

## R2 — 共享State的内联子图

**Decision**: 子图工厂返回StateGraph，以addNode(name,StateGraph)接入主图，在同一次compile内展开。
**Rationale**: 用户要求严格requestId threadId；内联后的所有节点共享state/saver，可在多步计划反复进入Query/Control而无需管理多条子thread。
**Alternatives considered**: CompiledGraph子图在共享saver时会派生threadId；手动invoke子图则不能自然获得内部state输出。
**Evidence**: [固定版StateGraph overload](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/StateGraph.java)、[CompiledGraph展开实现](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/CompiledGraph.java)。实际中断名使用SubGraphNode.formatId，不能对逻辑子图容器设置interruptAfter。

## R3 — Stream与人工中断

**Decision**: 使用CompiledGraph.stream(GraphInput,RunnableConfig)，读取NodeOutput.state；批准后updateState白名单delta并使用返回配置调用GraphInput.resume。配置releaseThread(false)。
**Rationale**: framework负责节点进度，业务服务负责身份/批准/版本，Controller只转换OutputContext。人工等待先checkpoint再断开SSE。
**Alternatives considered**: 不套用Python Command(resume=...)；不从START重建已执行计划，不直接暴露AgentState。
**Evidence**: [CompiledGraph](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/CompiledGraph.java)、[CompileConfig](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/CompileConfig.java)。

## R4 — Token输出桥接

**Decision**: 骨架使用节点级stream；正式TokenStream由有界队列桥接至节点嵌入的AsyncGenerator，产出具有安全OutputContext的独立state snapshot。回调禁止直接写SSE。
**Rationale**: 已核实core可compose节点Map中的generator；中间输出不自动合并主state，done(Map)才产生最终更新，必须区分临时token与持久结果。具体adapter及TEXT_RESET是项目设计，不声称框架自动提供。
**Alternatives considered**: 不新增与LangChain4j版本绑定的集成模块；不声称每个token都自动checkpoint；不在输出部分失败文本后无标记追加重试答案。
**Evidence**: [官方streaming机制](https://langgraph4j.github.io/langgraph4j/main/core/streaming/)。已用v1.8.27 CompiledGraph的嵌入generator处理核对版本。

## R5 — Saver与数据一致性

**Decision**: 实现BaseCheckpointSaver，JSON纯数据state、MyBatis-Flex/MySQL持久化、数据库版本和执行claim。
**Rationale**: SPI本身不保证外部业务事务或exactly-once。step结果先存，checkpoint后存时可从step结果修复，写前必须保存意图；禁止事务跨网络请求。
**Alternatives considered**: MemorySaver只用于G1测试；Redis不作持久源；不引第二套JDBC/MySQL持久封装，不用默认任意Java对象序列化。
**Evidence**: [固定版BaseCheckpointSaver](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/checkpoint/BaseCheckpointSaver.java)、[StateSerializer](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/serializer/StateSerializer.java)。实现list/get/put/release，不虚构名为CheckpointSaver的接口。

## R6 — requestId与旧生命周期

**Decision**: 首次接纳的requestId持久化为workflow主键/threadId；后续HTTP trace另用workflowRequestId关联。conversationId就是原sessionId。
**Rationale**: 当前RequestLogFilter的requestId只在request attribute/MDC；SessionProcessingService的recoverOwned/expire会把重启遗留工作判失败，新图必须替换该恢复路径。现默认processing-timeout300秒、lease180秒，不能把人工等待算入一次调用。
**Alternatives considered**: 不以userId或sessionId作为threadId；不让批准HTTP requestId覆盖原workflow；GET只补查不恢复。
**Evidence**: 本地common/RequestLogFilter、core/session/SessionProcessingService、SessionController及application.yaml。

## R7 — 当前设备客户端的幂等限制

**Decision**: 查询超时可在原确认范围内重试；写操作仅在远端去重或等效保证成立时允许重发。当前client无此能力时，已发送但结果未知以DEVICE_RESULT_UNKNOWN停止。
**Rationale**: DeviceServiceClient/DeviceSimulatorClient 的commands仅含command/parameters，start也没有operation key或按ID查询结果；本地step记录不能证明外部未执行。错误HTTP响应和业务拒绝也不是timeout。
**Alternatives considered**: 不盲目重放POST，不把读后当前状态当任意命令的exactly-once证明，不用额外未确认查询核对。stub可验证去重路径，真实上线须验证适配器能力。
**Evidence**: 本地core/device/client/DeviceServiceClient、DeviceSimulatorClient与core/repair既有调用边界。

## R8 — State、ChatMemory与用户缓存

**Decision**: 九个context整体替换，PlanContext唯一执行游标；第十个顶层键messages按稳定ID限窗。请求级LangChain4j ChatMemory承接已有历史读取，共享AI代理仍无状态且userId缓存不变。
**Rationale**: 现ConversationHistoryService已做本人会话/messageId边界及20条历史；UserAiServiceCache缓存direct/analysis/enhanced，不应承载可变会话记忆。SDK类型转换在边界处理，snapshot存项目message record。
**Alternatives considered**: 不同时自动ChatMemory和手工history重复输入；不以消息文本去重；不把ChatMemory对象放AgentState。
**Evidence**: [Channels固定版](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/state/Channels.java)、[AgentState](https://raw.githubusercontent.com/langgraph4j/langgraph4j/v1.8.27/langgraph4j-core/src/main/java/org/bsc/langgraph4j/state/AgentState.java)及本地ConversationHistoryService/UserAiServiceCache。

## R9 — 前端与API兼容

**Decision**: 保留五类legacy SSE，新增workflow白名单事件及步骤查询、批准、resume/cancel；当前前端按增量交互适配。
**Rationale**: ChatPage目前只识别token/status/awaiting/conclusion/error，不能把新state envelope直接替换旧data，也不能宣称现有页面已经支持Query批准。
**Alternatives considered**: 不新增自动事件重放；不把状态查询当作继续请求。完整内部state不公开。
**Evidence**: 本地domain/message/SseEvent、domain/dto/SessionResponse与frontend/src/pages/console/ChatPage.vue。

## R10 — 配置与阶段

**Decision**: 最多8步、重试2次/间隔1秒、确认TTL300秒、昂贵阈值30秒、执行区间300秒。先stub骨架，再MySQL恢复，再真实知识、其他子图与前端。
**Rationale**: 明确有界默认值便于验收，配置可调；人工等待不占调用预算。旧任务完成记录不构成新图证据。
**Alternatives considered**: 不在本次plan实现生产代码或跑真实设备，不用stub隐藏真实能力未接入。
**Evidence**: 用户本轮要求、002的五项已接受澄清、现application.yaml及代码复用边界。

## R11 — 移植结束删除旧编排

**Decision**: 删除SessionOrchestrator而非保留精简壳；同步移除旧状态机、Redis续接、dispatcher/handler协议、RepairExecutionRunner和专属配置，先提取仍需复用的业务职责。最终删除矩阵及门禁见plan。
**Rationale**: 本地依赖核对发现，旧Orchestrator还承担会话CRUD和Guard，ConversationHistoryService依赖SessionProcessingService.Accepted，UserAiServiceCache实现AcceptedConversationInitializer，KnowledgeEmbeddingProperties/Config依赖AssistantProperties。SessionTransitionLog承载事务审计及afterCommit日志；这些职责需要新入口承接，不能随类删除丢失。仅移出route而留原壳仍保留第二套生命周期。
**Alternatives considered**: 不永久保留旧Orchestrator作为graph门面，不把旧route/continueWaiting复制到改名后的服务，不因目录名为core/session就整包删除，也不为读取旧历史保留可执行旧引擎。历史状态文本和档案保留，不构成运行回退机制。
**Evidence**: 当前SessionController、SessionOrchestrator、SessionProcessingService、SessionTransitionLog、ConversationHistoryService、UserAiServiceCache、KnowledgeEmbeddingProperties/Config的引用；用户本轮明确删除要求。本轮只做本地依赖研究，沿用已核实的框架选型，没有新增外部技术未知。

## R12 — 五类逻辑持久数据与既有表复用

**Decision**: conversation映射repair_session、chat_message沿用原表；新增workflow_execution和command_execution，audit_event扩展repair_action_log，取消原拟建workflow_event。workflow_step/approval/checkpoint保留为workflow内部结构，共五张新表、三张旧表扩展，不为逻辑名称统一重命名或复制旧表。
**Rationale**: schema.sql及实体显示repair_session已是长期会话，chat_message保存可见正文；repair_action_log既保存设备动作结果，也保存state迁移、request接纳/完成及route事件，天然属于追加审计。旧RepairExecutor在设备调用后才记日志，没有命令幂等键、出站前意图、确认关联、attempt/fence，不能作为命令状态权威。problem_report缺少多步计划和checkpoint语义，继续作为领域描述。辅助表分别满足步骤结果/claim、批准独立生命周期及saver历史快照需求。
**Alternatives considered**: 不新建conversation/audit_event与旧表双写；不将repair_action_log改为可变命令行而丢失历史事实；不把旧动作日志反推成可安全重发的命令；不为凑五张物理表将checkpoint和批准塞入chat_message或诊断快照。新审计复用params为版本化安全元数据，保留旧行原值，避免重复payload列；其公开内容须经白名单投影。
**Evidence**: 本地src/main/resources/schema.sql、RepairSession/ChatMessage/RepairActionLog实体、SessionTransitionLog、SessionProcessingService、RepairExecutor、SessionSchemaValidator；已派发只读持久化复用研究并核对字段。现result为VARCHAR(16)、message为VARCHAR(1024)，细粒度结果另加result_code，正文引用chat_message。迁移用新增脚本及只读启动校验，不能靠CREATE TABLE IF NOT EXISTS升级既有表。

## Research closure（本轮复核）

所有影响本次设计选择的技术未知已有决策，无未解决占位符。实际依赖编译、子图中断实验、数据库迁移和真实设备适配能力验证属于实施验收，不在本研究中宣称通过。
001/003旧plan/tasks仍为历史边界；当前五feature规格已同步，新图以002本计划和契约为准，真实领域交付需后续同步各自计划任务。

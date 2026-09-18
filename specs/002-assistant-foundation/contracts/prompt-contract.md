# Contract: Planner 与既有 AI Service 资源提示词

**Date**: 2026-09-15
**Status**: 保留已有资源与验收记录，新增 planner 和 graph 接入设计尚未实施。
**References**: [路由契约](routing-contract.md)、[历史验证](../validation.md)。

## 1. 工厂与资源

所有固定规则放 src/main/resources/prompt，方法以 @SystemMessage(fromResource="/prompt/...") 加载；固定用户包装以 @UserMessage(fromResource) 加载。工厂继续在 ai/factory，结构化候选在 ai/model，业务运行状态在 graph/state。

| 能力 | 系统资源 | 输入资源 / 参数 |
| --- | --- | --- |
| 新 IntentPlannerService / IntentPlannerServiceFactory | 新 intent-planner.txt | 复用 conversation-input.txt：history、text |
| DirectAnswerService.answer / DirectAnswerServiceFactory | direct-answer.txt | conversation-input.txt：history、text |
| DirectAnswerService.answerKnowledge | knowledge-direct-answer.txt | knowledge-direct-input.txt：history、text、answerContext |
| EnhancedAnswerService.analyze / EnhancedAnswerFactory | problem-analysis.txt | conversation-input.txt：history、text |
| EnhancedAnswerService.analyzeKnowledge | knowledge-query-analysis.txt | knowledge-query-input.txt：history、text、catalog |
| EnhancedAnswerService.answerKnowledge | knowledge-answer.txt | knowledge-answer-input.txt：request |
| DiagnosisReasonerService / DiagnosisReasonerServiceFactory | diagnosis-reasoner.txt | diagnosis-input.txt：history、text、symptom、diagnostics |

现有intent-router.txt仅在移植期间保留作对照；最终与IntentRouterService/Factory及旧分类调用一起删除，新的graph只调用planner。DirectAnswer/EnhancedAnswer/Diagnosis等仍需使用的工厂和资源保留。不存在统一AiServiceFactory，也不恢复已不存在的ProblemAnalysisServiceFactory。
planner 系统资源定义候选计划、四步骤类型、有序依赖、受限条件、requiresKnowledgeBase、诊断不隐式访问设备、未知信息澄清和安全字段禁令。模型产物永远不是批准。
不在 prompt 中复制完整 Java schema；保留 SDK 结构化输出支持及实际代理解析测试。

## 2. Messages 与输入边界

LangChain4j ChatMemory 由 core/session/memory 的请求级适配器构造，为 graph messages 提供最近20条已保存可见历史与当前输入一次。共享 userId 代理不挂可写 memory/@MemoryId；调用适配器从 messages 拆成 history/text，避免再次自动注入一份。
诊断 evidence/检索来源来自本计划可信步骤引用或类型筛选知识，不以用户给出的设备状态冒充实时读数。内部计划、推理和知识原文不能加入用户历史或提升为 SystemMessage。
保留 PromptInputEncoder：只转义 JSON 字符串中的花括号，所有 @V 值非 null，不改变持久化原文。模板变量精确绑定，不用 Java 拼接规则、输出约束或用户包装。
服务缓存保持 userId 单键，DirectAnswerServiceFactory/EnhancedAnswerFactory 创建代理；graph threadId、conversationId、messages 均不进入该缓存键或成为可变代理字段。

## 3. 校验与测试

复用 AiServiceValidator，新增 planner 接口和资源登记。启动校验资源存在、非空、严格 UTF-8、变量名和方法参数一致；固定规则不可内联，缺失资源阻止真实模式装配，不能静默 mock。
真实 AI Service + WireMock 验证结构化多步输出、非法引用/条件、数据中模板字符、prompt injection 文本、当前输入只出现一次及跨会话隔离。mock 不替代这些代理测试。
JAR 验证覆盖所有实际引用资源，不再硬编码“只有六文件”。每条资源与源码内容一致且 classpath 可读；不创建覆盖主资源的同名测试模板。
LangChain4j TokenStream 回调不能直接向 SSE 写数据；经过 graph 流适配器形成安全 OutputContext，详见 graph-contract。该桥接不改变模型调用必须走 AI Service 的规则。

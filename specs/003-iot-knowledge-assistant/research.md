# Research: 共享索引与 AiServices 内嵌 Advanced RAG

**Date**: 2026-09-11 | **Feature**: [spec.md](spec.md)

本次研究替代 2026-09-10 的“外置 augment 后手工调用回答服务”方案。同时完成 FR-003/FR-008 的专用工厂直建、单用户缓存和跨型号声明研究；未决设计项已解决，不升级当前 SDK。

## R1 — 一次共享索引与最少文件

**Decision**: ai/rag/KnowledgeEmbeddingStore 以非 lazy 的 Spring Bean 工厂方法输出 EmbeddingStore<TextSegment>。局部创建 InMemoryEmbeddingStore，启动完整导入成功才返回，之后作为只读共享依赖。资源、metadata、校验均用私有方法/必要嵌套适配；不再定义 Loader/Splitter/Initializer/Snapshot 四层服务。

**Rationale**: 满足“静态一次、全部 AI Service 共用”，保留容器生命周期、测试隔离与未来标准接口替换。static 全局字段不会比单例减少文件，反而增加初始化和跨上下文污染风险。

**Alternatives considered**: 请求首次访问建库违反启动完整可用前提；每工厂建库重复 embedding；ApplicationReadyEvent 异步建库存在提前接收业务窗口；外部库与热更新本期均不需要。

## R2 — LangChain4j 导入组件

**Decision**: Spring Resource 枚举与输入流接 LangChain4j DocumentLoader/UTF-8 TextDocumentParser；DocumentSplitters.recursive；EmbeddingStoreIngestor。明确提供配置过的 model、splitter、store，不依赖 easy-rag 默认模型。继续严格失败检查，局部薄适配无需独立文件。

**Rationale**: 1.0.1 DocumentLoader 位于 dev.langchain4j.data.document。加载器负责关闭输入流并合并 metadata；splitter 继承 metadata 和添加 index；ingestor 执行分段、embedAll、addAll。Spring 输入流兼容源码 classpath 与 Boot JAR。TextDocumentParser 自身不能拒绝所有坏 UTF-8，需读取边界校验；ingestor 不替代向量数量/维度检查。

**Alternatives considered**: 批量 ClassPathDocumentLoader 会跳过异常/空文档，不符合 fail-fast；getFile 不能作为 JAR 通用接口；自写 Markdown parser 或向量循环没有必要。无需为每个 SDK 接口提供一层业务 Service。

## R3 — Advanced RAG 必须挂在回答代理

**Decision**: EnhancedAnswerFactory 装配 EmbeddingStoreContentRetriever → KnowledgeQueryRouter → DefaultRetrievalAugmentor → AiServices.retrievalAugmentor。同一接口提供无 RAG 分析代理和有 RAG 回答代理，调用方严格区分用途。

**Rationale**: 1.0.1 DefaultAiServices 的顺序是准备系统/用户消息 → augment → 输出格式/JSON schema 处理 → ChatModel。RAG 因而确实在每次增强回答内部执行，不需要业务先调 augment。接口中保留的诊断 analyze 必须走无 RAG 代理，不能让原诊断突然检索知识。

**Alternatives considered**: 单用 contentRetriever 简便但不能落实已要求的特殊 QueryRouter 和资源化注入；同时设置 contentRetriever 与 retrievalAugmentor 无意义。外部 augment + 无 RAG 服务已被本轮要求替代。

## R4 — QueryRouter 判不足且只检索一次

**Decision**: Retriever maxResults=topK、minScore=0.0；唯一 metadata filter 是规范 deviceType 相等。Router.route 检索一次，再按配置 minScore 校验候选。达标返回捕获不可变 hits 的回放 Retriever；空结果/全部低分抛 KnowledgeInsufficientException，携带 NO_MATCH/LOW_RELEVANCE 安全原因。外层只捕获该异常转 DirectAnswerService。

**Rationale**: 1.0.1 Router 本来在真实 retriever 前执行，因此用一次预取加零 IO 回放实现门槛判断。单查询/单回放 retriever 路径不启用并行任务；异常从 augment/AI Service 同步直传且发生在模型调用前。返回空 retriever 集合本身并不会禁止模型继续回答，不能用它实现所需回退。

**Alternatives considered**: 工厂外再检索一次产生重复 IO；模型回答后再检查空来源多消耗一次模型；共享 Router 字段/ThreadLocal 保存“本次结果”造成并发与异步泄漏。技术失败不转为业务资料不足。

**Score**: InMemoryEmbeddingStore 使用 (cosine + 1) / 2，范围 [0,1]；ContentMetadata.SCORE 可读分数。业务比较 score >= minScore，初始 0.75，需真实模型校准。SUFFICIENT 仅代表相关度通过，不承诺型号事实有据。

## R5 — 跨型号资料作为回答依据

**Decision**: 类型明确即检索，型号/版本/环境缺口不再触发前置澄清。有同类型另一型号的达标资料时据此回答，参数和条件以该资料为准；服务端生成当前型号信息缺口与实际依据型号声明。型号不同不作为过滤或引用拒绝条件。

**Rationale**: FR-003 已明确要求使用另一型号知识。“当前型号信息不足”与“无相关资料/低相关度”不同，前者继续增强回答，后者由 Router 回退直答。引用型号必须由本次已验证 sourceIds 对应 metadata 得到，声明随回答保存。

**Alternatives considered**: 缺型号先追问、只说明缺口却不使用已有相关资料均不符合新要求；隐去依据或无说明混合型号参数仍不可接受。

## R6 — JSON 数据注入与来源返回

**Decision**: answerKnowledge 使用 @SystemMessage 的 /prompt/knowledge-answer.txt 和 @UserMessage 的 /prompt/knowledge-answer-input.txt；后者仅含 {{request}}。服务端编码 request JSON，含 history/text/queryText/scope 以及内部截止。QueryTransformer 只提取 queryText；ContentInjector 在原请求数据中加入 evidence 并移除内部截止，再生成 UserMessage。不追加 Java 内联自然语言或默认 injector 提示词。返回 Result<KnowledgeAnswer>，以 Result.sources() 为本次来源基准。

**Rationale**: RAG 执行时 JSON 尚未被 SDK 结构化输出指令追加改变。1.0.1 query.Metadata 无 invocationParameters；无 ChatMemory 的代理不能通过 @MemoryId 传参。Result.sources 来自 AugmentationResult.contents，无需共享结果容器。JSON 由服务器构造，不能直接把用户输入当作 envelope。

**Alternatives considered**: 完整用户模板用作 embedding 会污染查询；默认 ContentInjector 带固定模板，不符合项目资源规范；动态拼接系统消息把资料提升为可信规则。原 PromptInputEncoder 的隔离 writer/括号转义继续复用，注入后不再做模板替换。

## R7 — 直接调用专用工厂与单个 userId 缓存

**Decision**: 移除 AiServiceFactory。UserAiServiceCache 注入 DirectAnswerServiceFactory / EnhancedAnswerFactory，cache.get 未命中映射函数直接调用三个服务创建方法；UserAiServices(direct, analysis, enhanced) record 共址缓存，唯一 Cache<Long, UserAiServices> 保存完整组合。最大 1000 个用户组合，访问后 30m 过期，成功接纳后原子创建，命中复用。DirectAnswerServiceFactory 与 EnhancedAnswerFactory 只装配新代理，不另行缓存。

**Rationale**: FR-008 要求直接调用专用工厂、每用户独立实例且仅 userId 缓存键。三种代理随组合完整创建；两个 EnhancedAnswerService 代理分别用于无 RAG 分析和有 RAG 回答，避免 SDK 对一个代理全部方法增强造成误检索。同用户切换类型仍复用同一 enhanced 代理，类型由每调用数据取得。

**Alternatives considered**: 复合键、按类型子缓存、每类型延迟代理均被新要求替代；共享可变 filter 会串类型。原统一组合工厂仅转发既有创建方法，用户要求去除该中间层；组合创建作为缓存内部私有映射函数即可，其他调用方直接使用对应工厂。保留完整三代理的原子发布，不复制模型配置或增加替代统一工厂。

## R8 — 当前代码与验证界限

**Decision**: 复用已存在的属性、资源、缓存依赖、接纳协作者与测试；按 live code 修复悬空 service/knowledge 引用，由用户缓存直接使用现有专用工厂，将增强装配工厂命名为 EnhancedAnswerFactory。2026-09-10 的 156 项测试仅保留历史记录，不能证明当前重构中的代码通过。

**Rationale**: 当前 EnhancedAnswerService 已重命名但仅有 analyze；导入实现目录已移除，旧配置仍引用这些类型。旧 tasks 中勾选状态也已变化，规划不代替用户改写完成状态，必须重新生成实施任务并复核。

**Alternatives considered**: 恢复整套被删除的旧类违背简化目标；直接继续旧任务会产生外置 RAG 与复合过滤回归。本轮只更新文档。

## R9 — 1.0.1 动态类型过滤与并发隔离

**Decision**: EmbeddingStoreContentRetriever.builder().dynamicFilter(Function<Query, Filter>) 从本轮 query.metadata().chatMessage() 的服务器编码 JSON 读取 scope.deviceType，返回新的类型等值 Filter。QueryTransformer 返回 Query.from(queryText, original.metadata())。Router 先验证 envelope、类型与截止，再调用 Retriever；证据与回放均为局部对象。

**Rationale**: 本地 1.0.1 源码已核实 dynamicFilter 存在，Query 可保留 Metadata；Metadata.chatMessage 持有原始用户消息。retrieve 内先 embed(query.text())，再计算 filter，故不能只在 dynamicFilter 回调里做输入前置校验。无类型时业务入口直答；若错误进入增强路径，在外呼 embedding 前明确失败，不返回 null filter 或不带过滤检索。

**Alternatives considered**: factory/Router 中可变 currentType、ThreadLocal、MemoryId 都不是安全的请求上下文传递方式；每次请求重新创建代理失去缓存意义；子类型缓存不符合 FR-008。

## R10 — 跨型号声明与来源输出

**Decision**: KnowledgeAnswer 保持 answer/sourceIds/status，status 仅 ANSWERED/CONFLICT；跨型号是可成功回答的场景，不单列拒绝状态。服务端根据 scope.missingInformation、请求型号是否缺失、引用 productKey 与请求型号差异派生声明，型号来自真实引用 metadata，全部来源非空且可追溯。

**Rationale**: 声明可以确定性生成，无须多加一次模型或让模型填写未经校验的型号列表。先校验 sourceIds 与本轮资料，再统一生成“没有当前型号设备信息”及依据型号说明；同类型不同型号可引用。多来源按型号关联，不能只靠提示词期待模型自行声明。

**Alternatives considered**: 旧 INSUFFICIENT_SCOPE 会把可用的另一型号资料变成无引用缺口回答，与 FR-003 不符。引用属于允许集合只能验证来源身份，不能自动证明每句话的事实准确；具体参数一致性仍以受控样例评估，不宣称结构校验可以证明所有自然语言断言。

## Evidence

固定版本依据为本地 Maven 1.0.1 sources：langchain4j 的 DefaultAiServices、DefaultRetrievalAugmentor、EmbeddingStoreContentRetriever、EmbeddingStoreIngestor、Result，本次补充核对 EmbeddingStoreContentRetriever.dynamicFilter/retrieve、Query.from(text, metadata) 与 Metadata.chatMessage；以及 langchain4j-core 的 DocumentLoader、DocumentSplitter、Query/Metadata 和 in-memory store 实现。实施时继续以 pom.xml 锁定版本编译验证。

官方背景：[RAG 的组件与 Advanced RAG 说明](https://docs.langchain4j.dev/tutorials/rag/)、[AI Services 装配与资源提示词](https://docs.langchain4j.dev/tutorials/ai-services/)。在线文档会更新，不能直接复制当前最新版新增 API 到 1.0.1；以上具体调用顺序与 API 已按本地固定版本源码核对。

---
description: "按 2026-09-11 最新计划同步的 IoT 知识咨询实施任务"
---

# Tasks: IoT 与设备知识咨询

**Branch**: `003-iot-knowledge-assistant` | **Updated**: 2026-09-11

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[运行契约](contracts/knowledge-runtime.md)、[导入契约](contracts/knowledge-documents.md)、[quickstart.md](quickstart.md)及[章程](../../.specify/memory/constitution.md)。

## 修订提示与 Execution status

**最新修订**：按用户要求移除统一工厂，缓存直接使用专用工厂方法；T019/T020 按实际实现同步，编号和已完成状态保留，Phase 3 及后续范围不变。历史归档中的统一工厂描述不再有效。

本清单是 FR-003/FR-008 修订后的唯一有效实施任务。已落实同类型跨型号依据与声明、缓存直接调用专用工厂创建三代理、唯一 userId 缓存及逐请求动态类型过滤，不再等待 plan 同步。

旧 47 项任务及原勾选完整保存在 [tasks-before-20260911-sync.md](tasks-before-20260911-sync.md)，仅供历史对照，其旧架构不再有效。旧文件中只有 T001 已勾选，而历史说明记录旧 T001–T014 已执行；这一区别原样保留，不用历史声明自动补勾。新清单重新编号 T001–T044，所有任务均为本次迁移、接入或重新验证；当前 T001–T040、T042–T044 已完成（43/44）；T041 已执行完整回归但仍有 13 项旧诊断/设备验收失败，保持未勾选。旧编号仅在归档文件和历史验证章节生效。

[validation.md](validation.md) 最新章节记录 Phase 3/4/5：普通 verify 187 项通过，知识定向集成与 9 项 JAR 检查通过；完整 -Pit 为 244 项、13 项失败，不宣称项目回归通过。下方历史章节保留 Phase 1/2 的 168 项结果。knowledge.md 仅作过期存档，原评审勾选不变。

**Prerequisites / Reuse**: 002 已有认证、用户控制、设备管理、公共路由、AI 配置、基础 DTO、会话/历史/SSE、Guard 与终态收尾继续复用。已有 Caffeine 依赖、配置、资源、工厂、接纳协作者和测试按现状迁移，避免重新创建公共基础。本 feature 不增加公开 API、表、前端页面或设备控制链路。

**Tests**: spec 的 Independent Test、验收场景及 SC-001–SC-008 已明确要求行为验证，章程要求关键路径与实际 AI 资源装配测试，故列出对应任务。先补有意义的失败场景，再实现并验证；真实 SDK 装配用本地可捕获模型/WireMock，不能只检查注解或用固定 mock 答案绕过 RAG。

## Format: `[ID] [P?] [Story] Description`

- 所有代码路径相对仓库根目录；用户故事按 spec 使用 [US1]/[US2]，Setup/Foundational/Polish 不加故事标签。
- [P] 只代表下述明确并行组在共同前置完成后可同时处理不同文件，不允许跳过依赖。其余任务按阶段与编号执行。
- 不可变 DTO/AI 输出优先 Java 21 record；AI 枚举在 ai/model/enums，业务枚举在 domain/enums，通用工具在 utils；固定 prompt 使用资源。
- Caffeine 与 ai/rag 的有限章程差异沿用 plan 中用户已授权说明，不替代 Redis 的身份/会话职责。

## Phase 1: Setup — 核对复用基线

**Goal**: 保留已有工程和依赖，扩展可计数夹具，不重复建设公共能力。

- [X] T001 复核并保留 `pom.xml` 中已加入的 Caffeine、Java 21、Boot 3.5.3、LangChain4j 1.0.1 与 Log4j 2 依赖；将当前旧引用和复用基线记录到 `specs/003-iot-knowledge-assistant/validation.md` 的新日期章节，不恢复已删除的多层知识服务或重写公共配置。

- [X] T002 扩充现有 `src/test/java/com/chh/autosense/support/KnowledgeFixtures.java`、`src/test/java/com/chh/autosense/support/DeterministicEmbeddingModel.java`：增加同类型不同型号/类别、跨类型、型号与条件缺口、冲突、门槛边界、恶意资料、坏资源及并发屏障夹具；分别计数启动 embedding、query embedding/search、模型和设备调用，使用本地模型，不修改生产资料。

**Checkpoint**: 当前引用缺口及既有资产可追溯；夹具支持后续行为测试。

## Phase 2: Foundational — 共享索引与完整用户服务组合

**Goal**: 收敛导入、修复装配，首次接纳就能直接调用专用工厂原子创建完整三代理。

**依赖说明**: FR-013 要求完整索引先于业务就绪，FR-008 要求首次接纳（包括非知识对话）创建完整 direct/analysis/enhanced。因此三代理共有的类型/模板及底层 RAG 装配属于基础前置，不能等 US2 再补一个真正可用的增强代理；US2 仍负责检索业务分支、跨型号声明、回退与来源保存。

### 基础行为测试

- [X] T003 [P] 调整 `src/test/java/com/chh/autosense/unit/KnowledgeConfigurationTest.java`，覆盖独立 embedding 配置、real 禁用 mock、未知 provider/store、别名歧义、非法限制及默认值；验证三次聊天调用加一次 query embedding 的最坏预算和 5 秒余量严格小于实际 AssistantProperties 截止，启动预算独立。

- [X] T004 [P] 迁移 `src/test/java/com/chh/autosense/unit/KnowledgeIndexInitializationTest.java` 至标准 store Bean：覆盖元数据/SDK index、单次导入、所有用户共享、重启来源稳定、零维修表读取；缺失/重复/未知文件名/坏 UTF-8/BOM/空白/解析/超量/向量数量维度或有限值/写入/启动超时均失败且不发布部分或迟到索引。

- [X] T005 [P] 调整 `src/test/java/com/chh/autosense/unit/UserAiServiceCacheTest.java`，从专用工厂实际或可观测三代理组合验证唯一 Long userId 键、同用户跨会话/类型复用、跨用户三个代理各自独立、并发一次完整创建、失败无半成品；用可控 Ticker 验证 1000 用户容量、30 分钟访问过期、移除后重建及驱逐不关闭在途流/共享依赖。

- [X] T006 [P] 新增 `src/test/java/com/chh/autosense/unit/KnowledgeRagAssemblyTest.java`，通过真实 AiServices/DefaultRetrievalAugmentor 捕获模型请求和 store search：queryText 单独向量化、只按本轮 deviceType 动态过滤、一次检索及零 I/O 回放、0.75 边界、Result.sources、JSON evidence/截止移除、资源加载与结构化输出；无/低分时增强模型零调用，非法类型或超时在 embedding 前失败，技术错误不伪装资料不足。

- [X] T007 [P] 调整 `src/test/java/com/chh/autosense/contract/KnowledgeAdmissionContractTest.java`，覆盖新会话/消息/等待续走接纳后且 Guard 建立后初始化，首次非知识对话也完整创建三代理；登录/GET/认证或归属拒绝/SESSION_BUSY 不创建，撤销或禁用后缓存命中仍拒绝，初始化失败清理处理指针并正确收尾。

### 基础实现与迁移

- [X] T008 复用并调整 `src/main/java/com/chh/autosense/config/KnowledgeProperties.java`、`src/main/java/com/chh/autosense/config/KnowledgeEmbeddingProperties.java` 和 `src/main/resources/application.yaml` 的绑定与校验：文档 32 MiB/10000 段、1000/150 字符、启动 300 秒、topK=4/业务门槛 0.75、缓存 1000/30m，独立 KNOWLEDGE_EMBEDDING_*；拒绝本期 external store，按 T003 校验调用预算，保留当前会话截止默认 300 秒。

- [X] T009 收敛 `src/main/java/com/chh/autosense/config/KnowledgeEmbeddingConfig.java`：构造共享 OpenAiEmbeddingModel（可选 dimensions、timeout=10s、maxRetries=0、batchSize=32），用小型嵌套装饰器复用向量数量/维度/有限值校验和安全日志；显式 mock 使用嵌套确定性实现，导入/查询共用同一 EmbeddingModel，移除对已删除包装类的依赖，真实故障不自动降级。

- [X] T010 在 `src/main/java/com/chh/autosense/ai/rag/KnowledgeEmbeddingStore.java` 内实现资源发现/解析私有方法：Spring classpath*:document/**/*.md 与 Resource 输入流适配 SDK DocumentSource、DocumentLoader、UTF-8 TextDocumentParser；严格路径、每型号 general.md/troubleshot.md 配对、重复/编码/字节上限校验，生成全部契约 metadata，保留 `src/main/resources/document/light/MI-MJDPL01YL/general.md`、`src/main/resources/document/light/MI-MJDPL01YL/troubleshot.md` 的内容。

- [X] T011 在 `src/main/java/com/chh/autosense/ai/rag/KnowledgeEmbeddingStore.java` 完成非 lazy 单例 Bean：recursive splitter 1000/150 字符、段数校验、EmbeddingStoreIngestor 写入局部 InMemoryEmbeddingStore<TextSegment>，全部成功才返回标准 EmbeddingStore；300 秒到期/取消/错误中止启动，使用 `src/main/java/com/chh/autosense/exception/KnowledgeInitializationException.java` 安全失败。元数据继承及 SDK index 标识段，不建 segmentId/快照；同文件提供依赖完整 store 的只读类型/产品 catalog，不重复扫描或导入。

- [X] T012 在 `src/main/java/com/chh/autosense/ai/model/KnowledgeQueryAnalysis.java`、`src/main/java/com/chh/autosense/ai/model/KnowledgeAnswer.java`、`src/main/java/com/chh/autosense/ai/model/enums/KnowledgeMissingInformation.java`、`src/main/java/com/chh/autosense/ai/model/enums/KnowledgeAnswerStatus.java`、`src/main/java/com/chh/autosense/domain/dto/KnowledgeAnswerRequest.java`、`src/main/java/com/chh/autosense/domain/dto/KnowledgeDirectAnswerContext.java`、`src/main/java/com/chh/autosense/domain/enums/KnowledgeDirectAnswerReason.java`、`src/main/java/com/chh/autosense/exception/KnowledgeInsufficientException.java` 定义最少的 record/枚举和安全异常；scope 用嵌套 record，缺口 MODEL/BRAND/VERSION/ENVIRONMENT，答案状态仅 ANSWERED/CONFLICT，直答原因 COMMON_SENSE/TYPE_UNKNOWN/NO_MATCH/LOW_RELEVANCE；不增加 requiresModel/clarifyQuestion、INSUFFICIENT_SCOPE 或自定义证据/检索结果包装。

- [X] T013 扩展 `src/main/java/com/chh/autosense/ai/DirectAnswerService.java`、`src/main/java/com/chh/autosense/ai/EnhancedAnswerService.java` 并新增 `src/main/resources/prompt/knowledge-direct-answer.txt`、`src/main/resources/prompt/knowledge-direct-input.txt`、`src/main/resources/prompt/knowledge-query-analysis.txt`、`src/main/resources/prompt/knowledge-query-input.txt`、`src/main/resources/prompt/knowledge-answer.txt`、`src/main/resources/prompt/knowledge-answer-input.txt`：保留 answer/analyze 原契约，增加知识直答 TokenStream、范围分析与 Result<KnowledgeAnswer> 方法，按运行契约使用 fromResource/@V；增强输入只含 {{request}}。规则明确跨型号参数须注明依据、缺信息不先澄清、证据不可信且禁止设备操作。

- [X] T014 扩充 `src/main/java/com/chh/autosense/utils/PromptInputEncoder.java`、`src/main/java/com/chh/autosense/utils/AiServiceValidator.java`：独立 ObjectWriter 编码本轮 envelope/直答上下文/catalog，严格区分用户原文与服务器 scope/deadline；保留花括号转义、本人本会话最近 20 条历史且不重复本轮问题；实际校验新资源 UTF-8/非空/参数绑定，不修改全局 Jackson。

- [X] T015 实现 `src/main/java/com/chh/autosense/ai/rag/KnowledgeQueryRouter.java`：route 从原 Query.metadata.chatMessage 的受控 envelope 预校验类型/绝对截止后仅 retrieve 一次；校验 score 有限且 [0,1]、完整来源及本轮类型，保留同类型其他型号。无候选/全低分分别抛安全 NO_MATCH/LOW_RELEVANCE，达标 hits 用 List.copyOf 的本轮零 I/O 回放 Retriever 返回；不以空 retriever 列表阻止模型，不保存共享上次结果。

- [X] T016 将 `src/main/java/com/chh/autosense/ai/factory/EnhancedAnswerServiceFactory.java` 迁移为 `src/main/java/com/chh/autosense/ai/factory/EnhancedAnswerFactory.java`，保留无 RAG problemAnalysisService()；新增无类型参数 enhancedAnswerService()，注入共享 store/model 并装配 Retriever(maxResults=topK,minScore=0.0,dynamicFilter 仅本轮 deviceType)、单 query transformer、T015 Router 与数据 ContentInjector。Transformer 在 embedding 前验证 envelope 并保留 metadata，Injector 投影 evidence/删除截止/检查预算；仅以 retrievalAugmentor 嵌入 AiServices，不外置 augment、不混用 contentRetriever、不使用默认内联提示词或可变类型字段。

- [X] T017 复用 `src/main/java/com/chh/autosense/ai/factory/DirectAnswerServiceFactory.java` 的构造器注入和资源检查，支持扩展后的接口且每次创建独立无状态直答代理；在 `src/main/java/com/chh/autosense/core/analysis/LangChain4jProblemAnalyzer.java` 更新 EnhancedAnswerFactory 引用，保留原诊断 analyze 的无 RAG 契约及错误语义，不重写诊断或设备推理流程。

- [X] T018 调整 `src/main/java/com/chh/autosense/ai/factory/MockKnowledgeAiServicesFactory.java`，提供与 real 相同的三个新代理所需本地聊天/流式模型，复用 T016 的真实 SDK RAG 装配；移除旧服务对和已删除类型引用，不以固定答案绕过检索、不持有另一份缓存，创建阶段不调用本地模型。

- [X] T019 在 `src/main/java/com/chh/autosense/service/knowledge/UserAiServiceCache.java` 内保存公开嵌套 UserAiServices record，直接注入 `src/main/java/com/chh/autosense/ai/factory/DirectAnswerServiceFactory.java` 与 `src/main/java/com/chh/autosense/ai/factory/EnhancedAnswerFactory.java`；未命中映射函数直接调用三个创建方法，全部成功才发布，不新增统一中间工厂。创建零模型/检索请求、失败无半成品、认证 userId 不进入 prompt，保留无 ChatMemory/MemoryId/tools 约束。

- [X] T020 在 `src/main/java/com/chh/autosense/service/knowledge/UserAiServiceCache.java` 实现唯一 Cache<Long,UserAiServiceCache.UserAiServices> 及 AcceptedConversationInitializer：cache.get(authenticatedUserId, ignored -> createServices()) 原子构建，容量/访问过期来自配置；同用户切换类型复用完整三代理，禁止复合键/类型子 Map/额外实例缓存，不保存历史/证据/回调/当前类型，驱逐不关闭共享依赖。

- [X] T021 核对 `src/main/java/com/chh/autosense/core/session/AcceptedConversationInitializer.java`、`src/main/java/com/chh/autosense/core/session/SessionOrchestrator.java` 已有接纳和 Guard 时序，让 T020 受管协作者在路由/续走前执行；缓存命中不改变每轮认证与归属检查。移除 `src/main/java/com/chh/autosense/config/KnowledgeConfig.java` 中已合并职责和冗余 Bean（无剩余职责则删除该文件），修复已删知识服务的剩余引用，保留未装配 003 时的可选行为及原有失败清理；在 `src/main/java/com/chh/autosense/core/session/RepairExecutionRunner.java` 将唯一旧诊断消费者迁移至既有 mapper，使用 `src/test/java/com/chh/autosense/unit/RepairKnowledgeCompatibilityTest.java` 保持失败后人工引导/售后行为。

- [X] T022 更新并执行 `src/test/java/com/chh/autosense/unit/AiServiceAssemblyTest.java` 与 T003–T007 测试，验证缓存直接调用专用工厂真实装配三代理、两个分析方法均零检索、创建零模型调用、同 store/model、真实资源内容和诊断兼容；增加同用户同 enhanced 代理并发灯/空调的 Filter/evidence/sources 隔离断言至 `src/test/java/com/chh/autosense/unit/KnowledgeRagAssemblyTest.java`，完成编译与基础行为回归后才解锁故事阶段。

**Checkpoint**: 标准 store 已完整共享；工厂/单用户缓存/接纳协作者可编译且基础测试通过。真实与 mock 均具备完整三代理，增强能力未接入业务分支也不能是占位空代理；不存在旧知识类型的悬空引用。

## Phase 3: User Story 1 — 咨询 IoT 与设备常识 (P1)

**Goal**: 未绑定设备的已登录用户可获得常识流式回答，并在自己的会话内追问。

**Independent Test**: 通过 sessions API 提问“色温和亮度有什么区别？”并追问，核对本人历史和持久化结果；范围分析、query embedding/search、设备读写均为零，跨用户会话访问拒绝。启动导入不计作对话检索；首次接纳仍须创建完整三代理。

### Tests

- [X] T023 [P] [US1] 在 `src/test/java/com/chh/autosense/unit/LangChain4jDirectAnswererTest.java` 添加复用缓存代理的知识直答测试：不逐轮新建、不调用范围分析/检索，TokenStream 正常/创建失败/接收失败/回调失败的 CompletionStage 和英文脱敏日志一致，异步 MDC 安装与清理可验证。

- [X] T024 [P] [US1] 新增 `src/test/java/com/chh/autosense/contract/KnowledgeCommonSenseContractTest.java`，以已登录未绑定用户通过现有 sessions API 验证常识及追问、本人本会话可见历史、跨用户拒绝，零 query embedding/search/设备读写，保持五类 SSE 和公共终态契约；模型与外部依赖使用本地桩。

### Implementation and verification

- [X] T025 [US1] 扩充 `src/main/java/com/chh/autosense/core/routing/DirectAnswerer.java`、`src/main/java/com/chh/autosense/core/routing/LangChain4jDirectAnswerer.java` 的知识回答入口以传递服务端认证用户/直答上下文，内部从 T020 获取 direct.answerKnowledge；保留原接口消费者兼容，复用流式拼接/错误映射/MDC，知识请求不得直接绕过公共缓存调用专用工厂。

- [X] T026 [US1] 调整 `src/main/java/com/chh/autosense/core/routing/MockDirectAnswerer.java` 的知识入口使其同样调用 T020 缓存的 mock DirectAnswerService 并复用流式契约；保留原公共 mock 行为，不新建第二套用户缓存或历史状态。

- [X] T027 [US1] 新增 `src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 实现 AssistantCapabilityHandler 并注册 KNOWLEDGE：requiresKnowledgeBase=false 时直接以 COMMON_SENSE 调用 T025/T026，消费现有 CapabilityRequest/history 并返回 CapabilityResult；沿用公共 finish/Guard，不要求绑定设备、不调用分析/检索/设备，不将尚未实现的 true 分支伪装常识成功。

- [X] T028 [US1] 通过 `src/test/java/com/chh/autosense/contract/KnowledgeCommonSenseContractTest.java` 和新增 `src/test/java/com/chh/autosense/integration/KnowledgeConversationIT.java` 验证常识/追问的 token 聚合、GET 历史、ConclusionDto.summary 一致、成功仅在持久化后通知、过期拒绝迟到提交及证据不进入可见用户历史；执行 T023/T024，集成测试按项目 -Pit 环境运行并记录实际结果至 `specs/003-iot-knowledge-assistant/validation.md`。

**Checkpoint / MVP**: Phase 1–3 可独立演示常识咨询与追问。此时不宣称非常识分支或整个 feature 完成；完整 feature 仍需 US2 和最终验证。

## Phase 4: User Story 2 — 获得有依据的型号与使用说明 (P1)

**Goal**: 类型明确时经内嵌 RAG 回答，缺型号/版本/环境仍可使用同类型其他型号资料，明确缺口和实际依据；无/低分转直接回答，未知类型跳过检索，技术失败保持失败语义。

**Independent Test**: 在隔离资料中准备同类型多个型号和其他类型高分资料，经 sessions API 分别验证同型号、缺型号/版本/环境、跨型号、多型号、未知类型、无结果、全低分、门槛边界、冲突与故障。每个可检索轮次恰好一次 query embedding/search，Filter 只含本轮类型；跨型号声明和来源可追溯，增强不足时答案模型零调用，所有场景零设备操作。该验收不依赖 004/005 实现，可用公共分派边界桩。

### Tests

- [X] T029 [P] [US2] 新增 `src/test/java/com/chh/autosense/unit/KnowledgeCapabilityHandlerTest.java` 的分支与来源测试：true 时分析一次，未知类型零查询，缺型号/版本/环境仍检索，同类型其他型号合法，服务器声明缺口/全部实际型号，跨来源参数/条件一致、冲突可解释；覆盖无/低分回退、技术失败不回退、非法输出/伪造来源不发未校验答案、超时不续调。

- [X] T030 [P] [US2] 新增 `src/test/java/com/chh/autosense/contract/KnowledgeRetrievalContractTest.java`，从公共 sessions API 覆盖 FR-003 的跨型号/多型号说明、FR-005 回退与错误区分、FR-010 仅设备类型筛选；核对服务调用次数、无额外 awaiting、来源可追溯及零设备实时读取/下发/绑定，捕获模型请求证明使用本轮资料而非历史或别的请求。

- [X] T031 [P] [US2] 扩充 `src/test/java/com/chh/autosense/integration/KnowledgeConversationIT.java`，覆盖同用户两会话并发不同类型复用同一三代理、跨用户新代理共用 store、来源声明/尾注随本轮消息持久化与查询、历史/回调/MDC 隔离、取消/超时/Guard 失败不提交迟到成功；计数证明后续咨询不再次启动导入。

### Implementation and verification

- [X] T032 [US2] 在 `src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 接入缓存 analysis.analyzeKnowledge：使用本轮问题/可见历史/只读 catalog，校验输出与类型别名，不使用设备/诊断白名单；保留明确型号和条件、服务器补齐未提供型号的 MODEL 缺口，缺型号/版本/环境继续处理，类型未知进入 TYPE_UNKNOWN 直答，结构非法走公共错误。

- [X] T033 [US2] 在 `src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 构造 KnowledgeAnswerRequest 并调用当前用户 enhanced.answerKnowledge；绝对截止沿用 CapabilityRequest，真实检索只发生在代理内部。只捕获 KnowledgeInsufficientException 转同用户 direct 的 NO_MATCH/LOW_RELEVANCE，其他异常按现有 AiFailureMapping/公共机制失败，不重复检索、不因型号不同丢弃证据。

- [X] T034 [US2] 在 `src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 用私有方法校验 Result<KnowledgeAnswer>：非空合法状态/answer、非空且不重复的 sourceIds 必须属于本轮达标 Result.sources，复核来源完整性/类型，合法跨型号引用通过；CONFLICT 必须明确冲突且有实际引用，非法结构/来源在任何增强 token 发布前失败，不从全局清单或缓存补证据。

- [X] T035 [US2] 在 `src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 完成服务器展示：按 scope 缺口、未知型号或引用 productKey 不同/无法确认一致生成“没有当前型号设备信息，本次回答依据型号：{实际型号列表}”，型号仅从有效 sourceIds 的 metadata 去重派生；多型号逐项关联内容/来源，生成安全 Markdown 来源尾注 sourceName/sourceId/documentHash。TYPE_UNKNOWN/NO_MATCH/LOW_RELEVANCE 添加准确直答前缀，不把未知类型说成已查无资料。

- [X] T036 [US2] 在 `src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 完成同步增强调用到现有 CompletionStage/SSE 的适配：仅校验完成后分块发布完整声明/回答/尾注，直答保持真实 TokenStream；范围分析前、检索前、模型前和提交前沿用剩余预算/Guard，超时不启动后继模型、不发布迟到成功，不把同步增强描述为模型 token 流。

- [X] T037 [US2] 执行并修复 `src/test/java/com/chh/autosense/unit/KnowledgeCapabilityHandlerTest.java`、`src/test/java/com/chh/autosense/contract/KnowledgeRetrievalContractTest.java`、`src/test/java/com/chh/autosense/unit/KnowledgeRagAssemblyTest.java` 的失败场景，补齐受控回答参数/使用条件与多型号依据的实际模型请求断言；验证普通无缺口同型号回答无需跨型号声明、恰好阈值可引用、低分不入 evidence、材料指令不能触发工具或二次模板插值。

- [X] T038 [US2] 运行 `src/test/java/com/chh/autosense/integration/KnowledgeConversationIT.java` 的 US2 矩阵，验证最终 token 拼接、数据库回答、GET 及 ConclusionDto.summary 全部一致且来源关联本轮 sessionId/round，保留错误与冲突不同终态、零设备操作；将跨型号、并发隔离及单次检索实测结果记入 `specs/003-iot-knowledge-assistant/validation.md`，不可把普通 verify 代替 -Pit 结果。

**Checkpoint**: 同类型跨型号资料可正常回答并声明，不再以补型号作为前置澄清；同用户同 enhanced 代理的并发不同类型查询无交叉证据、来源或历史。

## Phase 5: Polish — 回归、运行验收与交付记录

**Goal**: 核实日志、安全边界、可执行 JAR 和公共能力回归，用本方案实测结果交付。

- [X] T039 [P] 在 `src/main/java/com/chh/autosense/ai/rag/KnowledgeEmbeddingStore.java`、`src/main/java/com/chh/autosense/ai/rag/KnowledgeQueryRouter.java`、`src/main/java/com/chh/autosense/config/KnowledgeEmbeddingConfig.java`、`src/main/java/com/chh/autosense/service/knowledge/UserAiServiceCache.java`、`src/main/java/com/chh/autosense/core/routing/KnowledgeCapabilityHandler.java` 收敛关键日志，并扩充 `src/test/java/com/chh/autosense/unit/AiCallLogTest.java`、`src/test/java/com/chh/autosense/unit/LoggingInfrastructureTest.java`：用现有 SLF4J/Log4j 2、AiCallLog/LogSanitizer/LogContextUtils 区分 indexLoad/queryAnalysis/queryEmbedding/retrieve/enhancedAnswer/directAnswer/cacheCreate 的安全原因/耗时；启动无会话字段、对话维持 [] 和逗号、异步清理，不记录正文/密钥，预期回退无 ERROR 堆栈。

- [X] T040 [P] 扩充并运行 `src/test/java/com/chh/autosense/contract/AssistantRoutingContractTest.java`、`src/test/java/com/chh/autosense/unit/AiServiceAssemblyTest.java` 的兼容回归：公开型号知识归 003、本人实际设备参数归 004、诊断归 001、控制归 005，路由不确定常识时 requiresKnowledgeBase=true，真正意图不明仍公共澄清；原诊断 analyze 保持无 RAG，不因服务工厂改名改变行为。

- [ ] T041 按 `specs/003-iot-knowledge-assistant/quickstart.md` 用 `.\mvnw.cmd` 执行普通 verify，并准备隔离 MySQL/Redis/Docker/模拟器环境运行 verify -Pit；复用 `src/test/java/com/chh/autosense/integration/AbstractIntegrationIT.java` 的显式 mock embedding，完成现有会话/身份/设备/诊断及新增知识回归，实际命令/通过失败/环境阻塞分别记录到 `specs/003-iot-knowledge-assistant/validation.md`，不以历史 156 项结果顶替。

- [X] T042 按 `specs/003-iot-knowledge-assistant/quickstart.md` 对 T041 生成的可执行 JAR 做实际启动、查询和重启验证；在隔离测试资源/构建中注入缺文件、坏解析、embedding/写入失败和启动超时，证明 JAR 资源流加载、来源稳定、失败不就绪/不接收业务及修复后恢复；生产 `src/main/resources/document/light/MI-MJDPL01YL/general.md`、`src/main/resources/document/light/MI-MJDPL01YL/troubleshot.md` 不用于破坏性夹具，证据记录到 `specs/003-iot-knowledge-assistant/validation.md`。

- [X] T043 复核 `src/main/java/com/chh/autosense/ai/rag/KnowledgeEmbeddingStore.java`、`src/main/java/com/chh/autosense/ai/rag/KnowledgeQueryRouter.java`、`src/main/java/com/chh/autosense/ai/factory/EnhancedAnswerFactory.java`、`src/main/java/com/chh/autosense/service/knowledge/UserAiServiceCache.java` 并扩充 `src/test/java/com/chh/autosense/unit/KnowledgeRagAssemblyTest.java` 的本地标准 EmbeddingStore 替身验证：下游只依赖 SDK 接口、仅两份 ai/rag 核心文件、单 userId 缓存且无运行期导入/类型缓存/共享请求状态/外置 augment；不新增外部库占位实现，不宣称已支持外部数据库。

- [X] T044 根据实际结果更新 `specs/003-iot-knowledge-assistant/validation.md`、`specs/003-iot-knowledge-assistant/quickstart.md`、`specs/003-iot-knowledge-assistant/tasks.md`、`specs/003-iot-knowledge-assistant/spec.md` 的完成状态及 FR/SC 对照，执行 git diff --check，记录 JAR/-Pit/真实模型质量校准未执行项与原因；保留历史清单和验证记录，不代替评审者更改 `specs/003-iot-knowledge-assistant/checklists/knowledge.md`、`specs/003-iot-knowledge-assistant/checklists/self-review.md` 的勾选，不提前宣称 feature 验收完成。

## Dependencies & Execution Order

阶段依赖：

```text
Phase 1 (T001–T002)
    → Phase 2 (T003–T022)
    → US1 (T023–T028)
    → US2 (T029–T038)
    → Phase 5 (T039–T044)
```

两个用户故事均为 P1；US2 复用 US1 的 handler、直答回退入口与会话集成夹具，所以实施上在 US1 之后。US1 的业务验收不依赖 US2 接入；底层 RAG 在 Foundation 提前完成是完整用户组合的要求。

基础阶段的明确执行关系：

| 任务 | 前置及原因 |
| --- | --- |
| T003–T007 | T002；不同测试文件可并行，先描述失败行为，尚未具备生产类型时允许处于预期失败状态 |
| T008 → T009 → T010 → T011 | 先固定配置/共享模型，再解析和完整发布索引 |
| T012 → T013 → T014 | 类型先于 AI 接口/资源，再补编码与资源校验 |
| T015 | T011–T014；在稳定请求与来源契约上实现检索路由 |
| T016 → T017 → T018 → T019 | 先创建两种增强接口代理并保留原分析消费者，再实现 mock 与缓存内的完整组合创建 |
| T020 → T021 → T022 | 专用工厂及组合定义先于缓存接入，再接纳装配/清理旧引用，最后通过基础行为测试 |
| T023/T024 → T025 → T026 → T027 → T028 | US1 先测试，再直答接入、handler 和独立会话验收 |
| T029/T030/T031 → T032 → T033 → T034 → T035 → T036 → T037 → T038 | US2 先测试，再解析、增强调用、来源校验、声明、发布和验收；handler 编辑逐项串行 |
| T039/T040 → T041 → T042 → T043 → T044 | 独立日志/路由回归后完整验证，JAR 验收与最终结构核查后收敛交付；T043 若改代码则重跑受影响检查 |

阶段内未标并行的任务按编号串行处理。T010/T011、T032–T036 分别编辑同一文件，不能同时执行。T021 之前允许旧配置与新类处于迁移中；T022 必须清除悬空引用，不能以临时禁用模块通过检查。

## Parallel Opportunities

只有以下 12 项标注 [P]；并行是可选执行方式，不是实施授权或额外代理要求。

| 组 | 共同前置 | 可以并行的任务 |
| --- | --- | --- |
| 基础行为测试 | T001–T002 | T003 / T004 / T005 / T006 / T007，分别修改五个不同测试文件 |
| US1 示例 | Phase 2 完成 | T023 流式适配测试与 T024 常识 API 契约测试 |
| US2 示例 | US1 完成 | T029 handler 单元测试、T030 检索 API 契约测试、T031 会话集成测试 |
| 收尾 | US2 完成 | T039 日志实现/测试与 T040 公共路由/工厂兼容测试 |

例如，US1 可同时编写 LangChain4jDirectAnswererTest 和 KnowledgeCommonSenseContractTest，合并测试后按顺序实现直答与 handler。US2 可同时编写单元、API、集成场景，生产 handler 的 T032–T036 始终串行。

## Requirement Coverage

| 要求 | 主要任务 | 验收落点 |
| --- | --- | --- |
| FR-001 | T023–T028、T032–T038 | 未绑定咨询、本人追问、型号/使用回答 |
| FR-002 | T006、T024、T027、T029–T033、T040 | 常识零查询、非常识先检索、路由不确定时 true |
| FR-003 | T012–T016、T029–T037 | 缺型号/版本/环境不先追问；同类型跨型号、多型号依据与参数条件一致 |
| FR-004 | T029–T031、T034–T038 | 本轮真实 sources、来源安全尾注及保存 |
| FR-005 | T006、T015、T029–T038 | NO_MATCH/LOW_RELEVANCE、达标边界、CONFLICT 与技术错误区别 |
| FR-006/007 | T013、T019、T024、T030、T037、T040 | 无设备 tools/读写/绑定，查询/诊断/控制交公共分流 |
| FR-008 | T005、T007、T019–T022、T025–T028、T031、T038 | 专用工厂直建三代理、唯一 userId 缓存、认证、历史/SSE/来源 |
| FR-009/011 | T004、T009–T011、T022、T031、T042 | 文件导入一次、所有代理共享、零 SQL 知识加载、重启可追溯 |
| FR-010 | T006、T015–T016、T022、T029–T037 | 仅设备类型 filter、未知类型零查询、同型号不作硬过滤 |
| FR-012 | T008、T043 | 标准 SDK store 依赖边界；外部库实现不属于本期 |
| FR-013 | T004、T008–T011、T039、T042 | 完整才就绪、故障/超时不发布、安全英文日志 |
| SC-001 | T024、T029–T038 | 所有回答分支及信息缺口/跨型号矩阵 |
| SC-002 | T029–T030、T034–T038 | 来源、型号参数、使用条件与事实样例 |
| SC-003 | T024、T030、T038、T040 | 零设备操作 |
| SC-004 | T024、T028、T031、T038 | 未绑定咨询、保存/查询与跨用户隔离 |
| SC-005 | T006、T024、T029–T033、T037 | 零查询分支、单次类型检索及不足回退 |
| SC-006/007 | T004、T010–T011、T022、T042 | 启动/重启、来源一致、失败不就绪与修复恢复 |
| SC-008 | T005、T007、T019–T022、T031 | 完整三代理、单用户键、并发/TTL/容量/驱逐/类型切换 |

## Existing Work Migration

此表的“旧 T”仅指归档清单，不代表新任务已经完成。

| 可复用内容 / 历史任务 | 本轮对应 | 处理 |
| --- | --- | --- |
| 旧 T001–T002：依赖与夹具 | T001–T002 | 保留已加入依赖，扩展数据和计数，不重新初始化工程 |
| 旧 T003–T010：基础测试/配置/分层导入 | T003–T004、T008–T011 | 复用规则和测试，导入职责收敛到一个 ai/rag 文件，移除 snapshot/segmentId 设计 |
| 旧 T011–T014：分散工厂、服务对缓存、接纳 | T005、T007、T012–T022 | 专用工厂直接创建三代理、唯一 userId 缓存、复用接纳时机及诊断契约 |
| 旧 T015–T047：未完成的知识接入与验收 | T006、T012–T018、T023–T044 | 按新内嵌 RAG 与跨型号行为重新拆分，不按旧类路径继续实施 |
| 旧验证 156 项通过 | T022、T028、T037–T044 | 保留历史证据，对当前代码重新执行实际检查 |

## Implementation Strategy

1. 完成 Setup 和 Foundational，先修复当前装配引用，再确保完整共享 store、专用工厂创建三代理、唯一用户缓存和真实 SDK RAG 可用。
2. 交付 MVP：Phase 1–3，即未绑定用户常识咨询与追问。验证 US1 后再接入 US2；MVP 不等于整个 feature 已完成。
3. 完成 US2 的缺口/跨型号/回退/来源链路并通过独立场景；不用真实模型费用作为默认测试前提。
4. 完成普通 verify、项目集成回归、实际 JAR 验收与文档收敛。有环境阻塞时准确记录未执行项，不能勾选对应验收已完成，也不以 mock 成功声称真实模型 0.75 语义质量已校准。

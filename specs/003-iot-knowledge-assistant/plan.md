# Implementation Plan: IoT 与设备知识咨询

**Branch**: `003-iot-knowledge-assistant` | **Date**: 2026-09-11 | **Spec**: [spec.md](spec.md)

**Input**: 2026-09-11 FR-003/FR-008 修订：信息不足时使用同类型另一型号资料并声明依据；移除统一工厂，由各专用工厂直接创建服务，缓存键仅 userId。保留 ai/rag 最少文件、启动一次共享索引及 EnhancedAnswerFactory 内嵌 Advanced RAG。

## Summary

知识导入直接产出 Spring 单例 EmbeddingStore<TextSegment>，本期实现为 InMemoryEmbeddingStore。每应用实例启动完整导入一次，之后所有 AI Service 共用且只查询；“静态”指固定共享生命周期，不增加可变 public static 字段。

UserAiServiceCache 直接注入 DirectAnswerServiceFactory 与 EnhancedAnswerFactory，未命中时分别调用它们的服务创建方法，原子构建直答、无 RAG 分析、有 RAG 增强回答三个代理；不设置统一 AiServiceFactory 中间层。单个 Cache<Long, UserAiServices> 只按认证后的 userId 保存完整组合，首次成功接纳且缓存未命中时创建，同用户不同会话或设备类型均复用，不同用户使用不同代理。

缓存直接调用 EnhancedAnswerFactory.enhancedAnswerService() 装配内嵌 Advanced RAG。Retriever 使用 dynamicFilter 从本次服务器编码的请求读取设备类型，每次创建独立类型等值过滤条件；不保存可变类型、不创建类型缓存，所有代理注入同一 store/model。

常识不检索，类型未知直答；类型明确但缺型号/版本/环境信息仍检索，有可用同类型另一型号资料时据此回答，声明没有当前型号设备信息及实际依据型号。无结果/全部低分在增强模型调用前转该用户直答代理。型号、品牌、资料类别只作证据说明，不作为筛选条件。

## Technical Context

**Language/Version**: Java 21；既有 Vue 3/TypeScript 前端无需变更。
**Primary Dependencies**: Spring Boot 3.5.3、LangChain4j 1.0.1、community 1.0.1-beta6、MyBatis-Flex、Caffeine（Boot 管理）、SLF4J/Log4j 2；复用现有依赖，不引入 easy-rag 或外部向量库依赖。
**Storage**: Markdown 为知识来源，InMemoryEmbeddingStore 为进程内派生索引；MySQL/Redis 继续承担已有持久化、身份和会话职责。无新表或迁移。
**Testing**: JUnit 5、AssertJ、Mockito、WireMock、确定性 EmbeddingModel；复用已有测试夹具，验证真实 AiServices 装配与可执行 JAR 资源加载。
**Target Platform**: Windows/Linux Java 21 Spring Boot 可执行 JAR；每应用实例一份索引。
**Project Type**: 同仓库 Web 应用，本次仅修订后端设计。
**Performance Goals**: 每实例启动完整导入一次；常识/类型未知零查询；可检索轮次最多一次 query embedding/search；缓存命中零代理重建；单缓存最多 1000 个用户组合，访问后 30m 过期。topK=4、门槛初始 0.75，真实模型质量待校准。
**Constraints**: 索引完整可用后服务才能启动成功；不装配 ChatMemory 或设备工具；代理不能保存请求历史、证据、回调。沿用 AssistantProperties 的实际绝对截止，当前 application.yaml 默认 300 秒，不改回旧计划的 120 秒，也不延长截止。
**Scale/Scope**: 初期复用 light/MI-MJDPL01YL 两份 Markdown；保留已有配置上限：32 MiB、10000 段、启动预算 300 秒、分段 1000/150 字符。排除热更新、在线搜索、上传后台、外部数据库实现及设备操作。

## Constitution Check

依据 [constitution.md](../../.specify/memory/constitution.md) v2.3.0。

| Gate | 研究前 | 设计后 |
| --- | --- | --- |
| Java/Boot、配置外部化 | 通过 | 固定版本；复用 ConfigurationProperties；独立 embedding 配置与凭据环境注入 |
| 分层与依赖注入 | 需说明新目录 | 用户明确要求 ai/rag 承担 RAG 基础设施；通用属性仍在 config，AI Service 工厂仍在 ai/factory；见偏离说明 |
| 数据与缓存 | 已授权有限偏离 | Caffeine 单缓存仅按 userId 保存服务组合，容量与过期明确；不替代 Redis 业务缓存 |
| LangChain4j/提示词 | 通过 | 官方 Parser/Splitter/Ingestor/Retriever/Augmentor/AiServices；固定规则 @SystemMessage(fromResource)，用户模板 @UserMessage(fromResource) |
| 安全、来源与日志 | 通过 | 无设备工具；资料仅为数据；来源在发布前校验并生成跨型号依据声明；英文脱敏日志与现有 MDC 格式 |
| 简单性与复用 | 通过 | ai/rag 目标仅两个核心文件；不重建 loader/splitter/snapshot/retrieval service 等包装层 |
| 类型和测试 | 通过 | 不可变输出/DTO 优先 record，业务枚举与 AI 枚举分开；复用并调整现有行为测试 |

**Gate result**: Phase 0 与 Phase 1 复核通过。用户已授权的两项窄范围选型差异在 Complexity Tracking 中记录。本轮不修改章程或执行实现。

## Project Structure

### Documentation (this feature)

```text
specs/003-iot-knowledge-assistant/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── knowledge-documents.md
│   └── knowledge-runtime.md
├── tasks.md                 # 2026-09-11 同步后的 44 项有效实施任务
├── tasks-before-20260911-sync.md # 旧任务及原勾选归档，仅供对照
└── validation.md            # 2026-09-10 历史验证，不代表当前工作区
```

### Source Code (目标布局)

```text
src/main/java/com/chh/autosense/
├── ai/
│   ├── DirectAnswerService.java             # 复用，增加知识直答上下文
│   ├── EnhancedAnswerService.java           # 保留 analyze，增加知识分析/增强回答
│   ├── factory/
│   │   ├── DirectAnswerServiceFactory.java  # 复用
│   │   ├── EnhancedAnswerFactory.java       # 从现 EnhancedAnswerServiceFactory 迁移
│   │   └── MockKnowledgeAiServicesFactory.java # 复用本地模式
│   ├── rag/
│   │   ├── KnowledgeEmbeddingStore.java     # 启动导入并提供标准 store Bean
│   │   └── KnowledgeQueryRouter.java        # 一次检索、阈值判断、只读结果回放
│   └── model/                              # KnowledgeQueryAnalysis/KnowledgeAnswer，AI enum
├── config/                                 # 复用 KnowledgeProperties/EmbeddingProperties/EmbeddingConfig
├── service/knowledge/
│   └── UserAiServiceCache.java              # 单个按 userId 的缓存，兼任接纳后初始化协作者
├── core/routing/
│   └── KnowledgeCapabilityHandler.java      # 分支、调用、来源校验，复用公共收尾
├── exception/                              # 复用初始化异常；增加资料不足异常
├── domain/dto/                             # 最少的知识输入 record
└── utils/                                  # 扩充 PromptInputEncoder；复用日志与资源校验
src/main/resources/
├── prompt/                                 # 资源化分析、直答、增强回答规则
└── document/{deviceType}/{brand-model}/
    ├── general.md
    └── troubleshot.md
```

**Structure Decision**: 知识导入/RAG 技术组件集中在 ai/rag；工厂承担 AI Service 与 Augmentor 组合装配。KnowledgeEmbeddingStore 内用私有方法或局部 SDK 适配完成资源、metadata、校验；不为每一步建独立文件。用户代理组合用 UserAiServiceCache 内的公开嵌套 UserAiServices record，缓存自己保存；来源校验先留在能力入口的私有方法。不新增自定义向量仓库接口、索引快照服务、知识查询服务、增强器工厂或外部存储适配器占位类。

## Phase 0 — Research decisions

[research.md](research.md) 已确认 1.0.1 的 API 和调用顺序：RAG 在聊天调用前执行，Router 异常可由调用方捕获，`Result<KnowledgeAnswer>.sources()` 提供本次真实检索来源；结构化输出指令在 RAG 后追加。查询从本轮受控 JSON 输入中提取，不使用此版本不存在的 invocationParameters，也不借用 ThreadLocal/MemoryId 传范围。新增研究确认 1.0.1 支持 dynamicFilter(Function<Query, Filter>)；SDK 先向量化再计算过滤器，故必须在 Router 调用 Retriever 前验证类型和截止。

## Phase 1 — Index design

1. 非 lazy 的 store Bean 在应用启动时读取 classpath document 资源；Spring Resource 输入流接入 LangChain4j DocumentLoader/TextDocumentParser，按目录生成 metadata。
2. 使用 DocumentSplitters.recursive 和 EmbeddingStoreIngestor，在局部 `new InMemoryEmbeddingStore<TextSegment>()` 中导入。复用现有字节/分段/编码/维度及超时校验，适配器作为私有或嵌套实现，避免再次拆成多个服务。
3. 导入完成才从 Bean 方法返回标准 `EmbeddingStore<TextSegment>`；失败中止启动，不返回部分索引，不使用 ApplicationReadyEvent 后补建。所有工厂构造器注入同一 Bean，运行期不调用 add/remove/ingest。
4. 共享可配置 EmbeddingModel 同时用于导入和查询。原配置里的向量日志/校验与显式 mock 支持保留为小型嵌套装饰器，不依赖已删除的 service/knowledge 类。
5. 未来只替换标准 store 的 Bean 提供方式，外部模式不扫描文档、不批量 embedding、不重建索引；本期不实现该模式。

详情见 [文档与导入契约](contracts/knowledge-documents.md)。

## Phase 1 — Answer and cache design

1. **直接调用专用工厂**：UserAiServiceCache 实现 AcceptedConversationInitializer，在成功接纳、Guard 建立后执行 cache.get(authUser.userId, ignored -> createServices())。私有 createServices 直接调用 DirectAnswerServiceFactory.directAnswerService()、EnhancedAnswerFactory.problemAnalysisService() 和 enhancedAnswerService()，完整成功后返回组合。创建仅组装代理/校验资源，零检索、零模型请求；其他已有调用方可在需要时直接使用对应工厂，无统一工厂依赖。
2. **唯一用户缓存**：一个 Cache<Long, UserAiServices>，maximumSize=1000、expireAfterAccess=30m；不增加复合键、每类型子 Map 或第二个实例缓存。同用户切换设备类型仍取同一三个代理；不同用户代理独立，共享只读 store/model。驱逐不关闭共享依赖，旧调用可持有旧组合完成，后续未命中再新建。
3. **分流/理解**：常识直答；非常识用 analysis 解析 queryText、deviceType、型号/条件及 missingInformation。类型未知直答；缺型号/版本/环境不再等待补齐，而是保留信息缺口后检索。问题意图不明仍由公共路由澄清。原诊断 analyze 保留无 RAG 方法契约。
4. **请求类型隔离**：EnhancedAnswerFactory.enhancedAnswerService() 不接收类型。QueryTransformer 从受控 JSON 提取 queryText 并保留原 metadata；Retriever.dynamicFilter 每次从原请求读取规范 deviceType 并新建唯一类型等值条件。没有用户当前类型字段、ThreadLocal 或 MemoryId。Router 在调用 Retriever 前校验类型与绝对截止。
5. **单次检索/回退**：Router 一次检索、校验类型/来源/分数，达标结果以本次不可变回放 Retriever 返回；无候选/全部低分抛资料不足异常，入口调用同用户 direct。技术故障继续失败，型号不同不剔除相关证据。
6. **跨型号回答**：允许使用同类型其他型号的真实参数与条件。服务端根据输入缺口和实际引用 metadata 派生是否需声明及依据型号列表；补充“没有当前型号设备信息”和真实依据型号，不依赖模型自由填写型号名称。多型号依据逐项注明，参数必须与各自资料一致。
7. **来源/输出**：Result.sources 是本轮引用基准；合法跨型号引用可正常通过。校验后生成声明与来源尾注，由既有 SSE/finish 保存完整回答；原始证据不作为可见历史。只有无相关资料/全部低分触发检索不足直答，缺当前型号信息本身不触发拒答。

完整分支与方法见 [运行契约](contracts/knowledge-runtime.md)，字段和状态见 [data-model.md](data-model.md)。

## Current code reuse and migration

**实施状态（2026-09-11）**：Phase 1/2（T001–T022）已完成，verify 通过 168 项测试。下表与随后文字保留规划时的迁移基线；当前已删除悬空配置、迁移工厂并完成基础 RAG。MockKnowledgeAiServicesFactory 提供本地模型 Bean，real/mock 使用相同专用工厂直接装配，无另一份缓存。RepairExecutionRunner 的唯一旧诊断读取已迁移到既有 mapper，维持原人工引导行为。详见 [validation.md](validation.md)。

规划时只读核对发现：工作区已有配置、Caffeine、资源、接纳后协作者与测试；EnhancedAnswerService 已从旧 ProblemAnalysisService 改名，但目前只有诊断 analyze 方法。EnhancedAnswerServiceFactory 当前只创建无 RAG 代理。service/knowledge 实现目录已移除，KnowledgeConfig、KnowledgeEmbeddingConfig、MockKnowledgeAiServicesFactory 仍引用其中类型。因此不能把历史 Phase 1/2 完成记录等同于当前可编译状态。

| 当前内容 | 后续实现处理 |
| --- | --- |
| pom.xml、KnowledgeProperties/KnowledgeEmbeddingProperties、application.yaml | 复用依赖与合法配置；不重写用户/设备/AI 公共配置，不擅改实际 300 秒截止 |
| KnowledgeConfig 与已移除的导入类引用 | 合并导入职责到 ai/rag/KnowledgeEmbeddingStore；缓存自行受管后移除冗余 KnowledgeConfig，修复全部引用 |
| EnhancedAnswerServiceFactory / EnhancedAnswerService | 工厂按用户指定命名迁移为 EnhancedAnswerFactory；保留无 RAG 分析方法，新增不绑定类型的增强生成方法，由 UserAiServiceCache 在缓存未命中时直接调用专用工厂组合 |
| LangChain4jProblemAnalyzer / MockKnowledgeAiServicesFactory | 更新兼容工厂引用，诊断仍用无 RAG 分析代理；本 feature 服务由用户缓存获取，缓存直接调用专用工厂；mock 使用相同工厂创建三代理组合并走真实 RAG |
| AcceptedConversationInitializer / SessionOrchestrator | 复用接纳时机、Guard 和清理逻辑，由缓存实现协作者 |
| 已有知识测试与确定性向量夹具 | 复用行为断言，调整包名/Bean/缓存键；新增共享 store、同用户同代理并发不同类型、跨型号声明与专用工厂直接创建与缓存原子发布验证 |
| RepairKnowledgeService 删除 | 不恢复旧 SQL 知识加载；核对余下消费者并保持已有诊断契约，不能把不存在的旧服务列为现成复用组件 |

## Delivery and validation

[tasks.md](tasks.md) 已于 2026-09-11 同步为 44 项任务：Setup → Foundational（共享导入、内嵌 RAG 装配、专用工厂直建三代理与缓存）→ US1 常识直答 → US2 检索分支、跨型号声明、回退和来源 → 集成/JAR 验证。完整三代理在首次接纳时即创建，因此底层 RAG 装配先于故事接入完成。旧任务描述与原勾选保存在 [tasks-before-20260911-sync.md](tasks-before-20260911-sync.md)，不继续按旧路径实施；当前 T001–T022 已完成，T023–T044 尚未执行。

| 要求 | 验收 |
| --- | --- |
| FR-009/011/013 | 启动一次完整导入；不同用户的代理引用同一 store；重启重建；故障不就绪；零 SQL 知识读取 |
| FR-002/010 | 常识/未知类型零检索；已知类型只使用 deviceType filter，品牌/型号/类别不加入条件 |
| FR-003/004/005 | 缺型号/版本/环境仍检索并依据另一型号回答，说明缺口和依据型号；无结果/低分检索一次且增强模型零调用；故障与冲突区分 |
| FR-006/007/008 | 设备调用为零；公共路由/身份/会话/SSE/截止保持；来源与历史可追溯 |
| FR-008/SC-008、工厂与缓存 | 缓存直接调用专用工厂完整创建三代理，只有 userId 缓存键；跨用户独立；同用户切换类型零重建；TTL/容量/并发隔离 |
| FR-012 | 下游只依赖标准 EmbeddingStore；本期不宣称外部库支持 |

可执行验证步骤见 [quickstart.md](quickstart.md)。规划阶段未运行构建；后续 Phase 1/2 的实际实现与检查结果已记录到 [validation.md](validation.md)，不代表完整 feature 验收通过。

## User requirement traceability

| 2026-09-11 要求 | 落点 |
| --- | --- |
| 导入结果是 EmbeddingStore<TextSegment>，本期 InMemoryEmbeddingStore | Index design 1–3；documents §3 |
| 静态一次导入，所有 AiService 共用 | Spring 单例生命周期；documents §3，无运行期变更入口 |
| ai/rag、复用组件、尽量少文件 | Project Structure：两个核心文件，SDK 适配采用私有方法/嵌套实现 |
| Advanced RAG、EnhancedAnswerFactory 生成 | Answer design 4–6；runtime §3/5 |
| 检索使用导入提供的 store | 同一 Bean 构造器注入 factory |
| 仅设备类型 filter | runtime §5；每请求动态构造，型号/类别作证据说明 |
| FR-003 跨型号依据与声明 | runtime §4/6；data-model §6；SC-001/002 |
| FR-008 专用工厂直建、仅 userId 缓存 | DirectAnswerServiceFactory / EnhancedAnswerFactory + 单缓存；runtime §2；SC-008 |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| 原则 III 的 Redis 条款：服务代理使用 Caffeine | 用户已明确选定；仅进程内无状态代理，单个用户缓存具有容量和 TTL | Redis 无法保存并直接复用 JVM 代理；现有 Redis 会话/身份职责不变 |
| 原目录未列 ai/rag，通用配置通常在 config | 用户本轮明确指定知识导入放 ai/rag；仅领域专属 store 装配与 Router 放此处，属性/模型配置仍在 config | 旧 service/knowledge 多层包装增加文件和跳转；本次按 SDK 管道内聚，不扩展成第二套业务框架 |

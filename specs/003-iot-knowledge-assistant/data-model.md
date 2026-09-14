# Data Model: IoT 与设备知识咨询

**Date**: 2026-09-11 | **Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

本 feature 不新增数据库实体/表。复用 LangChain4j Document、TextSegment、Content、Result，不为这些对象建立同形包装类。业务输入和 AI 输出优先 Java 21 record；业务 DTO 在 domain/dto，AI 输出及其 enum 在 ai/model，业务 enum 在 domain/enums。

## 1. 共享 EmbeddingStore<TextSegment>

一个 Spring 应用上下文创建一个完整 store Bean，本期实际对象为 InMemoryEmbeddingStore<TextSegment>。所有增强回答代理的 Retriever 引用此对象；不同用户/设备类型不复制 store。只在构建时写入，运行期只查询。它是文件的派生索引，不是新增业务实体。

状态：启动 DISCOVERING → VALIDATING → INGESTING → READY；任意失败 → 启动失败。READY 前不向消费者发布 store，无部分 READY 状态。重启创建新对象；缓存失效不影响索引。未来外部 store 使用相同标准接口，但不执行启动全文导入。

## 2. Document / TextSegment metadata

| key | 约束/来源 |
| --- | --- |
| deviceType | 必填规范小写设备类型，来自第一层目录；**唯一检索 filter 字段** |
| brand / model / productKey | 产品目录拆分所得；model 必填；只用于适用性与来源说明 |
| knowledgeKind | GENERAL 或 TROUBLESHOOTING，来自 general.md / troubleshot.md；不用于 filter |
| sourceId | 规范 classpath 相对路径，如 document/light/MI-MJDPL01YL/general.md |
| sourceName | 服务端从路径生成，经过展示安全处理 |
| documentHash | 原始文件字节的 SHA-256，关联回答当时的资料版本 |
| index | SDK 分段的字符串序号 |

Document 承载上述来源和范围字段，TextSegment 继承它们并增加 index。一个 Document 对应多段；段身份直接用 (sourceId, documentHash, index)，不另建 segmentId 字段/类或索引快照实体。向量 ID 可以随机，不能当用户引用。元数据来自可信资源路径，不允许正文/AI 输出覆盖。

分数来自 Content.metadata 的 ContentMetadata.SCORE，不混入文档 metadata。保留 SDK Content 列表作为检索结果和 Result.sources，不另建 KnowledgeRetrievalResult/KnowledgeEvidence 类；序列化提示词时只投影安全字段。

## 3. 缓存对象

唯一缓存是 Cache<Long, UserAiServiceCache.UserAiServices>，由 UserAiServiceCache 持有。键只能是已认证的正数 userId；不包含设备类型、会话或模型名，也没有每类型子缓存。

UserAiServiceCache 内的公开嵌套 record UserAiServices 含三个非空字段：

| 字段 | 类型/用途 |
| --- | --- |
| direct | DirectAnswerService，直接回答 |
| analysis | EnhancedAnswerService，无 RAG，问题分析 |
| enhanced | EnhancedAnswerService，内嵌 RAG，知识回答 |

首次成功接纳且未命中时，UserAiServiceCache 在 cache.get 的映射函数中直接调用 DirectAnswerServiceFactory.directAnswerService()、EnhancedAnswerFactory.problemAnalysisService() 和 enhancedAnswerService()，全部成功后返回完整组合；同用户不同会话与设备类型复用，跨用户三个代理均独立。模型客户端及知识 store 共享，不属于用户私有状态。

maximumSize=1000 表示每应用实例最多缓存 1000 个用户组合（每组合三代理），expireAfterAccess=30m，无主动刷新。ABSENT → 原子创建完整组合 → PRESENT → 过期/移除 → ABSENT；失败仍 ABSENT。在途调用可继续持有已驱逐的旧组合，不关闭共享依赖。

组合不含历史、问题、证据、TokenStream、回调、MDC 或 currentType。filter 根据每请求数据新建；每轮认证和会话归属校验不因命中而跳过。

## 4. KnowledgeQueryAnalysis — AI 范围理解

字段：可空 deviceType、brand、model；非空 queryText；missingInformation（MODEL/BRAND/VERSION/ENVIRONMENT 集合）；已明确的版本/环境条件。字段用不可变 record 和受控 enum，不引入按资料类别过滤的输出。

- 类型仅允许规范值/显式别名；集合来自配置与知识路径，不依赖用户设备或诊断白名单。
- 类型未知按 FR-010 直答。类型已知时，缺型号/版本/环境只成为声明依据，不产生 CLARIFYING 或阻断检索；移除旧 requiresModel/clarifyQuestion 驱动的前置澄清设计。
- brand/model 保留用户明确值，未知型号不替换为已收录型号；queryText 可保留该值，但 metadata filter 不包含它。
- missingInformation 描述回答相关的信息缺口；解析结果经服务端校验。没有用户型号时服务器必定记录 MODEL 缺口，不依赖模型漏填后的默认推断。
- queryText 只表达本轮问题与必要指代，不扩展目标；意图不明仍由公共入口处理，结构非法属于 AI 输出错误。

## 5. KnowledgeAnswerRequest — 本轮服务输入

不可变服务端 DTO：history、text、queryText、scope、deadlineEpochMillis。scope 包含校验后的 deviceType、brand/model、missingInformation 及版本/环境条件。history 只含本人当前会话最近 20 条可见 role/content，不重复本轮 text。

PromptInputEncoder 一次编码为 JSON，AI 方法绑定 @V("request")。该字符串是服务器生成的 envelope，不能直接信任用户提交的 JSON。QueryTransformer 只提取 queryText，保留原 Query.metadata；Router 从原消息取得并预校验截止和 scope.deviceType；dynamicFilter 从同一原消息取得本轮类型，每次生成独立过滤条件。ContentInjector 在原数据中增加只读 evidence 投影并移除内部 deadline 字段。deadline 不进入模型提示词，也不写入向量 metadata。

evidence 的投影字段：text、sourceId、sourceName、documentHash、index、deviceType、brand、model、knowledgeKind、score。全部来自本次达标 Content；不从缓存读取“上次证据”。

## 6. KnowledgeAnswer / Result<KnowledgeAnswer>

AI 输出：非空 answer、去重的非空 sourceIds、status（ANSWERED / CONFLICT）。来源必须是 Result.sources 中的实际达标来源；不能从历史或其他请求补证据。

- ANSWERED 包含同型号回答及 FR-003 的跨型号依据回答。缺当前型号/条件信息不再作为 INSUFFICIENT_SCOPE 拒答状态。
- CONFLICT 明确冲突及相应证据；引用至少能支持所述冲突，同来源内部冲突可使用一个 sourceId。
- 同类型另一型号的参数、条件可直接作为回答依据；不能隐去依据型号、伪称已获取当前型号资料或编造参数。
- 当 scope 存在相关信息缺口、请求型号为空或实际引用的 productKey 与请求型号不同/无法确认一致时，服务器生成当前型号信息缺口与依据型号说明。依据型号列表由有效 sourceIds 对应 metadata 的 brand/model/productKey 去重派生，不增加 AI 生成的型号字段。
- 跨型号声明格式为“没有当前型号设备信息，本次回答依据型号：{实际型号列表}”，随后关联各型号来源与支持内容；混合引用当前/其他型号时说明各自依据范围。该展示文字由服务器组织，模型行为规则仍在 prompt 资源。
- 用户型号未知时只写缺口，不猜测用户拥有的型号；引用了多个型号时均须列出。
- 身份/来源/声明规则在发 token 前校验；不能因型号不同拒绝合法引用。结构校验不证明所有句子事实准确，具体参数与条件仍需要受控资料样例评估。
- 无候选/全部低分由检索阶段转直答，不能以虚构 sourceIds 填满结构。

## 7. 直答原因与异常

知识直答上下文使用 COMMON_SENSE、TYPE_UNKNOWN、NO_MATCH、LOW_RELEVANCE。COMMON_SENSE/TYPE_UNKNOWN 是检索前分支；NO_MATCH/LOW_RELEVANCE 由 ai/rag/KnowledgeQueryRouter 判定，借 exception/KnowledgeInsufficientException 向外传递。

资料不足异常只携带上述安全原因，不携带文档或底层异常。技术失败、非法 metadata/分数、模型输出错误均走公共错误机制，不能映射为资料不足。

## 8. 持久化与业务状态

沿用 ChatMessage、RepairSession、ConclusionDto 和公共 finish；完整回答包含跨型号声明（适用时），尾注包含 sourceName/sourceId/documentHash，与消息 sessionId/round 关联，GET、SSE 聚合文本与 conclusion.summary 一致。不新增 API 字段；内部 query/证据不写入可见历史。

业务状态：ROUTING → DISPATCHING → COMPLETED_ANSWERED；只有公共入口无法理解问题意图等原有情形才进入 CLARIFYING；型号/版本/环境不足继续依据其他型号回答；技术失败 → FAILED_REQUEST；超时沿公共终止机制。冲突或声明依据的跨型号回答均可作为完成回答；知识服务失败不可伪装为完成。

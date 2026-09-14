# Contract: EnhancedAnswerFactory 与内嵌 Advanced RAG

**Date**: 2026-09-11 | **Feature**: [spec.md](../spec.md)

本文件描述目标契约，取代旧的外部 augment + 无 RAG 回答代理方案。

## 1. 公共能力边界

复用 [002 会话 API](../../002-assistant-foundation/contracts/assistant-api.md)，不新增独立知识端点。KnowledgeCapabilityHandler 实现 AssistantCapabilityHandler，消费服务端 CapabilityRequest 和 requiresKnowledgeBase；身份、归属、Guard、历史及 finish 沿用公共入口。

SSE 保持 token、status、awaiting、conclusion、error，ConclusionDto 外形不变。来源通过完整回答和 summary 的服务端尾注展示/保存。003 不读取用户设备实时数据、不执行控制/绑定；设备参数查询 → 004，现场故障诊断 → 001，控制 → 005。公开排故资料不构成工具调用授权。

## 2. 专用工厂与缓存

- 不设置统一 AiServiceFactory 类或 Bean。需要新 AI Service 时直接调用对应专用工厂；用户缓存保存 UserAiServiceCache 内的公开嵌套 record UserAiServices，不增加独立组合工厂或额外 DTO 文件。缓存入口只接受服务端已验证身份，不把 userId 编入模型提示词。
- UserAiServiceCache 直接调用 DirectAnswerServiceFactory.directAnswerService() 创建直答代理；直接调用 EnhancedAnswerFactory.problemAnalysisService() 创建无 RAG 分析代理；直接调用 EnhancedAnswerFactory.enhancedAnswerService() 创建内嵌 RAG 回答代理。EnhancedAnswerFactory 由当前 EnhancedAnswerServiceFactory 迁移，增强生成方法不接受设备类型。
- UserAiServiceCache 只持有 Cache<Long, UserAiServices>，唯一 key 是认证的 userId。成功接纳并建立 Guard 后原子 cache.get(userId, ignored -> createServices())，首次非知识对话也创建完整组合；GET、登录、认证拒绝、SESSION_BUSY 不创建。
- 一个用户一个缓存条目，每条含 direct/analysis/enhanced 三代理；同用户不同会话或不同设备类型使用同一组合，跨用户三个代理均是新实例。没有复合缓存键、按类型子 Map、第二份服务实例缓存或按类型延迟创建。
- 创建只组装对象/检查资源，零检索、零 embedding、零聊天请求。全部成功才缓存；中途失败不发布部分组合，走公共失败收尾，后续请求可重新创建。
- maximumSize=1000 是每实例最多 1000 个用户组合；expireAfterAccess=30m，无 refresh。驱逐不关闭共享模型、store 或在途流；旧调用可完成，后续未命中重新创建。每轮身份/归属/用户状态独立校验。
- 所有代理共用 store/model，但不装配 ChatMemory、MemoryId 或设备 tools，不保存历史、当前类型、证据、回调、MDC、TokenStream。设备类型和过滤条件仅属于单次调用。
- 本 feature 的对话能力从用户缓存获取代理；缓存仅在未命中时直接调用专用工厂，createServices 是缓存内部私有映射方法，不提供新的公共统一工厂。专用工厂只负责装配，不持有用户缓存。原诊断等调用方需要新实例时仍可直接调用对应工厂，诊断 analyze 保持无 RAG 契约；不重写其他 feature 的推理或设备流程。
- mock 也由缓存直接调用同一组专用工厂创建三代理组合，复用 MockKnowledgeAiServicesFactory 的本地模型支持；它不单独持有缓存。mock 增强仍经过真实 SDK RAG，不能用固定答案绕过检索。
- 同一接口的所有方法都会受该代理的 RAG 配置影响；analysis 只能调用 analyze/analyzeKnowledge，enhanced 只能调用 answerKnowledge。测试和调用适配层必须区分两种代理。

## 3. 方法与提示词契约

| 方法 | 返回 | 系统资源 | 用户资源/参数 |
| --- | --- | --- | --- |
| DirectAnswerService.answer | TokenStream | 既有 /prompt/direct-answer.txt | 既有 conversation-input.txt：history/text |
| DirectAnswerService.answerKnowledge | TokenStream | /prompt/knowledge-direct-answer.txt | /prompt/knowledge-direct-input.txt：history/text/answerContext |
| EnhancedAnswerService.analyze | ProblemAnalysis | 既有 /prompt/problem-analysis.txt | 既有 conversation-input.txt：history/text |
| EnhancedAnswerService.analyzeKnowledge | KnowledgeQueryAnalysis | /prompt/knowledge-query-analysis.txt | /prompt/knowledge-query-input.txt：history/text/catalog |
| EnhancedAnswerService.answerKnowledge | Result<KnowledgeAnswer> | /prompt/knowledge-answer.txt | /prompt/knowledge-answer-input.txt：request |

系统规则必须 @SystemMessage(fromResource="/prompt/...")；固定用户模板必须 @UserMessage(fromResource="/prompt/...")；参数显式 @V。系统不含动态变量；用户模板每个参数绑定一次。knowledge-answer-input.txt 只含 {{request}}，使增强前 UserMessage 是可解析的 JSON。

request 为服务端构造的 KnowledgeAnswerRequest，含当前问题、本人可见历史、解析后的 queryText/scope 和内部截止。PromptInputEncoder 复用独立 writer 与花括号转义；不改变全局 Jackson。QueryTransformer 从 JSON 提取 queryText 创建单一 Query，并保留原 metadata。不得把整个历史/JSON 模板作为检索文本。

ContentInjector 校验剩余预算，把本次达标 Content 安全投影为 evidence 数组写入原请求 JSON，删除内部截止后返回 UserMessage；此步骤仅组装数据，不拼接固定自然语言，不使用默认 injector 提示词，不再次模板插值。系统资源定义资料不可信、跨型号可作为依据、必须声明缺口与依据型号、引用、冲突及无设备操作规则。

AiServiceValidator 在装配阶段实际检查资源可读、UTF-8、非空、绑定；JSON 用户资源同样校验。新增测试必须运行真实 AiServices 代理并捕获实际模型请求，证明 evidence 注入、当前问题/历史不重复与 SDK 结构化输出兼容，不能只检查注解。资料中的 {{...}}、恶意指令和代码块仍是数据。

## 4. 分支契约

| 条件 | 范围分析 | query embedding/search | 后续 |
| --- | --- | --- | --- |
| requiresKnowledgeBase=false | 0 | 0 | 缓存 DirectAnswerService，常识回答 |
| true、类型未知 | 一次 | 0 | 直答并说明类型不明缺可靠资料；不追问类型、不伪称检索 |
| true、类型明确但缺型号/版本/环境 | 一次 | 一次 | 不先追问；有同类型另一型号相关资料即据此回答并声明缺口及依据型号 |
| true、类型明确且有达标资料 | 一次 | 一次 | 当前用户增强代理，模型在内嵌 RAG 后回答 |
| 无候选 | 一次 | 一次 | NO_MATCH 异常被入口捕获，直接回答；增强答案模型 0 次 |
| 候选全部低分 | 一次 | 一次 | LOW_RELEVANCE，同上，不传低分材料 |
| 当前型号信息缺失而同类型另一型号资料达标 | 一次 | 一次 | 使用实际资料中的参数/条件作答，注明没有当前型号设备信息及实际依据型号，不按型号不同拒绝 |
| 达标资料相互冲突 | 一次 | 一次 | 增强回答明确冲突（CONFLICT），校验来源后完成 |
| embedding/search 失败、非法 metadata/分数/维度 | 一次 | 已尝试，不重复 | 公共失败收尾，不按资料不足调用直答 |
| 分析/答案结构非法或引用越界 | 按已发生调用 | 不补检索 | 公共失败收尾，未校验答案不能展示 |

DirectAnswerService 直答上下文为 COMMON_SENSE、TYPE_UNKNOWN、NO_MATCH、LOW_RELEVANCE。后面三类由服务器在可见答复前追加准确的缺口说明；模型规则同步约束不编造精确参数/来源。TYPE_UNKNOWN 不能描述成“检索未找到”。

技术错误复用 AI_SERVICE_UNAVAILABLE / REQUEST_TIMEOUT / INTERNAL_ERROR；对用户只说明知识服务/助手暂不可用或超时。不得吞掉异常返回空内容。资料不足是正常回退，不记录成未预期 ERROR。

## 5. 工厂内的 Advanced RAG

EnhancedAnswerFactory 构造器注入共享 EmbeddingStore<TextSegment>、EmbeddingModel、ChatModel、配置与编码支持；不加载文档。每次用户缓存未命中、直接调用增强工厂时生成新的增强代理，该代理可服务同用户的多个类型。

1. 增强方法接收服务器构造的 JSON envelope。QueryTransformer 验证 schema、queryText、scope.deviceType 和截止，从配置/目录归一化类型；返回 Query.from(queryText, original.metadata())。用户原文只是 envelope 内的数据，不能覆盖类型/截止字段。
2. 业务入口在类型未知时直接调用 direct，不调用 enhanced。若不合法/缺失类型的 envelope 错误进入增强路径，必须在外部 embedding 前失败；不能返回 null filter 或取消过滤。
3. EmbeddingStoreContentRetriever 配置共享 store/model、maxResults=topK、minScore=0.0，并使用 dynamicFilter(query -> 根据原始 metadata.chatMessage 中 scope.deviceType 创建新 Filter)。唯一条件为 metadataKey("deviceType").isEqualTo(本轮规范类型)，不包含品牌/型号/版本/资料类别。
4. KnowledgeQueryRouter 只持有不可变 Retriever/阈值等依赖。route 先验证本轮类型和绝对截止，再真实 retrieve 一次；SDK 的动态 Filter 在 query embedding 后计算，因此前置验证不能只放在 dynamicFilter 回调。
5. 校验结果 score 有限且在 [0,1]、deviceType 与本轮类型一致、来源 metadata 完整。跨类型或非法元数据属于技术错误；同类型另一型号是合法候选，不能被后处理悄悄剔除。
6. 无候选抛 KnowledgeInsufficientException(NO_MATCH)，有候选但无 score >= configuredMinScore 抛 LOW_RELEVANCE（初始 0.75）；异常不携带文档或底层响应。有达标资料返回捕获本轮不可变 hits 的回放 Retriever，回放零 embedding/search。
7. DefaultRetrievalAugmentor 组合上述单 Query transformer、Router 与数据 ContentInjector，挂入 AiServices.builder(EnhancedAnswerService.class).chatModel(chatModel).retrievalAugmentor(augmentor).build()。不同时设置 contentRetriever、不在能力入口另行 augment。
8. ContentInjector 只将本次 evidence 投影加入原 JSON，移除内部截止后返回 UserMessage；固定指令来自资源，不能用默认 injector 内联模板。注入前检查剩余预算。
9. 资料不足异常在增强模型调用前传回入口，只该异常转同用户 direct；技术失败不回退。结果通过 Result.sources 返回，状态、Filter、证据和声明均为本次局部数据，不使用共享 lastResult/currentType、ThreadLocal 或 MemoryId。

跨用户共享只读知识，跨类型隔离每次查询。同一用户两个会话可同时查询灯和空调，各自 Filter/回放资料只来自本轮 envelope。

## 6. 来源、跨型号声明与保存

增强调用返回 Result<KnowledgeAnswer>，KnowledgeAnswer 含 answer/sourceIds/status（ANSWERED 或 CONFLICT）。来源必须是本次 Result.sources 中的非空子集；不能从全局清单、其他会话或缓存补来源。服务端在发 token 前验证结构和来源。

FR-003 的跨型号回答属于 ANSWERED，不使用 INSUFFICIENT_SCOPE 拒答。模型根据已有同类型另一型号资料回答，可使用该资料中的具体参数与条件；不能伪称这些资料就是当前型号说明书。相关资料的型号不同不能导致检索失败或引用越界。

服务端按以下输入派生声明，过程不需要第二次检索或模型：

- 当前问题包含型号/版本/环境等相关信息缺口，或请求型号未知；或者引用的产品型号与请求型号不同/无法确认一致时，添加“没有当前型号设备信息”说明。
- 依据型号从已通过校验的 sourceIds 对应 metadata 的 brand/model/productKey 派生、去重，不使用 AI 自填的任意型号列表。
- 示例展示格式：没有当前型号设备信息，本次回答依据型号：MI-MJDPL01YL。多型号引用逐项列明实际型号与各自来源；正文的参数/条件也必须说明所属依据。
- 请求型号未知时不猜测用户设备型号。混合引用当前和其他型号时说明每部分依据，不能把多个型号参数混合成当前设备的已核实配置。
- 固定模型行为规则仍在 /prompt/ 资源；服务端生成的声明是最终用户展示文本，不作为内联系统提示词。

缺口声明与来源尾注（sourceName/sourceId/documentHash）由服务端统一组织。来源身份校验不能证明所有自然语言事实，实施验收必须包含跨型号参数、使用条件和多来源冲突样例；对合法引用不同型号的回答不得仅以型号不相等拒绝。

直接回答仍用 TokenStream；增强回答同步校验后发 token。完整 token 文本、保存的 assistant 内容与 ConclusionDto.summary 包含同一声明/尾注，保存成功才发 conclusion。Guard 拒绝迟到成功，原始 query/evidence 不加入可见历史。

## 7. 截止与日志

沿用实际 AssistantProperties 截止（当前 YAML 默认 300 秒），不延长、不每步重置。最长路径：路由 + 分析 + query embedding/search + 一个答案模型。外层检查路由/分析/调用前后预算；请求携带服务器生成的绝对截止，Router 在检索前、Injector 在增强模型前再次检查。模型超时/重试计入配置预算；迟到输出不得提交成功。

数据库保存自身时钟上的权威截止并校验最终提交；接纳时同时在应用时钟上确定同一轮固定执行预算，供 Accepted/CapabilityRequest、Guard 和模型检查使用。不能把数据库无时区 DATETIME 直接当成 JVM 本地时间，否则数据库与应用时区不同时会误判超时。后续阶段不得重新开始预算。

使用现有 AiCallLog、LogSanitizer、LogContextUtils；英文 operation/reason 区分 indexLoad/queryAnalysis/queryEmbedding/retrieve/enhancedAnswer/directAnswer/cacheCreate，记录数量/耗时/原因，不记录原文、证据、完整模型输入输出或凭据。启动日志不填会话 MDC；对话日志维持已有方括号、逗号格式，异步结束清理上下文。预期回退无异常堆栈，真正故障保留安全定位信息。

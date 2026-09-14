# Contract: 知识导入与共享 EmbeddingStore

**Date**: 2026-09-11 | **Feature**: [spec.md](../spec.md)

目标产物是可供所有 AI Service 共同查询的 EmbeddingStore<TextSegment>，不是自定义知识仓库/快照服务。本期由 InMemoryEmbeddingStore 实现；实现尚待后续任务。

## 1. 文件约定

```text
src/main/resources/document/{deviceType}/{brand-model}/general.md
src/main/resources/document/{deviceType}/{brand-model}/troubleshot.md
```

运行时路径为 classpath /document/，不是 /resources/document/。保留 troubleshot.md 拼写。复用 light/MI-MJDPL01YL 两份资料，不在本轮修改其内容。

每个产品目录包含两个非空文件；目录为空、缺文件、重复逻辑路径、未知 Markdown 文件名或错误层级均启动失败，不静默忽略。文件严格 UTF-8、无 BOM；资料维护说明放 documents/。路径不含空段、穿越或控制字符。品牌不含 ASCII 连字符，产品目录按第一个 "-" 分隔，剩余非空部分是型号。

通过显式别名归一化设备类型，例如智能灯泡/灯泡 → light。类型集合可含当前未收录资料的已知类型，查询该类型可正常得到 NO_MATCH。品牌/型号别名仅用于理解、跨型号依据说明，**不能生成额外 filter**。已知类型且目标型号未收录或条件信息不足，仍只按类型检索；可用的同类型另一型号资料按 FR-003 作为回答依据，并明确缺口与依据型号，不伪称已取得目标型号资料。

## 2. Metadata 与来源

Document 保存 deviceType、brand、model、productKey、knowledgeKind、sourceId、sourceName、documentHash；TextSegment 继承并保留 SDK index。字段细则见 [data-model.md](../data-model.md)。

- general.md → GENERAL；troubleshot.md → TROUBLESHOOTING。
- sourceId 为稳定 classpath 相对路径；sourceName 由服务端路径生成；documentHash 为原始字节 SHA-256。
- 段身份由来源/摘要/index 表达，不增加独立 segmentId 或索引快照模型。
- **deviceType 是唯一检索 metadata filter，其值从每轮请求读取；型号和知识类别必须导入，但不参与筛选，并用于跨型号依据声明。**
- 资料/Markdown 作为文本处理，不执行代码块、不抓取远程链接或图片、不授予设备操作权限。

## 3. 最小导入实现与生命周期

生产导入集中在 ai/rag/KnowledgeEmbeddingStore 一个文件。可使用领域专属 @Configuration 与 @Bean 方法；标准配置属性和 embedding 客户端配置仍在 config。工厂方法返回类型必须是 EmbeddingStore<TextSegment>。

1. 非 lazy 初始化时，Spring PathMatchingResourcePatternResolver 枚举 classpath*:document/**/*.md，按相对路径稳定排序，验证结构、重复和字节限制。
2. 读取 Resource.getInputStream，严格校验编码；用局部/嵌套 DocumentSource 适配 LangChain4j DocumentLoader.load 与 TextDocumentParser(StandardCharsets.UTF_8)。通过资源流支持 Boot JAR，不使用 Resource.getFile 或源码绝对路径。
3. DocumentSplitters.recursive(maxSegmentChars, overlapChars) 负责切分并继承 metadata；默认 1000/150 **字符**，不是 token。用局部 DocumentSplitter 装饰仅检查段数，不重新实现分段算法。
4. 在局部 InMemoryEmbeddingStore<TextSegment> 中，用 EmbeddingStoreIngestor 配置明确的 splitter、共享 EmbeddingModel 和 store，执行 ingest。向量数量/维度/有限值校验沿用现有约束，以小型嵌套装饰器实现，不重新拆出服务层。
5. 所有文件/分段/向量写入成功后才返回 store Bean；消费者通过构造器注入同一对象。其他组件不得自行 new store、重复 ingest 或运行期 add/remove。不能在 ApplicationReadyEvent 后异步导入。
6. 文件、解析、embedding、写入或总预算失败抛安全的 KnowledgeInitializationException 并终止启动。启动预算可用方法局部受控任务实现：容器初始化线程等待结果；超时取消、失败返回，迟到任务不能发布 Bean。关闭任务资源，不增加长期后台加载服务。

默认总文件 32 MiB、10000 段、启动预算 300 秒；超限失败，不截断或返回部分索引。不能用会跳过错误项的批量 ClassPathDocumentLoader。不能以“无相关资料”或直接回答掩盖初始化失败。

“静态共享”指 **每应用实例/上下文一次构建、运行期固定只读使用**。不使用可变 public static 字段；不跨应用实例共享 JVM 对象。Spring 启动失败不得宣告就绪或接受业务；需要启动失败测试验证不存在业务可用窗口。

每次重启新建内存索引，不读取上次缓存或 MySQL 维修知识表。相同文件的 sourceId/hash 稳定，不要求底层随机 embedding ID 相同。删除旧服务后的诊断消费者须保持兼容，但不恢复它作为本 feature 的 SQL 导入来源。

分析用的类型 catalog 由配置与本次已验证路径生成，只保留不可变的类型/产品名称集合；可在同一文件提供依赖已完成 store 的小型 Bean，不重复解析或向量化，不另建 catalog/snapshot 服务。来源校验直接使用本次 Result.sources，无需保存另一份全文或全局来源查询仓库。

## 4. EmbeddingModel 与配置复用

复用 KnowledgeProperties、KnowledgeEmbeddingProperties 和 application.yaml。模型由 KnowledgeEmbeddingConfig 输出标准 EmbeddingModel，共用于建库与查询；将悬空的旧包装类引用收敛为必要的嵌套适配。

| 属性（autosense.knowledge 前缀） | 值/要求 |
| --- | --- |
| store.type | memory；其他值当前明确拒绝 |
| documents.location | classpath*:document/**/*.md |
| documents.max-total-bytes / max-segments | 33554432 / 10000 |
| documents.startup-timeout-seconds | 300 |
| documents.max-segment-chars / overlap-chars | 1000 / 150，0 ≤ overlap < max |
| retrieval.top-k / min-score | 4 / 0.75；topK 为正，score 有限且在 [0,1] |
| service-cache.maximum-size / expire-after-access | 单个用户缓存 1000 个组合 / 30m，均为正 |
| type-aliases | 显式归一化映射，不允许歧义 |
| brand-aliases / model-aliases | 复用现有解析配置；只用于理解/适用性，不影响 filter |
| embedding.provider | openai-compatible；显式 mock 仅允许 LLM_MODE=mock |
| embedding.base-url / api-key / model-name | 真实模式独立配置且必填，不继承聊天模型名/凭据 |
| embedding.dimensions | 可选正数；不指定时验证实际维度一致 |
| embedding.timeout-seconds / max-retries / max-segments-per-batch | 10 / 0 / 32，均按现有范围校验 |

真实模型使用已有 OpenAiEmbeddingModel，关闭请求/响应正文日志。凭据使用 KNOWLEDGE_EMBEDDING_* 环境变量注入，日志不记录配置原文。显式 mock 使用确定性本地 EmbeddingModel，仍经过实际 SDK 导入/检索链；真实故障不能自动切换 mock。

同一实例建库/查询必须使用相同模型和向量空间；换模型/维度需重启重建。只保留必要启动日志摘要（模型名、实际维度、数量、耗时），不为未来迁移增加指纹快照服务。模型调用与向量校验阶段应可区分，且不泄漏知识正文、用户原文或凭据。

预算使用当前 AssistantProperties 实际值（application.yaml 默认 processingTimeoutSeconds=300）。最坏请求包含路由、范围分析、一个答案模型及一次 query embedding，按超时/重试加 5 秒余量校验；不得每步重置截止。启动预算独立于会话预算。

## 5. 外部向量库迁移边界

依赖方向：UserAiServiceCache → EnhancedAnswerFactory → EmbeddingStoreContentRetriever → EmbeddingStore<TextSegment> + EmbeddingModel。下游不强转 InMemory、不读取内部 entries/序列化快照完成业务查询。

后续选定外部库，只替换 Bean 提供方式及配置；memory 的资源导入配置不在外部模式激活。外部模式启动不扫描文档、不批量 embedding、不清空/重建索引，应用查询已存在知识；数据导入另行迁移。

保持 metadata/来源身份、**仅设备类型 filter**、score 语义和回退规则。新库评分语义不同需适配与校准。当前不增加外部库接口包装、具体数据库依赖或假 external 模式；未来迁移不列为本期已实现能力。

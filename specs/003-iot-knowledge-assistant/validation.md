# Validation: 知识咨询实施与运行验证

## 2026-09-11 Phase 3 / Phase 4 / Phase 5

**当前结论**：US1/US2 已实现，知识能力的定向集成与实际 JAR 验证通过。项目完整 `-Pit` 仍有 13 项旧诊断/售后/设备测试失败，T041 保留未完成，不能宣称整个项目回归通过。T023–T040、T042–T044 完成；历史章节只记录当时状态。

### 已交付行为

- 新增 `KnowledgeCapabilityHandler`，注册公共 KNOWLEDGE 能力；常识直答、类型未知直答、按类型检索、无结果/低相关度回退、冲突与技术故障分别处理。没有用户设备客户端、设备工具或绑定操作。
- 知识直答通过认证用户的缓存 DirectAnswerService 调用真实 TokenStream，mock 同样走缓存代理；原 `answer` 接口保留。范围分析与增强回答分别使用缓存内无 RAG/内嵌 RAG 代理，未恢复 AiServiceFactory。
- EnhancedAnswerFactory 内部完成唯一一次检索，入口只消费 `Result<KnowledgeAnswer>`。在发布前复核本轮来源、类型、分数、hash 和引用子集；非法输出零增强 token，不能用全局目录补来源。型号/品牌/资料类别均不参与检索 filter。
- 服务端根据已验证来源生成跨型号声明和 sourceName/sourceId/documentHash 尾注；CONFLICT 明示冲突。信息不足但类型已知仍检索，不先追问型号。同步增强结果校验后分块发送；直答保留模型流。
- token 聚合、数据库助手消息、GET 历史/详情和 conclusion.summary 保持一致；同用户两个会话并发查询 light/air 复用同一组代理且资料隔离，跨用户三代理独立、共享启动索引，后续查询不再次导入。
- 补充 queryAnalysis/enhancedAnswer/directAnswer 的安全阶段日志及预期回退 skipped 日志；延续英文日志、MDC 白名单、方括号逗号格式。初始化日志无会话字段，回调恢复原上下文。

### 集成时发现并修复的问题

1. `SessionProcessingService` 原来把 MySQL `NOW()` 得到的无时区 DATETIME 直接作为 JVM 截止时间。数据库 UTC、应用 Asia/Shanghai 时，新请求会立即超时。现在数据库继续保存自身时钟上的权威截止；Accepted 携带接纳时在应用时钟计算的同一固定总预算，后续步骤不重置。MySQL/Redis 实测覆盖该时区差异。
2. `SessionOrchestrator` 收到迟到 COMPLETE/WAIT 且 finish 拒绝时，原来只停止 Guard，没有关闭 SSE。现在关闭旧流，不发布迟到 conclusion。集成测试在增强模型运行中使数据库请求过期并 GET 补偿结清，随后放行模型，验证无成功落库且连接关闭。
3. 知识直答流增加剩余预算定时完成；无响应或迟到回调不能无限保留业务 future，也不转成成功回答。底层 SDK 请求仍遵守原模型客户端 timeout，不因缓存驱逐关闭共享客户端。
4. 录制型公共路由测试配置改为替换新增 knowledge handler，避免重复注册。旧租约测试的 30 秒常量改为读取实际 AssistantProperties（当前 YAML 为 180 秒），没有改变生产租约配置。mock 路由覆盖规格中的“色温和亮度有什么区别”和追问。

### 实际执行结果

| 验证 | 实际结果 / 证据 |
| --- | --- |
| `.\mvnw.cmd -l target/knowledge-final-verify.log verify` | 187 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS |
| 知识与公共基础定向 `verify -Pit` | 87 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS；`target/knowledge-focused-it.log`；真实 Testcontainers MySQL/Redis、本地模型与真实 AiServices |
| `.\mvnw.cmd -Ddevice.service.base-url=http://127.0.0.1:18081 -l target/knowledge-final-it.log verify -Pit` | 244 tests，11 failures，2 errors，0 skipped，BUILD FAILURE；231 项通过，失败明细如下 |
| 可执行 JAR 启动/重启/恢复及故障副本 | 9/9 通过，`target/knowledge-jar-validation/result.json`；脚本 `scripts/manual-test/knowledge-jar-validation.py` |
| 标准 EmbeddingStore 替身 | KnowledgeRagAssemblyTest 使用仅实现 SDK 接口的本地 store，增强代理正常检索和引用；下游没有 InMemory cast |
| `git diff --check` | 通过；仅 Git 的 LF/CRLF 提示，不存在空白错误 |

完整集成测试的 13 项失败集中在未改动的旧验收路径，新增知识测试均通过。源码检查确认以下差异；不把这些失败改成跳过或成功，也不在 003 中新增其他 feature 的生产处理器：

| 类 | 失败数量 | 实际原因 |
| --- | --- | --- |
| AutoRepairFlowIT | 6 | 5 项仍从设备创建响应顶层读取 name 等字段，而当前数据在 data；1 项期望 UNSUPPORTED_DEVICE_TYPE，但当前公共入口无 DIAGNOSIS handler，返回 CAPABILITY_NOT_AVAILABLE |
| ManualGuideIT | 4 | 1 项设备响应旧结构造成空字段；3 项售后/诊断公共能力尚未注册，返回 CAPABILITY_NOT_AVAILABLE |
| DeviceBindingConcurrencyIT | 2 | 期望符号错误码 DEVICE_NOT_FOUND，而当前 API 返回 40006；SQL 仍查询 userId，但表列为 user_id |
| UserManagementIT | 1 | 期望符号错误码 DEVICE_ALREADY_BOUND，而当前 API 返回 40008 |

### 可执行 JAR 证据

独立 MySQL 端口 13366、Redis 端口 16366；已有模拟器二进制复制到 target 后以独立数据库、18081 端口运行。未使用现有业务库作故障注入。所有故障类只编译进 target 中的 JAR 副本，不进入正式 src 或生产 JAR。

正常启动/重启/修复后恢复均实际注册并登录用户、通过 sessions API 检索，校验 SSE、GET 历史与结论一致。三次启动的来源尾注 SHA-256 均为 `7fc6c9b4b07cab42eb44262a9d2bd144677953c7bed1681bbec4d2c74781dde5`。每次仅记录一次 indexLoad，2 个文档；启动行无会话 MDC。

| 故障副本 | 实际失败阶段 |
| --- | --- |
| 缺 troubleshot.md | VALIDATING / MISSING_PAIRED_DOCUMENT |
| 非法 UTF-8 | VALIDATING |
| DocumentParser 抛错 | PARSING |
| EmbeddingModel 抛错 | EMBEDDING / MODEL_OR_VECTOR_FAILED |
| EmbeddingStore 写入抛错 | WRITING / STORE_WRITE_FAILED |
| 启动预算 1 秒、模型阻塞 | INITIALIZATION / TIMEOUT |

六类故障均非零退出，没有 index loaded 或应用 ready，业务端点不可用。恢复使用原 JAR 后成功。生产 general.md / troubleshot.md 字节 hash 保持不变。

最终构建另执行 `knowledge-jar-validation.py --smoke`，启动、实际检索与保存再次通过，来源尾注一致。产物 SHA-256：`d9f3c4c5c2e14b91fdeab8a7b1e0f1160f8d51ebaa8df34330ef56d7cefbf8b5`；记录于 result.json。临时 Java 进程由脚本退出时关闭，独立模拟器及两个验证容器在验收后清理。

JAR 验证显式使用 mock 和 minScore=0 以稳定检查资源打包、检索和保存链路；业务 0.75 边界、低分排除、零候选和跨类型隔离由确定性向量测试单独验证。未调用付费/真实外部模型，未校准真实模型的 0.75 语义相关度；mock 成功不代表自然语言事实质量已验收。

### FR / SC 对照及限制

| 要求 | 证据与状态 |
| --- | --- |
| FR-001/002/008；SC-001/004/008 | 常识 API、知识会话 IT、接纳/缓存/流式测试：未绑定咨询、历史、缓存生命周期及鉴权隔离通过 |
| FR-003/004/005/010；SC-001/002/005 | Handler、检索 API、真实 SDK RAG：缺口、跨型号、多来源、参数/条件、冲突、来源越界、门槛边界和技术故障通过受控样例 |
| FR-006/007；SC-003 | 知识路径零 DeviceServiceClient 调用；公共路由与原分析接口兼容测试通过；其他能力是否已生产接入不由知识能力代替 |
| FR-009/011/013；SC-006/007 | 启动导入、配置、并发与 9 项 JAR 检查通过；索引共享、故障不就绪、来源稳定 |
| FR-012 | 只验证标准 EmbeddingStore 依赖边界，外部数据库实现明确不属于本期 |

项目总体回归仍受上述 T041 失败阻塞；真实模型回答质量与阈值校准尚未执行。knowledge.md 继续仅作过期存档，self-review.md 与 requirements.md 的评审勾选不改动。

---


## 2026-09-11 后续修订：移除统一工厂

- 按用户要求删除 AiServiceFactory，UserAiServiceCache 直接注入 DirectAnswerServiceFactory、EnhancedAnswerFactory，并在缓存未命中时调用它们的三个创建方法。
- UserAiServices record 移入 UserAiServiceCache；只有完整创建成功才写入缓存。认证 userId、首次接纳时机、TTL/容量、跨用户隔离、共享 store/model 及零创建调用行为保持。
- 原诊断等需要新代理的调用方继续直接使用对应专用工厂。没有添加替代统一工厂、额外服务缓存或业务分支。
- 更新 spec 的 FR-008/SC-008、plan、research、data-model、contracts、quickstart、tasks，以及 002 公共基础中的旧统一工厂约束；历史归档与评审勾选原样保留，旧记录中的统一工厂描述不再作为当前规范。
- 实际执行 `mvn -l target/knowledge-direct-factories-verify.log verify`：**BUILD SUCCESS，168 tests，0 failures，0 errors，0 skipped**，完成后端打包。现有测试已改为观察专用工厂直接调用，覆盖同用户并发仅创建一次、跨用户独立、第三个代理创建失败不缓存半成品、重试/过期/驱逐、real/mock 装配和 RAG 隔离。
- 本次未运行 Docker 集成测试或实际 JAR 启动。Phase 3 及之后保持未实施。

以下章节保留本次删除统一工厂之前的实现/验证过程；当前创建方式以上述修订及最新 spec/plan 为准。

## 2026-09-11 Phase 1 / Phase 2 迁移基线

用户授权实施新任务 T001–T022，并确认 knowledge.md 已过期、仅作存档；不修改评审勾选。
已核对 Caffeine、Java 21、Boot 3.5.3、LangChain4j 1.0.1、Log4j 2 及忽略配置，可直接复用。
KnowledgeConfig、KnowledgeEmbeddingConfig、MockKnowledgeAiServicesFactory 和旧测试仍引用已删除的 service/knowledge 类型；EnhancedAnswerService 目前仅有诊断 analyze。
保留现有 Markdown、独立 embedding 配置、300 秒会话截止、接纳协作者和公共会话/身份/设备流程；按新方案收敛导入、重建工厂与缓存并重新验证。

### 本次完成范围

新清单 T001–T022 已完成，T023–T044 未执行。

- 复用依赖、配置、Markdown 和接纳时机；导入集中在 ai/rag/KnowledgeEmbeddingStore，SDK Loader/Parser/Splitter/Ingestor 生成完整共享 InMemoryEmbeddingStore Bean。类型/产品 catalog 仅保存名称，取消独立加载/分段/快照/segmentId 类。
- 共享 EmbeddingModel 保留独立配置、批量与向量验证；provider 调用和 OUTPUT_VALIDATION 日志阶段可区分。启动失败不返回部分索引，取消后迟到工作不能发布 catalog/Bean。
- EnhancedAnswerFactory 分别创建无 RAG 分析和内嵌 Advanced RAG 回答代理；QueryTransformer 预检类型/截止，dynamicFilter 只含本轮 deviceType，QueryRouter 真实检索一次后回放不可变达标列表。
- 真实 AiServices 测试验证 JSON evidence、原问题/历史不重复、queryText 单独向量化、Result.sources、无/低分提前停止、非法类型/分数/来源技术错误、同代理并发灯/空调隔离。
- AiServiceFactory 完整创建 direct/analysis/enhanced，单 Cache<Long,UserAiServices> 使用认证 userId；同用户复用、跨用户三个代理独立、TTL/容量/原子失败重试和在途流驱逐行为通过验证。创建代理不调用模型或检索。
- real/mock 复用同一组工厂；MockKnowledgeAiServicesFactory 只提供本地模型 Bean，增强回答仍走真实 SDK RAG，不维护第二套实例缓存。
- 接纳后的可选协作者保持原 Guard/身份/归属/忙检查时序。唯一残留 RepairKnowledgeService 消费者 RepairExecutionRunner 改用既有 RepairKnowledgeMapper 和原匹配规则；公开 run 路径回归验证失败后人工步骤或售后等待保持、不会继续下发动作。
- AI 输出/业务输入使用 record 与分层枚举，新增六份 prompt 资源并验证绑定与可解析 envelope；没有实现知识 handler 或替代公共设备流程。

### 本次实际验证

| 检查 | 实际结果 |
| --- | --- |
| 测试先行与编译基线 | 修改基础行为测试后执行 test-compile，因已删除旧类型而预期失败；日志 target/knowledge-20260911-red.log |
| 定向基础测试 | KnowledgeConfigurationTest、KnowledgeIndexInitializationTest、UserAiServiceCacheTest、KnowledgeRagAssemblyTest、KnowledgeAdmissionContractTest、AiServiceAssemblyTest 全部通过 |
| 第一轮完整 verify | 167 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS；target/knowledge-20260911-verify.log |
| 最终完整 verify（含向量输出日志回归） | **168 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**；执行命令 `mvn -l target/knowledge-20260911-final-verify.log verify`，完成可执行 JAR 打包 |
| Spring 真实资源加载 | 当前两份生产 Markdown 经资源流加载；shared store/catalog/三代理装配、失败不完成上下文刷新通过 |
| 启动失败与取消 | 缺失/重复/未知文件名/坏 UTF-8/BOM/空白/超量/解析/向量/写入失败及不响应取消的模型超时场景通过，未发布迟到 catalog |
| 诊断兼容 | RepairKnowledgeCompatibilityTest 经公开执行路径验证人工指导/售后分支及锁/上下文清理通过 |
| 差异和结构 | git diff --check 通过；ai/rag 仅两个核心文件，service/knowledge 仅 UserAiServiceCache；src 无被删除的旧知识类型引用 |

本机 Maven Wrapper 在一次再次启动时报告启动脚本错误，后续使用已安装 Maven 执行相同项目构建；最终运行 Java 21。所有模型测试使用显式本地 mock/WireMock，未访问真实模型、设备或生产数据库。

### 明确未执行的后续范围

- US1/US2 的 KnowledgeCapabilityHandler、常识/检索业务接入、回退调用、服务端跨型号声明/来源保存仍属 T023–T038。基础 prompt/RAG 验证不等于完整对话能力已交付。
- 未运行 verify -Pit，未启动实际 JAR 或进行 JAR 故障注入（T041/T042）；普通 verify 的 skipped=0 不表示 Docker 集成通过。
- 未校准真实 embedding 的 0.75 语义阈值，未实现外部向量库。
- knowledge.md 按用户本轮确认作为过期存档；requirements.md 的 2 项沿用此前授权，self-review.md 保持 18 项已勾选；本轮不修改任一 checklist 或其勾选。

以下 2026-09-10 结果为迁移前历史，不代表本次完成。


> **历史验证范围说明（2026-09-11）**：以下结果仅对应 2026-09-10 当时的实现。当前工作区正在重构，且 [plan.md](plan.md) 已改为共享索引与 AiServices 内嵌 RAG；这些测试结果不证明当前代码可编译或新方案已通过验收。后续实施需更新相关测试并重新验证，本轮规划未执行构建/运行验证。

**Date**: 2026-09-10
**Scope**: [旧任务归档](tasks-before-20260911-sync.md) 的 T001–T014；用户当时明确限定先实现 Setup 与 Foundational。本文 2026-09-10 章节的所有任务编号均指归档版本，不对应 2026-09-11 新清单。

**后续任务同步（2026-09-11）**：[tasks.md](tasks.md) 已重新编号为 T001–T044；新任务状态以顶部 2026-09-11 实施章节为准。此说明只消除历史编号歧义，不修改下面历史结果或宣称新方案通过验证。

## Completed behavior

- 新增 Boot 管理版本的 Caffeine 依赖；只缓存服务端认证 userId 对应的无状态 DirectAnswerService/ProblemAnalysisService 服务对。默认容量 1000、访问后 30 分钟过期；创建失败不缓存，驱逐不关闭共享客户端或在途流。
- 复用两个真实 AI Service 工厂并改为构造器注入；提供显式本地 mock 工厂。创建代理不调用模型，保留原有资源验证和诊断 analyze 方法。
- 在公共对话成功接纳、Guard 建立后初始化服务对，覆盖新会话、已有会话消息和等待续走。GET、认证拒绝、忙冲突不初始化；缓存命中不能绕过令牌解析。初始化失败沿公共事务收尾与上下文清理。
- 新增独立知识/embedding 配置，校验别名、范围、加载限制、provider、独立模型、可选维度和累计调用预算。沿用当前实际 YAML 会话截止默认值 **300 秒**，未改成设计示例的 120 秒；预算校验读取 AssistantProperties 的实际值。
- 以资源流加载 document 下的 general.md/troubleshot.md，经 LangChain4j DocumentLoader、UTF-8 TextDocumentParser、递归字符 splitter、EmbeddingStoreIngestor 写入 InMemoryEmbeddingStore。
- 导入保留类型、品牌/型号、一般/排故分类、sourceId、sourceName、documentHash、index 与 segmentId；每次启动完整重建，快照保留模型/schema/分段指纹。业务注入标准 EmbeddingStore 接口。
- 缺文件/配对、重复路径、空白/BOM/坏编码、解析失败、字节/分段超限、向量数量/维度/数值异常、写入失败或启动超时均失败，不发布部分索引。Spring 装配失败测试验证无法完成应用上下文启动；迟到加载结果不能发布。
- 关键日志为英文，embedding 请求使用现有安全 AI 调用日志；初始化异常不附带可能含文档/凭据的 SDK 原始异常。

## Executed checks

| 检查 | 实际结果 |
| --- | --- |
| 先编写基础测试后执行 | 新类缺失导致预期编译失败，之后补实现；期间修复 SDK import、测试断言 API 和属性键夹具 |
| 定向 Maven 测试 | 21 项通过；之后增加已有会话/续走与真实流驱逐测试，并由下述完整 verify 覆盖 |
| `.\mvnw.cmd -l target/knowledge-phase12-verify.log verify` | **BUILD SUCCESS，156 tests，0 failures，0 errors，0 skipped**；完成 JAR 打包 |
| 真实 LangChain4j 代理与 embedding HTTP 协议 | 使用本地 WireMock；验证独立模型/维度/批量请求和创建服务零模型请求，不连接真实提供方 |
| 实际项目知识资源加载 | Spring ApplicationContextRunner 使用当前两份生产 Markdown 与显式 mock embedding，完整加载成功，缓存仍为空直到首个用户调用 |
| 代码差异检查 | `git diff --check` 通过 |

主要测试类：KnowledgeConfigurationTest、KnowledgeIndexInitializationTest、UserAiServiceCacheTest、KnowledgeAdmissionContractTest、AiServiceAssemblyTest。完整 verify 同时覆盖现有路由、会话、用户、设备、日志和诊断相关的普通测试。

## Configuration for local use

新增向量化配置不会默默复用聊天模型参数。若使用本地桩，需同时设置：

```powershell
$env:LLM_MODE = 'mock'
$env:KNOWLEDGE_EMBEDDING_PROVIDER = 'mock'
```

真实向量化需独立设置 `KNOWLEDGE_EMBEDDING_BASE_URL`、`KNOWLEDGE_EMBEDDING_API_KEY`、`KNOWLEDGE_EMBEDDING_MODEL_NAME`；`KNOWLEDGE_EMBEDDING_DIMENSIONS` 可选。真实配置缺失会按约定停止启动。现有集成测试基座已显式配置 mock embedding，避免后续测试意外连接真实模型。

## Remaining scope

- T015–T047 保持未完成。当前只完成公共基础；尚未注册知识回答 handler、实现知识 QueryRouter、范围解析、检索后回答或来源尾注保存。
- 本轮没有运行 `verify -Pit`，未启动 Docker、真实设备服务或真实模型。普通 verify 的 skipped=0 不表示 Docker 集成测试已执行。
- 实际可执行 JAR 启动及其失败注入验收属于 T043/T046，本轮完成打包与 classpath/Spring 加载测试，未将其冒充实际 JAR 运行验证。
- 真实 embedding 的 0.75 门槛质量校准、后续外部数据库接入仍未执行；mock 验证不证明生产语义检索质量。
- requirements.md 保持 14/16，用户已明确允许保留两项“规格含指定技术细节”的未勾选项继续实施，本轮没有修改清单。

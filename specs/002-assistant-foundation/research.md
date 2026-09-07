# Phase 0 Research: 公共基础与统一意图路由

**Date**: 2026-09-07
**Feature**: [002-assistant-foundation](spec.md)
**Constitution**: [2.3.0](../../.specify/memory/constitution.md)
**Status**: 研究完成；以下为待实施决策，不表示当前代码已满足新规格。

研究保留既有AI Service/路由、公共数据/API/鉴权、对象兼容及日志决策；本轮按章程2.3.0补充两项独立只读研究并合并为R14：固定1.0.1的资源加载/模板实现，以及四条现有调用的prompt迁移和打包验收。依据当前源码、本地固定版本官方sources.jar及官方文档。本轮没有运行应用、模型、数据库、Docker或业务测试。

## R1 — 公共基础的交付边界

**Decision**: 保持用户、SN 绑定、公共响应、会话、设备定位/客户端等既有能力，增量交付四路分类、能力分发契约、统一 AI Service 创建、消息隔离及必要兼容修正。四类业务处理器由 003/004/001/005 各自交付。

**Rationale**: 当前 SessionOrchestrator 同时持有路由、分析、诊断、维修、售后及持久化依赖；旧 DEVICE_ACTION 会直接进入包含设备写操作的混合流程。公共路由不能继续以此作为默认分支。

**Alternatives considered**: 本次一次性实现四项业务会突破 002 范围；复制用户/设备/会话体系损失已有成果；仅更换分类枚举不能建立真正独立的能力边界。

- 分发器通过四能力业务契约选择已注册处理器；重复注册启动失败，缺失注册不伪造成功，请求时返回 CAPABILITY_NOT_AVAILABLE。
- 002 的测试专用接收器记录分发与上下文，禁止生产装配；不将“已接收测试请求”表示为设备已查询、已诊断或已控制。
- 旧流程代码保留供后续 feature 复用，旧 DEVICE_ACTION 不作为新分发器回退。旧等待修复确认不能继续通过公共入口直接调用 RepairExecutionRunner。
- 已有处理器只有满足新能力边界并显式注册后才能接入；002 的独立验收不要求缺失的业务处理器全部就绪。

## R2 — 固定版本的 AI Service 创建

**Decision**: 保持 Java 21、Spring Boot 3.5.3、MyBatis-Flex 1.10.9、LangChain4j 1.0.1 和 community-redis 1.0.1-beta6，不新增 AI starter，不升级依赖。由 ai/factory 中受 Spring 管理的工厂创建同步结构化代理和流式回答代理。

**Rationale**: 当前 LangChain4jConfig 的四条真实调用分别创建 ProblemAnalyzer、DiagnosisReasoner、IntentClassifier、DirectAnswerer，仍直接调用模型并手动解析；已声明的两个内部 AI Service 接口并未实际使用。固定版本已有需要的能力。

**Alternatives considered**: 仅移动配置类不能满足实际 AI Service 重写；调用提供商原生 SDK 违反章程；引入新的 AI starter 或 agent 框架没有本期必要性。章程新增的日志 starter 在 R12 单独评估，不改变 AI 接入方案。

- 同步代理使用 AiServices.builder(...).chatModel(...).build()；输出为 enum/结构化对象（本期不可变对象优先 record，见 R11），解析后仍做业务语义校验。
- 流式代理单独配置 streamingChatModel，返回 TokenStream；使用 onPartialResponse、onCompleteResponse、onError、start。
- 既有 DirectAnswerer 的 CompletionStage 外部契约可由 TokenStream 适配，不假设 1.0.1 原生支持任意异步返回类型。
- 工厂接管四条调用的创建/执行契约；诊断提示、规则、维修检索与知识咨询业务仍归所属 feature。ProblemAnalysis、DiagnosisConclusion 按用途迁至 ai/model，不改变它们现有语义。
- ai/factory 中可用一个 AiServiceFactory 管理四类代理，并以内嵌公开接口定义服务方法，避免无必要的新包和逐类工厂。所有受管对象由 Spring 注入。

**Evidence**: [AiServices 1.0.1 官方源码](https://raw.githubusercontent.com/langchain4j/langchain4j/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/AiServices.java)已浏览核验；[DefaultAiServices](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/DefaultAiServices.java)、[TokenStream](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/TokenStream.java)、[ServiceOutputParser](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/output/ServiceOutputParser.java)能力以本地 Maven 缓存中的 1.0.1 sources.jar 核验，链接为对应官方版本定位。无需依赖最新文档中的新增 API。

## R3 — 四能力与歧义结果分开建模

**Decision**: 路由输出 RoutingDecision，包含 RoutingOutcome、可空 CapabilityIntent、目标线索、澄清问题及诊断子类型。结果和输出枚举放 ai/model、ai/model/enums；经校验显式转换为 domain/enums/AssistantCapability 后分发。

**Rationale**: 旧 COMMON_SENSE、DEVICE_ACTION、MODEL_SPECIFIC、AFTERSALES_QUERY、UNCLEAR 不能区分只读查询与控制，也无法表示复合/条件任务。

**Alternatives considered**: 单一四分类 enum 无法描述澄清原因；自由文本分类需脆弱手写提取；让模型直接指定处理器、已授权用户或设备命令会把安全边界交给模型。

- outcome 为 SINGLE、CLARIFY、COMPOSITE、OUT_OF_SCOPE；SINGLE 才要求 intent 为 KNOWLEDGE、DEVICE_QUERY、DIAGNOSIS、CONTROL 之一。
- 型号咨询映射 KNOWLEDGE；明确售后请求映射 DIAGNOSIS + AFTERSALES，不先探测设备。
- 多意图、条件请求和多设备写映射 COMPOSITE 并先澄清；本人多设备元数据列表仍是单一 DEVICE_QUERY。
- 输出没有 userId、verifiedDeviceId、confirmed、权限等级或可调用端点；targetHint 仅是未验证线索。
- 非法分类、未知枚举或矛盾结构转确定性的澄清提示；模型超时/认证/连接故障转服务错误，不吞成 UNCLEAR，不静默切换 mock。
- 已存 ProblemReport.intent 为字符串；旧值保留作历史，新轮写新分类，不将旧 DEVICE_ACTION 批量解释为 CONTROL。

## R4 — 只读历史快照与工具隔离

**Decision**: AI Service 不挂 chatMemory/chatMemoryProvider，不使用 @MemoryId；由公共会话提供最近 20 条可见历史与当前输入，使用 @V 参数传入。公共工厂不配置 tools/toolProvider。

**Rationale**: 固定版本无 ChatMemory 时各调用独立，且 DefaultAiServices 拒绝这种模式下声明 @MemoryId。现 RedisChatMemoryStore 的缓存可能陈旧，自动 AI Service 记忆还会写入内部提示与结构化结果。

**Alternatives considered**: 共享可写 AI 记忆会污染历史；每次临时构建 ChatMemory/代理增加生命周期复杂度；自动扫描所有 BaseTool Bean 存在过度授权，且当前没有必须由模型直接执行的工具。

- 入站保存本次 USER 消息后，以它的 messageId 为界：查询同 sessionId、id 小于本次 ID 的最近 20 条 USER/ASSISTANT，按 id 升序送入模型；当前输入单独传一次。
- 历史序列保留角色并作为资料处理，不将历史里的文本提升为系统指令；会话和身份由服务端校验，模型不负责授权。
- 同次路由/分析/回答共享同一不可变历史边界；本次内部分类、分析 JSON 和中间调用不保存为可见对话。
- 公共编排为唯一可见历史写入方；完整回答、人工步骤或售后联系方式保存成功后才清空上一轮结论投影。
- 002 默认从 MySQL 构造短窗口，避免依赖已有不可靠热缓存；旧 RedisChatMemoryStore/ChatMemoryFactory 从真实 AI 装配链移除。无需另建记忆平台或为了使用 Redis 增加新历史缓存；Redis 继续用于令牌、上下文和租约。

## R5 — 同会话异步处理的持久化隔离

**Decision**: 复用 repair_session、problem_report、chat_message；仅为 repair_session 增加两个可空字段 processing_message_id、processing_deadline_at。MySQL 当前消息指针是回调写入的最终约束，Redis 短租约防止重复工作。

**Rationale**: 当前没有同会话消息互斥，异步流可能在新请求开始后继续写入；仅靠 Redis TTL 不能阻止已过期 worker 的晚到回调，也不能在重启后解释 ROUTING/ANSWERING 悬挂状态。

**Alternatives considered**: 纯内存锁不支持多实例/重启；仅 Redis owner-token 无法可靠恢复长期记录；另建会话/轮次/任务表超出本期必要性。

- 入站短事务锁会话行，验证归属与状态，插入一次当前 USER 消息和必要的问题报告，设置处理指针与固定截止时间；网络/模型调用不占数据库事务。
- Redis 会话租约 30 秒、每 10 秒核对 owner 并续期，覆盖整个异步处理直到最终回调及持久化完成；owner 包含 sessionId、messageId 和随机值。设备租约是另一种资源，不以会话锁代替。
- 涉及持久化的回调和收尾必须在同一短事务中锁会话并核对 processing_message_id、数据库当前时间及状态，再写历史、状态与追溯；迟到回调不得写入、清新指针或释放新 owner。
- Redis 租约丢失但 DB 指针尚未到期时，不重新执行请求；到期后由 GET 补查或下一次 POST 原子标记旧处理失败、留存说明并清指针，再允许新轮。
- processing_deadline_at 为该消息总截止，不随 Redis 续期无限延长。正常业务收尾仍将完整可见消息、状态投影、追溯及处理指针清理放在同一事务中；提交成功后发送相应结果。未接纳请求和持久化异常的错误通知遵循会话接口契约，不以数据库提交成功为前提，也不代表持久化收尾已经完成。
- 超时恢复是独立的短事务：核对同一 processing_message_id 且截止已到，再记 REQUEST_TIMEOUT 并清指针，不能复用要求“尚未到期”的正常回调条件。活跃请求到期主动执行恢复并关流；GET/后续POST承担进程崩溃后的补偿恢复，均不得覆盖新处理消息。
- 仅有 confirmRepair 的兼容请求也须获得可追溯消息 ID，但是否确认/执行由 005 判定；旧待确认上下文不能直接放行。
- 这些字段只保障公共消息一致性，不声称已提供设备命令幂等、有效控制确认或完整控制审计。

## R6 — 账号与令牌撤销保持现有模型

**Decision**: 继续复用 User、UserService、AuthTokenService、Redis token 和用户 token 索引；不新增账号体系或角色功能。验证 token 时要求其本体和所属用户索引成员资格同时有效，并检查账号当前状态。

**Rationale**: 现 token 查询只依赖 token 本体，索引 TTL 可先到期；禁用后再启用可能让漏删的旧 token 恢复有效。删除能枚举到的 token 不足以保证撤销。

**Alternatives considered**: 仅延长索引 TTL 不能撤销已有孤儿 token；新增 authVersion 列可行，但现 token/index 模型通过成员资格和原子操作即可满足本期要求。

- token 签发、校验并续期、本体/索引注销操作使用原子 Redis 脚本；两键 TTL 统一续期，不允许只延长 token 本体。
- 缺索引成员即无效，即使旧 token 本体尚存；禁用删除索引，恢复账号不能重建旧成员。
- 登录签发、禁用、启用按用户行短事务串行；签发前重新检查账号可用，关键Redis签发/撤销失败抛错并回滚数据库事务。启用必须在旧索引撤销成功后才提交启用状态；索引已撤销后的残留token本体可尽力清理，不恢复旧成员。Redis 不可用时拒绝认证/签发。
- 无跨数据库/Redis 的伪原子承诺：失败优先撤销或拒绝，补偿不恢复旧 token；数据库事务内仅进行有界短 Redis 操作，不进行外部模型/设备调用。
- UserController.me 经 UserService 访问 Mapper；保留注册校验、BCrypt、当前用户信息、管理员自身状态限制和本人资源范围。

## R7 — 数据与包归属迁移

**Decision**: 按章程做实际职责迁移，保持现有 HTTP 字段和持久数据；五个 View 移至 domain/vo，AI 输出迁 ai/model，业务枚举仍留 domain/enums。

**Rationale**: 当前 UserView、AdminUserPageView、DeviceView、ChatMessageView、SessionListItemView 仍在 domain/dto；只更名目录不应改变公共 JSON。

**Alternatives considered**: 所有 DTO 都迁 VO 混淆请求/响应；将所有参与模型调用的枚举迁 AI 包会混淆业务状态。

- Request、LoginResponse、SessionResponse、ConclusionDto 仍归 domain/dto；AI 分类与业务分发枚举按用途分离。
- 全局权限等级等常量在 constant，工具在 utils；不创建没有用处的常量或工具填目录。
- 保留 SN 全局唯一、名称优先、外部稳定标识；不持久化运行状态。DeviceView.online 现为 SN 可发现性投影，异常 false 只表示未确认在线，不是可靠离线结论。
- 设备定位、归属、客户端/适配器及设备互斥只维护一套；002 补原子 owner 检查等共享缺口，005 负责操作确认和执行期使用规则。

## R8 — 公共状态与 SSE 失败语义

**Decision**: 保留现有 URL、DTO 外形和五类 SSE 事件；新公共分发阶段增加 DISPATCHING，公共失败增加 FAILED_REQUEST 终态及对应错误码。不使用 COMPLETED_ANSWERED 表示异常成功。

**Rationale**: 当前常识回答的失败 fallback 可按正常结果结束；Controller 还会在异步接收后将异常编码为 SSE error，因此不能把流内错误误写成 HTTP 4xx/5xx 响应。

**Alternatives considered**: 为每个故障新增状态会扩大枚举；继续把失败表示为回答成功影响补查和恢复；重做整套诊断状态机超出范围。

- 身份过滤、创建请求校验等发生在接受流之前的错误沿用 JSON HTTP 状态。
- 已接收流内业务失败使用 error，必要时先记录 FAILED_REQUEST；忙请求不能将另一正在处理的消息标记失败。
- 路由 CLARIFY/COMPOSITE → CLARIFYING；OUT_OF_SCOPE 返回确定性的范围说明；SINGLE → DISPATCHING → 所属处理器。
- 处理器缺失、AI 服务失败、处理超时等各有错误码；GET 仍可查看保存的失败说明并重新提问。
- 断线不回放；业务生命周期与流连接分离，最终回调成功提交一次。晚到 token/结果不能在过期轮次继续提交。
- 旧已结束状态继续可读，GUIDED_MANUAL 应视为已交付指引后的终态；本次不补齐旧 001 全部诊断状态边。

## R9 — 配置与工程运行边界

**Decision**: 沿用 autosense.llm 和既有环境变量，新增显式 maxRetries 配置，默认 0；规划默认模型调用超时 30 秒、单消息公共处理截止 120 秒，均可配置。这是避免悬挂的工程默认，**不是新增诊断时延或生产吞吐 SLA**。

**Rationale**: 当前模型超时默认 360 秒，固定版本同步模型 maxRetries 默认 2，不适合清晰的公共错误与处理截止；流式错误重放还可能重复内容。

**Alternatives considered**: 保留隐式重试使次数/耗时不可见；硬编码预算违反配置外部化；为未测量的容量许诺 QPS 没有证据。

- real 要求合法 HTTP(S) 地址、非空模型/密钥、正超时；mode 仅 real/mock，未知值启动失败。默认 real，mock 仅显式配置，生产关闭开发身份。
- CHAT_MEMORY_WINDOW 对本规格固定为 20，不能通过配置改变验收语义；与规格不一致时配置校验失败。
- 上下文 JSON 以原子 SET+TTL 写入，30 分钟；不沿用会残留旧字段的 Hash 增量覆盖。旧格式按版本失效后重新澄清，不能恢复控制授权。
- 并发按不同会话独立、同会话一条在处理；不新增消息队列或分布式限流基础设施。任务提交失败及时返回公共错误。
- [OpenAiChatModel 1.0.1 源码](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j-open-ai/src/main/java/dev/langchain4j/model/openai/OpenAiChatModel.java)的本地 sources.jar 证实默认 maxRetries=2；显式设定后以配置/错误测试验证，无需调用真实模型来研究。

## R10 — 验收分层与外部依赖

**Decision**: 区分已有回归、新公共能力验收和真实提供商小样本；真实设备业务不在 002 测试接收器中执行。

**Rationale**: SessionApiContractTest mock 了编排；LlmConfigurationTest 只断言 mode；DirectAnswererTest mock 低层模型；旧 IT 强制 LLM mock，均不能单独证明 AI Service 重写或四路分发。

**Alternatives considered**: 只改 mock 分类会绕过真实代理；要求 001/005 完整修复闭环作为 002 完成条件会让拆分失去意义。

- 新测试保留实际 AI Service 代理，用本地模型协议替身覆盖模型选择、类型解析、流式回调和异常；不 mock 掉 Router/AI Service 后宣称重写已验收。
- 公共编排集成使用仅测试装配的四能力接收器，核对身份、sessionId、round、messageId、仅目标接收一次；模糊/复合场景零设备读写，接收器场景零设备写。
- 已有 UserManagementIT、DeviceBindingConcurrencyIT 验证用户/SN 基础，使用 Testcontainers MySQL/Redis 与外部真实 deviceSimulator。
- 未接入业务能力在生产明确不可用；真实提供商仅用无设备副作用的分类/回答小样本作人工验证，不把不稳定调用纳入默认离线回归。
- 002 不建设向量检索、售后地图、设备模拟器，不运行或重新声称通过旧 001 的修复测试。

## R11 — Lombok、record 与兼容迁移

**Decision**: 本期 User、Device、RepairSession、ProblemReport、ChatMessage、RepairActionLog 六个公共实体使用 @Getter + @Setter 替换 @Data，保留公开无参构造、字段和 MyBatis-Flex 映射。现有 14 个 DTO/VO 已为 record，保持形态；五个 View 仅迁包。ProblemAnalysis/DiagnosisConclusion 保留 record 并迁入 ai/model，RoutingDecision、CapabilityRequest 同样选择不可变 record。

**Rationale**: 当前八个 Entity 全用 @Data，但本期六个只发现无参构造、访问器及 Mapper 使用，没有依赖生成 equals/hashCode/toString 的证据。DTO 与 AI 输出既有 record 可直接复用；为了使用 Lombok 改成可变对象会增加迁移风险。

**Alternatives considered**: 批量将所有 DTO 改为 @Data 不符合不可变 DTO 优先 record；替换全部八个 Entity 会触及不属于 002 的领域模型；为 record 一概引入无参构造或 setter 没有框架依据。

- DiagnosticSnapshot 的注解迁移归 001，RepairKnowledge 归 003；新增 Entity/可变 DTO 按当前章程选择访问器与必要构造器，不默认生成实体相等或完整输出。
- 保留 @Table/@Id/@Column（含 User 逻辑删除）、现有字段名及主键回填语义；无参构造可由 @NoArgsConstructor 显式表达，不新增无用途的全参构造、Builder 或 APT 配置。
- pom.xml 有两份 Lombok（一份无版本、一份 1.18.36）；实施时合并为单一 optional=true、保留工作区显式 1.18.36。Boot BOM 虽管理 1.18.38，本期不借去重升级 Lombok；Java21 与当前用法未发现兼容阻碍。
- Jackson/Bean Validation 的原 record 绑定、null/布尔/日期/列表形状和 View 工厂方法保持；需要可变 DTO 时使用 @Data 或 @Getter/@Setter，不改动其职责目录。
- 固定 LangChain4j 1.0.1 的结构化输出与 record 支持按官方版本源码核对；以真实 AI Service 代理和模型协议替身验证三种 AI record 的合法、缺失和非法枚举解析。构造成功不代表模型值可信，解析后仍做组合校验。
- CapabilityRequest 与历史快照中的集合按需复制为只读值；record 只固定组件引用，不自动保证深度不可变。既有公共DTO的null语义保持。
- 注解/包迁移以现有用户、设备与会话回归补充 ORM 插入/读取/更新、主键回填和 JSON/校验断言，不建立仅检查注解存在的镜像测试。

**Evidence**: 本地 1.0.1 sources.jar 核对 [PojoOutputParser](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/output/PojoOutputParser.java)、[JsonSchemaElementUtils](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j-core/src/main/java/dev/langchain4j/internal/JsonSchemaElementUtils.java) 按非静态字段生成说明/Schema，并经 [JacksonJsonCodec](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j-core/src/main/java/dev/langchain4j/internal/JacksonJsonCodec.java) 解析；本地 Boot 管理的 Jackson 2.19.1 使用 [JDK14Util](https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.19.1/src/main/java/com/fasterxml/jackson/databind/jdk14/JDK14Util.java) 识别 record 规范构造器。该 codec 与 Spring HTTP ObjectMapper 独立，因此 HTTP record 回归不能替代 AI Service 解析验收。MyBatis-Flex 1.10.9 本地 processor 按字段处理，不依赖 @Data；现有编译路径无需因注解替换改造。

## R12 — Boot 3.5.3 的 Log4j 2 替换

**Decision**: 显式声明 spring-boot-starter 并排除 spring-boot-starter-logging，增加 spring-boot-starter-log4j2，保持 Boot 3.5.3 管理的 Log4j 2 2.24.3 / SLF4J 2.0.17；不加日志 BOM 或新版本覆盖。新增 log4j2-spring.xml，默认 Console、root/项目 INFO，LOGGING_CONFIG 可覆盖。runtime/test 依赖树必须证实单一提供者。

**Rationale**: 当前没有 Log4j 2 starter 或专用日志配置；web/validation/security/data-redis/test 和 springdoc 的传递依赖使“只排除 web 的默认日志”不足。官方 Maven 方案以直接 starter 的排除完成依赖选择；若实际依赖树仍有旁路，再针对具体路径排除。MyBatis-Flex 1.10.9 的本地 POM 使用 boot-autoconfigure，不据 starter 名称臆造日志依赖。

**Alternatives considered**: 只增加 Log4j 2 而保留 Logback 导致多提供者；手工拼装 API/Core/桥接增加维护负担；直接换用最新 BOM 与固定基线无关；默认文件、异步日志及采集平台没有本期需求。

- Log4j 2 starter 包含 log4j-slf4j2-impl、log4j-core、log4j-jul。禁止同时存在反向 log4j-to-slf4j、旧 log4j-slf4j-impl 或其他 SLF4J provider。
- 业务通过 SLF4J/@Slf4j，不直接耦合 Core；仅日志装配验证需要识别实际提供者。
- 配置项和输出格式见[日志契约](contracts/logging-contract.md)。若部署启用文件 appender，其外置配置必须给出滚动/有限保留；不使用 Logback 专属配置键推断 Log4j 行为，也无需添加 log4j-spring-boot。
- 实施时检查依赖树和实际启动绑定，不把 pom 字符串检查当作运行成功。

**Evidence**: [Boot 3.5 日志接入](https://docs.spring.io/spring-boot/3.5/how-to/logging.html)、[Boot v3.5.3 Log4j2 starter](https://raw.githubusercontent.com/spring-projects/spring-boot/v3.5.3/spring-boot-project/spring-boot-starters/spring-boot-starter-log4j2/build.gradle)、[v3.5.3 依赖管理](https://raw.githubusercontent.com/spring-projects/spring-boot/v3.5.3/spring-boot-project/spring-boot-dependencies/build.gradle)、[Log4j 2.24.3 桥接冲突检查](https://raw.githubusercontent.com/apache/logging-log4j2/rel/2.24.3/log4j-slf4j2-impl/src/main/java/org/apache/logging/slf4j/Log4jLoggerFactory.java)、[Boot 日志配置](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html)。确切版本以固定 tag 与本地 POM 为依据，不把 3.5 在线文档中的后续版本当成当前项目版本。

## R13 — 英文关键日志、异步 MDC 与可靠追溯

**Decision**: 002 为公共链补英文参数化关键事件，用白名单关联字段贯穿 HTTP、任务执行、AI 回调及超时收尾；状态成功日志在提交后记录。运行日志和 MySQL 业务追溯分别承担排障与可靠记录职责。

**Rationale**: 当前公共链只有少量中文异常日志，部分直接输出异常消息；没有 MDC。SessionController 将任务交给 applicationTaskExecutor，模型/CompletionStage/定时回调可在别的线程执行，仅使用 TaskDecorator 或线程继承无法保证关联。

**Alternatives considered**: 全量打印用户对话和模型内容违反安全规范；日志代替 repair_action_log 破坏持久化语义；HTTP 返回 emitter 就记录成功会掩盖异步失败；逐token和逐项探测 INFO 带来噪声且无业务价值。

- requestId 服务端生成；其余ID在验证/接纳后加入，不使用模型给出的身份，不为缺失上下文编造ID。
- 每种异步入口显式捕获不可变白名单快照、作用域安装、finally 恢复旧 MDC；旧回调保留旧消息关联，不从当前会话覆盖它。MDC 不承担授权/锁/处理指针职责。
- 认证/校验拒绝 WARN，正常路由/澄清和已提交状态 INFO，未预期失败 ERROR；同一异常仅最终边界打印一次脱敏堆栈。SSE断线及重复回调受控 DEBUG，列表/轮询采用汇总。
- 不记录原始异常 message/cause/suppressed 内容、完整 DTO/Entity/record 或 Redis token 键。对象自动生成 toString 并不适合日志；脱敏需覆盖异常渲染与 CR/LF 等控制字符。
- 003/004/001/005 的领域检索/读取/诊断/控制日志有明确后续归属；002 自己补齐公共链，不宣称全仓日志已经完成。
- 沿用关键行为测试捕获运行日志，增加并发线程复用/同步与异步回调、超时和失败、敏感标记/控制字符、事务回滚及日志量的行为断言。

**Evidence**: [SLF4J MDC API](https://www.slf4j.org/apidocs/org/slf4j/MDC.html)、[Log4j Thread Context](https://logging.apache.org/log4j/2.x/manual/thread-context.html)。putCloseable 会移除指定键，不自动恢复被覆盖的旧值；实现应显式复制/恢复而非假定关闭即恢复。

## R14 — 提示词资源、启动校验与变量数据编码

**Decision**: 在src/main/resources/prompt/定义四个系统资源与两个共享用户包装资源；AiServiceFactory四个公开嵌套接口在方法上分别声明@SystemMessage(fromResource)与@UserMessage(fromResource)，路径统一为/prompt/文件名。固定规则从LangChain4jConfig移出，真实调用继续经过AI Service；资源清单和语义见[prompt契约](contracts/prompt-contract.md)。

**Rationale**: 当前四条实际低层调用把规则/用户/历史拼成Java文本块，两个内嵌注解接口未被使用。固定1.0.1在调用阶段prepareSystemMessage/prepareUserMessage读取资源，build不校验；系统注解仅检查方法。资源提取通过接口Class.getResourceAsStream，使用未显式指定字符集的Scanner。资源化不能只新增文件或假定Bean创建成功即验证通过。

**Alternatives considered**: 排除内联value、工厂拼接模板、systemMessageProvider取代必需注解、接口级系统注解及读文件后继续低层直调。六文件方案复用三个代理的history/text包装；给每个代理复制一份相同包装没有额外价值。

**Decision**: 真实模式工厂先执行本地预校验，再发布代理。核对实际方法注解、六资源存在/可读/非空/无BOM/严格UTF-8、默认JVM字符集、精确变量集及非null样例渲染；使用classpath流，失败阻止装配且不触发模型请求。默认离线测试即使应用mock模式也覆盖真实工厂和生产主资源。

**Rationale**: Java21通常默认UTF-8，但部署可改变默认字符集；因此文件编码和SDK实际解码都需一致。变量提取可识别空格，1.0.1最终替换却只处理精确的{{name}}；全部@V实参参与渲染，null参数可能使不引用它的系统模板也报错。统一精确变量和非null表示，减少延迟到请求时的配置故障。

**Alternatives considered**: 不依赖源码磁盘路径/getFile，不用真实模型暖机校验资源，不增加热加载或远程prompt管理。仅检查文件存在、反射注解或AiServices.build成功都不足以证明资源可用。

**Decision**: 运行时text/history/symptom/diagnostics只进入用户消息并序列化为JSON值。新增utils/PromptInputEncoder，使用独立Jackson ObjectWriter/CharacterEscapes，只将字符串值及键内的花括号编码为\u007b/\u007d，保留JSON结构与解码后逻辑值；不改公共ObjectMapper或持久化原文。history空→[]，diagnostics的null Map→{}，symptom缺失→字符串形式的JSON字面量null。

**Rationale**: 1.0.1 DefaultPromptTemplateFactory遍历变量Map并连续String.replace，参数数据含后续变量名或SDK隐式current_date等标记时可能再次替换。仅使用@V或普通JSON编码不能保证字面花括号保持。专用字符串转义消除数据内的模板标记，同时保持用户原文、历史内容和诊断结构；真实代理捕获测试验证解码后值一致，而非简单扫描渲染文本是否含双花括号。

**Alternatives considered**: 不拒绝用户合法花括号，不依赖Map遍历顺序，不把参数上的@UserMessage原文当模板，不全局替换JSON结构括号。无需升级SDK、替换全局PromptTemplateFactory SPI或新增第三方模板引擎。

**Decision**: 同步返回record仍使用SDK结构化输出；项目固定语义规则在资源，SDK自动生成的输出格式后缀/response format保留。TokenStream单独覆盖。真实代理测试、装配失败零模型调用与Boot JAR资源字节检查共同构成验收。

**Rationale**: 同步AI Service可能向最终user消息追加返回类型格式说明，TokenStream不走同一输出解析分支；测试应识别用户资料和SDK后缀，不要求整条请求逐字等于文件。当前pom没有资源过滤配置，沿用Maven默认打包即可；实现时检查实际六条目及字节，只有发现过滤/编码问题才修构建配置。

**Alternatives considered**: 不在资源中复制完整record schema，不恢复手写JSON解析，不让测试同名资源遮盖生产模板；不把日志或文件存在检查当成实际调用证据。

**Evidence**: 另已核对Boot管理的Jackson2.19.1官方sources.jar：ObjectWriter.with(CharacterEscapes)独立设置writer生成器，CharacterEscapes作用于字符串值及属性名，结构括号由生成器直接写入。编码范围限定普通JSON数据，排除RawValue/自定义writeRaw等旁路，编码后不再次反序列化再toString。已只读核对本地官方langchain4j-1.0.1-sources.jar与langchain4j-core-1.0.1-sources.jar中的SystemMessage/UserMessage、DefaultAiServices、InternalReflectionVariableResolver、PromptTemplate、DefaultPromptTemplateFactory和ServiceOutputParser；版本来源对应[1.0.1 DefaultAiServices](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/DefaultAiServices.java)、[1.0.1模板实现](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j-core/src/main/java/dev/langchain4j/model/input/DefaultPromptTemplateFactory.java)。默认字符集依据[Java21 Charset](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/charset/Charset.html#defaultCharset())；输入转义是针对已核对替换行为作出的项目设计，不宣称SDK自动防止二次替换。

## Research closure

规格中的 15 条 FR 均有后续设计与验证归属，无未解决的技术澄清。待实施工作包括两列前向迁移、代理/分发重写、DTO/VO 归位、六个实体的 Lombok 调整、Log4j 2 接入/英文日志、六prompt资源/装配校验/JSON绑定与打包验证、令牌一致性、公共异步隔离与专项验收；均在本 feature 的 plan/data-model/contracts/quickstart 展开。原有 001 的 plan 仅作为研究参考，不作为 002 的完成证据。

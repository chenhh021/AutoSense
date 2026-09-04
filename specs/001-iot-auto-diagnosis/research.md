# Phase 0 Research: IoT 设备自动诊断与修复

**Date**: 2026-08-22(2026-08-27、2026-08-29、2026-09-04 刷新) | **Plan**: [plan.md](./plan.md)

本文件解决 Technical Context 中的所有未知项与技术选型决策。
2026-08-27 刷新:R9 撤销(保留策略变更)、R11 修订(对接 deviceSimulator),
新增 R12~R17；2026-09-04 再次修订 R10/R11/R16，并新增 R24~R26。

## R1. Spring Boot 版本对齐

- **Decision**: 将 pom parent 从 4.1.0 降级为 **3.5.3**(Spring Framework 6.2.x)。
- **Rationale**: 章程原则 II 固定基线为 Spring Boot 3.5.3;LangChain4j 官方
  `langchain4j-spring-boot-starter` 1.x 系列面向 Boot 3.x 验证，4.x 存在兼容风险。
- **Alternatives considered**: 保留 4.1.0 —— 违反章程且生态（MyBatis-Flex、
  LangChain4j starter）未充分验证，否决。

## R2. LLM 编排方式：确定性状态机 vs 自由工具调用

- **Decision**: 修复会话由**确定性状态机**编排（见 data-model.md 状态定义）;
  LLM 仅承担两类职责:(a) 语义分析——从用户输入提取结构化属性（设备类型、
  问题表现、复现方式）;(b) 诊断推理——结合诊断快照与 RAG 检索结果生成问题结论。
  修复动作**不**走 LLM tool-calling，只能由状态机调用设备适配器的白名单动作。
- **Rationale**: spec FR-008/FR-017 要求修复动作受控、失败即停；让 LLM 自由决定
  调用设备写操作会把幻觉风险直接传导到用户家中设备，不可接受。确定性编排也
  与 spec 固定的 8 步运行逻辑一一对应，便于测试与审计（FR-013)。
- **Alternatives considered**: LangChain4j @Tool 全自动编排（灵活但安全性与
  可测试性不达标）；纯规则无 LLM（无法处理自然语言输入，否决）。

## R3. LangChain4j 集成形态

- **Decision**: 使用 `langchain4j-spring-boot-starter` 装配 ChatModel；
  语义分析与诊断推理用 LangChain4j **AiServices** 声明式接口，结构化输出映射到
  Java record(`ProblemAnalysis`、`DiagnosisConclusion`)；模型提供商、base-url、
  api-key、model-name、temperature、timeout 全部经 `application.yaml`
  （环境变量占位）注入，满足章程原则 V。
- **Rationale**: AiServices 结构化输出可直接产出强类型结果，替代手写 JSON 解析；
  starter 的 yaml 配置天然符合配置外部化要求。
- **Alternatives considered**: 手写 ChatLanguageModel 调用 + Jackson 解析（冗余）;
  直连厂商 SDK（违反章程原则 IV，否决）。

## R4. RAG 知识库与向量存储

- **Decision**: 知识条目（repair_knowledge）持久化在 **MySQL**；向量索引使用
  **LangChain4j Redis Embedding Store**(`langchain4j-redis`，依赖 RediSearch)。
  应用启动/知识变更时将 MySQL 条目同步嵌入到 Redis;`EmbeddingStoreContentRetriever`
  按问题结论检索 Top-K 方案。Redis 键前缀 `autosense:rag:`,TTL 不适用（索引型数据，
  以显式失效/重建管理，属章程允许的缓存管理例外并在代码注释说明）。
- **Rationale**: 章程技术栈已有 Redis，无需引入新向量库组件；MySQL 作为知识
  的权威来源保证可运维（CRUD/审计）,Redis 仅作检索加速。
- **Alternatives considered**: PGVector（章程无 PostgreSQL，引入新组件，否决）;
  纯内存 EmbeddingStore（重启即丢、不可水平扩展，否决）；直接全文检索 MySQL
  （中文语义匹配弱，否决）。

## R5. 设备扩展机制（FR-012）

- **Decision**: 定义 `DeviceAdapter` SPI(`getDiagnostics()` / `executeRepair(action, params)` /
  `supportedRepairActions()`)。受支持设备类型注册表由 **yaml 配置**驱动：
  每个类型声明 code、诊断项清单、可执行修复动作白名单；Adapter 实现为 Spring Bean。
  新增设备类型 = 新增一个 Adapter Bean + yaml 注册条目 + 知识库内容，核心状态机
  零改动。
- **Rationale**: 满足 SC-006(1 个工作日内配置化接入）；类型注册外置到 yaml 符合
  章程配置外部化原则，白名单动作显式声明符合修复安全性要求。
- **Alternatives considered**: 设备类型存数据库动态注册（首期无运营界面，过度设计）;
  硬编码 if/else 分支（违反 FR-012，否决）。

## R6. 设备互斥锁与会话态存储（FR-016）

- **Decision**: Redis 分布式锁 `autosense:lock:device:{deviceId}`,`SET NX PX`
  (TTL 10 分钟，状态机推进时续期，会话终态释放）。进行中的会话上下文存
  `autosense:session:{sessionId}`(Hash,TTL 30 分钟滚动）；终态会话完整落 MySQL。
  获取锁失败即返回"该设备正在处理中"(HTTP 409)。
- **Rationale**: Redis 已在章程栈内；锁 TTL + 续期避免服务崩溃导致的死锁；
  活跃会话放 Redis 避免高频状态读写打 MySQL。
- **Alternatives considered**: MySQL 行锁/唯一约束（长事务风险、无法表达"进行中"
  的 TTL 语义，否决）；应用内内存锁（多实例部署失效，否决）。

## R7. 鉴权方案（FR-015）

- **Decision**: Spring Security 资源服务端模式：请求头携带平台已签发的访问令牌，
  本服务校验令牌并解析 userId（具体签发方是平台既有账号体系，spec Assumption);
  设备归属校验在服务层执行（device.user_id == token.userId)，越权返回 403。
  令牌校验相关配置（issuer/jwks 或共享密钥）经 yaml 注入。
- **Rationale**: spec 假设平台已有登录体系，本服务只做资源端校验，不重复实现
  登录；服务层归属校验逻辑简单且可单测。
- **Alternatives considered**: 本服务自建账号体系（超出 spec 范围，否决）;
  信任网关透传头（零信任不足，仅在部署拓扑确定后可作为简化项）。

## R8. 售后网点查询集成（FR-011）

- **Decision**: 定义 `AfterSalesClient` 接口，首期实现走外部地图/POI 服务的
  HTTP API(Spring **RestClient**,Boot 3.5 标准同步客户端）;base-url、api-key、
  默认搜索半径（10km)、超时经 yaml 注入。位置入参为文字地址/城市（API 调用方
  在会话中提供，spec Assumption)。查询无结果或位置缺失时返回品牌官方客服兜底
  （官方客服信息同样经 yaml 配置）。
- **Rationale**: RestClient 是 Boot 3.5 推荐的同步 HTTP 客户端，无需额外依赖；
  兜底策略直接来自 spec 边界场景要求。
- **Alternatives considered**: WebClient/WebFlux（引入响应式栈与既定同步 MVC 不一致，
  复杂度无收益，否决）；自建网点库（数据维护成本高，否决）。

## R9. ~~会话记录 90 天保留(FR-013)~~ → 撤销(2026-08-22 对话记忆澄清)

- **Decision(已撤销)**: ~~`@Scheduled` 每日清理 90 天前记录。~~
- **现行决定**: 对话记录**长期保留,不设删除期限**(用户选择"全部长期保留",
  撤销 90 天策略)。`SessionRetentionJob` 移除;`retention.days` 配置项删除。
  MySQL 表保留 `created_at` 索引供列表查询排序与后续运营使用。
- **Rationale**: 对话记忆机制(FR-018)要求每个对话可长期回看与继续,90 天清理
  与之直接冲突。
- **Alternatives considered**: 90 天后匿名化(用户已明确否决)。

## R10. 外部依赖与测试策略(2026-08-27 修订)

- **Decision**: LLM、售后 API 在测试中一律以 **WireMock** 打桩;MySQL/Redis 用
  **Testcontainers**;**设备路径连真实 deviceSimulator 实例**(2026-08-27 澄清)。
  接入方式固定为:docker-compose 启动的模拟器(固定地址,默认
  `http://localhost:8081`),集成测试经 `DEVICE_SERVICE_BASE_URL` 指向该实例,
  与手动开发环境同一形态(不用 Testcontainers 内嵌模拟器,避免双形态维护);
  测试启动前经 `/healthz` 等待就绪。设备夹具先直接调用模拟器创建并取得 SN，再调用
  AutoSense 的设备添加接口完成绑定；故障经模拟器命令预置 state + yml 规则构造
  (R13 注:指 R12 规则引擎),不经 WireMock/Mockito 桩模拟设备端到端行为。
  LangChain4j model 与 retriever 通过配置指向 WireMock 端点(验证配置外部化)。
- **Rationale**: 模拟器接口稳定且有独立实现,连真实实例可同时验证 HTTP 契约与
  故障规则端到端行为;LLM 不可进 CI 的约束不变。
- **Alternatives considered**: WireMock 打桩设备接口(无法验证真实契约偏差,用户
  已否决);仅单测桩(覆盖不足,用户已否决)。

## R11. 设备交互通道:HTTP 对接外部 deviceSimulator(2026-08-27 修订)

- **Decision**: 设备诊断信息采集(FR-005)与操作下发(FR-008)的唯一实现为
  **HTTP 调用外部 deviceSimulator 服务**(已有外部实现,本项目不开发模拟器本体;
  契约见仓库根《后端接口说明.md》)。`DeviceSimulatorClient` 实现
  `DeviceServiceClient` 接口(Spring RestClient,与 R8 一致);base-url(默认
  `http://localhost:8081`)、超时经 `application.yaml` + `@ConfigurationProperties`
  注入(章程原则 V);模拟器无认证,但 base-url 必须可配置以指向不同环境。
  **2026-08-22"项目内硬编码 mock、所有操作一律成功"的决定撤销**,Mock 实现删除。
  关键端点映射:`GET /devices/{id}/data` → 诊断探测(state 读取,免确认,FR-008);
  `POST /devices/{id}/commands` → 改变状态的操作(必经用户确认);
  `POST /devices/{id}/start` → stopped 设备的恢复操作;
  `GET /devices/by-sn/{sn}` → 设备添加前的只读发现(FR-020)。旧的 `POST /devices`
  代建能力自 2026-09-04 起从本项目客户端移除。
- **Rationale**: 用户明确"模拟器已有外部实现,从指定 url 调用即可"(2026-08-27);
  接口先行不变,适配器与状态机对客户端实现无感知,未来接真实设备服务仅换 Bean。
- **Alternatives considered**: 并存内置 mock 与模拟器双实现(用户选择完全替代);
  WireMock 桩模拟器(集成测试要求连真实实例,R10)。

## R12. 配置化故障判定规则(2026-08-27 新增)

- **Decision**: 模拟器无故障注入,故障判定由**规则引擎**完成:yaml 设备类型注册表
  为每类设备声明 `fault-rules` 列表,每条规则 = 条件(state 字段阈值/枚举匹配,
  如 `brightness < 5`、`running_status == stopped`)+ 结论类型
  (`AUTO_REPAIRABLE` / `MANUAL_ONLY` / `AFTERSALES`)+ 关联修复动作码或人工步骤引用。
  规则按序匹配,首个命中生效;无命中 → `NORMAL`(无异常,结合用户症状给观察建议)。
  修复动作 = 模拟器命令(set_brightness/set_color_temperature/set_color/start),
  仍在白名单管控下经状态机下发。
- **Rationale**: 满足"状态值映射故障+命令修复"澄清;规则配置化符合章程原则 V 与
  FR-012 扩展约束;确定性判定可测试(不依赖 LLM 自由判断),US2 不可修复分支可
  用规则确定构造。
- **Alternatives considered**: LLM 自由判断故障(不确定、验收断言难写,否决);
  模拟器侧注入故障(模拟器为外部服务不可改,否决)。

## R13. 意图路由实现(FR-019,2026-08-22 新增)

- **Decision**: 新增 `routing/` 包。入口由 LLM 意图分类器(AiServices 结构化输出:
  `intent ∈ COMMON_SENSE | DEVICE_ACTION | MODEL_SPECIFIC | AFTERSALES_QUERY | UNCLEAR`)
  完成;`MODEL_SPECIFIC` 本期并入 `COMMON_SENSE` 直接回答(型号 RAG 后续增强);
  `UNCLEAR` → 复用 CLARIFYING 追问(FR-002);`DEVICE_ACTION` → 现有诊断修复状态机;
  `AFTERSALES_QUERY` → 独立网点查询路径(无需先经过修复流程)。常识/型号回答与
  网点查询同样经状态机落 chat_message 与会话记录(FR-013 长期保留)。
- **Rationale**: 风险不对称——误分类进 DEVICE_ACTION 会触碰设备,故不确定时必须
  追问;路由层用 LLM 分类是处理自然语言边界的唯一现实方案,但分类结果只决定
  "是否进入设备流程",设备写操作仍由状态机+确认门管控。
- **Alternatives considered**: 关键词规则路由(中文表达边界覆盖差,否决);LLM 自由
  工具调用(违反 R2 安全约束,否决)。

## R14. 网点查询本期形态(FR-019 路由 4,2026-08-22 新增)

- **Decision**: 网点查询本期由**项目内固定 mock 数据**生成(与设备 mock 时代的既定
  方针一致,确定可测):`AfterSalesClient` 增加 `MockAfterSalesClient`(固定网点列表,
  按城市/区域键路由),经配置开关启用;接口与配置(base-url/api-key)保持 R8 形态,
  后续接入地图搜索工具仅替换实现 Bean。
- **Rationale**: 用户明确"目前模拟生成,后续调用地图搜索工具";固定数据可写确定性
  验收断言。
- **Alternatives considered**: LLM 现场编造网点(幻觉地址当事实展示,否决)。

## R15. 对话记忆(FR-018,2026-08-22 新增)

- **Decision**: LangChain4j **ChatMemory**:`MessageWindowChatMemory`(maxMessages=20,
  窗口大小经 yaml 配置)+ 自定义 `ChatMemoryStore` 持久化到 Redis
  (键 `autosense:chatmemory:{sessionId}`,TTL 与会话上下文一致 30min 滚动;
  冷启动从 MySQL `chat_message` 表重建最近 20 条)。意图分类器与诊断推理的 AiServices
  挂接同一 memoryId(= sessionId)。用户侧:新增 `GET /api/v1/sessions` 历史对话列表
  (按 userId 过滤,FR-015);继续已有对话 = 对该 sessionId 调 `POST /messages`;
  终态会话收到新消息时状态机从终态迁移回 `ANALYZING`(新一轮诊断)。
- **Rationale**: 满足"最近 20 条窗口、超出部分仍长期留存可查"澄清;ChatMemory 是
  LangChain4j 标准机制,MySQL 长期留存与 Redis 热窗口分层符合既有数据纪律。
- **Alternatives considered**: 全量历史送入 LLM(token 成本与上下文上限不可控,否决);
  仅存 MySQL 不经 Redis(每次请求全表读,性能差,否决)。

## R16. 按 SN 发现并绑定设备(FR-020,2026-09-04 修订)

- **Decision**: 保留认证端点 `POST /api/v1/devices`，请求改为 `sn` + 用户显示名称
  `name`。流程为本地格式/重复检查 → deviceSimulator
  `GET /api/v1/devices/by-sn/{sn}` → `exists:true` 稳定字段校验 → 写入用户绑定；
  不再接收用户提交的类型/型号，不再调用模拟器创建设备。添加阶段不检查诊断支持列表，
  未支持类型/型号仍可绑定和列出，诊断入口再按 FR-004 拒绝。
- **Rationale**: 类型、型号和模拟器 ID 必须以设备服务返回为权威，避免用户输入与真实
  设备不一致；发现和诊断支持解耦后，新设备可先进入列表，后续启用适配器无需重新绑定。
- **Alternatives considered**: 保留类型/型号入参（存在冲突来源，否决）；并存代建与
  SN 认领两条路径（职责互斥且扩大测试面，否决）；添加时拒绝不支持型号（与澄清冲突）。

## R17. 终态对话续聊的状态机扩展(FR-018,2026-08-22 新增)

- **Decision**: 状态机增加迁移 `任意终态 → ANALYZING`(触发条件:终态会话收到新的
  用户消息)。新一轮诊断复用同一 sessionId 与对话线程;`problem_report` 每次轮次
  新建一条(会话 1─N 问题报告);设备互斥锁在新一轮进入 LOCATING 时重新获取。
  `repair_session.status` 从终态回到进行中态,终态结论字段保留在当轮
  problem_report/快照中供追溯。
- **Rationale**: 满足"终态可继续,同一线程新一轮诊断"澄清;sessionId 不变使对话
  记忆(R15)与列表语义简单。
- **Alternatives considered**: 终态不可变、新问题强制新会话(用户已否决)。

## R18. SSE 流式推送实现(FR-021,2026-08-27 新增)

- **Decision**: `POST /sessions` 与 `POST /sessions/{id}/messages` 返回
  `text/event-stream`(Spring MVC **SseEmitter**,同步 servlet 栈内标准方案)。
  事件类型:`token`(LLM 逐 token 文本)、`status`(状态机迁移)、
  `awaiting`(进入等待用户态,携带待答问题)、`conclusion`(终态结论)、
  `error`(错误,携带统一错误码)。LLM 侧启用 LangChain4j **StreamingChatModel**
  (yaml 配置同源;mock 模式逐段回放以保测试确定性)。进入等待用户态或终态时
  服务端 `complete()` 结束流;**断线不补发**(无事件持久化),客户端用
  `GET /sessions/{id}` 补查。修复执行在确认后的同一条流内继续推进
  (REPAIRING→VERIFYING→终态事件),原"异步化+轮询"复杂度取消。
- **Rationale**: 用户明确"POST 即 SSE 流、等待/终态即关闭、断线靠轮询补查";
  SseEmitter 无需引入 WebFlux(与既定同步 MVC 栈一致);状态机每步迁移天然是
  事件源,token 流由 StreamingChatModel 的 onPartialResponse 桥接。
- **Alternatives considered**: WebSocket(双向能力本期无用,运维更重,否决);
  WebFlux SSE(引入响应式栈,与同步 MVC 不一致,否决);独立订阅端点
  (订阅竞态+事件缓冲复杂,用户已否决);Last-Event-ID 补发(事件需持久化,
  用户已否决)。

## R19. 登录令牌形态与存储(FR-023/025,2026-08-29 新增)

- **Decision**: 登录成功生成密码学随机令牌(`SecureRandom` 32 字节 Base64URL,不带
  签名结构),写入 Redis 键 `autosense:token:{token}`,值为 JSON `{userId, role}`,
  **TTL 7 天、每次鉴权命中滚动续期**;注销=删除该键(立即失效,FR-025);管理员
  禁用用户=删除该用户全部令牌键(经辅助索引 `autosense:usertokens:{userId}` Set
  记录其令牌,禁用时整组清除,FR-027)。令牌不透明,客户端不解析。
- **Rationale**: 澄清已定"Redis 令牌"方案;不透明随机令牌无伪造面,注销/禁用
  即时生效(相比 JWT 无需吊销列表);滚动 TTL 符合既有 Redis 键纪律。
- **Alternatives considered**: 无状态 JWT(吊销/禁用需额外黑名单,用户已否决);
  MySQL 存令牌(每次请求查库,Redis 已在栈内,否决)。

## R20. 密码加密与校验(FR-022,2026-08-29 新增)

- **Decision**: 引入 `spring-security-crypto`(已有 Spring Security,零额外框架),
  `BCryptPasswordEncoder` Bean;注册时 `encode()` 入库,登录时 `matches()` 比对。
  校验规则在应用层 Bean Validation + 服务层双重执行:账号 4~32 位
  `^[a-zA-Z0-9_]+$` 唯一(库表 uk_userAccount 兜底,冲突映射 409);密码 8~64 位且
  同时含字母与数字;确认密码必须一致。昵称缺省=账号。任何接口响应/日志不得输出
  密码或散列(响应 DTO 无 password 字段,实体序列化排除)。
- **Rationale**: BCrypt 自带盐、抗彩虹表,是 Spring 生态标准;服务层双写校验保证
  即使绕过 MVC 层也无法入库弱口令。
- **Alternatives considered**: Argon2(更强但需额外依赖,BCrypt 对本场景足够);
  SHA-256+盐(不如 BCrypt 的自适应成本,否决)。

## R21. 认证链路改造与 dev-mode 共存(FR-023,2026-08-29 新增)

- **Decision**: `UserTokenResolver` 扩展为两级解析:先按真实令牌查 Redis
  `autosense:token:{token}`(命中则续期并返回 `AuthUser(userId, role)`);未命中且
  `AUTH_DEV_MODE=true` 时才接受 `user-{id}` 形式(角色固定 user)。`AuthUser` 增加
  `role` 字段。`SecurityConfig` 放行 `POST /api/v1/users/register`、`POST
  /api/v1/users/login`(匿名可达),其余 `/api/**` 维持 authenticated;`/api/v1/admin/**`
  追加 admin 角色检查,非管理员返回 403。生产部署必须 `AUTH_DEV_MODE=false`。
- **Rationale**: 澄清已定"dev 令牌保留为显式开关、真实令牌优先";两级解析对既有
  控制器/集成测试零侵入(dev 令牌仍可用于本地调试)。
- **Alternatives considered**: 一次性移除 dev 令牌(本地与测试调试成本升高,用户
  已否决);角色放 Redis 之外每次查库(热点读浪费,角色变化本期不存在,否决)。

## R22. user 表落库与 camelCase 列映射(FR-022,2026-08-29 新增)

- **Decision**: 用户表 DDL 按用户给定原样采用(camelCase 列名:`userAccount`/
  `userPassword`/`userName`/`userAvatar`/`userProfile`/`userRole`/`editTime`/
  `createTime`/`updateTime`/`isDelete`),写入 schema.sql(`create table if not
  exists`)。实体 `User` 使用 MyBatis-Flex `@Table("user")`,因列名非 snake_case,
  无法走默认驼峰转换,**每个字段显式 `@Column`**;`isDelete` 配 `@Column(isLogicDelete
  = true)` 使禁用/启用走逻辑删除语义;`createTime`/`updateTime` 由 DB 默认值与
  `on update` 维护(`editTime` 由应用更新资料时显式赋值)。注意实体类名 `User`
  与既有 `AuthUser`(安全上下文)区分。
- **Rationale**: 用户明确给定 DDL,保持与既有库表风格不同的列名是用户决定;显式
  `@Column` 是 MyBatis-Flex 对非默认命名列的标准做法;逻辑删除复用框架能力避免
  手写 `isDelete=0` 条件。
- **Alternatives considered**: 改列名为 snake_case(违背用户给定 DDL,否决);
  关闭全局驼峰映射(影响既有全部实体,否决)。

## R23. 初始管理员种子与注册防提权(FR-027,2026-08-29 新增)

- **Decision**: data.sql 幂等种子初始管理员:账号 `admin`,密码为**预计算 BCrypt
  散列**(对应明文仅用于首次部署后登录,README 注明上线即改密;散列值硬编码在
  data.sql,不明文入库)。`INSERT IGNORE` 保证重复启动幂等。注册端点入参 DTO
  **不含 role 字段**,服务层写死 `userRole='user'`;即使请求体夹带 role 也忽略
  (Spring 默认忽略未知字段)。
- **Rationale**: 满足"初始管理员存在"边界条件且无明文凭据;注册不接受角色是
  防水平/垂直提权的标准做法(澄清已定)。
- **Alternatives considered**: 启动时 Java 代码种子(需注入 encoder,生命周期复杂,
  data.sql 更简单);注册允许自选角色(明显提权漏洞,否决)。

## R24. SN 查询客户端、响应模型与错误分类(2026-09-04 新增)

- **Decision**: `DeviceServiceClient` 以 `findDeviceBySn(String sn)` 替换
  `createDevice(...)`，返回强类型 `DeviceLookupResult`：可空 `Boolean exists` 用于检测
  协议缺字段；`exists:true` 时必须含模拟器设备 ID、SN、类型/型号编码与 ID、模拟器原始
  名称。HTTP 实现调用 `GET /api/v1/devices/by-sn/{sn}`，只映射上述稳定字段并忽略
  `state`、`running_status`、`created_at`、`updated_at`。SN 按原值精确匹配，不 trim、
  不改大小写；入口校验 `^[A-Z0-9]{4}[0-9]{9}$`。
- **Rationale**: 上游成功体是 `exists` 与设备详情同层的扁平对象，`exists:false` 时只有
  一个字段。强类型模型可区分正常未发现与缺少 `exists`/稳定字段的协议错误，并从类型
  层面阻止易变状态进入持久化模型。现有 `CreatedDevice` 只有 ID/SN/name，已不足以满足
  FR-020；接口契约见 `documents/新增接口说明-按SN查询设备.md`。
- **Alternatives considered**: `Optional<Device>`（丢失正常未发现与畸形响应的区别）；
  `JsonNode`/`Map`（必填字段校验弱）；映射完整响应（诱导持久化易变状态），均否决。

- **Decision (错误映射)**: 上游 `200 + exists:false` → `404 DEVICE_NOT_FOUND`，消息
  “设备不存在或不在线”；上游 400/本地格式错误 → `400 BAD_REQUEST`；上游 5xx、连接
  失败、连接/读取超时或 2xx 畸形响应 → `503 DEVICE_SERVICE_UNAVAILABLE`，消息
  “设备服务暂不可用，请稍后重试”。所有分支均不得写库；不得泄漏上游 500 的内部 message。
- **Rationale**: 参数修正、检查设备在线状态和稍后重试是三种不同客户端动作；明确状态码
  可避免把基础设施故障误报为设备不存在，也符合上游“先判断 HTTP 状态再读取 exists”的契约。
- **Alternatives considered (错误映射)**: 复用旧 `422 SIMULATOR_ERROR`（调用方无法判断是否可重试）；
  将 5xx/超时降级成 `exists:false`（违反上游契约）；复用诊断期 `DEVICE_UNREACHABLE`
  （混淆单设备不可达与整个服务不可用），均否决。

## R25. SN 全局唯一、并发裁决与事务边界(2026-09-04 新增)

- **Decision**: 保留数据库级 SN 全局唯一约束并命名 `uk_device_sn`，`user_id` 不进入
  唯一键；SN 列收紧为 `CHAR(13) CHARACTER SET ascii COLLATE ascii_bin`。添加流程先做
  本地重复预检，命中即返回 `409 DEVICE_ALREADY_BOUND` 且不访问模拟器；远程只读查询
  在数据库事务外执行；成功后以单条短事务插入。并发请求触发 `DuplicateKeyException`
  时同样映射 `409 DEVICE_ALREADY_BOUND`，不得覆盖记录或暴露原绑定用户。
- **Rationale**: 预检查改善已绑定但当前离线时的错误语义，数据库唯一索引则是两个并发
  请求同时通过预检查后的最终裁决。远程 GET 无副作用，不需要分布式事务，也不应在最长
  10 秒的 HTTP 等待期间占用数据库事务/连接。项目的账号注册已有“唯一约束 +
  `DuplicateKeyException` → 409”的成熟模式可复用。
- **Alternatives considered**: `UNIQUE(user_id,sn)`（允许跨用户重复，违背澄清）；只做
  先查再插（存在 TOCTOU）；Redis/JVM 锁（增加复杂度仍须唯一索引）；HTTP 调用包在长事务
  中（无回滚收益且占用连接），均否决。

## R26. 稳定设备元数据、支持性派生与验证分层(2026-09-04 新增)

- **Decision**: 设备绑定持久化用户显示名称、模拟器原始名称、SN、模拟器设备 ID、
  类型/型号编码与 ID；不保存上游运行状态、state 或时间戳。外部类型事实与平台诊断键
  分离：配置注册表按 `simulator-type-code + model-code` 反向解析现有 adapter 键；无法解析
  时设备仍可列出，`supported=false` 为实时派生值而非数据库列，诊断入口明确拒绝。
- **Rationale**: 当前 `device_type=smart_bulb` 是平台键，而上游返回 `LITE`；混用会破坏
  “未知类型也能添加”。当前代码还用模拟器名称覆盖用户名称并写死 `ONLINE`，会产生名称
  丢失与陈旧状态。分离事实字段、用户字段和派生支持性可保持列表稳定且支持后续扩展。
- **Alternatives considered**: 只存 SN/name（每次列表都依赖上游）；保存完整响应（状态
  过期）；持久化 `supported`（配置变更后陈旧）；把 `LITE` 与 `smart_bulb` 放同一字段
  （语义冲突），均否决。

- **Decision (测试策略)**: 验证分层采用：(1) 新增 `DeviceApiContractTest` 覆盖认证、请求/响应、400/404/409/503、
  未知类型仍 201；(2) 客户端/服务单测覆盖 GET by-sn、true/false/畸形响应、无写库、名称与
  字段映射、重复预检和唯一冲突；(3) `-Pit` 集成测试先直接在真实 deviceSimulator 创建
  设备，再用 SN 绑定，覆盖成功、停止后未发现、重复绑定以及诊断使用实时状态。真实模拟器
  不因“绑定”新增设备；5xx/超时留在隔离的客户端测试中，避免停掉共享集成环境。
- **Rationale**: MVC 契约、外部协议解析、持久化零写入/并发和真实模拟器协作属于不同
  风险层；分层后既能验证真实路径，也不会为 5xx/超时场景破坏共享模拟器环境。
- **Alternatives considered (测试策略)**: 只修改会话契约测试（不覆盖设备端点）；契约测试直连模拟器
  （MVC 合同与外部环境耦合）；继续由 AutoSense 创建测试夹具（会掩盖代建能力回归），
  均否决。

## 结论

所有 Technical Context 未知项已解决，无遗留待澄清项。2026-09-04 设计继续
沿用既有依赖与模块边界，不新增章程例外；进入 Phase 1。

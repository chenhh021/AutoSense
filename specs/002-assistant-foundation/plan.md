# Implementation Plan: 公共基础与统一意图路由

**Branch**: `002-assistant-foundation`（Spec Kit 功能标识） | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)

**Input**: `specs/002-assistant-foundation/spec.md`
**Workspace**: 实际 Git 分支为 `master`，当前 feature 由 `.specify/feature.json` 指向 002；本次不切换或创建 Git 分支。
**Status**: 已于 2026-09-08 完成实施，[56项任务](tasks.md)全部勾选；验证证据见 [validation.md](validation.md)（默认 verify 125 项、显式 -Pit IT 33 项、JAR prompt 逐字节检查、隔离数据手动链）。模拟器相关 IT 与真实模型小样本保留外部前提未运行。
**Constitution**: [2.3.0](../../.specify/memory/constitution.md)，包含 Entity/DTO 形态、英文日志与 AI Service 提示词资源规范。

## Summary

复用已有账号、设备绑定、会话、DTO/VO 和流式响应，以 LangChain4j 1.0.1 AI Service 重写 Intent Router 与 AI Config 的实际调用链。路由区分知识、设备查询、诊断、控制，并显式处理澄清、复合请求与范围外输入。分类结果经服务端校验后只投递至已注册能力，不默认回退旧 DEVICE_ACTION 混合流程。

本 feature 交付公共能力契约及基础修正，不交付 003/004/001/005 的完整业务处理器。生产未注册能力明确不可用；测试使用仅测试装配的记录型处理器证明四路分发、身份/上下文正确和零设备写。

同步修复本范围内会导致基础复用失效的缺口：当前用户查询越层、五个展示对象归位、令牌撤销与索引一致性、20 条只读历史、异步跨轮回调、上下文残留和设备锁 owner 原子校验。完整控制确认、设备命令幂等和修复闭环留给 005/001。

保留公共基础的 Entity/Lombok 迁移和 SLF4J + Log4j 2 接入设计：保留现有不可变 record，精简本期持久实体注解；补全英文关键日志、异步关联和脱敏验收。日志规范不改变用户响应语言，也不替代持久化业务追溯。

本轮将四条 AI 调用的固定规则规划为 `src/main/resources/prompt/` 下的四个系统资源，共享两个用户输入模板；方法通过 @SystemMessage/@UserMessage 的 fromResource 引用。增加本地装配校验、数据编码与实际代理/打包验收，保持已有分类、结构化输出和单次历史输入契约。

## Technical Context

**Language/Version**: Java 21，无 preview。
**Primary Dependencies**: Spring Boot 3.5.3、Spring Security/Web/Validation/Data Redis、MyBatis-Flex 1.10.9、LangChain4j 1.0.1、langchain4j-community-redis 1.0.1-beta6；新增与 Boot 3.5.3 同版本的 spring-boot-starter-log4j2，排除全部默认日志 starter 传递路径；Lombok 复用并消除重复声明，版本决策见 R11。不新增 AI starter。
**Logging**: SLF4J 2 门面 + Log4j 2 单一提供者，版本由 Boot 管理；优先 @Slf4j、英文参数化模板，默认 Console/INFO，配置见[日志契约](contracts/logging-contract.md)。
**Data Objects**: 本期六个持久实体使用 @Getter + @Setter 和必要构造器；现有不可变 DTO/VO 保持 record，新 AI 候选输出及内部请求采用经固定版本验证的 record。
**Prompts**: 六个UTF-8资源随应用打包；系统规则无运行时变量，输入资料经非null JSON参数绑定。LangChain4j 1.0.1 在调用时加载资源且按默认字符集读取，工厂须提前验证资源与UTF-8运行环境，详见[prompt契约](contracts/prompt-contract.md)及R14。
**Storage**: MySQL 为账号、设备、对话与追溯权威源；Redis 承载有 TTL 的 token/index、上下文与租约。仅在 repair_session 增加两个 nullable 处理字段，不新建用户/设备/会话/轮次表。
**Testing**: JUnit 5、Spring Boot Test、MockMvc、Mockito；模型协议使用现有 WireMock 测试依赖；真实 Redis/MySQL 使用已有 Testcontainers；设备路径按既有集成约束连接外部真实 deviceSimulator。
**Target Platform**: 现有 JVM 后端服务，Windows PowerShell 本地开发与 Linux/Docker 部署均沿用既有方式；仓库已有前端作为 API 调用方。
**Project Type**: 同仓库前后端分离 web service，本期仅后端基础与契约。
**Performance Goals**: 同会话同一时刻只接纳一个处理中消息；不同会话可独立处理；模型上下文为 20 条历史加当前输入一次。工程默认模型调用 30 秒、同步重试 0、单消息总截止 120 秒，配置可调；不新增生产 QPS、首诊断时延或可用性 SLA。
**Constraints**: 未确认设备写为零；错误不得假成功；公开 URL/JSON 外形兼容；状态/错误码允许明确的增量扩展。不得引入 Spring AI、模型原生 HTTP 调用或第二套基础设施。
**Scale/Scope**: 四能力分类、一个公共分发契约、现有用户/设备/会话 API；五个 VO 归位、两个 AI 输出归位、六个 Entity 注解调整、两列前向迁移与公共日志接入。容量压测不构成本期规格门槛；线程提交失败与处理超时必须可观察。

## Constitution Check

| 门禁 | Phase 0 前评估 | Phase 1 后结论 |
| --- | --- | --- |
| Java 21 / Boot 3.5.3 固定基线 | 通过；新增日志 starter 须兼容评估 | 设计通过；日志 starter 由同版本 Boot 管理，其他既有版本保持 |
| MySQL / MyBatis-Flex / Redis TTL | 现有组件可复用，token/上下文/锁一致性需改进 | 设计通过；短事务与原子 Redis 操作明确，无持久 Redis 替代 |
| LangChain4j 与配置外部化 | 框架已使用，AI Service 实际接入待重写 | 设计通过；固定版本代理、外部配置、无模型写工具 |
| AI Service资源提示词 / 实际加载验收 | 四条现有调用包含内联模板；SDK build不验证资源 | 设计通过；六资源、方法级fromResource、启动失败边界、输入编码及代理/JAR验收已定义 |
| Controller→service/core→Mapper | UserController.me 存在既有偏离 | 设计通过；本次迁移至 UserService，不放任越层 |
| AI 模型 / DTO / VO / enums / utils 归属 | 既有类型部分偏离 | 设计通过；具体迁移列于下文，保留 JSON |
| Entity / DTO 的 Lombok 与 record | 8个实体默认@Data；现有DTO/VO已为record；pom有重复Lombok | 设计通过；本期6个实体调整，2个领域实体列明后续归属；保持JSON/校验/ORM兼容 |
| SLF4J + Log4j 2 / 英文关键日志 | 默认日志依赖未替换；中文日志及关键覆盖缺口 | 设计通过；单提供者、配置、关键事件矩阵、异步关联与脱敏验收均已定义 |
| 安全与敏感信息 | 现有鉴权存在撤销缺口 | 设计通过；校验 token+索引+账号，路由不构成授权 |
| 最小实现与功能边界 | 公共组件可复用 | 通过；不重建账户/设备，不实现四项领域业务 |
| 验收与工作流 | 当前测试不能证明新路由 | 设计通过；专项验收已定义，运行通过需实施后取得 |

“设计通过”不是当前代码全部符合章程或测试已通过。所有既有偏离都有本期修正或明确的其他 feature 归属，无未解释的章程例外。

## Project Structure

### Documentation (this feature)

```text
specs/002-assistant-foundation/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── tasks.md
├── checklists/requirements.md
└── contracts/
    ├── routing-contract.md
    ├── assistant-api.md
    ├── user-device-api.md
    ├── logging-contract.md
    └── prompt-contract.md
```

[tasks.md](tasks.md) 已按三个P1用户故事同步56项任务，全部待实施；复用原ID及阶段，prompt增量并入相应创建/适配/验收任务，依赖与并行波次已核对，归属见下文。

### Source Code (repository root)

以下为实现目标及主要复用入口，新增类尚未创建：

```text
src/main/java/com/chh/autosense/
├── controller/                       # 保留入口；移除直接 Mapper 访问
├── service/user/                     # UserService / AuthTokenService 增量修正
├── core/
│   ├── routing/                      # IntentClassifier、分发器和能力接收契约
│   ├── session/                      # 公共编排、处理生命周期、上下文与共享锁
│   │   ├── memory/                   # 只读历史快照，退出自动可写模型记忆
│   │   └── statemachine/             # 公共阶段与失败状态增量
│   ├── security/                     # token解析与设备归属
│   ├── device/                       # 绑定、定位复用与统一客户端/适配器
│   ├── analysis/                     # 保留业务接口，输出模型归位
│   ├── repair/                       # 保留供005复用，不挂默认路由
│   └── aftersales/                   # 保留供001复用
├── ai/
│   ├── factory/AiServiceFactory.java # 新增，校验资源并创建四类实际代理
│   ├── model/                        # RoutingDecision、ProblemAnalysis、DiagnosisConclusion
│   │   └── enums/                    # CapabilityIntent、RoutingOutcome、DiagnosisMode
│   └── tools/                        # 现BaseTool保留，本期不向代理注册设备工具
├── config/                           # LlmProperties、LangChain4jConfig、公共处理配置
├── mapper/                           # 现有Mapper，受控行锁及条件写入
├── domain/
│   ├── entity/                       # 本期6个实体精简注解；RepairSession增两列
│   ├── dto/                          # Request、LoginResponse、SessionResponse、ConclusionDto
│   ├── vo/                           # 五个同名View归位
│   ├── enums/                        # 业务AssistantCapability、SessionStatus等
│   └── message/                      # 五类SSE事件与统一收尾
├── common/                           # 请求日志上下文过滤器及统一异常边界
├── exception/                        # 追加公共失败错误码
├── constant/                         # 有实际引用的角色/全局常量
└── utils/                            # 日志脱敏/MDC；新增PromptInputEncoder做纯数据编码
pom.xml                              # Log4j2接入、排除冲突、Lombok去重
src/main/resources/                   # application.yaml、log4j2-spring.xml、DDL同步
└── prompt/                           # 新增六个UTF-8模板，文件清单见prompt契约
scripts/migration/                    # 两列前向迁移 20260907-assistant-processing.sql（可重复执行）
src/test/java/com/chh/autosense/       # 现有回归+新公共专项验收
frontend/                            # 仅核对兼容，不安排前端重做
```

**Structure Decision**: 沿用章程目录及现有后端基包。能力处理契约放 core/routing；内部请求不作为 public DTO 暴露。不新建 repository/、util/ 或平行基础模块。

## Phase 0: Research

[research.md](research.md)已解决十四项问题：范围与分发、固定版本 AI Service、结构化路由、只读记忆/工具隔离、异步消息持久化边界、令牌撤销、类型归位、公共 SSE 状态、运行配置、分层验收、对象形态兼容、Log4j 2 接入、英文日志与异步关联，以及本轮的提示词资源、固定版本加载与数据绑定。

主要结论：无自动 ChatMemory 的共享 AI Service；20 条历史按 messageId 边界选取；路由结果与业务能力显式转换；未注册能力报错；MySQL 消息指针保障晚到回调不能污染新轮。所有技术未知已给出决策与替代方案。

## Phase 1: Design & Contracts

### 公共调用链

```text
Controller
  -> SessionOrchestrator / UserService / DeviceRegistryService
  -> 受控短事务接纳消息 + 历史快照
  -> IntentClassifier -> AiServiceFactory创建的路由代理
  -> 校验RoutingDecision -> CapabilityDispatcher
  -> 已注册AssistantCapabilityHandler（所属feature）
  -> 公共持久化收尾 -> SSE / GET补查
```

AI Service 只产出候选数据；服务端的身份、归属、允许能力、确认和执行机制独立存在。公共分发器没有设备命令回退，也没有默认批准字段。

### 数据、契约与迁移

- [data-model.md](data-model.md)：复用字段、两列迁移、当前处理指针、历史/轮次、token/index、上下文及租约。
- [routing-contract.md](contracts/routing-contract.md)：AI 结构化输入/输出、校验、业务接收契约、缺失处理器、配置及失败映射。
- [assistant-api.md](contracts/assistant-api.md)：现有会话 URL/JSON、五类 SSE、等待/终态/错误、续聊与旧状态兼容。
- [user-device-api.md](contracts/user-device-api.md)：账号、管理员、SN 绑定与列表的准确兼容边界。
- [logging-contract.md](contracts/logging-contract.md)：日志依赖、配置、事件位置/级别、异步关联、脱敏与业务审计边界。
- [prompt-contract.md](contracts/prompt-contract.md)：六文件清单、四代理的资源注解/变量、JSON数据编码、装配失败及资源验收。
- [quickstart.md](quickstart.md)：已有回归命令、环境前提，以及待实施的公共行为、对象兼容、日志和提示词/JAR专项验收。

迁移保留旧记录与原 ID。部署前排空旧正在处理的工作；无法恢复的旧非终态请求留存中止说明并安全失效，不能凭旧 confirmRepair 或已过期 Redis 上下文执行设备写。历史 DEVICE_ACTION 保持原值，新轮重新路由。

### Entity、DTO 与日志接入

六个公共持久实体为 User、Device、RepairSession、ProblemReport、ChatMessage、RepairActionLog：以 @Getter + @Setter 替换默认 @Data，保留 MyBatis-Flex 所需无参构造和现有字段/映射。当前没有使用这些实体的值相等、哈希键或生成 toString 的证据；实施时再次核对调用点，不随注解迁移增加实体输出。

六个 Request、LoginResponse、SessionResponse、ConclusionDto 与五个 View 已为 record，保留其形态；VO 归位只调整包和引用。RoutingDecision、迁移后的 ProblemAnalysis/DiagnosisConclusion 及 CapabilityRequest 按不可变 record 设计，并通过真实 AI Service 的 JSON 解析与 Bean Validation/序列化回归验证。若新增对象确需修改则采用 Lombok 访问器及必要构造器，不为使用 @Data 把 record 改成可变类。

日志以本期公共调用链为实施范围。依赖替换、Console 配置、HTTP/异步关联、认证与参数拒绝、账号状态、设备绑定/外部读取、路由/分发/澄清、提交后的状态与处理结果均由 002 完成。英文事件、字段和原因码见日志契约；不要复制整个请求、AI 输出、异常正文或凭证。

DiagnosticSnapshot 的对象规范及诊断/售后日志在 001 落实；RepairKnowledge 的对象规范与共享知识检索日志由 003 落实；设备查询业务日志由 004、安全控制校验/确认/取消/过期/执行/复检与可靠审计由 005 落实。002 保留的未接入领域代码不是已合规完成；后续接入时按本契约补齐。公共链自身不得依赖这些后续日志来通过本期验收。

### AI Service 提示词与绑定

系统资源为 intent-router.txt、problem-analysis.txt、diagnosis-reasoner.txt、direct-answer.txt；共享用户模板 conversation-input.txt 用于路由/分析/直答，diagnosis-input.txt 用于诊断。AiServiceFactory 的四个公开嵌套接口在方法上声明 `@SystemMessage(fromResource = "/prompt/对应文件.txt")` 和用户资源注解，参数逐个显式 @V。代码只保留路径、绑定名和数据编码；固定角色、任务、分类/输出语义及用户包装全部在资源中。SDK依据返回record自动生成格式约束仍保留。

LangChain4jConfig 删除四条旧低层直调及未使用的内联接口；业务适配器把同轮不可变历史与本次文本传给代理。DirectAnswerer.question 映射为 text；诊断补充 symptom/diagnostics。utils/PromptInputEncoder 使用独立Jackson writer，序列化时仅转义字符串值及键内的花括号，防止1.0.1顺序替换再次解释数据中的模板标记；不改持久化原文、共享HTTP序列化配置或历史边界。

真实模式工厂在发布四个代理前，用实际接口的classpath流检查六资源路径、存在/可读/非空、UTF-8与默认字符集、精确变量集及非null样例渲染。失败阻止装配，不调用模型、不转mock或内联回退。mock模式不创建真实代理，默认离线契约测试仍覆盖主资源。资源随版本发布，不增加热加载、远程提示词库或配置接口；完整规则见[prompt契约](contracts/prompt-contract.md)。

### 增量实施归属

| 工作 | 本期处理 | 验收关联 |
| --- | --- | --- |
| 实际 AI Service 创建和四条适配调用 | 新增工厂/模型，替换底层直调与错误假成功 | FR-004、FR-005、FR-006，SC-001、SC-005 |
| 四能力路由及处理器契约 | 新分类、结构校验、显式分发、未接入报错 | FR-001、FR-002、FR-003，SC-001 |
| 用户/设备/公共响应 | 复用，修正越层/VO及既有权限缺口 | FR-007、FR-008、FR-009、FR-010、FR-011，SC-002、SC-003 |
| 20条历史与可见消息 | 单一写入方、完整终态内容、隔离边界 | FR-012、FR-014，SC-004 |
| 同会话处理与SSE收尾 | 两列迁移、owner租约、回调事务与超时恢复 | FR-012、FR-013、FR-014，SC-004 |
| 共享设备能力 | 同一定位/归属/客户端、原子锁owner检查 | FR-015，SC-002、SC-005 |
| Entity/DTO形态 | 6个公共实体、Lombok依赖去重、record兼容与VO迁移 | 章程2.3.0，FR-004、FR-007，SC-002、SC-005 |
| 日志基础与公共埋点 | Log4j2单提供者、英文模板、MDC跨线程、配置与脱敏 | 章程2.3.0，FR-008、FR-014，SC-003、SC-004 |
| AI Service提示词资源 | 六模板、方法注解、装配校验、JSON参数编码、打包与真实代理验收 | 章程2.3.0，FR-004、FR-005、FR-006、FR-012，SC-004、SC-005 |
| 四项领域业务 | 003/004/001/005交付；仅定义接入边界 | 不纳入002业务成功率验收 |
| 设备写确认/幂等/全量控制审计 | 005负责；002禁止旧路径绕过 | 不宣称本计划已完成005 |

### 提示词任务归属

现有56项任务已按章程2.3.0同步，保留原ID、三个P1故事及未完成状态；以下为已并入清单的设计增量，实际执行结果仍待实施取得：

| 现有任务 | 已纳入的设计增量 |
| --- | --- |
| T015、T029 | 实际四代理加载/绑定，资源缺失/空白/错变量/编码失败，消息角色、字面花括号及本次一次证据 |
| T020、T021 | 六资源、方法级注解、工厂本地预校验，移除旧内联提示词和低层旁路 |
| T022、T023 | PromptInputEncoder、非null JSON值、question→text、共享历史快照绑定及本地技术失败与模型歧义的区分 |
| T044 | 资源装配失败与异步错误日志不泄露prompt正文或数据标记 |
| T053、T054、T056 | 章程2.3.0审查、Boot JAR六资源字节保持验证、交付证据与状态同步 |

T004的本地配置验证、T010的历史边界、T014的分类验收和T028的内部提示不落历史继续复用；T016/T042补充prompt技术失败与用户原文/内部包装分离的回归。T001明确仅在实际构建配置确有过滤/编码问题时调整资源打包，不重复建基础设施或任务。

### 验证与完成判据

1. 既有用户、管理员、SN 绑定与公共会话响应回归通过，旧数据仍可用。
2. 固定版本真实 AI Service 代理在模型协议替身测试中被实际调用；配置切换、类型解析、TokenStream 成功/错误均可核对。
3. 四能力接收器只在测试装配，接收身份/轮次/当前消息准确；模糊/复合请求不分发、不读写设备；生产缺失处理器明确失败。
4. 同会话并发、晚到回调、超时恢复、冷/热路径、超过20条历史和多用户隔离通过；完整结论保存后才能清投影。
5. token失效、禁用/启用竞态、缺索引成员、Redis故障、SN并发唯一性与锁owner原子操作通过。
6. 以源码/装配检查确认目录职责、无默认设备写回退、无工具全量注入；不以旧测试勾选代替新验收。
7. 实体插入/读取/更新与主键回填、DTO校验和JSON外形、固定版本AI输出record解析通过；Lombok仅保留一个依赖声明。
8. 运行期唯一SLF4J提供者为Log4j 2；公共成功/拒绝/超时/异常输出英文关键事件，异步上下文不串用；日志不含凭据、完整用户/模型内容或原始异常正文，事务回滚不输出已提交成功，默认不逐token打印。

9. 四代理实际从六资源读取规则/包装；启动校验覆盖缺失/空白/错误变量/编码失败且模型请求为零。JSON数据含字面模板标记时逻辑值不变，用户/历史/诊断资料不进入system、本次输入一次；同步结构化与TokenStream行为保持。Boot JAR含六资源且与源文件逐字节相同。

本次只完成文档检查；上述运行验收在实施阶段执行。旧001已完成SN任务保留为复用证据，不复制为本feature完成状态。

## Complexity Tracking

无需要豁免的章程违反项。新增两列用于公共异步写入隔离，避免新建轮次/任务表；业务与AI分类枚举分开用于明确可信边界；能力接口用于四项独立业务接入，均由当前规格直接需要。

## 2026-09-09 路由扩展实施设计

在 RoutingDecision 和 CapabilityRequest 中追加可空 Boolean requiresKnowledgeBase，真实 AI Service 依靠资源提示词输出，RoutingDecisionValidator 校验仅 KNOWLEDGE 必填，其余必须为空。SessionOrchestrator 透传已校验字段，日志仅增加布尔值。同步调整显式 mock 的确定性分类：常识、型号知识、本人设备参数问答各有验收样例。已有业务等待续接不重新路由，该字段为空，由原处理器恢复自己的上下文。

修改 intent-router.txt 明确静态产品知识与本人设备参数问答的边界；保持模型不持有设备授权、公共 API/数据库结构不变。通过契约测试、真实 SDK 协议替身和隔离 MySQL/Redis 的路由集成测试验证字段解析、错误组合拒绝、分发透传及路由零设备调用。003/004 同步接入要求，本次不提前实现其业务处理器。

# Phase 0 Research: IoT 设备自动诊断与修复

> **2026-09-07 规格拆分说明**：下文保留拆分前的设计/契约/验证指南作为参考，尚未按五个 feature 的新边界重新规划；其中工作区状态、需求编号和流程描述均属于编制时上下文。当前需求以[feature 总览](../README.md)及各自 spec 为准；原需求可查[拆分前规格](history/20260907-before-feature-split.md)。复用适用部分时须核对新职责，本文不代表新 feature 已实现或验收通过。

**Date**: 2026-09-07 | **Plan**: [plan.md](./plan.md) | **Constitution**: 2.0.0

本次基于当前规格、源码及章程重新核对既有 R1–R26 决策，并新增 R27–R31。
以下“目标”属于后续实施范围，不表示代码已经完成。研究已解决技术选型及边界问题；
本阶段未运行应用、迁移或测试。规格范围仍是后端 API，同仓库现有前端作为调用方。

## R1. 固定技术基线

- **Decision**: 沿用 Java 21、Spring Boot 3.5.3、MyBatis-Flex 1.10.9、
  LangChain4j 1.0.1、community Redis 集成 1.0.1-beta6，均以 `pom.xml` 为准。
- **Rationale**: 当前项目已满足章程版本基线；本次目录与能力收敛无需升级依赖。
- **Alternatives considered**: 再次降级 Boot、增加 AI starter、迁移响应式栈均无必要。

## R2. 确定性编排与操作边界

- **Decision**: `core/session/` 状态机决定业务流程；LLM 仅分类、提取属性及生成解释。
  设备写操作必须经过归属校验、独占锁、有效确认、规则结果和适配器白名单。
- **Rationale**: FR-008/016/017 要求受控操作、设备互斥、失败即停；AI 结果不是执行授权。
- **Alternatives considered**: 自由 tool-calling 触发设备写入不满足约束；仅关键词规则不能覆盖真实自然语言。

## R3. LangChain4j AI Service 创建与模型归属

- **Decision**: `config/` 保留配置绑定及模型 Bean，`ai/factory/` 创建
  `AiServices` 代理；`core/routing/`、`core/analysis/` 保留业务能力接口。
  `ProblemAnalysis`、`DiagnosisConclusion` 迁至 `ai/model/`，
  AI 分类 `Intent` 迁至 `ai/model/enums/`。业务状态枚举留在 `domain/enums/`。
- **Rationale**: 当前 `LangChain4jConfig` 的真实路径使用低层模型调用和手写解析，
  工厂/模型目录尚未接入；不能把定义过内部接口当作已使用声明式代理。
  [固定 1.0.1 AiServices 源码](https://raw.githubusercontent.com/langchain4j/langchain4j/1.0.1/langchain4j/src/main/java/dev/langchain4j/service/AiServices.java)
  提供本设计所需的 enum/POJO 输出与流式接口能力。
- **Alternatives considered**: 新增 starter 或升级版本扩大变更；直连厂商 SDK 违反原则 IV。
  供应商 strict JSON schema 能力不作默认假设，输出仍需业务校验。

## R4. 最小知识检索路径与向量开关

- **Decision**: 本期以 MySQL `repair_knowledge` 为权威源，规则 `knowledgeRef` 优先，
  再按设备类型和问题特征筛选 Top-K；将命中的正文/人工步骤作为模型已知资料。
  空查询不得通过空串匹配任意返回第一条知识。没有命中时转入有依据的人工/售后分支。
  `embeddings-enabled=false` 为本期支持模式；设为 true 时目标为启动失败并说明未实现。
- **Rationale**: 当前服务仅做 MySQL 候选匹配，true 仍静默回退；并没有向量检索链。
  该检索增强路径满足 FR-007，不需要新增嵌入提供商或数据库。
  旧“Redis 索引免 TTL 属章程例外”决定撤销：章程没有此例外。
- **Alternatives considered**: 立即补向量链需增加嵌入模型、维度、原子缓存写入及过期策略，
  不属于当前最小实现。固定 community Redis 1.0.1-beta6 源码没有 TTL builder 参数；
  将来启用必须保证缓存数据原子写入并设置 TTL、过期可从 MySQL 重建。
  [官方 Redis 集成说明](https://docs.langchain4j.dev/integrations/embedding-stores/redis/)
  可核对组件名称，但不能替代旧版能力验证。

## R5. 设备扩展

- **Decision**: `core/device/spi/DeviceAdapter` + Spring Adapter Bean +
  yaml 类型/型号、诊断字段、命令白名单、故障规则及知识条目构成扩展点。
- **Rationale**: 新设备类型不改核心流程，满足 FR-012；本期仅 LITE:LA001/LB001 可诊断。
- **Alternatives considered**: 在状态机硬编码型号分支违反扩展要求；数据库动态插件体系没有本期需求。

## R6. 会话并发、设备锁与有效确认

- **Decision**: 复用 Redis；设备锁采用含 sessionId/轮次/随机持有者标识的 owner，
  获取、续期和释放必须原子校验 owner，执行期间有受控续期。会话消息另以短租约
  串行处理，覆盖无设备的常识对话；相同会话并发消息不得重复消费确认。
  确认前验证用户、设备、轮次、状态、待执行方案和锁所有权。
  会话消息租约覆盖异步流式处理直至最终回调或明确取消，不能在发起异步调用后立即释放。
- **Rationale**: 现有 10 分钟设备锁与 30 分钟上下文存在过期差，续租未校验持有者，
  释放先读后删有竞态；设备锁也无法保护常识对话的历史写入。
  目标中锁/上下文失效时先停止旧操作，重新定位、获取锁并探测生成方案，再要求新确认；
  旧 `confirmRepair=true` 不能成为新方案的授权。
- **Alternatives considered**: JVM 锁不能覆盖多实例；持有数据库长事务等待用户不合适。
  [Redis 锁设计说明](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)
  支持唯一持有者与比较后释放的基础方案；执行租约失效时必须停止后续命令，不能无条件续租。

## R7. 账号体系与鉴权来源

- **Decision**: 采用本平台 US3 注册/登录签发的不透明 Redis token，Spring Security
  解析身份，业务入口校验设备和会话归属；角色管理与资源所有权分开。
- **Rationale**: 2026-08-29 规格已替代“已有外部登录平台”假设；`AUTH_ISSUER_URI`
  当前不参与实际校验，不能列为运行必需项。
- **Alternatives considered**: OAuth issuer/JWKS 接入或信任请求体 userId 不属于本期设计。

## R8. 售后集成边界

- **Decision**: `core/aftersales/AfterSalesClient` 隔离来源，本期用固定 mock 网点；
  HTTP 实现和配置保持可替换。无位置、无结果或查询失败时使用配置的官方客服兜底。
- **Rationale**: 与 FR-011/019 澄清一致；地点、电话不由模型编造。
- **Alternatives considered**: 本期接入新地图工具扩大范围；自建售后网点库增加运营职责。

## R9. 长期记录保留

- **Decision**: MySQL 长期保存会话、消息、快照与日志，不设 90 天清理任务。
- **Rationale**: FR-013/018 已撤销旧保留期限；Redis 缓存过期不能删除历史。
- **Alternatives considered**: 90 天删除或匿名化均与现行澄清不符。

## R10. 验证分层

- **Decision**: 默认测试复用 JUnit、MockMvc、Mockito 和客户端协议替身；
  `-Pit` 用 Testcontainers MySQL/Redis 加外部真实 deviceSimulator。
  当前集成基类强制 mock LLM，真实模型协议、记忆与并发专项需要另外补测。
- **Rationale**: 按风险区分 API 契约、客户端解析和真实设备协作；端到端设备路径不由桩替代。
- **Alternatives considered**: 把现有 IT 通过解释为真实 LLM 已验收不成立；
  自动测试调用付费生产模型不利于确定性与复现。

## R11. 外部 deviceSimulator

- **Decision**: `core/device/client/DeviceSimulatorClient` 实现 `DeviceServiceClient`，
  使用 Spring RestClient，经属性绑定设置地址与超时。只读 by-sn 用于绑定/发现，
  data 用于诊断，commands/start 等写入只能由已确认的修复路径调用。
- **Rationale**: 模拟器已有外部实现，本项目不开发本体，也不代用户创建设备。
  应用缺省端口与 Compose/IT 不一致，指南必须显式设置 `DEVICE_SERVICE_BASE_URL`。
- **Alternatives considered**: 恢复内置设备 mock 或平台代建路径均违背最新澄清。
  本地契约为 `documents/后端接口说明.md` 与 `documents/新增接口说明-按SN查询设备.md`。

## R12. 故障规则

- **Decision**: yaml 条件按序首个命中，覆盖 AUTO_REPAIRABLE、MANUAL_ONLY、
  AFTERSALES、NORMAL；规则给出可执行动作或知识引用，AI 只解释已核验事实。
- **Rationale**: 模拟器无通用故障注入；亮度 3 等合法状态值可构造确定性故障。
- **Alternatives considered**: 亮度 0 不符合模拟器参数范围；让 LLM 自由定动作无法可靠验收。

## R13. 意图分类

- **Decision**: AI 分类为 COMMON_SENSE、DEVICE_ACTION、MODEL_SPECIFIC、
  AFTERSALES_QUERY、UNCLEAR。MODEL_SPECIFIC 本期归一化为 COMMON_SENSE；
  无效或不确定分类进入 CLARIFYING，不探测设备。
- **Rationale**: 符合 FR-019；类型的包归属调整不改变持久化 intent 字符串。
- **Alternatives considered**: 将未知分类默认视为设备操作不满足安全边界。

## R14. 独立网点查询

- **Decision**: 网点查询可直接由路由进入，不要求先有设备故障；缺位置时等待补充，
  无法提供位置则给官方客服渠道，所有用户可见答复进入同一对话历史。
- **Rationale**: 对应 FR-011/019 的独立路线和兜底要求。
- **Alternatives considered**: 强制先诊断设备增加无关操作。

## R15. 历史与模型记忆

- **Decision**: 编排是用户可见历史的唯一写入方；落 MySQL 后统一刷新/失效 Redis。
  每次模型调用以当前消息 ID 为边界取之前最近 20 条历史，并仅加入一次当前输入。
  分类 JSON、内部提示及检索片段不进入共享历史。Redis 目标用 String(JSON)
  加原子 SET/TTL 保存窗口；冷缓存按时间及 ID 确定排序从 MySQL 恢复。
- **Rationale**: 当前热缓存会陈旧、冷缓存可能重复当前输入，模型层自动回写会污染历史。
  AI Service 使用显式只读历史快照；不将所有内部调用挂到同一可写 memoryId。
  [Chat Memory 文档](https://docs.langchain4j.dev/tutorials/chat-memory/)区分模型记忆和完整历史。
- **Alternatives considered**: 全量历史入模型成本不可控；多个 AI 调用自动共享可写记忆会互相污染。
  现有 Redis List 缓存格式切换时只淘汰该会话的缓存，不迁移或清除 MySQL 历史。

## R16. 按 SN 绑定

- **Decision**: `POST /api/v1/devices` 接收 sn/name，本地校验和重复预检后
  调用只读 by-sn；仅完整 exists:true 响应可写用户绑定，未支持型号仍允许添加。
- **Rationale**: FR-020 要求设备身份来自上游，显示名来自用户，添加和诊断支持分离。
- **Alternatives considered**: 类型/型号由用户提交或新增代建分支会形成冲突来源。

## R17. 多轮状态迁移与审计

- **Decision**: 目标按 FR-018 从终态直接进入 ANALYZING，在新轮内先重新分类，
  再走常识、售后或设备分析路径；初次创建仍经 CREATED → ROUTING。
  补齐 LOCATING → REJECTED_UNSUPPORTED、VERIFYING → FAILED_DEVICE_UNREACHABLE，
  以及显式失败收尾边；所有迁移统一校验并审计。
- **Rationale**: 当前续聊直接 ROUTING 与规格文字不同；两条实际调用边未在状态表放行。
  异常处理不能直接 setStatus 绕开迁移日志。状态更新和审计需在短事务中一致提交。
- **Alternatives considered**: 静默改写规格为 ROUTING 或放行任意迁移均不合适。
  每轮 PRE/POST 查询同时限定 sessionId/round，避免新轮引用旧快照。

## R18. SSE 与时限

- **Decision**: 保留 Spring MVC SseEmitter；POST 返回流，等待态或终态关闭，断线不补发。
  HTTP 接受前错误走状态码+JSON，流建立后的业务错误走 error 事件。
  结构化内部分析不直接向用户输出；需要模型生成的用户说明通过独立流式服务产生，
  确定性规则文本可直接输出。
- **Rationale**: 现仅直答完整使用流式模型。目标单次模型调用上限 30 秒、重试 0，
  首诊断总预算不超过 120 秒（含排队/模型/HTTP），均配置化；迟到回调不得继续推进或写设备。
  当前默认单次 360 秒及 SDK 重试不能证明 SC-001。
- **Alternatives considered**: WebFlux、WebSocket、事件补发均无本期需求；
  将结构化 JSON 拆成 token 不等于面向用户的说明。
  [Spring MVC 异步响应说明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
  用于核对响应流生命周期。

## R19. 令牌、索引与失效

- **Decision**: 保留 32 字节随机 token、7 天滚动 TTL 和用户 token Set。
  token 有效性同时要求用户启用、token 存在、Set 中有其成员；Redis 原子校验并续期
  token/Set，索引缺失时拒绝并且不自动重建。签发原子写入 token/成员/双 TTL。
- **Rationale**: 现仅续 token，Set 可先过期而导致禁用漏撤销。删除整个 Set 可使
  未枚举到的残留 token 失效；重新登录生成的新 Set 不接受旧 token。
- **Alternatives considered**: 新增 JWT 或 authVersion 列并非必要。
  登录签发与禁用/启用按同一用户的短数据库行锁串行（含已禁用行）；
  禁用/启用均清索引，Redis 失败不返回成功，事务回滚，已撤销令牌无需恢复。

## R20. 密码和注册校验

- **Decision**: BCrypt；账号 4–32 位字母/数字/下划线，密码 8–64 位含字母和数字，
  两次一致，昵称默认账号，唯一冲突为 409；服务边界保留业务校验。
- **Rationale**: 完全沿用 FR-022；DTO/VO 均不输出密码或散列。
- **Alternatives considered**: 新增密码框架或重置接口超出规格。

## R21. 真实身份与 dev 身份

- **Decision**: 真实 token 优先；`AUTH_DEV_MODE=true` 才允许本地 user-{id} 入口，
  dev 身份不具有 admin 权限，生产必须关闭。真实鉴权检查用户是否存在/启用。
- **Rationale**: 保留规格要求的本地替代身份，不将 dev token 当作可注销的真实登录凭证。
- **Alternatives considered**: 由客户端指定角色或无条件接受 dev 身份违反 FR-015/026。

## R22. 用户表映射

- **Decision**: 保留现有 camelCase 列及 MyBatis-Flex 显式 @Column；
  isDelete 负责禁用，管理查询显式处理已禁用行。无需新增用户表字段。
- **Rationale**: 当前结构已实现，不能因包迁移更名数据库列；启用与行锁不能被默认逻辑删除过滤。
- **Alternatives considered**: 全局关闭驼峰映射影响其他实体，重建用户表没有必要。

## R23. 管理员与权限常量

- **Decision**: 注册角色固定 user；初始管理员沿用既有种子初始化边界。
  角色/权限等级等全局常量归 `constant/`，保留外部 user/admin 字符串，不新增角色体系。
- **Rationale**: 对应 FR-026/027 与章程；生产初始化凭据由部署环境管理。
- **Alternatives considered**: 注册可选角色、管理员改角色/重置他人密码均不在本期。

## R24. SN 上游解析和错误

- **Decision**: 强类型 DeviceLookupResult 区分缺少 exists、false、完整 true。
  sn 原样满足 `^[A-Z0-9]{4}[0-9]{9}$`，不 trim/转大写。
  参数错误 400，exists:false 404，重复 409，上游异常/畸形响应 503；失败均不写库。
- **Rationale**: 未发现与服务不可用要求客户端采取不同动作；不能透传上游内部错误。
- **Alternatives considered**: 将异常转为 exists:false 或接受缺字段响应会污染绑定数据。

## R25. 唯一性与数据库迁移

- **Decision**: SN 使用 ascii_bin 的 CHAR(13) 全局唯一键 uk_device_sn，
  远程 GET 在数据库事务外，最终短 INSERT/唯一冲突负责并发裁决。
  复核已有 `scripts/migration/20260904-device-sn-binding.sql` 的适用基线与回填前置条件。
- **Rationale**: 先查后插不能单独解决竞态；CREATE TABLE IF NOT EXISTS 不升级旧表。
  现脚本按已知旧灯泡结构回填，不证明任意旧数据都无损或原模拟器名称可从显示名还原。
- **Alternatives considered**: 唯一键加 user_id、Redis 代替唯一索引或自动修补未知旧数据均不合适。

## R26. 稳定元数据与在线投影

- **Decision**: 仅稳定元数据和用户显示名写 device；supported 从注册表派生。
  保留当前新增的 online:boolean：添加成功为 true，列表按 SN 再发现，
  exists:false 或探测异常为 false（未确认在线），不持久化，也不替代诊断快照。
- **Rationale**: 与现有 API/前端兼容；设备 state、running_status、模拟器时间戳仍按需实时读取。
- **Alternatives considered**: 改成三态 online 或持久化运行状态会改变现有契约或违反 FR-020。

## R27. 目录和服务分层迁移

- **Decision**: 按章程 2.0.0 使用 controller/service/core/mapper/domain/ai/common/
  exception/config/constant/utils。UserController.me 的 Mapper 调用迁至 UserService；
  设备列表支持性和在线探测编排收敛到 core/device 的业务入口。
- **Rationale**: 新规范已明确职责；不新建 repository、api 或空壳 ServiceImpl。
- **Alternatives considered**: 仅改文档路径却保留越层业务，不能视为章程合规实施完成。

## R28. DTO/VO 与兼容性

- **Decision**: UserView、AdminUserPageView、DeviceView、ChatMessageView、
  SessionListItemView 移至 domain/vo，类名和 JSON 不变；Request、LoginResponse、
  SessionResponse、ConclusionDto 留 domain/dto。SSE 类型留 domain/message。
- **Rationale**: 按展示/传输职责归位，外部契约不受 Java 包路径迁移影响。
- **Alternatives considered**: 因 View 后缀批量更改字段或重命名 API 版本没有需求。

## R29. 多轮与失效数据边界

- **Decision**: 每轮独立 problem_report；新轮清理当前 conclusion/conclusion_extra 和旧待执行方案，
  历史留在消息/快照/日志；当前结论选本轮确定排序的 PRE/POST。
  清理当前结论投影前，编排必须先将完整用户可见结论（摘要、人工步骤或售后网点的
  名称/地址/电话）写为一条 ASSISTANT 长期消息；不能只存摘要而丢失旧轮步骤和联系方式。
  超时、取消或租约失效禁止新增写操作；已经执行的动作必须如实记录，失败后能读则复检。
- **Rationale**: 长期线程不能混用上一轮授权、快照或结论；FR-013/017 要求完整追溯。
- **Alternatives considered**: 仅按 sessionId 查一条快照或恢复 Redis 后直接执行旧 pendingAction 不安全。

## R30. 验证环境与证据

- **Decision**: quickstart 使用 PowerShell 和真实返回的设备 ID/SN；应用及 IT 显式指定
  DEVICE_SERVICE_BASE_URL，mock 模式也显式设置。真实 LLM/记忆/租约专项另列目标。
- **Rationale**: 应用默认模拟器 8080，Compose/IT 默认 8081；LLM 默认 real。
  2026-09-04 的 validation 文档是历史证据，不能冒充本次执行结果。
- **Alternatives considered**: 假定 .env 被 Maven 自动加载、写死示例 SN 或用 stopped 代替可修复亮度故障均不可复现。

## R31. 门禁结论

- **Decision**: 目标设计无未经论证的章程例外；既有分层、TTL、一致性缺口列入 plan 的迁移范围，
  由后续 tasks/implementation 落实和验收，不能据此宣称当前运行代码全部合规。
- **Rationale**: 规划门禁检查设计是否可行且受约束，不代替实现与执行证据。
- **Alternatives considered**: 将全部历史任务已勾选视为本轮设计完成，会漏掉新章程和已确认的实现缺口。

研究未知项已解决；进入 Phase 1 数据模型、契约与验证指南设计。

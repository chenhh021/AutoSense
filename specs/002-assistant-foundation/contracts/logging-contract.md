# Contract: 公共英文日志与关联信息

**Date**: 2026-09-07
**Feature**: [002 spec](../spec.md)
**Constitution**: [2.3.0](../../../.specify/memory/constitution.md)
**Status**: 待实施的内部日志契约；不新增公开响应字段、接口或持久化表。

## 1. 日志依赖与配置

业务代码通过 SLF4J，优先 Lombok @Slf4j；运行时使用 Log4j 2。pom.xml 显式声明 spring-boot-starter，并排除它的 spring-boot-starter-logging；加入 spring-boot-starter-log4j2，均继承 Boot 3.5.3 版本管理。新增直接 starter 用于 Maven 依赖选择与默认日志替换，不替换既有业务 starter。

Boot 3.5.3 管理的 Log4j 2 为 2.24.3、SLF4J 为 2.0.17；不额外锁定这些组件或引入另一份日志 BOM。实施时检查 runtime/test 完整依赖树：仅保留 log4j-slf4j2-impl 提供者，不得残留 logback-classic、logback-core、log4j-to-slf4j、slf4j-simple 或旧 log4j-slf4j-impl。若第三方旁路仍引入冲突，按树中实际路径追加排除，不能仅排除 web starter 就认为完成。

| 配置 | 目标 |
| --- | --- |
| src/main/resources/log4j2-spring.xml | Console 输出，显式格式；不启用异步日志基础设施 |
| logging.config / LOGGING_CONFIG | 可替换日志配置文件；缺省使用项目内 log4j2-spring.xml |
| logging.level.root / LOGGING_LEVEL_ROOT | 默认 INFO |
| logging.level.com.chh.autosense / LOGGING_LEVEL_COM_CHH_AUTOSENSE | 默认 INFO；DEBUG/TRACE 仅受控排障 |
| logging.pattern.console / LOGGING_PATTERN_CONSOLE | 默认含时间、level、logger、thread、白名单MDC字段和英文message；自定义XML须读取Boot导出的 CONSOLE_LOG_PATTERN 系统属性，不能假设自动应用 |

Console 是本期默认输出位置。若部署通过外部配置启用文件输出，必须同时配置大小/时间滚动和有限保留策略；本期不默认创建日志文件或增加采集平台。框架与第三方日志沿用其自身文案，项目编写的模板、固定字段、事件及原因标签使用英文。日志级别变更不能启用模型原始请求/响应、HTTP wire/body 或凭据输出；同步和流式模型明确关闭 request/response logging。

配置格式至少包含 requestId、userId、sessionId、messageId、round、deviceId 中实际可用的值；没有上下文的字段保持空，不填 -1、0 或虚构对象。非敏感事件详情使用 operation、outcome、capability、fromState、toState、result、errorCode、elapsedMs 等参数化字段。

## 2. 请求、线程与回调关联

- 公共请求上下文过滤器位于 common，在认证过滤前生成服务端 requestId，保存到 request attribute；同一请求的异步/错误 dispatch 复用它。客户端提供的标识不直接作为可信上下文，不要求新增请求头或响应头。
- userId 只在身份成功验证后加入；sessionId/messageId/round/deviceId 只在服务端确认或接纳后加入。未知身份的拒绝只记录 requestId 和原因码，不把 token、账号原文或整个 AuthUser 放入 MDC。
- 仅复制白名单字段的不可变快照。HTTP → applicationTaskExecutor、LangChain4j TokenStream/CompletionStage 回调、续租/超时任务及 SSE 关闭回调分别安装上下文，并在 finally 恢复调用线程原有 MDC；空快照须移除本请求字段，防止线程复用串号。
- TaskDecorator 只能覆盖经对应执行器提交的任务；不能假定 SDK 回调、定时任务或全局线程继承自动传播 MDC。通用作用域安装/恢复及脱敏辅助放 utils，不在工具类编排业务。
- MySQL 处理指针和截止条件仍决定是否允许写入；MDC 只服务于日志，不用于身份、授权、去重或判断请求有效性。
- 迟到回调使用旧快照，只记录它被拒绝的事实，不覆盖新轮标识、不输出成功结果。进程重启后的补偿使用当前恢复请求和已有业务ID；无法恢复原 requestId 时不伪造它、不为日志增加数据库列。
- HTTP 返回 SSE emitter 仅代表接受请求；业务结束日志在最终持久化提交后产生，不能把 Controller 返回或连接关闭当成业务成功。

## 3. 关键日志覆盖矩阵

下列是英文事件模板与主要责任位置；动态值使用 SLF4J {} 占位符。开始与结果只在承担该操作的边界记录，不能在 Controller、Service、Mapper 同时重复记录相同完成事实。

| 操作 / 主要位置 | 英文模板或事件 | 级别 / 必要结果 |
| --- | --- | --- |
| UserService / AuthTokenService 注册、登录、注销、账号状态变更 | User operation started / User operation completed / User operation rejected | INFO 开始和已提交结果；WARN 可预期拒绝；operation与已验证userId，状态变更区分操作者/目标ID |
| 安全入口、统一异常处理、业务归属检查 | Authentication rejected / Access denied / Request validation failed | WARN；reasonCode/errorCode，参数只记录字段名及校验规则，不记录 rejectedValue |
| DeviceRegistryService 绑定/列表 | Device binding started / Device binding completed / Device binding rejected / Device list completed | INFO 已提交绑定与列表汇总；WARN 冲突；数量、deviceId，不输出整份列表或原始SN/名称 |
| 共享外部设备客户端 | External call completed / External call timed out / External call failed | INFO 结果和elapsedMs；WARN 有界超时/降级；ERROR 关键依赖故障由最终边界记堆栈；operation而非完整URL |
| AiServiceFactory资源装配校验 | AI prompt resources validated / AI prompt resource validation failed | INFO一次汇总成功；ERROR配置失败；仅安全service/method/resource/reasonCode，不含模板、变量值或原始异常正文 |
| AI Service 外层适配器 | AI call completed / AI call timed out / AI call failed | INFO 结果、operation和elapsedMs；WARN 超时；系统失败最终边界ERROR；无prompt/history/输出正文 |
| IntentClassifier / CapabilityDispatcher | Intent routed / Routing clarification required / Request out of scope / Capability dispatched / Capability unavailable | INFO 分类/分发；未知结构或缺失能力WARN；记录枚举，不输出targetHint/clarifyQuestion全文 |
| 公共会话事务与状态迁移 | Session request accepted / Session state changed / Session request completed | INFO；messageId/round/fromState/toState/result；接纳、迁移、完成事实均在对应提交成功后记 |
| 会话并发、上下文与租约 | Session request rejected / Session context expired / Session lease lost / Session request timed out | WARN；忙请求不使用另一请求的messageId冒充本次已接纳；租约不输出随机owner或完整Redis键 |
| 迟到回调、SSE断线、重复收尾 | Stale callback ignored / SSE connection closed | DEBUG 汇总；生命周期内一次，禁止逐token重复日志 |
| 统一最终异常处理/提交失败 | Request failed / Session finalization failed | ERROR；一次脱敏堆栈，errorCode/异常类型与已知关联标识 |

普通澄清和范围外输入是正常业务 INFO；非法模型输出可为 WARN。参数/权限拒绝不统一升级 ERROR。外部调用和业务结果是不同事件，可以各记一次；相同异常堆栈只在负责最终处理的边界记录，避免 SDK 适配器、编排器、Controller 重复打印。

列表对外部服务的逐项探测不逐项输出 INFO；按一次列表操作汇总成功/失败/未确认在线数量及耗时，逐项细节最多受控 DEBUG。逐 token、定时续租成功和高频轮询也不得逐项 INFO。记录技术调用成功不能推断设备在线、业务查询完成或控制生效。

## 4. 脱敏与持久化边界

- 日志参数只选必要标识、枚举、布尔、数量、耗时及错误码。禁止密码/确认密码、Bearer token、API Key、凭据连接串、完整请求/响应对象、用户对话、提示词和模型输入输出。
- 不调用 DTO/Entity/record 的 toString 作为日志参数；record 自动生成的方法和 Lombok 注解不能替代脱敏。User/LoginRequest/LoginResponse 等尤其不得整体输出。
- 默认不记录 e.getMessage() 或第三方原始 Throwable：它们可能带地址、请求正文或凭据。记录白名单异常类型与错误码；确需堆栈时由公共脱敏辅助构造仅含安全类型/栈帧的诊断信息，所有 cause/suppressed 的消息均不得保留原文，也不能把原异常挂回安全包装后输出。
- 允许保留的外部摘要要限长并清理 CR/LF、制表符及其他控制字符；消息模板使用英文常量和参数化占位符，不拼接用户文本生成事件或字段名。
- 数据库 repair_action_log 继续保存要求可靠追溯的 route/state/request 记录，并与当前业务事务一致。运行日志不取代这些记录，不保存第二份完整会话；英文运行日志规范不批量翻译历史审计内容。
- 回滚不得产生“已完成/已提交”日志。日志输出本身不参与业务提交事务；日志设施失败不允许跳过业务审计、权限或确认，也不能把失败设备动作解释为成功。

提示词缺失/空白/非法编码/变量不匹配等失败可能来自SDK异常，包装时不能将包含模板正文的原异常作为cause带入启动日志。失败只保留安全资源路径与英文原因码；真实代理、资源校验和异步失败测试使用内部prompt/数据标记证明无正文泄露。资源路径由方法注解固定，不能用未经验证的用户值冒充日志字段。详见[prompt契约](prompt-contract.md)。

## 5. 后续 feature 的接入义务

003 补知识检索结果、来源数量/不足与耗时；004 补本人设备实时读取；001 补诊断证据/规则结果和售后调用；005 补控制校验、确认、取消、过期、互斥执行、命令结果、复检及可靠审计。它们使用同一 SLF4J/Log4j 2 配置、英文事件与关联规则。

002 只为当前公共调用链及本期修改代码完成日志迁移；未接入的 RepairKnowledgeService、HttpAfterSalesClient、RepairExecutor、RepairExecutionRunner 由所属 feature 补齐。002 的验收不得依赖未实施的控制日志，也不得将仅有 Logger 声明视为全仓日志合规。

验证步骤见[quickstart](../quickstart.md)，依赖与线程上下文的官方依据见[research](../research.md) R12/R13。

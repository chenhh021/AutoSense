# Data Model: 公共基础与统一意图路由

**Date**: 2026-09-07
**Constitution**: [2.3.0](../../.specify/memory/constitution.md)，包含对象形态、日志关联与本轮提示词数据边界。
**Scope**: [002 spec](spec.md) / [research](research.md)。所有“新增/目标”均待实施；本次不执行迁移。

## 1. 持久实体复用

| 实体 / 表 | 保留字段与关系 | 本期处理 |
| --- | --- | --- |
| User / user | id、userAccount、userPassword、userName、userAvatar、userProfile、userRole、isDelete及审计时间 | 保持字段/唯一账号/密码加密；用户状态行锁查询须包含禁用记录 |
| Device / device | id、userId、sn、name、simulatorName、simulatorDeviceId、deviceTypeCode/Id、deviceModelCode/Id、审计时间 | 保持SN唯一及本人归属，不新增运行状态列 |
| RepairSession / repair_session | id、userId、deviceId、status、conclusionType、conclusion、conclusionExtra、createdAt、updatedAt | 继续作统一会话；新增两列处理指针与截止时间 |
| ProblemReport / problem_report | id、sessionId、round、intent、rawText、deviceType、symptom、reproduction、clarifications、时间 | 继续作会话轮次记录；公共请求可无诊断属性，不新建轮次表 |
| ChatMessage / chat_message | id、sessionId、role、content、createdAt | 长期可见历史，id作为本次处理边界 |
| RepairActionLog / repair_action_log | id、sessionId、actionCode、params、result、message、createdAt | 复用记录公共路由/状态/异常关联，设备动作审计仍由005扩展 |
| DiagnosticSnapshot / RepairKnowledge | 既有诊断快照与维修知识字段 | 保留数据供001/003复用，本期不重构领域模型 |

设备元数据的外部类型/型号编码与ID、SN、模拟器设备ID及原始名称保持原值。平台 name 始终为用户显示名称。查询返回的 state、running_status 和外部 created_at/updated_at 不写入 Device；平台自身审计时间不属于该禁存范围。

### 对象定义与迁移边界

- User、Device、RepairSession、ProblemReport、ChatMessage、RepairActionLog 使用 @Getter + @Setter，保留 public 无参构造（可显式 @NoArgsConstructor），不默认生成 equals/hashCode/toString。全部 @Table/@Id/@Column、逻辑删除及主键回填规则保持。
- 当前调用只需要无参创建和访问器，无需新建全参构造或 Builder。DiagnosticSnapshot 由 001、RepairKnowledge 由 003 在所属功能规划中迁移；本期不为形式统一修改领域模型。
- DTO/VO 与 Entity 继续分离；Entity 不直接作为 API 响应。Lombok 依赖合并与固定版本解析依据见[research](research.md) R11。

## 2. 最小数据库增量

仅新增：

| 表.列 | 类型 | 空值 / 含义 |
| --- | --- | --- |
| repair_session.processing_message_id | BIGINT NULL | 当前被接纳的 chat_message.id；空表示没有消息正在处理 |
| repair_session.processing_deadline_at | DATETIME NULL | 当前消息固定总截止时间；与消息指针同时设置/清空 |

不新增用户认证版本、设备状态、轮次表或公共任务表。已有 problem_report.intent 的 VARCHAR(32) 足以承载四能力值和澄清标记。索引设计复用现有主键/会话查询索引；实施前核查执行计划，新增索引须有实际访问依据。

**不变量**：

- 两个处理字段同时为空或同时非空；处理消息必须属于当前会话。
- 同一会话一条处理中消息，当前 round 仍从该会话问题报告确定。
- 每个被接纳的 POST 在短事务内保存 USER 消息一次；仅有兼容 confirmRepair 的请求也记录确定性的用户可见意向文本以获得消息ID，但这不代表控制授权有效。
- 未接纳的忙请求不保存为当前输入、不改变另一请求的处理指针。
- 先锁会话行并验证当前处理，再插入消息/必要新轮报告；网络调用在事务外。
- 回调必须在同一短事务中验证 messageId、截止时间和当前状态，再写消息、状态、结论与日志。验证失败的回调不得继续写任何持久数据。
- 正常业务收尾仍将完整可见消息、状态投影、追溯及处理指针清理放在同一事务中；提交成功后发送相应结果。未接纳请求和持久化异常的错误通知遵循会话接口契约，不以数据库提交成功为前提，也不代表持久化收尾已经完成。
- 数据库时间是截止判断基准，应用/JDBC/数据库时间配置须一致，不批量改写历史时间。
- 超时采用独立恢复事务，条件是同一processing_message_id且截止已到，而非正常回调的“未到期”条件；原子记录REQUEST_TIMEOUT、失败说明并清指针。活跃请求到期由公共定时收尾触发并关流，GET/后续POST用于崩溃后补偿；任何恢复都不得覆盖新的处理指针。
- token流发送不逐条写数据库；公共sink只接受当前仍有效处理的片段，处理结束/失租约/到期后停止发送。最终持久化仍必须经过数据库指针校验，不能只依赖内存标志。

**迁移约束**：

新增前向迁移脚本并同步新建库 DDL；旧行两列初始 NULL。保留已有行、主键、消息、SN绑定与状态历史。部署时排空旧在途工作；无法恢复的旧活动状态须记录中止后转公共失败，不回放模型或设备操作。迁移不清空数据库、不执行旧 seed 覆盖已有用户，不对旧 DEVICE_ACTION 做自动诊断/控制映射。

## 3. 公共处理轮次与状态

AI 分类不再与设备控制状态混在一起。现有诊断状态仍可用于历史展示及后续能力接入，002只新增下列公共状态：

| 状态 | 类型 | 用途 |
| --- | --- | --- |
| CREATED / ROUTING | 既有 | 新建后或新轮进行意图判定 |
| CLARIFYING | 既有等待态 | 意图不明、复合任务或无效结构的澄清 |
| DISPATCHING | 新增进行态 | 已验证单一意图，投递能力接收方 |
| ANSWERING / COMPLETED_ANSWERED | 既有 | 用户可见解释及已完成回答；范围外说明亦可使用 |
| FAILED_REQUEST | 新增终态 | 公共处理失败、未接入、超时或旧流程安全失效 |

- CREATED → ROUTING。
- 已有终态（包含已交付人工步骤的 GUIDED_MANUAL）或 FAILED_REQUEST + 新问题 → 新报告 round + ROUTING。
- ROUTING + CLARIFY/COMPOSITE → CLARIFYING；答复本身保存为可见历史，随后关闭本轮流。
- CLARIFYING 的后续内容经最近20条历史与当前输入重新分类，不拼接为重复当前输入。
- ROUTING + OUT_OF_SCOPE → ANSWERING → COMPLETED_ANSWERED，保存确定性的服务范围说明。
- ROUTING + SINGLE → DISPATCHING；处理器开始后进入其已登记的业务状态，公共状态机校验迁移，禁止直接 setStatus 绕过追溯。
- 无处理器或公共模型/执行期错误 → FAILED_REQUEST。它的 conclusionType 为新增 ERROR，conclusion 为面向用户的错误说明，conclusionExtra 仅存脱敏 code 等恢复信息。
- 错误响应接纳前失败或 SESSION_BUSY 不使另一个正在处理的会话转 FAILED_REQUEST。
- 旧控制等待状态的 confirmRepair 不能直达旧 runner；必须由005已注册的控制处理器核对，不可用时明确失败。
- 等待某能力期间收到新问题，先由原能力完成取消/失效或确认续办判断，再允许公共入口新轮路由；不能跳过旧控制状态处理直接把文本当作确认。
- GET发现超时处理时可原子结清为FAILED_REQUEST并保留说明；不重新调用模型/设备，不产生设备副作用。下一次POST同样先结清旧处理再接纳新问题。

## 4. AI 输出与业务输入

**AI 层**（下列输出对象采用 Java 21 record，枚举仍为 enum）：

- ai/model/RoutingDecision：outcome、intent、diagnosisMode、targetHint、clarifyQuestion、requiresKnowledgeBase。最后一项为可空 Boolean：仅 SINGLE + KNOWLEDGE 必填，常识 false、需检索 true，其余为空；经校验后透传 CapabilityRequest，不新增数据库列。
- ai/model/enums/RoutingOutcome：SINGLE / CLARIFY / COMPOSITE / OUT_OF_SCOPE。
- ai/model/enums/CapabilityIntent：KNOWLEDGE / DEVICE_QUERY / DIAGNOSIS / CONTROL。
- ai/model/enums/DiagnosisMode：DEFAULT / AFTERSALES；仅DIAGNOSIS可有意义值。
- ai/model/ProblemAnalysis：沿用 deviceType、symptom、reproduction、sufficient、clarifyQuestion。
- ai/model/DiagnosisConclusion：沿用 problemSummary、conclusionText、likelyAutoFixable。

**业务层**：

- domain/enums/AssistantCapability：四项可接入业务能力，由已验证AI分类显式映射；不把任意模型字符串用于查找Bean或方法。
- core/routing/CapabilityRequest（不可变 record）：服务端AuthUser、sessionId、reportId/round、messageId、当前原文、只读历史边界、已校验能力/子模式及未验证目标线索。
- 目标线索不是最终设备ID；设备归属与支持能力必须由所属服务核对。
- CapabilityRequest 的可选兼容输入 confirmRepair 仅是用户原始意向；正式控制请求、确认ID/有效期、动作参数校验与命令幂等由005定义。

CapabilityRequest 的历史/上下文集合须形成不可变快照；record 不自动深度不可变，不能携带仍被其他线程修改的集合。其真实身份和处理指针由服务端传递，日志 MDC 不能代替这些业务字段。

完整规则和例子见[routing-contract.md](contracts/routing-contract.md)。

## 5. 可见历史及追溯

- MySQL chat_message 为唯一长期权威。仅 USER/ASSISTANT 参与模型历史；服务端系统提示、路由JSON和内部分析不写入可见历史。
- 以当前USER消息ID为界，取同会话 id 较小的最后20条，再按id升序。当前输入单独出现一次，各次内部AI调用使用同一边界。
- 身份与session归属先校验，历史查询始终限定session；不同用户相同文本不共享记忆。
- 对完整回答、人工步骤、网点名称/地址/电话保存一条最终可见ASSISTANT消息，之后才允许新轮清当前结论投影。
- 流式部分token不逐条落为历史消息。失败时保存明确失败说明；已经发出的部分文本不是已成功完成答复，GET/历史以最终持久结果为准。
- SLF4J/Log4j 2 运行日志另按[日志契约](contracts/logging-contract.md)记录英文结果及关联；不取代本节的持久化记录，也不在运行日志复制content/rawText。数据库成功与状态变更日志在事务提交后产生，失败提交不得记录成功。
- 公共追溯复用repair_action_log，actionCode采用 route / state / request 等类别；params保存round、reportId、messageId、能力与结果码等必要关联。禁止写模型密钥、token、密码、内部提示和完整原始供应商错误。
- 不新建第二份可写模型记忆；旧 chatmemory Redis 键不作为新AIService输入，等原TTL失效。

## 6. Redis 数据与一致性

| 键/用途 | 值/默认TTL | 目标规则 |
| --- | --- | --- |
| autosense:token:{token} | 当前用户ID/角色；7天滚动 | 必须与用户索引成员共同有效；不在日志中输出键的token部分 |
| autosense:usertokens:{userId} | token集合；与token统一滚动 | 签发原子加入；校验同时续期两键；禁用/启用撤销旧集合 |
| autosense:session:v2:{sessionId} | version、userId、round、messageId、能力和等待上下文JSON；30分钟 | 原子SET+TTL完整替换，防旧Hash残留；不持久保存控制授权 |
| autosense:lock:session:{sessionId} | messageId + 随机owner；30秒 | SET NX，10秒owner校验续期；最终回调结束后owner校验删除 |
| autosense:lock:device:{deviceId} | 所属session/round/request与随机owner；沿用10分钟 | 续期/释放原子核对owner；业务获取与失效策略归001/005 |

token/index 多键原子操作按当前独立 Redis 部署实现；不在本期引入 Redis Cluster 迁移。索引缺失不自动补回成员，孤立旧 token 即使仍有 TTL 也无效。

会话租约和数据库指针共同使用：不能因 Redis lease 过期就重放仍在DB截止时间内的请求。丢失owner后停止后续回调提交；原DB处理到期由公共恢复流程结清。续期设备锁和释放设备锁不得误操作别的owner；此原语不等价于005设备写幂等。

## 7. 账号有效性与撤销边界

真实请求需满足token存在、所属用户索引包含它、数据库用户存在且启用。角色以服务端可信记录核对。登录、禁用、启用按同一用户行串行；锁查询包含禁用行。

- 签发在锁内复核账号，原子写token与索引/TTL；DB提交后返回。
- 禁用与启用都撤销旧索引，必要时尽力删token本体。旧成员不得恢复，新登录只加入新token。
- 关键Redis签发/索引撤销失败必须抛错并回滚数据库事务，不只返回错误；启用必须在撤销旧索引成功后才提交isDelete=0。已删除索引后的残留token可尽力清理，已撤销的凭据不补偿恢复。
- 请求校验/状态变化需有明确先后关系，不能把已完成请求追溯撤销；后续请求和待启动设备操作需重新检查。
- 开发身份只在显式开关下可用，固定普通用户角色，生产关闭；不能用dev token验证真实注销。

## 8. DTO / VO 兼容迁移

| 保持名称 | 目标包 | 公共影响 |
| --- | --- | --- |
| UserView、AdminUserPageView、DeviceView、ChatMessageView、SessionListItemView | domain/vo | 保留record、静态工厂及注解；仅Java归位，JSON字段不变 |
| 六个Request、LoginResponse、SessionResponse、ConclusionDto | domain/dto | 均保留record、现有校验/Schema注解、组件类型及null语义 |
| SseEvent、SseEventStream | domain/message | 保持五类事件形状 |
| SessionStatus、ConclusionType、ActionResult等业务枚举 | domain/enums | 仅公共状态/ERROR结论类型增量 |
| AI分类与结构化输出 | ai/model、ai/model/enums | 不直接作为外部JSON响应或已验证业务身份 |

与URL/字段有关的具体契约见[会话接口](contracts/assistant-api.md)和[用户/设备接口](contracts/user-device-api.md)。新增状态与错误值需客户端按字符串处理未知值，既有字段不得删除或改名。


## 9. 日志关联视图（非持久实体）

只在日志上下文保存 requestId、已验证 userId、已接纳 sessionId/messageId/round、实际关联 deviceId 等白名单快照。不新增日志表、requestId数据库列、公开DTO字段或审计授权凭证。

HTTP、executor、AI回调、定时收尾分别显式安装/恢复该快照；迟到回调保留旧消息关联。requestId不能作为认证身份或业务幂等键。业务截止校验和可靠追溯仍由既有事务与处理指针负责；MDC丢失不能改变请求授权或业务事实。

日志不输出完整对象或原始异常。带敏感字段的 record 自动toString同样受此约束；迁移到Lombok/record不能降低脱敏标准。

## 10. Prompt资源与输入视图（非持久模型）

六个文件作为classpath资源随应用发布，不建prompt表、Redis缓存、可编辑管理实体或公开DTO。具体文件、变量、加载与失败规则见[prompt契约](contracts/prompt-contract.md)。

| 数据 | 形态 / 生命周期 | 边界 |
| --- | --- | --- |
| 四系统规则 | /prompt/四类代理对应文件；应用版本内固定文本 | 无用户/历史/检索变量，不生成可信身份或授权 |
| 两用户包装 | conversation-input.txt、diagnosis-input.txt | 仅固定资料区及精确模板变量，正文不落会话历史/审计 |
| text / history | 本次文本的JSON字符串、只读历史JSON数组 | 复用第5节的20条与messageId边界；本次单独一次，同轮快照不变 |
| symptom / diagnostics | JSON字符串或null字面量、JSON对象 | 前者是AI候选；后者是服务端授权读取资料；缺失Map→{}，全部@V值非Java null |
| 模板输入编码 | utils/PromptInputEncoder输出的临时JSON值文本 | 独立writer转义字符串值及键中的花括号；普通JSON数据限定及字符规则见prompt契约；不更改持久化原文、HTTP序列化或record输出解析 |

编码只是模板绑定前的内部表示，解码后逻辑值等于原数据，不新增持久字段或第二份历史。用户资料始终在UserMessage资料区；history内role为数据字段，不能生成新的SystemMessage。SDK输出格式生成与RoutingDecision等AI候选模型继续按既有契约处理。

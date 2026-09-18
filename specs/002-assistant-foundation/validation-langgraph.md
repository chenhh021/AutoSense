# LangGraph4j 实施验证

日期：2026-09-15。当前分支：dev。以下仅记录本期实际执行结果，不替代旧版validation。

## T001 — 基线

- requirements.md只读检查：16/16通过，未修改清单；无extensions.yml钩子。
- Git仓库及.gitignore/.dockerignore已核对，包含Java、前端、环境变量、日志及临时文件必要规则；没有ESLint/Prettier/Terraform/Helm或npm发布需求，不新增无关忽略文件。
- 旧任务快照包含59项完成记录，SHA256：988cc512c2ebf28e93164e7b9d145de15dd82b752a779ed8911225f98c6462fb。
- mvnw.cmd未进入Maven即失败：PowerShell wrapper报“Cannot index into a null array”。使用同一发行版本的已安装Maven 3.9.16运行等效verify，Java 21.0.2。
- 基线mvn verify：187 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS；已打包。日志：target/langgraph-baseline-maven.log（PowerShell UTF-16重定向）。外层PowerShell进程曾返回1，Maven日志明确完成成功；后续命令显式返回Maven退出码并核对报告。
- 尚未执行本期新图测试、数据库迁移、真实设备或新前端验收。

## 实施边界

优先迁移公共图与持久化，再切换入口并删除旧编排；未完成的任务不勾选。旧会话、消息及动作审计不重建，命令账本不从历史动作日志推断。

## T002/T003 — 依赖及配置

- 固定core 1.8.27已下载，mvn -B -DskipTests compile退出0、BUILD SUCCESS。运行测试classpath核对：LangChain4j/core/open-ai均1.0.1，community 1.0.1-beta6，SLF4J 2.0.17，仅Log4j2绑定2.24.3，无Logback或graph的LC4j集成模块。
- GraphPropertiesTest：3项通过，验证有限默认值、局部步骤超时覆盖、未知模式/步骤及无效预算拒绝；日志target/langgraph-properties-test.log。默认real，显式graph-stub profile强制mock模型及embedding，无供应商调用配置。
- 图子图/stream/saver API已以实际1.8.27 JAR javap核对；SubGraphNode位于org.bsc.langgraph4j，后续最小图实验验证运行语义。
- Maven dependency插件首次下载仍需完成，target/langgraph-dependency-tree.log保留诊断进度；实际编译/测试classpath已核对。一次build-classpath参数未引用导致命令解析失败，未改变源码或依赖选择。
- 忽略文件补充Python验证脚本的__pycache__/、*.pyc、.venv/。

## T004–T010 — 公共图基础

- 已实现十个顶层state键、不可变context/计划/结果、深层JSON值复制、消息ID去重与21条窗口（20条历史+当前输入）、游标一致性和运行对象拒绝。
- 已加入公开WorkflowEvent/WorkflowView和最小WorkflowStepActions边界，未接替真实Controller或数据库。
- GraphConfiguration限制stub只在显式graph-stub/test且非prod/production使用；MemorySaver仅在测试内创建。
- mvn -B -Dtest=GraphPropertiesTest,StateContractTest,GraphAssemblyTest test：10项通过，0失败/错误/跳过。日志target/langgraph-foundation-test.log。
- 真实1.8.27最小内联子图可在AwaitApproval前中断，updateState后使用其返回config及GraphInput.resume继续，保持原threadId，读取一次且无提前读取。
- 初次实验发现getState(updateState返回config)读取的是指定检查点，不能据此判断执行后最新值；改用无checkpointId的原thread配置查询最新状态后通过，断言没有删除。
- dependency:tree最终BUILD SUCCESS（target/langgraph-dependency-tree.log），固定框架/AI版本与单日志绑定确认。

## T011–T025 — G1 离线图

- 先增加计划、主图和流测试；首次运行因目标类尚未实现而编译失败，记录于 `target/langgraph-g1-before.log`。
- 已实现受限候选/正式计划hash、四类步骤、前向引用拒绝、条件跳过/缺证据失败、独立查询/控制批准、澄清和失败停止；三个子图内联编译，使用真实 LangGraph4j stream。
- 未发布计划的澄清回到Planner；已发布步骤的目标澄清保留计划hash，等待独立批准。过期批准不能触发查询。
- 请求超时按图节点有限重试，明确失败或拒绝不重试；未知控制结果且无远端去重保证时停止。AttemptCalls共享尝试截止并保存成功子调用，活跃区间到期不再发起请求；等待结束活跃区间但保留已用重试次数。
- WorkflowEventStream只投影OutputContext、按eventId去重；等待通知在完整消费graph中断后才交付，检查点失败不会发布等待成功；STEP_RESULT之后继续到CONCLUSION。
- 首轮7项通过；扩展28项通过；加入子调用/截止验证后30项通过。最新 `target/langgraph-g1-us2-authorized.log` 中图测试32项通过（MainGraphStubTest 10，其他22），包括目标澄清和过期批准。
- MemorySaver仅在隔离测试使用，应用工厂等待G2持久saver装配；未切换Controller、未连接真实设备、未证明跨JVM恢复。stub业务结果均标记simulated，不能作为真实控制验收。

## US2 复核进行中

- 复用账号/设备契约与TokenRevocationIT，补充管理员设备列表仍按本人过滤的契约；修复DeviceBindingConcurrencyIT里过期的SQL列名userId为user_id。
- 沙箱内组合测试58项通过，但TokenRevocationIT因Docker访问权限失败；已获得权限重跑，使用Testcontainers隔离数据库和Redis。该环境失败不作为业务测试通过。

- 授权重跑：TokenRevocationIT 7项通过，UserApiContractTest 22项通过；管理员设备契约最初因替身只匹配token、未匹配完整Bearer头失败，修正替身后DeviceApiContractTest 7项通过，生产鉴权无需修改。
- 隔离模拟器使用target/graph-simulator、端口18082和专用autosense-graph-validation-mysql容器（13368），未连接开发设备库。DeviceBindingConcurrencyIT 3项通过；同时修正其旧错误封装断言为data.code。新增DeviceRegistryServiceTest验证未知型号仍绑定、online失败只是展示状态、不读取控制状态或发命令。

## T031/T036/T037 — 数据迁移与实体

- 先运行迁移测试，确认旧版因缺少workflow_request_id和前向迁移文件而失败：target/langgraph-migration-before.log。
- 新增20260915前向迁移并同步schema.sql；新增5张表、扩展3张旧表，业务消息/审计唯一键允许历史null，批准当前步骤唯一约束保留旧决定记录，命令按步骤及operationKey唯一。
- 新/旧库测试均在Testcontainers临时MySQL完成：旧ID、正文、短result及审计保留；旧关联为null，未伪造命令或checkpoint；不存在重复conversation/audit_event/workflow_event表。target/langgraph-migration-test.log为BUILD SUCCESS。
- 扩展旧实体并新增5个Lombok实体；启动schema检查仍只读，缺迁移时明确给出20260907和20260915脚本路径。

## Checkpoint 实施进行中

- 固定schema/graph版本的JSON serializer已替换主图默认Java对象序列化；拒绝未知版本、缺context、额外类型字段，数据限4MiB，不调用readObject。
- AssistantStateSerializerTest、MainGraphStubTest和GraphStreamContractTest共15项通过（target/langgraph-json-serializer.log）。
- 已添加MyBatis saver与数据库claim/fence校验，开始运行真实MySQL检查点最新/指定查询、历史保留及失效执行权拒绝测试；跨JVM及昂贵结果恢复尚未验证。

## G2 持久化、入口与恢复增量

- MySQL saver、命令账本、独立批准、追加审计及图节点投影已接入；所有外部调用在短事务之外。命令先保存意图/尝试，结果与步骤在同一事务提交。
- Controller已切换WorkflowExecutionService/ConversationQueryService；新增workflow查询、批准、恢复和取消，实际消费LangGraph4j stream，再投影公开DTO和legacy SSE。旧引擎源码仍待G5删除，真实能力仍待G3/G4接入。
- `target/langgraph-entry-it.log`：8项通过，覆盖新请求初始checkpoint、多轮独立workflow、查询后独立控制确认、取消保留查询结果、重启后批准不能代替resume，以及昂贵结果已提交/后续checkpoint失败的补齐。
- `target/langgraph-entry-unit.log`：27项通过，包含10项新HTTP契约、图流、序列化及日志格式。安全字段上传返回400，版本冲突在接纳前返回409；重复snapshot不重复输出，步骤结果不关闭计划，原始state不外泄。
- `target/langgraph-g2-boundaries.log`：15项通过，覆盖并发相同决定只有一次续接、参数变化不能复用批准、过期确认生成新确认且零设备读取、数据库结算超时后拒绝迟到成功/未知写重发、旧会话workflow=null、只读查询不执行、关联数据删除，以及6项原恢复/入口场景。
- 工作流确认等待在释放数据库claim后才发送给客户端；确认/取消竞争受版本与claim限制，活动调用期间冲突不会抹去已经发生的效果。
- 已新增仅在test-classes存在的崩溃子JVM夹具和进程级脚本；正在运行未批准/已批准/昂贵结果/命令结果的真实重启验证。脚本自动创建和清理独立Docker数据库，不操作应用数据。
- 进程验证已全部通过：`target/langgraph-restart-validation/results.json`、`target/langgraph-restart-run.log`。实际启动独立JVM，未批准/已批准两次重启保持WAITING_RESUME，GET不执行、跨用户403；KnowledgeConsult及ExecuteCommand在结果事务提交后、checkpoint写入前以退出码73崩溃，重启后显式resume完成。命令尝试仍仅1次，账本SUCCEEDED；临时容器已清理。
- 首轮真实启动暴露graph-stub惰性初始化未创建AuditTimestampConfig，导致旧实体时间列显式null；将该基础配置标为非惰性初始化后，完整重启脚本通过。未关闭SQL约束或削弱测试。
- G2数据/接口已通过；后续G3/G4仍需真实AI代理/设备验证，G5仍需迁移旧测试并删除旧引擎。当前应用数据库未自动迁移。

## G3 AI、知识与流适配

- 新IntentPlannerService通过方法级资源prompt及专用工厂创建；实际LangChain4j代理+WireMock验证两步骤条件计划、模板字符隔离、额外安全字段拒绝及503不重试。候选condition按公开契约转换为运行表达式，额外字段不会被SDK默认解析静默丢弃。
- 请求级GraphChatMemoryAdapter使用20条历史+当前输入一次；userId缓存保留三代理原子发布/过期，已解除AcceptedConversationInitializer依赖。
- KnowledgeWorkflowService复用共享索引、Advanced RAG、Direct/Enhanced工厂，返回完整正文和来源引用；保留类型/相关度校验、低相关度回退和跨型号声明。成功分析及检索结果进入步骤子调用缓存。
- AiTokenStreamAdapter使用256条队列嵌入真实graph stream；临时TEXT只进入独立snapshot，完成delta才持久化。部分输出超时有TEXT_RESET，队列溢出受控失败，迟到callback不能影响新尝试；legacy文本等STEP_RESULT提交后发送。
- 模型、embedding、设备HTTP请求的超时限制到当前尝试剩余时间；SDK重试强制为0，由图管理有限重试。启动知识导入继续使用既有独立startupTimeoutSeconds。
- `target/langgraph-g3-planner-retest.log`：13项通过；`target/langgraph-g3-budget-retest.log`：19项通过；`target/langgraph-g3-integration.log`：22项通过，包括真实图+AI Service代理+MySQL的常识直答/增强回答及来源持久化。模型传输明确使用LLM_MODE=mock，业务图为real，事件不标为STUB。
- 继续运行既有RAG、索引、缓存及实际代理回归；G4设备能力及G5旧链删除尚未完成。
- G3回归完成：`target/langgraph-g3-regression.log`共33项通过（真实代理8、RAG9、索引5、知识业务4、缓存3、装配4）。进入G4迁接设备能力。

## G4 领域能力迁接

- `target/langgraph-g4-safety.log`：15项通过（真实领域调用安全5、图流程10）；`target/langgraph-g4-integration.log`：14项通过（真实模拟器查询/控制/复检2、取证诊断与售后2、领域安全5、HTTP client5）。业务图为real，LLM传输为明确的mock模型；没有用stub替代设备调用。
- Query只在批准后读取；诊断IT以client spy确认批准前零调用、批准后仅一次取证读取，诊断没有额外读取或任何命令。结果保存目标、采集时间、白名单状态及证据引用。
- Control只执行一个型号白名单动作，校验整数参数、当前账号/归属/设备绑定和独立批准。未知写入不重发；设备锁使用每次调用独有owner，迟到释放不能解除另一调用的锁。结果明确标记NOT_PERFORMED复检状态；拒绝复检保留已成功控制事实。
- Diagnosis复用规则、RAG、专用DiagnosisReasonerServiceFactory、旧人工知识和售后服务，记录建议和来源，不自动增补控制。缺少设备证据时说明限制；新增读取、修复或复检须生成独立计划步骤。
- `target/langgraph-g4-locks.log`：19项通过（锁IT4、锁单测3、HTTP契约10、知识图1、运行后端OpenAPI导出1）。首次无Docker权限运行失败；权限重跑通过。工厂构造器改动导致一次测试编译失败，已同步测试装配后通过。
- 本期设备适配器支持亮度/色温控制；电源控制没有匹配的远端命令协议，返回CAPABILITY_NOT_AVAILABLE，属于005后续能力缺口，不伪装为start或执行成功。004后续扩展设备型号/参数语义回答沿用其规格，当前完成已有设备状态及诊断快照迁接。
- G4完成。前端交互与G5旧引擎删除仍待完成；应用数据库未自动迁移。

## 前端交互与浏览器验收

- T070：`WorkflowOpenApiIT`从实际运行的测试后端导出`target/workflow-openapi.json`，包括WorkflowEvent/WorkflowView/批准/恢复/取消DTO；`OPENAPI_SCHEMA_PATH`指向该文件运行`npm run openapi2ts`，未手写生成类型。`target/langgraph-openapi-export.log`1项通过。
- T071：页面读取公开workflow事件，逐步显示结果/状态/失败，处理临时TEXT_RESET，现代事件与legacy文本互斥消费。批准携带stepId/approvalId/version，澄清携带inputRequestId/version；显式resume/cancel，发送期间按钮禁用。断线/刷新只GET，不自动批准或恢复。
- 本地浏览器`http://127.0.0.1:15174`连接隔离后端18184；`scripts/manual-test/workflow-ui-server.py`使用专用测试数据库、Redis和18082模拟器，不连接应用数据库或真实模型供应商。
- 已实际登录并验证：常识与型号追问留在同一会话；查询→控制→复检各自确认；拒绝复检显示2/3完成且控制成功保留；亮度80时条件控制SKIPPED；待确认查询可取消；点击后操作按钮禁用；旧版仅有conclusion的会话仍能展示正文；第二账户打开第一账户会话显示“无权访问此会话”且禁用输入、没有泄露历史。
- 在WAITING_APPROVAL期间实际重启后端，刷新显示WAITING_RESUME与“继续执行”；服务端只读GET验证查询仍未执行。点击继续后仍呈现设备确认，随后人工点击批准才读取测试设备。截图/可访问性树已在本轮浏览器工具结果检查，失败边界与控制成功结果显示正常。
- 浏览器检查发现恢复会重复保存同一批准提示，已按approvalId/inputRequestId/stepId/最终输出种类生成稳定消息键；`target/langgraph-ui-recovery.log`21项通过，新增未批准重启恢复消息数不增断言。
- `npm run type-check`与`npm run build`通过，最终证据`target/langgraph-frontend-build.log`。Sites构建包装脚本在Windows定位npm失败，使用项目原有构建命令成功；未改动Sites配置或部署。既有大chunk提示不影响构建。
- T072完成，进入G5；本期完整默认/IT/JAR最终回归尚未完成。

## G5 删除、并发回归与最终制品

以下为最终实施验收；前面的“待完成”文字按当时状态保留。

- T073–T076：删除28个旧生产文件，包括旧Orchestrator、Processing、Context/Store、Transition、Runner、状态机、dispatcher/handler、回调DirectAnswerer、旧IntentRouter及专属类型/prompt、initializer与AssistantProperties。RepairExecutor仅保留已确认单次命令入口，旧日志与隐式读取/复检路径已移除。历史表和必要状态枚举继续保留。
- T077/T080：旧测试的有效断言迁移至新图、知识服务和事务边界；无mock旧引擎。LegacyOrchestrationRemovalTest验证源码和class/prompt不可加载、生产无旧引用或MemorySaver；WorkflowLoggingIT验证单一CompiledGraph、MyBatisCheckpointSaver、并发MDC隔离和批准HTTP新requestId关联旧workflowRequestId。WorkflowAuditIT验证回滚不写入消息/事件/会话且无afterCommit假成功。
- T078：启动只读门禁拒绝仍持有processing_message_id且无图工作流的旧请求，不自动删除/改写数据。WorkflowPersistenceMigrationIT同时验证20260907迁移重复执行、旧表升级、存量数据保留、旧未结束请求拒绝和新库唯一约束。生产不允许stub，图异常不回退旧链。
- T079：章程2.3.1同步Java基包graph目录与分层；002/001/003接入说明和规格总览更新，带日期旧设计和任务快照保留。旧任务快照SHA-256仍为`988cc512c2ebf28e93164e7b9d145de15dd82b752a779ed8911225f98c6462fb`。
- 回归发现并修复：计划发布对不存在的步骤行加FOR UPDATE造成并发间隙锁死锁，改为持有工作流主记录锁时原子发布全部步骤；命令初始化使用同事务步骤引用避免锁不存在的命令。KnowledgeConversationIT验证同用户两会话并发不同类型检索不串来源，执行权过期后迟到回答不能落库，GET只读。
- 最终ResponseAggregator使用已提交步骤正文形成结论，保留来源，修复旧客户端与JAR接口仅得到泛化结束提示的问题。步骤结果与最终输出分别留审计；前端合并相邻同文助手气泡，最终浏览器新会话及刷新均显示一条完整回答。`target/langgraph-g5-frontend-build-final.log`构建通过。
- 其他回归修复：缓存初始化失败且恢复写入也失败时仍释放claim；attemptId保持固定长度UUID；非整数设备引用拒绝；检索超时保留TimeoutException原因供图识别；LangGraph4j/async原始异常日志关闭，应用边界继续输出脱敏诊断。用户重复SN绑定测试使用现有BaseResponse.data.code契约。
- T081：执行干净构建清除残留class后，最终`mvn -B verify`为**205项、零失败/错误/跳过**，证据`target/langgraph-g5-verify-release.log`。Windows wrapper在当前环境启动失败，使用同版本已安装Maven运行，未略过测试。
- 最终完整`mvn -B -Pit -Ddevice.service.base-url=http://127.0.0.1:18082 verify`为**257项、零失败/错误/跳过**，其中52项IT；证据`target/langgraph-g5-full-it-final.log`。实际使用Testcontainers MySQL/Redis和独立deviceSimulator，包含真实查询/控制、独立复检确认、未知写入/迟到结果、批准与取消竞争、历史与删除、权限隔离、新建/升级库。
- 依赖树`target/langgraph-g5-dependencies.txt`：LangGraph4j core 1.8.27、LangChain4j 1.0.1、SLF4J 2.0.17和Log4j2 2.24.3；无logback/第二日志提供者。`graph-artifact-validation.py`按生产AI接口实际注解动态发现**12个prompt**，Boot JAR逐字节一致，旧prompt/编排class缺失；独立数据库上的JAR启动、知识SSE正文/来源与持久化校验通过。证据`target/graph-artifact-validation/result.json`，JAR SHA-256为`b83ee66dd0cf555aaf8f69a13f9c91987c7f0a9dbbed7e17d615eb0e3956edb4`。
- 首次完整回归的失败及修复过程保留在`target/langgraph-g5-full-it.log`、`target/langgraph-g5-regression.log`；以final/release文件为最终结果。干净构建前的早期日志已另存`.validation/langgraph/`，未覆盖本期之前的规格验证档案。
- 验收使用本地mock模型传输与实际LangChain4j代理/RAG，未调用真实外部模型或宣称模型准确率。004/005尚未实现的设备业务扩展仍按各自规格推进，本次完成公共图与已有能力迁接。未对使用者的业务数据库执行迁移，未部署或提交Git。
- T082：最终独立JVM脚本三组全部PASS，证据`target/langgraph-restart-validation/results.json`及`target/langgraph-restart-run.log`。未确认/已确认两次重启均等待本人显式resume，GET和跨用户请求不执行；KnowledgeConsult与ExecuteCommand在结果已提交、后续checkpoint尚未写入时以退出码73终止，重启后复用结果，命令尝试仍为1。脚本已清理自己的临时容器。
- T083：83项新任务全部具备本期证据；旧任务快照未改写。完整默认、IT、JVM恢复、JAR/资源、依赖与前端验收通过；`git diff --check`通过；无`.specify/extensions.yml`，无待执行after_implement hook。

### 需求覆盖复核

| 需求 / 成功标准 | 本期证据 |
| --- | --- |
| FR-001–006 / SC-001、005 | IntentPlannerServiceTest、PlanValidationTest、MainGraphStubTest、GraphPropertiesTest：四类计划、条件与引用、非法输入、资源AI代理及配置 |
| FR-007–011 / SC-002、003 | UserApi/DeviceApi契约、UserManagementIT、TokenRevocationIT、DeviceBindingConcurrencyIT与DeviceWorkflowSafetyTest：账号、绑定、所有权、每次设备请求前校验 |
| FR-012–015 / SC-004、006 | WorkflowApiContractTest、WorkflowEntryIT、KnowledgeConversationIT、GraphChatMemoryTest、GraphTokenStreamTest、WorkflowAuditIT及浏览器验收：跨轮、步骤事件、来源、历史与流 |
| FR-016–018 / SC-007、008 | WorkflowApprovalIT、CommandExecutionIT、DeviceWorkflowSafetyTest、DeviceLockIT、StepAttemptExecutorTest：独立批准、过期/变更/撤权拒绝、超时有限重试、未知写和迟到结果 |
| FR-019–021 / SC-009、010 | WorkflowCheckpointIT、WorkflowRecoveryIT、MainGraphStubTest及独立JVM三组恢复：持久检查点、昂贵步骤复用、失败停止、重启不自动执行 |
| FR-022 / SC-011 | LegacyOrchestrationRemovalTest、WorkflowLoggingIT、WorkflowCutoverTest、源码搜索、JAR缺失旧类、章程与接入文档同步 |
| FR-023 / SC-012 | WorkflowPersistenceMigrationIT、PersistenceMappingIT、WorkflowAuditIT、CommandExecutionIT、ConversationHistoryIT及命令提交后进程终止恢复：五类数据、旧表复用、唯一身份、不可覆盖结果与审计 |

以上为确定性验收样例覆盖，不代表真实模型在任意自然语言上的准确率。模型供应商连通性和业务数据库迁移需在实际部署环境按配置执行。

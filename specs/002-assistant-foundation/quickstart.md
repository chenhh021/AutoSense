# Quickstart: LangGraph4j 骨架与恢复验收

**Date**: 2026-09-15
**Status**: 实施后的运行指南。下列新增测试、配置、端点和迁移尚未实现，本次plan没有执行它们；历史通过记录见validation.md。

## 1. 前提与既有回归

Java21、项目Maven Wrapper；持久化验证需要Docker或隔离的MySQL/Redis。骨架stub不需要模型密钥、embedding网络或外部模拟器。不要对生产库运行验证或重放历史设备命令。

仓库根执行：

```powershell
.\mvnw.cmd verify
.\mvnw.cmd dependency:tree "-Dincludes=org.bsc.langgraph4j:*,dev.langchain4j:*,com.fasterxml.jackson.core:*,org.slf4j:*,org.apache.logging.log4j:*"
```

预期：LangGraph4j core=1.8.27、LangChain4j仍为1.0.1，单一SLF4J提供者；新增graph测试通过，同时保持既有知识/用户/设备回归。不得将-Pit旧失败静默排除后宣称全通过。

## 2. G1离线骨架

计划新增测试类及命令（实施后可执行）：

```powershell
.\mvnw.cmd "-Dtest=MainGraphStubTest,GraphStreamContractTest,PlanValidationTest" test
```

| 场景 | 预期 |
| --- | --- |
| 四类简单意图 | 每类恰好一个对应节点/子图，输出包含simulated |
| 查询亮度，低于30则调80 | 先Query批准，再条件判定，再Control批准；不是一次整体授权 |
| 条件false | 控制SKIPPED，无命令；CompleteStep正常推进 |
| 两个连续Query | 两次独立确认，第二步不沿用第一步上下文/批准 |
| 非法类型/循环/前向引用/试图绕过Control | 进入Reject或澄清，无设备调用 |
| 一次timeout随后成功 | 有限自动重试，同一步骤同operation key、一次业务效果 |
| 明确错误/拒绝/重试耗尽 | 整计划停止，剩余NOT_EXECUTED，先前结果保留 |
| 输出白名单 | Controller只投影OutputContext；无state/messages/prompt泄漏 |

此层可用MemorySaver，不能证明跨进程恢复。

## 3. G2 MySQL检查点与确认

迁移脚本和新建库DDL按[data-model](data-model.md)在实施阶段生成，在隔离库执行；配置数据库/Redis连接与凭据使用环境变量。stub profile必须显式启用并且禁用真实知识索引/模型装配。

```powershell
$env:LLM_MODE = 'mock'
$env:AUTOSENSE_GRAPH_MODE = 'stub'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=graph-stub"
```

取得现有登录接口返回的测试Bearer token后，以环境变量传入；以下URL按默认8180，本地端口不同时自行替换：

```powershell
curl.exe -N -H "Authorization: Bearer $env:AUTOSENSE_TEST_TOKEN" -H "Content-Type: application/json" --data-raw '{"problem":"查询客厅灯亮度，低于30就调到80"}' http://localhost:8180/api/v1/sessions
```

从workflow/awaiting事件读取sessionId、requestId、stepId、approvalId及version，按[API契约](contracts/assistant-api.md)提交approval；这些标识必须来自响应，不能硬编码复用另一轮。
确认第一步后应收到STEP_RESULT，若条件满足则等待第二步确认；最终只收到一次整计划conclusion。拒绝任一步骤后不出现后续设备操作。
命令行可能因PowerShell版本的原生参数转义差异改变JSON；如遇请求体绑定错误，把请求JSON写为UTF-8文件，curl使用--data-binary @文件路径，不能将400误判为graph失败。

执行新集成测试：

```powershell
.\mvnw.cmd -Pit "-Dtest=WorkflowCheckpointIT,WorkflowApprovalIT,WorkflowRecoveryIT" test
```

覆盖真实MySQL的saver get/list/put及版本化JSON往返、事务失败、并发resume/approval、唯一step效果、租约丢失后迟到回调、持久结果先于checkpoint的修复。
五类持久数据按[data-model](data-model.md#4-持久化设计)分别验收；测试实施后执行：

```powershell
.\mvnw.cmd -Pit "-Dtest=WorkflowPersistenceMigrationIT,CommandExecutionIT,WorkflowAuditIT" test
```

| 场景 | 预期证据 |
| --- | --- |
| 存量库升级与全新库初始化分别启动 | 扩展repair_session/chat_message/repair_action_log，新增workflow_execution/command_execution及step/approval/checkpoint三张辅助表；启动只读schema校验通过，不新增平行conversation/audit_event/workflow_event表 |
| 含旧状态、动作日志与多轮消息的库升级 | 原ID、正文、action_code/result/params及行数不变；新增关联可为null，未伪造可恢复workflow/command；旧列表和历史可读 |
| 同一会话先知识后查询/控制 | 共用原conversationId，多次workflow关联各自输入；查询只产生步骤/审计，控制有独立command；每步结果与消息可追溯 |
| 有限安全重试与重复恢复 | 同一控制步骤始终同commandId/operationKey，attempt各有审计；相同业务事实/最终消息不重复写入；真实不支持幂等的未知命令不重发 |
| 意图或写前checkpoint保存失败 | 设备请求数为零；已存在准备记录不等于执行成功 |
| 命令结果事务成功、随后checkpoint失败并重启 | 用户显式恢复后读取command结果补齐state，不再次发送；最终消息不重复，审计事件关联正确 |
| 状态/命令结果/审计事务回滚 | 不出现半提交的成功投影，SSE不报告成功；已出站命令不可误标为未发送 |
| 审计与SSE区别 | 审计包含步骤/批准/尝试事实；公开输出仅白名单，允许序列间隙，不逐token写审计；批准SUCCESS不显示为设备已执行 |
| 会话删除 | 非终止或UNKNOWN命令拒绝；允许删除时关联新表一并处理，无孤立命令、消息或审计 |

进程级验证必须真正停止并重新启动JVM，不能仅新建一个Java对象代替：
1. 在Query或Control的WAITING_APPROVAL暂停，记下原requestId和checkpoint。
2. 重启服务；GET显示WAITING_RESUME且没有任何业务步骤自动执行。
3. 用户显式resume；未批准仍等待确认，批准有效则按既有作用域恢复。
4. 在昂贵stub成功并保存结果后、下一步骤执行前重启；恢复不再次调用昂贵stub。
5. 检查重试次数保持、原threadId不变、同会话新轮使用新requestId。
6. 对终止计划提交resume只返回已有终态；检查点丢失/未知版本明确失败，不从START重放。

## 4. G3真实知识与TokenStream

保留003共享InMemoryEmbeddingStore、DirectAnswer/EnhancedAnswer专用工厂及Caffeine userId缓存。真实AI代理测试使用WireMock而不是供应商密钥。

```powershell
.\mvnw.cmd "-Dtest=IntentPlannerServiceTest,GraphChatMemoryTest,GraphTokenStreamTest,KnowledgeWorkflowServiceTest" test
```

预期：常识无检索、专用知识按类型筛选、资料不足直答、跨型号声明和来源持久化不回退；同用户两会话不串历史，当前输入一次，resume不再次追加原始输入。
验证同步/异步completion、部分token后timeout、TEXT_RESET、legacy缓冲、重复snapshot输出去重和晚到callback；最终done delta可恢复，中间token不假装已持久化。
按实际接口枚举@SystemMessage/@UserMessage引用并检查Boot JAR中的每个prompt字节，不再用旧固定六文件清单。

## 5. G4真实设备安全边界

仅在隔离外部deviceSimulator及测试用户绑定数据上运行；按既有方式提供DEVICE_SERVICE_BASE_URL。stub通过不能证明真实控制幂等。
验证读取、取证、复检全部有独立有效确认；Diagnosis没有DeviceServiceClient调用；Control没有隐式读后复检。
模拟命令发送后响应超时，当前client无远端幂等时应返回DEVICE_RESULT_UNKNOWN并停止，命令重发数为零；不得用另一次未确认查询探测结果。
若后续adapter支持operation key，应新增独立契约证明相同key多次尝试仅一次效果，再开放该adapter重试。

## 6. G5旧编排删除、前端与完整回归

切换前先停止旧版本接纳新请求，等待其执行结束；对无法完成的旧请求，由运维核对设备实际结果并在旧版本中明确结束。不要把旧请求伪造为可恢复图任务。以下只读检查必须返回 0：

```sql
SELECT COUNT(*) FROM repair_session
WHERE processing_message_id IS NOT NULL AND active_workflow_request_id IS NULL;
```

SessionSchemaValidator 在启动时执行同样检查，存在未处理旧请求时拒绝启动，不自动清空指针或修改历史。数据库先执行旧处理字段迁移，再执行 `scripts/migration/20260915-langgraph-workflow.sql`；本次实现未对使用者的业务数据库执行迁移。

迁移完成的验收必须同时满足下列条件；只禁用旧Bean、保留精简SessionOrchestrator或把原方法搬到新服务不能通过。

1. 按[计划中的删除矩阵](plan.md#旧编排删除与职责迁移)核对：旧编排专属源码、Spring Bean和生产引用为零；当前测试不再依赖这些类型。代码引用检查须覆盖src/main/java、src/test/java及配置，不能将历史档案里的类名误判为运行依赖。
2. 创建、续聊、确认、恢复、取消、补查、列表、历史及删除均由新工作流入口或独立查询服务提供；执行只走graph。模型异常、检查点异常和旧请求兼容路径均不回退旧引擎。
3. 历史消息读取不依赖SessionProcessingService.Accepted；历史记录仍可查询。旧Redis续接上下文不再读写或驱动恢复，删除旧编排不要求删除历史业务数据。
4. 原接纳事务、租约/互斥、afterCommit、缓存初始化及日志关联的行为已迁移并有回归证据；移除旧配置前核对embedding启动预算、工作流截止和SSE超时的全部消费者。
5. 将旧状态机、能力分发和回调驱动测试替换为graph行为测试；保留知识来源/回退、身份隔离、会话历史、设备幂等和日志脱敏断言。旧RepairExecutionRunner不得作为子图节点继续执行隐式取证或复检。
6. 当前设计以graph为准；旧编排图与迁移草案已注明历史状态。验证WorkflowExecutionService仅负责接纳、授权恢复、并发与运行资源管理，没有第二套路由、状态迁移表或业务回调链。

前端实施后在frontend执行：

```powershell
npm run build
```

手动检查旧会话、单步知识、复合步骤进度、Query/Control分别确认、失败原因、刷新后GET补查、重启后显式继续、重复点击批准/恢复。页面不得把simulated当真实结果或把未复检显示为已修复。
最终运行默认verify、完整-Pit及JAR启动验证，记录环境、计数及未满足外部前提；不覆盖既有validation历史，将本期证据另设日期章节或文件。

### 本期已执行结果

默认verify 205项、完整verify -Pit 257项全部通过；独立JVM恢复三组、最终JAR启动及12个实际prompt逐字节校验、前端构建与浏览器交互均通过，详见[验证记录](validation-langgraph.md)。设备验收使用隔离deviceSimulator，模型传输使用mock及真实LangChain4j代理。业务数据库未自动迁移。

额外可重复验收脚本：`scripts/manual-test/langgraph-restart-validation.py`使用独立容器验证进程恢复；`scripts/manual-test/graph-artifact-validation.py`检查最终JAR实际prompt并在独立容器上验证启动与知识HTTP。脚本自动清理自建容器，不连接默认业务数据库；运行前先完成verify并生成test classpath（restart脚本文件头列出前提）。

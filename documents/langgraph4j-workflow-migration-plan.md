# AutoSense LangGraph4j 业务流程迁移方案

- 编制日期：2026-09-13
- 状态：历史迁移草案，已于2026-09-15被[002实施计划](../specs/002-assistant-foundation/plan.md)取代，保留存档。
- 目的：将会话业务编排逐步迁移至 LangGraph4j，提高路由、等待用户、审批、控制和诊断流程的可读性与扩展性。
- 范围：六步迁移路线、文件调整、阶段目录结构、验收标准及切换约束。
- 本文是迁移设计，不代表相关功能已完成，也不替代各 feature 的 `spec.md → plan.md → tasks.md` 工作流。

> 以下正文保留原草案，不再指导当前实施。其中保留精简SessionOrchestrator、旧目录和旧流程的建议均已失效。现行目标是在Java基包根目录graph下完成迁移，删除SessionOrchestrator及旧编排专属设计和运行链；具体职责迁移与验收以002计划为准。

## 1. 当前实现与迁移边界

当前两个相关类的职责不同：

- `core/session/statemachine/SessionStateMachine.java`：校验会话状态迁移是否合法，本身不执行完整业务流程。
- `core/session/SessionOrchestrator.java`：负责首次路由、等待后的续接、能力调用、结果收尾，以及请求执行期间的租约、超时和 SSE 生命周期。

因此，迁移的主要对象是 `SessionOrchestrator` 中的业务流程控制，不能只替换 `SessionStateMachine`。

编制本文时，正式注册的 `AssistantCapabilityHandler` 实现为知识咨询处理器。设备查询、诊断和安全控制还需要结合已有代码及所属 feature 补齐接入。因此，本方案同时包含“替换现有编排”和“补齐能力流程”，不能将所有步骤视为纯粹的等价重构。

建议新增 `core/workflow/` 存放公共图编排与运行入口；LangChain4j AI Service、工厂和 RAG 继续保留在 `ai/`。业务专属图放在对应的 `core/` 业务包中。

LangGraph4j 提供条件分支、检查点、中断及恢复机制，节点可以调用模型或普通 Java 业务逻辑。具体依赖版本须在第一步验证并锁定，不能直接假设与项目当前 LangChain4j `1.0.1` 兼容。

参考：[LangGraph4j 官方核心文档](https://langgraph4j.github.io/langgraph4j/1.9/core/core-library/)、[等待用户输入示例](https://langgraph4j.github.io/langgraph4j/1.9/how-tos/wait-user-input/)。

## 2. 总体设计约束

### 2.1 流程及业务状态的职责

| 状态 | 职责 |
| --- | --- |
| 图执行状态 | 当前节点、下一步路径、可恢复的流程数据 |
| 会话展示状态 | 正在分析、等待确认、执行中、已完成等用户可见进度 |
| 业务操作状态 | 某项具体设备操作的待审批、已取消、执行中、成功、失败或结果未知 |

图负责决定业务流程接下来走哪里；业务数据库负责会话归属、操作授权、执行记录和审计的权威数据。检查点与业务记录之间必须设计一致性及恢复策略，不能让两套状态独立决定同一操作是否可执行。

### 2.2 复用与安全边界

- 复用现有 Intent Router、AI Service 工厂、用户 AI 服务缓存、Advanced RAG、设备客户端、适配器、归属校验及设备锁。
- 知识咨询作为已有能力接入图，不为图化而重复拆建检索链路。
- 直接控制与诊断提出的修复动作统一进入确定性的审批和执行流程。
- AI 可以理解回复、提取操作方案，但不得绕过权限、参数校验、确认和审计直接控制设备。
- AI Service 缓存可以继续使用 `userId`；图执行标识必须关联具体会话或业务任务，不能仅使用 `userId`。
- 图状态只保存可恢复的数据及业务记录引用，不保存 AI Service 实例、HTTP 请求、SSE 对象或回调。
- 等待用户时结束本次 SSE 并释放请求执行资源。审批有效期与单次模型调用、请求处理预算分别管理。
- 检查点恢复不等于设备命令恰好执行一次。命令下发前记录执行意图，通过操作标识、执行资格及结果记录防止重复执行；结果不明时只读核查，不能自动重新下发。

### 2.3 实施组织

六步分别提交和验收。新增文件名为建议命名，实施时应根据正式 plan 和代码复用情况调整。

各步目录树展示该步完成后的相关部分；未列出的文件沿用前一步，不表示删除。`<日期>` 是数据库迁移脚本的命名占位，实施时替换为实际日期。

每个图优先使用一个图定义类和一个步骤实现类，简单节点可作为方法组织，避免为每个节点创建独立文件。

## 3. 第一步：确定迁移契约，建立图运行基础

### 3.1 目标与工作

明确图属于哪个会话、如何持久化及恢复、如何隔离用户，以及业务状态和检查点各自的职责。

1. 在章程中增加 `core/workflow/` 分层规范。
2. 更新 `002-assistant-foundation` 的编排设计、数据模型、运行契约及迁移任务；涉及用户可见行为变化时同步 spec。
3. 验证并锁定 LangGraph4j 与现有 LangChain4j 的兼容版本。
4. 配置持久化检查点。内存检查点可以用于局部测试，不能承担生产重启恢复。
5. 建立业务任务与图执行实例的关联，记录用户、会话、轮次、图版本和执行版本。
6. 在运行契约中明确检查点与业务记录的写入顺序、并发版本检查、失败恢复及旧版本执行实例处理策略。

### 3.2 文件调整与阶段结构

```text
AutoSense/
├── pom.xml                                      修改：增加并锁定依赖
├── .specify/memory/constitution.md               修改：补充分层规范
├── specs/002-assistant-foundation/
│   ├── plan.md                                  修改
│   ├── data-model.md                            修改
│   ├── tasks.md                                 修改
│   └── contracts/workflow-runtime.md            新增
├── scripts/migration/
│   └── <日期>-workflow-runtime.sql              新增
└── src/main/
    ├── java/com/chh/autosense/
    │   ├── config/WorkflowConfig.java           新增：图及检查点配置
    │   ├── core/workflow/
    │   │   ├── AssistantState.java              新增：可持久化流程数据
    │   │   └── WorkflowRuntime.java             新增：启动、恢复入口
    │   ├── domain/entity/WorkflowExecution.java 新增：业务执行关联
    │   └── mapper/WorkflowExecutionMapper.java   新增
    └── resources/
        ├── application.yaml                     修改
        └── schema.sql                           修改：新库初始化
```

### 3.3 验收标准

- 简单测试流程能够保存、停止，并在重建运行环境后恢复。
- 同一用户的不同会话及不同用户之间的执行状态相互隔离。
- 依赖兼容性和状态序列化得到验证。
- 本步不切换正式请求入口。

## 4. 第二步：入口图接管首次路由，复用知识咨询

### 4.1 目标流程

```text
接收消息 → 意图识别 → 校验识别结果
                       ├─ 需要澄清
                       ├─ 超出范围
                       └─ 分发已接入能力 → 回答完成
```

本步先让已有知识咨询通过新图运行，验证编排替换对现有能力的影响。

### 4.2 文件替换方式

| 现有位置 | 本步处理 |
| --- | --- |
| `SessionOrchestrator.route()` | 将业务分支迁移到 `AssistantGraph` |
| `SessionOrchestrator` 中超时、租约和 SSE 生命周期 | 保留为请求外围管理 |
| `IntentClassifier`、`RoutingDecisionValidator` | 直接复用 |
| `CapabilityDispatcher` | 暂时保留，由图调用 |
| `KnowledgeCapabilityHandler` | 直接复用 |
| `SessionProcessingService` | 保留事务、归属校验及过期写入防护 |

```text
core/
├── workflow/
│   ├── AssistantState.java
│   ├── WorkflowRuntime.java
│   ├── AssistantGraph.java                新增：节点与分支定义
│   └── AssistantSteps.java                新增：入口节点实现
├── session/
│   ├── SessionOrchestrator.java           修改：转调图运行入口
│   ├── SessionProcessingService.java      保留
│   ├── SessionLeaseService.java           保留
│   └── statemachine/SessionStateMachine.java
└── routing/
    ├── IntentClassifier.java
    ├── RoutingDecisionValidator.java
    ├── CapabilityDispatcher.java
    └── KnowledgeCapabilityHandler.java
```

### 4.3 验收与切换

- 原有知识咨询、常识直答、RAG 回退、意图澄清及 SSE 契约保持通过。
- 未接入能力仍明确返回不可用。
- 迁移期间按会话固定旧引擎或新引擎，并记录归属；不能在一次失败后自动换引擎重跑同一请求。
- 第三步完成前，澄清等待可以通过现有兼容路径处理，但同一会话的执行归属必须明确。

## 5. 第三步：统一等待用户、接收回复与恢复流程

### 5.1 目标与业务语义

替换 `continueWaiting()` 和 Redis 等待上下文所承担的流程恢复职责。恢复入口先判断回复与当前任务的关系：

- 补充信息；
- 确认或拒绝；
- 修改目标或参数；
- 询问当前方案；
- 切换到新任务。

新增等待记录标识，保证回复对应当前有效等待点。普通澄清与设备审批共用暂停机制；设备审批的授权记录在第四步建立。

按钮产生的明确确认、拒绝事件直接按业务规则处理；自然语言回复需要理解时再调用 AI。询问方案影响时保留待审批状态，修改方案时使旧审批失效。新话题如何挂起或取消原任务，需要在实施前写入对应业务契约。

### 5.2 文件调整与阶段结构

```text
core/
├── workflow/
│   ├── AssistantGraph.java                修改：区分新任务与等待回复
│   ├── AssistantSteps.java                 修改
│   ├── WorkflowRuntime.java                修改：恢复前校验
│   └── WaitingReplyResolver.java           新增：识别回复与任务的关系
└── session/
    ├── SessionOrchestrator.java            修改：移出 continueWaiting 流程
    ├── SessionContext.java                 暂留：旧会话兼容
    └── SessionContextStore.java            暂留：旧会话兼容

domain/
├── dto/
│   ├── MessageRequest.java                 修改：携带等待记录标识
│   └── WaitingRequestDto.java              新增：前端等待信息
└── enums/WaitingKind.java                  新增：等待原因

ai/
├── WaitingReplyService.java                新增：自然语言回复理解
├── factory/WaitingReplyServiceFactory.java 新增
├── model/WaitingReplyDecision.java         新增
└── model/enums/WaitingReplyType.java        新增

src/main/resources/prompt/waiting-reply.txt  新增
```

同时调整以下文件或契约：

- `domain/message/SseEvent.java`、`SseEventStream.java`：输出等待信息并结束本次流。
- `controller/SessionController.java` 和相关会话 DTO：传递等待回复字段。
- `frontend/src/pages/console/ChatPage.vue`：展示并恢复等待交互。
- `frontend/src/utils/sse.ts` 及会话 API 客户端：适配新增字段，按正式接口契约生成客户端。
- `002-assistant-foundation` 的运行及 API 契约：定义刷新页面后获取当前等待信息的方式。

AI 提示词保存在资源目录，通过 `@SystemMessage(fromResource = "/prompt/waiting-reply.txt")` 引用。实体使用 Lombok 简化，不可变 DTO 优先使用 Java 21 record，关键日志使用英文。

### 5.3 验收标准

- 离开页面或服务重启后，可以继续同一等待任务。
- 重复、过期及其他会话的回复不能推进流程。
- 等待期间关闭本次 SSE、释放处理租约，不消耗模型调用预算。
- 新任务切换按既定规则取消或挂起原任务，不隐式丢失。
- 缺失恢复上下文时明确失败或要求重新发起，不自动推进设备操作。

## 6. 第四步：建立共用审批与设备控制流程

### 6.1 目标流程

本步既包含重构，也包含 `005-safe-device-control` 尚未完成的业务建设。先同步其 `spec.md`，再形成或更新 `plan.md`、`data-model.md`、契约与 `tasks.md`。

```text
形成操作方案 → 校验 → 保存并展示审批
                         ↓
                     等待用户
                         ↓
确认 → 复核 → 抢占执行资格 → 下发 → 验证 → 审计与反馈
拒绝或过期 → 取消
修改方案 → 使旧审批失效 → 重新校验和审批
```

审批绑定用户、设备、动作、参数、方案版本及有效期。恢复后重新检查审批有效性、用户权限和设备条件。

### 6.2 文件调整与阶段结构

```text
core/
├── workflow/AssistantGraph.java                 修改：接入设备控制
├── device/control/
│   ├── DeviceControlGraph.java                  新增：控制流程
│   ├── DeviceControlSteps.java                  新增：节点实现
│   ├── DeviceOperationService.java              新增：方案、审批及状态事务
│   └── DeviceCommandExecutor.java               新增：受控设备写入口
├── repair/RepairExecutor.java                   暂留，逐步迁移执行逻辑
└── session/
    ├── DeviceLockService.java                   复用
    └── RepairExecutionRunner.java               暂留旧路径

domain/
├── entity/DeviceOperation.java                  新增
├── enums/DeviceOperationStatus.java             新增
└── dto/
    ├── DeviceOperationProposal.java             新增
    └── MessageRequest.java                     修改：审批标识和方案版本

mapper/DeviceOperationMapper.java                新增
scripts/migration/<日期>-device-operation.sql    新增
src/main/resources/schema.sql                   修改：新库初始化

frontend/src/
├── components/DeviceApprovalCard.vue            新增
├── pages/console/ChatPage.vue                    修改
└── api/sessionController.ts                     按契约重新生成
```

职责分工：

- `DeviceOperationService` 管理谁批准了哪份方案、方案是否过期、执行资格是否已被占用。
- `DeviceCommandExecutor` 复用适配器、白名单及设备锁，增加执行前记录和重复执行防护。
- `RepairExecutor` 中的可复用实现逐段迁入，调用全部迁移完成后才删除旧类。
- `RepairActionLog` 等现有审计模型按需要扩展关联操作编号，避免另建重复的审计体系。

当前 `RepairExecutor` 主要承担白名单校验、执行和执行后日志，不能直接视为已具备完整审批及恢复安全性的控制入口。

### 6.3 验收标准

- 修改目标或参数后必须重新确认。
- 重复确认、重复请求和恢复执行不会重复下发命令。
- 权限变化、审批过期或方案版本不符时拒绝执行。
- 下发前无法可靠保存执行意图时不执行。
- 执行结果不明时记录未知，并进行只读核查，不自动重发。
- 拒绝或过期时没有设备写调用；命令已下发后的取消不能被表述为撤销已发生的操作。

## 7. 第五步：接入设备查询、诊断并复用控制流程

### 7.1 实施顺序及业务边界

先接只读设备查询，再接诊断。两者复用设备定位、归属校验和设备客户端。

设备查询包括依赖设备参数才能回答的问题，不要求所有查询都经过诊断。

诊断必须区分：

- 控制操作成功，例如重启命令执行成功；
- 故障已修复，需要诊断复检才能确定。

因此，共用控制流程返回执行结果后，诊断图仍需继续复检。

```text
定位设备 → 收集证据 → 分析
                      ├─ 追问用户 → 恢复分析
                      ├─ 人工或售后建议
                      └─ 提出修复方案
                             ↓
                       共用控制流程
                             ↓
                         诊断复检
```

### 7.2 文件调整与阶段结构

```text
core/
├── workflow/
│   ├── AssistantGraph.java                 修改：四能力入口
│   └── ...
├── device/
│   ├── query/
│   │   ├── DeviceQueryGraph.java           新增
│   │   └── DeviceQuerySteps.java           新增
│   ├── control/                            共用审批与控制流程
│   ├── client/                             复用
│   ├── adapter/                            复用
│   ├── rule/                               复用
│   └── spi/                                复用
├── analysis/
│   ├── DiagnosisGraph.java                 新增
│   ├── DiagnosisSteps.java                 新增
│   ├── DiagnosisReasoner.java              复用
│   └── ProblemAnalyzer.java                按当前职责复用
├── aftersales/                             复用
└── session/DeviceLocator.java               复用
```

`RepairExecutionRunner` 原先混合承担的执行、复检及会话收尾职责，分别迁至控制流程、诊断流程和公共请求入口。

同步 `004-user-device-query`、`001-iot-auto-diagnosis` 的 spec、plan、数据模型、契约及 tasks；知识咨询只调整编排接入说明，不重写已有 RAG 设计。

### 7.3 验收标准

- 设备查询全程只读，并能处理目标不明确时的补充信息。
- 诊断无法绕过共用审批直接执行设备变更。
- 诊断追问可跨请求恢复。
- 控制成功但故障仍存在时，正确进入人工或售后分支。
- 诊断、控制调用同一操作流程时的子图暂停、恢复及结果回传得到验证。

## 8. 第六步：切换全部入口，清理旧编排及兼容代码

### 8.1 清理前提

只有新流程验收通过，且旧等待会话已经结束或按明确策略处理后，才删除旧路径。

不能将旧版本检查点直接交给不兼容的新图恢复。上线与回退策略必须明确处理在途会话、待审批操作、图版本及检查点保留期限。已执行的设备操作不能通过软件回退撤销。

### 8.2 文件最终处理

| 文件 | 最终处理 |
| --- | --- |
| `SessionOrchestrator.java` | 保留精简入口：接收请求、管理租约和超时、调用图、管理 SSE |
| `SessionProcessingService.java` | 保留短事务、会话归属及过期写入防护 |
| `SessionStateMachine.java` | 将必要约束收敛为 `SessionStatusPolicy.java`，去除细粒度流程表 |
| `SessionTransitionLog.java` | 保留持久化及审计职责，改用新的状态约束 |
| `SessionContext.java`、`SessionContextStore.java` | 旧会话兼容结束、引用清零后删除 |
| `RepairExecutionRunner.java` | 职责迁移完成后删除 |
| `RepairExecutor.java` | 受控设备写入口完成接替后删除 |
| `CapabilityDispatcher.java` | 图已明确完成能力分发后删除 |
| `AssistantCapabilityHandler.java` | 移除等待续接协议，仅在仍有实际复用价值时保留接口 |
| `CapabilityRequest.java`、`CapabilityResult.java` | 去除旧编排专用的 WAIT/REROUTE 语义，保留必要数据契约 |

`SessionStatusPolicy` 约束数据库中的业务状态，例如终态不能被迟到结果覆盖。下一步执行哪个节点统一由图决定，不能保留两套完整流程定义。

同步更新章程目录说明、各 feature 的验收记录和 `specs/README.md`。历史任务完成记录、旧验证记录及已标记归档的 checklist 保留其历史含义，不改写为本次迁移的完成证据。

### 8.3 最终主要结构

```text
com/chh/autosense/
├── controller/
│   └── SessionController.java
├── config/
│   └── WorkflowConfig.java
├── core/
│   ├── workflow/                      公共图运行与任务入口
│   │   ├── AssistantGraph.java
│   │   ├── AssistantSteps.java
│   │   ├── AssistantState.java
│   │   ├── WorkflowRuntime.java
│   │   └── WaitingReplyResolver.java
│   ├── session/                       请求生命周期及会话事务
│   │   ├── SessionOrchestrator.java
│   │   ├── SessionProcessingService.java
│   │   ├── SessionStatusPolicy.java
│   │   ├── SessionTransitionLog.java
│   │   ├── SessionLeaseService.java
│   │   ├── DeviceLockService.java
│   │   ├── DeviceLocator.java
│   │   └── memory/
│   ├── routing/                       意图理解、校验、知识能力接入
│   ├── analysis/                      诊断图与诊断服务
│   ├── device/
│   │   ├── query/                     只读查询流程
│   │   ├── control/                   审批与设备控制流程
│   │   ├── client/
│   │   ├── adapter/
│   │   ├── rule/
│   │   └── spi/
│   ├── security/
│   └── aftersales/
├── ai/
│   ├── factory/                       AI Service 工厂
│   ├── model/
│   ├── rag/                           现有知识库及检索实现
│   └── tools/
├── service/
├── domain/
│   ├── entity/
│   ├── dto/
│   ├── enums/
│   ├── message/
│   └── vo/
├── mapper/
├── constant/
└── utils/
```

## 9. 分阶段验证与交付

| 阶段 | 主要验证 | 可复用的现有测试入口 |
| --- | --- | --- |
| 第一步 | 依赖、序列化、隔离、持久化恢复 | 增加专门的图运行及恢复测试 |
| 第二步 | 原有路由、知识咨询及流式契约 | `AssistantRoutingContractTest`、`AssistantRoutingIT`、`KnowledgeConversationIT`、知识相关单元和契约测试 |
| 第三步 | 跨请求等待恢复、重复回复、过期及切换任务 | `SessionApiContractTest`、`SessionProcessingIT`、`SessionLifecycleIT`，补充恢复场景 |
| 第四步 | 审批有效性、并发执行资格、重复命令、未知结果 | `RepairExecutorTest`、设备锁测试，补充操作审批和重启恢复测试 |
| 第五步 | 只读查询、诊断追问、控制移交及复检 | `AutoRepairFlowIT`、`ManualGuideIT`，按最终业务契约调整并补充 |
| 第六步 | 全部能力及旧数据兼容、最终入口切换 | 整体后端验证、前端构建及人工业务流程检查 |

测试应验证业务行为，不仅验证图节点是否存在或调用顺序是否与实现相同。现有测试名称表示复用入口，不代表无需修改即可覆盖新流程。

后端按阶段执行针对性测试；最终执行 `mvnw.cmd verify`，并在具备 Docker 和模拟器环境时执行 `mvnw.cmd verify -Pit`。前端变更执行 `npm run build`，人工检查对话恢复、审批卡片、重复确认和会话切换。

每步提交应说明：业务行为变化、文件职责调整、数据库或接口兼容变化、实际验证结果及剩余限制。未完成的阶段保持计划状态，不提前删除其仍依赖的旧文件。

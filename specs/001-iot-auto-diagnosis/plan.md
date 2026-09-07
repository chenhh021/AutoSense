# Implementation Plan: IoT 设备自动诊断与修复

> **2026-09-07 规格拆分说明**：下文保留拆分前的设计/契约/验证指南作为参考，尚未按五个 feature 的新边界重新规划；其中工作区状态、需求编号和流程描述均属于编制时上下文。当前需求以[feature 总览](../README.md)及各自 spec 为准；原需求可查[拆分前规格](history/20260907-before-feature-split.md)。复用适用部分时须核对新职责，本文不代表新 feature 已实现或验收通过。

**Branch**: `001-iot-auto-diagnosis`（活动功能标识） | **Date**: 2026-09-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-iot-auto-diagnosis/spec.md`

**Workspace**: 实际 Git 分支为 `master`；`.specify/feature.json` 指向本功能目录，
setup-plan 返回上述功能标识。本次不切换分支。
**Constitution**: [2.0.0](../../.specify/memory/constitution.md)。

## Summary

本功能提供对话式诊断修复 API：登录用户按 SN 绑定模拟器已有设备，以自然语言发起
问题；意图分类将输入路由至常识回答、设备诊断、售后或澄清。受支持灯泡经实时诊断、
配置化规则及知识检索生成方案，所有改变设备状态的操作经用户确认后执行，失败即停并复检。
对话和关键过程长期保存，最近 20 条历史对后续模型调用可见，POST 通过 SSE 返回处理过程。

本次重新规划现有规格的实施边界，使设计符合当前目录和章程；不增加新产品功能，
不将仓库已有前端误写成不存在，也不扩大规格要求的后端 API 交付范围。
保持 SN 全局唯一、稳定元数据、角色与归属隔离、外部设备服务和既有 JSON/SSE 契约。

当前代码已有账号、SN 绑定、规则诊断及 mock 端到端路径；仍需落实 AI 工厂/模型归位、
展示 VO 迁移、Controller 持久化访问收敛、有效确认与租约、可靠令牌撤销、
对话记忆和多轮快照隔离。以下目标不能视为已实现或已通过验收。

## Technical Context

**Language/Version**: Java 21，无预览特性；仓库已有 Vue/TypeScript 前端作为 API 调用方。

**Primary Dependencies**: Spring Boot 3.5.3（MVC、Validation、Security、Data Redis）、
MyBatis-Flex 1.10.9、LangChain4j 1.0.1、community Redis 1.0.1-beta6、
Spring RestClient、SseEmitter。沿用现有 pom，不新增 starter、消息队列或响应式框架。
Redis 向量依赖虽存在，本期不启用向量检索。

**Storage**: MySQL 8 为用户、设备、会话、消息、快照、日志、知识的权威来源；
Redis 保存可重建上下文/20 条历史缓存、设备与会话租约、登录 token 及其成员索引。
所有缓存键有 `autosense:` 命名空间及明确 TTL；无向量免 TTL 例外。

**Testing**: JUnit 5、Spring Boot Test、MockMvc、Mockito、现有 HTTP 协议替身；
Testcontainers MySQL/Redis + 外部真实 deviceSimulator 用于 `-Pit`。
真实 LangChain4j 客户端协议通过本地替身确定性验收，外部真实模型另做受控验证。
已有 IT 固定 mock LLM，不能覆盖真实模型、记忆和全部租约边界。

**Target Platform**: JVM 21 服务，支持 Windows 本地验证和 Linux 部署；
Docker 用于开发/测试依赖。模拟器是外部项目，地址从配置注入。

**Project Type**: 同仓库前后端分离项目中的单模块 Spring Boot web-service。
本功能规划后端 API；`frontend/` 已存在，不新增页面或更改其构建方式。

**Performance Goals**: SC-001 首诊断不超过 120 秒，含排队、模型和设备查询。
目标单次模型超时 30 秒、模型自动重试 0，并受剩余总预算约束；这些配置与截止处理待实施。
设备服务读取/连接超时沿用可配置的 10 秒上限。50 并发会话仅为原计划的容量验证假设，
不是规格新增验收条件；SC-002 本期仅场景验证，不宣称达到 70% 统计成功率。

**Constraints**: Spring 构造器注入；所有外部连接/模型参数通过 yaml 与属性类注入；
平台只能发现并绑定设备，不调用模拟器创建设备。当前应用模拟器默认 8080、
Compose/IT 默认 8081，验证必须显式设置同一 `DEVICE_SERVICE_BASE_URL`。
LLM 默认 real，确定性验证显式使用 mock；真实 token 优先，生产关闭 dev 身份入口。
任何设备写入都需当前轮次的有效确认；状态/租约失效或模型输出异常不能触发新操作。

**Scale/Scope**: US1/US3 为 P1，US2 为 P2；覆盖 FR-001–FR-027 和 SC-001–SC-009。
可绑定上游可发现的任意类型/型号，本期诊断仅 LITE:LA001/LB001；
路由器/空调、型号专用 RAG、真实地图查询、向量检索和新 UI 不在本轮实施范围。

## Constitution Check

*GATE: Phase 0 前识别既有偏离并确定纠正方向；Phase 1 后复核目标设计。
“通过”表示设计满足约束，不表示代码或运行验收通过。*

| 章程约束 | Phase 0 检查及处理 | Phase 1 设计复核 |
| --- | --- | --- |
| I. Java 21 | pom 已为 21，保留基线 | 通过，无预览 API |
| II. Boot 3.5.3/依赖注入 | 固定当前依赖，AI 工厂由 Spring 管理 | 通过，不手工 new 受管业务 Bean |
| III. MySQL/MyBatis-Flex/Redis TTL | 撤销旧文档向量免 TTL 例外；识别缓存及索引非原子问题 | 通过，MySQL 权威源，Redis 原子写入/TTL，未实现向量模式明确拒绝 |
| IV. LangChain4j | 现有调用符合框架选型，但工厂和结构化模型待迁移 | 通过，AiServices 与流式模型仍经 LangChain4j |
| V. 配置外部化 | 地址、凭据沿用外置；模型 360 秒旧默认不满足总预算 | 通过，模型超时/重试/请求截止及租约参数经属性绑定 |
| 项目结构/分层 | UserController 直接使用 Mapper、5 个展示 View 在 DTO 包 | 通过，迁移清单明确，Controller → service/core → mapper |
| DTO/VO/异常 | 保留 JSON 与模型职责，错误处理区分 HTTP/SSE | 通过，DTO/VO/AI 输出分开，common/exception 分工明确 |
| 安全/权限 | 确认时锁未复核、token 索引可先过期 | 通过，原子所有权校验、一次确认、用户状态与索引成员校验 |
| 测试/可验证性 | 旧任务和 09-04 报告不证明新设计已覆盖 | 通过，quickstart 明确现有命令与待补专项 |
| 最小实现/工作流 | 不新增 UI、设备类型或向量基础设施 | 通过，本命令止于 Phase 1，不修改 spec/tasks/实现 |

所有已发现偏离均有目标修复方案；无需要额外批准的设计例外，无未解决技术澄清项。
当前实现与目标的差距由下方迁移清单交给后续任务生成，不能在实施前关闭其验收门禁。

## Project Structure

### Documentation (this feature)

```text
specs/001-iot-auto-diagnosis/
├── spec.md                    # 现行需求，本命令保留
├── plan.md                    # 本次实施设计
├── research.md                # Phase 0：R1–R31 决策及依据
├── data-model.md              # Phase 1：实体、模型归属、状态与缓存
├── contracts/
│   ├── diagnosis-api.md       # 设备/会话 JSON 与 SSE 契约
│   └── user-api.md            # 用户、令牌与管理接口
├── quickstart.md              # 可执行验证指南与验收边界
├── tasks.md                   # 既有任务；需后续按本计划重新生成
├── checklists/                # 既有规格检查清单
└── validation/                # 既有历史执行证据，本次不覆盖
```

### Source Code (repository root)

```text
src/main/java/com/chh/autosense/
├── AutoSenseApplication.java
├── controller/                # HTTP/API 入口
├── service/
│   ├── user/                  # 用户/令牌服务
│   └── knowledge/             # MySQL 知识检索
├── core/
│   ├── aftersales/            # 网点客户端及引导
│   ├── analysis/              # 分析/诊断能力接口
│   ├── device/
│   │   ├── adapter/           # 设备适配器实现
│   │   ├── client/            # 外部模拟器协议
│   │   ├── rule/              # 配置化规则
│   │   └── spi/               # 设备扩展契约
│   ├── repair/                # 修复动作执行
│   ├── routing/               # 意图路由与直接回答
│   ├── security/              # 认证/授权及安全模块配置
│   └── session/
│       ├── memory/            # 用户历史快照与 Redis 热缓存
│       └── statemachine/      # 确定性迁移
├── mapper/                    # MyBatis-Flex 数据访问
├── domain/
│   ├── dto/                   # 请求/传输响应
│   ├── entity/                # 持久化实体
│   ├── enums/                 # 业务枚举
│   ├── message/               # SSE 事件与流封装
│   └── vo/                    # 5 个展示模型待迁入
├── ai/
│   ├── factory/               # AI Service 创建工厂待实现
│   ├── model/                 # 结构化 AI 输出待迁入
│   │   └── enums/             # AI 分类枚举待迁入
│   └── tools/                 # 已有 BaseTool，不新增自由设备写工具
├── common/                    # 公共错误响应/全局处理
├── exception/                 # 自定义异常与错误码
├── config/                    # 配置属性与通用装配
├── constant/                  # 全局角色/权限等常量
└── utils/                     # 通用工具，按需使用

src/main/resources/            # application.yaml、schema.sql、data.sql
src/test/java/com/chh/autosense/
├── contract/
├── device/                    # 设备服务/HTTP 客户端单测
├── integration/               # 真实模拟器端到端测试
└── unit/

frontend/                      # 既有 Vue 客户端
scripts/migration/             # 已有 SN 迁移脚本
documents/                     # 模拟器协议与项目说明
docker-compose.yaml            # MySQL、Redis、外部模拟器运行配置
```

**Structure Decision**: 保留现有单模块 Spring Boot 工程，按层与业务模块共同组织。
Controller 可调用 service 或 core 的业务入口；Mapper 专用于数据访问。
AI 工厂组装模型服务，核心编排验证其结果并决定动作，工厂不承担业务状态机。
只将符合职责的类迁入对应目录，不为填满目录新建空壳类。

## Phase 0: Research

研究结论见 [research.md](./research.md)。主要决策：

- R3/R27/R28：按新章程归位 AI 模型、工厂与 VO，保留 API 字段和已用依赖。
- R4：MySQL 检索为本期完整可交付路径，向量开关不得静默伪成功。
- R6/R19：原子租约与索引成员校验解决确认和身份失效，不新增认证表字段。
- R15/R17/R29：统一历史写入，以消息/轮次为界隔离 AI 上下文与诊断结论。
- R18/R30：SSE 流内错误与 HTTP 错误分开，补足预算与实际环境验证边界。

未知项已通过本地源码、既有协议和固定版本官方资料解决。

## Phase 1: Design & Contracts

### 模型与接口

[data-model.md](./data-model.md)定义现有表、非持久化模型、Redis 类型/TTL、状态迁移和数据一致性。
[诊断契约](./contracts/diagnosis-api.md)和[用户契约](./contracts/user-api.md)维持既有端点：

- 请求 DTO 和传输响应仍在 domain/dto，展示 VO 调整 Java 包，JSON 字段不变。
- 设备响应保留 online:boolean；false 表示未确认在线，不等同确定离线，不保存上游运行态。
- 会话查询响应是 sessionId/status/reply/awaitingInput/conclusion，不新增旧文档虚构的 device 字段。
- 会话 POST 已建立 SSE 后业务失败通过 error 事件表达；认证/请求校验及 JSON 查询错误按 HTTP 返回。
- 终态续聊按规格直接 ANALYZING 并先重分类；常识和不确定意图仍不调用设备。

### 迁移与待实施清单

以下是设计责任范围，任务编号及勾选状态由后续 `speckit-tasks` 生成。

| 范围 | 当前差距 | 目标及验证 |
| --- | --- | --- |
| 目录/分层 | UserController.me 直接 Mapper；DeviceController 编排在线/支持性 | 用户查询归 UserService；设备投影归 core/device 业务服务，契约保持 |
| AI 接入 | 工厂/模型目录未接入，配置类集中低层调用/解析 | 工厂创建分类、分析、诊断代理；AI 输出严格校验，流式说明与内部结构化调用隔离 |
| AI 类型 | ProblemAnalysis/DiagnosisConclusion 位于 core/analysis，Intent 位于 domain/enums | 前两者移 ai/model，Intent 移 ai/model/enums；数据库/JSON 枚举值保持 |
| VO 类型 | 5 个展示 record 位于 domain/dto | UserView/AdminUserPageView/DeviceView/ChatMessageView/SessionListItemView 移 domain/vo，引用同步 |
| 全局常量 | user/admin 等跨模块字面量分散 | 抽取至 constant，保持现有角色值，不引入新权限等级协议 |
| 状态/审计 | 部分失败边缺失，异常直接 setStatus | 补状态表，状态与审计短事务一致提交；续聊入口、失败复检和超时均有确定边 |
| 设备确认 | 旧锁可过期、续租/释放有竞态、双确认可重入 | owner 原子校验、每会话串行、状态条件更新一次消费；过期重探测并重新确认 |
| token 撤销 | 单 token 续期而索引不续期 | 原子 token/Set 生命周期；用户启用检查；登录/禁用/启用短行锁串行，旧 token 不复活 |
| 对话记忆 | 热缓存陈旧、当前输入可能重复、内部提示可污染 | 编排唯一写入；之前 20 条快照+一次当前输入，Redis 原子 JSON 缓存与冷恢复 |
| 多轮结论 | 查询未按 round 限定，历史仅摘要可能丢失步骤/网点详情 | 完整用户可见结论先存 ASSISTANT 消息，再清当前投影；PRE/POST 按轮确定排序 |
| 知识检索 | 向量开关 true 实际仍回退 | 明确 MySQL 路径、空查询/未命中/knowledgeRef 优先，true 启动报配置错误 |
| 时限/错误 | 默认模型超时 360 秒且重试；流式晚回调未隔离 | 属性化 30 秒/0 重试/120 秒总截止，逾期不能继续推进设备动作 |
| 旧数据库 | 已有 SN 脚本仅适用于已知旧结构 | 验证既有脚本与元数据回填前提；未知旧数据先报告，不自动覆盖 |

数据库表名和公开接口不因目录迁移而变化。SN 脚本复核只针对已有迁移路径，
本命令不运行迁移，不凭空新增 DDL。缓存 List → String(JSON) 需要受控失效或版本化，
保留 MySQL 历史；待确认上下文格式不兼容时重新探测并要求确认。
会话消息租约覆盖异步流式回调的完整生命周期，结束或明确取消后才释放；
人工步骤和售后联系方式随完整答复进入长期历史，不能在新轮清理时丢失。

### 验证与交付边界

验证步骤见 [quickstart.md](./quickstart.md)，覆盖账号、SN、诊断确认、不可达、
人工/售后、SSE 与跨用户隔离；待补专项覆盖真实模型协议、20 条历史、
过期/并发确认、索引丢失后的禁用、按轮快照及总预算。
后续实施复用现有测试框架，验证外部行为与不发生未授权写入。

本次只检查文档和设计一致性，不运行 Maven、Docker、模型调用或端到端场景。
现有 `tasks.md` 的完成勾选及 `validation/` 的历史数据不代表上述迁移已完成。
Phase 1 复核结果：目标设计通过章程门禁，实施和运行验证仍待后续执行。

## Complexity Tracking

目标设计没有需要豁免的章程违规。设备/会话两种租约分别保护设备操作和对话消息，
用户短事务行锁用于保证既有禁用语义，均对应现行需求而非新基础设施。
现存偏离仅按上表迁移，不作为长期例外保留。

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| 无新增设计例外 | 不适用 | 不适用 |

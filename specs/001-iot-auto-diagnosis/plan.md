# Implementation Plan: IoT 设备自动诊断与修复

**Branch**: `001-iot-auto-diagnosis` | **Date**: 2026-08-22(2026-08-27、2026-08-29、2026-09-04 刷新) | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-iot-auto-diagnosis/spec.md`

## Summary

提供一个 API-only 的对话式设备服务：用户输入经**意图路由**(FR-019）分流为——
(1) 常识性问题直接回答（型号特异性问题本期并入此路由，RAG 后续增强）;
(2) 可自动修复/操作的问题进入诊断修复闭环：定位设备（本平台绑定表 + 模拟器引用)→
经 deviceSimulator 采集设备 state → **配置化故障规则**判定（可自动修复/不可修复/无异常）
→ 全部改变设备状态的操作经用户确认（FR-008 扩大）后下发模拟器命令 → 复检输出结论；
不可修复时输出人工步骤或附近售后网点；(4) 独立网点查询（本期固定 mock 数据，后续
地图工具）。对话以 session id 长期保留，支持历史列表/继续对话，LLM 端记忆窗口为最近
20 条消息（FR-018)；终态对话可在同一线程发起新一轮诊断。核心编排仍为**确定性状态机**,
LLM 仅做意图分类、语义分析与诊断推理，设备写操作只能经状态机调用适配器白名单。

**2026-08-27 重大变更**：设备交互由项目内硬编码 mock 改为 HTTP 对接外部
**deviceSimulator** 服务（base-url 经 yml 配置）；本期诊断范围收敛为智能灯泡
（LITE:LA001/LB001)，路由器/空调暂停；引入平台设备绑定与长期对话保留。

**2026-08-29 增量（US3 用户管理）**：新增用户账号体系——注册（账号/密码/确认密码,
账号 4~32 位字母数字下划线唯一、密码 8~64 位须含字母+数字、BCrypt 加密存储)、
登录颁发随机令牌（Redis 存储 + TTL,`Authorization: Bearer <token>` 鉴权)、
获取当前登录用户（脱敏）、注销（删令牌立即失效）；角色分 user/admin，管理员最小
管理集=用户列表（分页/搜索）+ 禁用/启用（isDelete，禁用即令牌失效），本期不提供
改角色/重置密码；每用户仅见本人设备与对话（沿用 FR-015 归属过滤）。开发态
`user-{id}` 令牌保留为 `AUTH_DEV_MODE` 显式开关，真实令牌优先，生产必须关闭。

**2026-09-04 增量（FR-020 设备添加修订）**：`POST /api/v1/devices` 改为只接收
SN 与用户显示名称；平台不再代建设备，而是调用 deviceSimulator
`GET /api/v1/devices/by-sn/{sn}` 发现运行中的既有设备，再写入用户绑定。SN 全局唯一，
同用户或跨用户重复均返回冲突；不支持诊断的类型/型号仍允许加入列表，支持性在诊断
入口动态判断。平台只保存模拟器 ID、SN、类型/型号编码与 ID、模拟器原始名称及用户
显示名称，不保存 `state`、`running_status` 或模拟器时间戳。

## Technical Context

**Language/Version**: Java 21（章程原则 I)

**Primary Dependencies**: Spring Boot **3.5.3**（章程原则 II)、Spring Web、Spring Validation、
LangChain4j（意图分类/语义分析/诊断推理/ChatMemory/**StreamingChatModel 流式输出**,
章程原则 IV)、MyBatis-Flex（持久层，章程原则 III)、Spring Data Redis（会话态/锁/
对话记忆存储/向量索引）、Spring Security（资源服务端鉴权，FR-015)、
Spring RestClient（模拟器/售后 HTTP 客户端）、Spring MVC SseEmitter(SSE 推送,FR-021)、
Spring Security Crypto(**BCrypt** 密码散列,FR-022;2026-08-29 新增)

**Storage**: MySQL 8（用户、设备绑定、会话、消息、快照、知识条目，**长期保留**——2026-08-22
撤销 90 天清理；设备绑定以 SN 数据库唯一约束保证全局唯一，并分离平台显示名称与
模拟器原始名称）+ Redis（进行中会话状态、设备互斥锁、LLM 对话记忆窗口、向量索引、
**登录令牌** `autosense:token:{token}`,TTL 7 天滚动——2026-08-29 新增；
所有键带 TTL 与 `autosense:` 前缀）

**Testing**: JUnit 5 + Spring Boot Test + Testcontainers(MySQL/Redis)+
**真实 deviceSimulator 实例**（集成测试经 docker-compose 启动；先在模拟器准备设备，
再由平台按 SN 绑定；设备路径不再用 WireMock/Mockito 桩覆盖端到端行为）+
WireMock(LLM/售后外部服务)+ Mockito（单元层）

**Target Platform**: Linux 服务器（JVM 21),Docker 部署

**Project Type**: web-service（纯 REST API，无前端）

**Performance Goals**: 首诊断结论 < 2 分钟（SC-001)；单实例支撑 50 并发会话（家庭场景量级）；
设备添加的外部查询受现有可配置连接/读取超时约束（默认 10 秒），超时不落库

**Constraints**: 所有外部服务配置（LLM、售后、deviceSimulator、地图工具预留）经
`application.yaml` + `@ConfigurationProperties` 注入（章程原则 V)；模拟器为本项目外
已有服务，不开发模拟器本体（2026-08-27 澄清）；修复/操作动作不可由 LLM 自由触发，
只能经状态机调用适配器白名单；**所有改变设备状态的操作必须用户确认**(FR-008,2026-08-22
扩大）,state 读取类探测免确认；同设备会话互斥（Redis 分布式锁）；意图分类不确定时
必须追问澄清，不得触碰设备（FR-019);**会话接口以 SSE 流式推送**(POST 即流，
等待/终态关流，断线不补发，FR-021);**注册/登录端点匿名放行，其余 `/api/**` 需
Bearer 令牌；管理端点仅 admin 角色，否则 403**(FR-023/027,2026-08-29 新增);
注册接口不得接受角色字段（防提权，FR-022)

设备添加的专用约束：本地校验 → 本地重复预检 → 无数据库事务的模拟器只读查询 →
单条短事务写绑定；数据库唯一索引是并发最终裁决。客户端不得修改 SN 大小写或字符；
`exists:false`、上游 400、上游 5xx/超时必须分别映射，任何未确认 `exists:true` 且稳定字段
完整的响应都不得落库。添加时不得按诊断支持列表拒绝，也不得调用模拟器创建设备。

**Scale/Scope**: 设备列表可绑定 deviceSimulator 可发现的任意类型/型号；本期仅 1 类设备
具备诊断能力（智能灯泡 LITE:LA001 单色/LB001 彩光）。27 条 FR（含 2026-08-29 用户管理
FR-022~FR-027);单模块单体服务；路由器/空调待经 FR-012 机制启用诊断

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 章程原则 | 门禁 | 结果 |
| --- | --- | --- |
| I. Java 21 基线 | pom `java.version=21`，无预览特性 | ✅ 通过 |
| II. Spring Boot 3.5.3 | pom parent 已为 3.5.3(T001 已降级) | ✅ 通过 |
| III. 数据访问纪律 | MySQL + MyBatis-Flex;Redis 全部键带 TTL 与前缀（RAG 索引为例外，已注明）；登录令牌存 Redis 带 TTL(R19) | ✅ 通过 |
| IV. AI 集成规范 | 全部模型调用经 LangChain4j（含 ChatMemory)，参数经配置注入 | ✅ 通过 |
| V. 配置外部化 | LLM/售后/deviceSimulator/Redis/MySQL/故障规则注册表/AUTH_DEV_MODE 开关均走 yaml + `@ConfigurationProperties`，SN 查询复用可配置 device-service base-url/timeout，凭据用环境变量占位 | ✅ 通过 |

无需要论证的违规项。

**2026-08-29 用户管理增量复核**：新增 Spring Security Crypto(BCrypt）为 Spring
官方生态依赖，不违反原则 II；密码散列不入日志/不出接口（FR-022）属应用纪律，由
契约测试与代码评审保证；令牌存 Redis 带 TTL 符合原则 III；初始管理员经 data.sql
种子（散列预生成），无明文凭据入库，符合原则 V。

**2026-09-04 设备添加增量复核（Phase 1 后）**：沿用既有 Spring RestClient 与
MyBatis-Flex，无新增依赖；SN 唯一性由 MySQL 约束保证；设备服务地址和超时继续由
`DeviceServiceProperties` 注入。外部查询不跨数据库事务，设计不引入分布式事务或
额外缓存，五项章程门禁仍全部通过。

## Project Structure

### Documentation (this feature)

```text
specs/001-iot-auto-diagnosis/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出(2026-09-04 刷新:按 SN 发现/唯一性/错误映射/测试)
├── data-model.md        # Phase 1 输出(2026-09-04 刷新:稳定设备元数据、移除运行态持久化)
├── quickstart.md        # Phase 1 输出(2026-09-04 刷新:先模拟器建机、再按 SN 绑定)
├── contracts/           # Phase 1 输出(2026-09-04 刷新设备添加契约)
│   ├── diagnosis-api.md
│   └── user-api.md      # 用户管理接口(2026-08-29 新增,US3)
└── tasks.md             # /speckit-tasks 输出(本命令不生成)
```

### Source Code (repository root)

```text
src/main/java/com/chh/autosense/
├── AutoSenseApplication.java
├── config/                  # @ConfigurationProperties(LLM/Redis/售后/模拟器/设备类型注册表)
├── api/                     # REST Controller + DTO + 全局异常处理
│   └── dto/                 #   含会话列表、按 SN 添加设备端点(FR-018/FR-020)
├── security/                # 登录态校验(真实令牌优先+dev 开关,R21)、角色/归属鉴权(FR-015/027)
├── user/                    # 用户账号体系(2026-08-29 新增,US3):注册/登录/me/注销/管理
├── domain/                  # 实体、枚举(会话状态机含终态→ANALYZING 回路、意图路由枚举)
│   ├── model/
│   └── enums/
├── repository/              # MyBatis-Flex Mapper + Service
├── session/                 # 会话编排:状态机、Redis 会话态、互斥锁、对话记忆窗口(FR-018)
│   └── statemachine/
├── routing/                 # 意图分类与四路路由(FR-019):常识直答/诊断修复/网点查询
├── analysis/                # LangChain4j 语义分析(设备类型/问题表现提取,FR-002)
├── device/                  # DeviceAdapter SPI + 智能灯泡适配器 + 注册表(FR-012)
│   ├── spi/
│   ├── client/              # DeviceServiceClient + DeviceSimulatorClient(含按 SN 查询)
│   ├── adapter/             # SmartBulbAdapter(LA001/LB001)
│   └── rule/                # 状态→故障判定规则引擎(yml 配置化,2026-08-27)
├── knowledge/               # RAG:修复知识检索(FR-007);型号 RAG 后续接入(FR-019 路由 3)
├── repair/                  # 修复执行器:白名单动作、全操作确认门、失败即停(FR-008/017)
├── aftersales/              # 售后网点查询(本期固定 mock 数据,地图工具预留)
└── retention/               # (已撤销 90 天清理;保留包位或移除,FR-013 2026-08-22 修订)

src/main/resources/
└── application.yaml         # 全部外部服务配置入口(含模拟器 base-url、故障规则注册表)

src/test/java/com/chh/autosense/
├── contract/                # API 契约测试
├── integration/             # 连真实 deviceSimulator 的端到端场景(docker-compose)
└── unit/                    # 状态机/规则引擎/适配器/分析器单测

docker-compose.yaml          # MySQL 8 + Redis(含 RediSearch)+ deviceSimulator
```

**Structure Decision**: 单模块 Spring Boot 服务。按业务子域分包；新增 `routing/` 承载
意图路由（FR-019),`device/rule/` 承载配置化故障判定；设备类型扩展点集中在
`device/spi` + yaml 注册表，满足 FR-012/SC-006。模拟器客户端经 DeviceServiceClient
接口隔离，未来接入真实设备服务仅需新增实现 Bean。

设备添加改造保持上述目录不变：`DeviceController`/请求与响应 DTO 定义公开契约，
`DeviceRegistryService` 编排重复检查、远程发现和绑定写入，`DeviceSimulatorClient`
只负责上游协议与异常分类，`DeviceMapper`/MySQL 唯一约束负责持久化并发裁决。

## Complexity Tracking

> 无章程违规需要论证，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

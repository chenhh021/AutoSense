# 提交变更记录:002 公共基础与统一意图路由 + AiServiceFactory 拆分

**日期**: 2026-09-08
**范围**: 155 个文件,+9583/-2206
**关联**: [specs/002-assistant-foundation](../../specs/002-assistant-foundation/spec.md)(56 项任务全部完成,验收证据见 [validation.md](../../specs/002-assistant-foundation/validation.md))

## 1. AI 服务工厂拆分(本次重构)

原聚合 `AiServiceFactory` 按用途拆分为四个独立工厂,下游经工厂方法**按次新建代理**,不共享单例:

| 工厂(ai/factory/) | 方法 | 注入模型 |
| --- | --- | --- |
| IntentRouterServiceFactory | intentRouterService() | ChatModel |
| ProblemAnalysisServiceFactory | problemAnalysisService() | ChatModel |
| DiagnosisReasonerServiceFactory | diagnosisReasonerService() | ChatModel |
| DirectAnswerServiceFactory | directAnswerService() | StreamingChatModel |

- 四个服务接口提升为 `com.chh.autosense.ai` 包顶层(IntentRouterService / ProblemAnalysisService / DiagnosisReasonerService / DirectAnswerService)
- 通用装配校验抽至 `utils/AiServiceValidator`:资源存在性/BOM/UTF-8/非空、禁止内联 value、变量集合与 @V 精确匹配、合成样本渲染;失败即中止装配,零模型请求,不回落 mock/内联模板
- 工厂 @Resource 注入模型、@PostConstruct 校验,仅 autosense.llm.mode=real 装配
- LangChain4jConfig 只保留外部化同步/流式模型 Bean

## 2. 统一意图路由与会话编排(002 主体)

- 六份 prompt 资源外置 `src/main/resources/prompt/`(四个系统模板零变量 + 两个共享用户包装),PromptInputEncoder 转义用户文本防模板再解释
- 能力分发:CapabilityDispatcher/AssistantCapabilityHandler,缺失处理器返回 CAPABILITY_NOT_AVAILABLE,绝不回落设备写;AI 输出仅为候选数据,不构成设备写授权
- 会话处理:SessionProcessingService + SessionLeaseService 租约守卫(30s 租约/10s 续期/DB 时间截止补偿 REQUEST_TIMEOUT)、迟到回调零污染
- SessionContext v2(autosense:session:v2:{id}),旧结构/错误版本一律 CONTEXT_EXPIRED
- ConversationHistoryService 固定 20 条窗口(移除 ChatMemoryProperties 与 ChatMemoryFactory/RedisChatMemoryStore)
- SSE 事件与错误语义:补查、SESSION_BUSY、FAILED_REQUEST、CONTEXT_EXPIRED

## 3. 账号与令牌(US2)

- AuthTokenService Lua 原子脚本:签发/校验续期/注销同步维护 token 与索引,7 天统一滚动 TTL;孤立 token 无效不补回;全量撤销先删索引,异常传播回滚
- UserService:登录/禁用/启用同用户 SELECT ... FOR UPDATE 行锁串行(含禁用行);BCrypt 前 UTF-8 ≤72 字节预校验;启用先撤销旧索引再恢复
- UserTokenResolver:令牌+索引+当前启用账号三重校验,角色以数据库为准,Redis 不可用拒绝
- UserRoleConstants 收敛角色引用;VO 归位 domain/vo

## 4. 设备兼容

- DeviceLockService:Lua 原子 owner 续期/释放(RENEW_IF_OWNER/RELEASE_IF_OWNER),10 分钟 TTL
- 设备链路英文 operation/result/elapsedMs 日志,列表整体汇总不打 SN

## 5. 日志合规

- MDC 上下文(requestId/userId/sessionId/messageId/round/deviceId)
- com.chh.autosense.mapper 固定 INFO,杜绝 MyBatis SQL 参数原文(用户输入/口令散列)外泄
- log4j2-spring 单一提供者,LangChain4j/HTTP wire 日志关闭

## 6. 测试

- 默认 `mvnw.cmd verify`:125 项通过(含 AiServiceAssemblyTest 四路真实代理 WireMock 验收、校验失败零模型请求)
- 显式 -Pit IT 集合 33 项:SessionProcessingIT、AssistantRoutingIT、TokenRevocationIT、DeviceLockIT、ConversationHistoryIT、SessionLifecycleIT、AssistantLoggingIT
- 未运行:模拟器相关 IT(外部 devicesimulator 镜像不可用)、真实模型小样本(无凭据)

## 7. 文档

- 002 全套 spec/plan/tasks/contracts(assistant-api、logging、prompt、routing、user-device)/validation
- 003/004/005 特性 spec 与 specs/README;迁移脚本 scripts/migration/20260907-assistant-processing.sql

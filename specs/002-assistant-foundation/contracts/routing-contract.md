# Contract: AI 路由与公共能力接入

**Date**: 2026-09-07
**Feature**: [002 spec](../spec.md)
**Status**: 目标内部契约，尚未实施。此文件不新增公开路由接口，也不把AI输出暴露为公共响应。

## 1. AI Service 输入与创建

由 ai/factory/AiServiceFactory 创建四类代理：意图路由、问题分析、诊断推理、流式回答。同步和流式代理分开装配；LangChain4jConfig 提供外部化模型配置与Bean接线。现有业务接口可保留作为适配边界，实际调用必须经过代理。

路由代理输入为：

| 输入 | 来源 / 约束 |
| --- | --- |
| text | 本次被接纳的用户输入，按prompt契约编码为JSON字符串，只出现一次 |
| history | 服务端校验会话后提供的最近20条可见历史JSON数组，保留role/content；字符串字段采用专用转义 |
| system instructions | /prompt/intent-router.txt中的固定规则；资源不含运行时变量，历史/检索/设备名称均不得提升为系统指令 |

四代理的方法使用 @SystemMessage(fromResource) 和 @UserMessage(fromResource)，固定规则与输入包装均由src/main/resources/prompt/资源加载；六文件及精确绑定见[prompt契约](prompt-contract.md)。路由使用conversation-input.txt绑定history/text，全部参数非null；utils/PromptInputEncoder防止固定1.0.1再次替换数据中的字面模板标记，不修改数据库原文。

代理使用显式 @V 模板参数，不挂ChatMemory或@MemoryId。sessionId/userId/messageId只由外层持有以校验与追溯，不由模型生成可信身份。

工厂不调用 tools/toolProvider，不自动扫描注入BaseTool。002 无需模型执行任何设备工具；后续只读工具接入也须由对应feature明确设计权限。

## 2. RoutingDecision

RoutingDecision 使用不可变 record，ProblemAnalysis/DiagnosisConclusion 保留现有 record 并迁入 ai/model。解析依靠固定版本真实 AI Service，不能以手写解析旁路替代；字段名与候选语义保持。字段均属于AI候选输出：

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| outcome | RoutingOutcome | 必填；SINGLE / CLARIFY / COMPOSITE / OUT_OF_SCOPE |
| intent | CapabilityIntent或null | SINGLE必须为KNOWLEDGE / DEVICE_QUERY / DIAGNOSIS / CONTROL；其他outcome必须为空 |
| diagnosisMode | DiagnosisMode或null | DIAGNOSIS时DEFAULT或AFTERSALES；其他意图为空 |
| targetHint | String或null | 未验证的设备线索，不作为已授权设备ID |
| clarifyQuestion | String或null | CLARIFY/COMPOSITE应有简短问题；为空时服务端使用固定澄清文案 |

明确查询：

```json
{
  "outcome": "SINGLE",
  "intent": "DEVICE_QUERY",
  "diagnosisMode": null,
  "targetHint": "客厅灯",
  "clarifyQuestion": null
}
```

复合条件任务：

```json
{
  "outcome": "COMPOSITE",
  "intent": null,
  "diagnosisMode": null,
  "targetHint": null,
  "clarifyQuestion": "你想先查询亮度，还是发起调节亮度的请求？"
}
```

售后：

```json
{
  "outcome": "SINGLE",
  "intent": "DIAGNOSIS",
  "diagnosisMode": "AFTERSALES",
  "targetHint": null,
  "clarifyQuestion": null
}
```

不接受模型给出的confirmed、userId、role、verifiedDeviceId、接口URL或执行权限。未知额外字段不参与授权和分发，类型/枚举/字段组合不合法时不得“尽量猜出”控制目标。

## 3. 路由规则与失败

| 条件 | 公共行为 | 设备访问 |
| --- | --- | --- |
| 四类明确单一意图 | 显式映射业务AssistantCapability并投递已注册处理器 | 公共路由本身零设备读写 |
| 型号知识 | KNOWLEDGE | 不因此查询本人设备 |
| 明确售后 | DIAGNOSIS + AFTERSALES | 不先探测或控制 |
| 意图不明 / 非法类型 / 矛盾结构 / 未知分类 | CLARIFYING + awaiting，使用脱敏澄清文案 | 零读写 |
| 多意图 / 条件请求 / 多设备写 | CLARIFYING，要求选择本轮事项 | 零读写 |
| 本人设备元数据列表 | 单一DEVICE_QUERY | 不误判成批量控制 |
| 范围外请求 | 固定服务范围说明，正常结束 | 零读写 |
| 模型连接/超时/认证/提供商服务失败 | FAILED_REQUEST + AI_SERVICE_UNAVAILABLE | 零读写，不mock成功 |
| SINGLE但能力未注册 | FAILED_REQUEST + CAPABILITY_NOT_AVAILABLE | 零读写，无旧DEVICE_ACTION回退 |

解析器抛出的结构错误与传输错误必须分开处理；空文本/无法形成合法输出属于结构失败。用户看不到原始模型JSON、提示词或供应商错误正文。

## 4. AssistantCapabilityHandler 内部契约

core/routing 中定义一份接口和分发器，以 domain/enums/AssistantCapability 为注册键，显式映射AI分类。接口只承担必要的能力声明和处理/续办，不按功能另造基础平台。

**输入 CapabilityRequest**（不可变 record，历史/上下文集合复制为只读快照）：

- 服务端已认证的AuthUser、sessionId、reportId、round、当前messageId。
- 当前content、可选兼容confirmRepair意向、所属等待态/能力上下文。
- 已验证能力及售后子模式、未验证targetHint。
- 固定历史边界与本次处理截止；不给处理器任意其他用户历史。

**行为**：

- 处理器通过异步完成契约返回用户可见结果、等待提示或明确失败；公共编排负责一致的最终持久化与SSE关闭。
- 流式文本经公共sink输出；内部分类和推理不进入sink。完成/异常回调最多结清一次。
- 业务状态迁移由公共状态机和追溯入口验证；处理器不能直接覆盖其他轮的会话状态。
- handler接收成功不是业务成功。生产缺失处理器明确报错，注册重复在启动时失败。
- 等待状态属于原能力：后续输入先由它判定续办、取消或失效；需要新一轮路由时，先持久化旧等待处理结果。旧confirmRepair不直接调用修复runner。
- 设备查询/诊断/控制的目标解析、当前账号有效性与设备归属在服务端再次核对，公共分发传入的线索不构成授权。
- 控制只可经005确定性入口；候选生成、消息租约和分发都不能替代005的权限/参数/确认/审计。
- 002保留旧领域代码以供复用，不为每条历史DEVICE_ACTION推断一个新处理器。

测试专用实现只记录capability/userId/sessionId/round/messageId并返回明确测试标记；它们放src/test，生产Bean扫描不可见。模拟模型模式也不能自动注册这些测试业务处理器。

## 5. AI 配置目标

| 配置键 / 环境变量 | 现有或新增 | 目标 |
| --- | --- | --- |
| autosense.llm.mode / LLM_MODE | 现有 | real默认；只接受real/mock，未知值启动失败 |
| autosense.llm.base-url / LLM_BASE_URL | 现有 | real模式合法HTTP(S)地址，不硬编码凭据 |
| autosense.llm.api-key / LLM_API_KEY | 现有 | real非空，日志与接口不输出 |
| autosense.llm.model-name / LLM_MODEL_NAME | 现有 | real非空，实际代理请求使用配置值 |
| autosense.llm.temperature / LLM_TEMPERATURE | 现有 | 校验为有限数值；仅校验本地明确声明的提供商范围，不推断任意兼容端点的能力 |
| autosense.llm.timeout-seconds / LLM_TIMEOUT_SECONDS | 现有 | 正数，规划默认30秒，可按部署调整 |
| autosense.llm.max-retries / LLM_MAX_RETRIES | 新增 | 非负整数，默认0；流式不重放 |
| autosense.chat-memory.window / CHAT_MEMORY_WINDOW | 现有 | 本规格为20，其他值需明确拒绝不一致配置 |
| autosense.assistant.processing-timeout-seconds / ASSISTANT_PROCESSING_TIMEOUT_SECONDS | 新增 | 单消息固定总截止，默认120秒，正数且大于一次模型超时 |
| autosense.assistant.session-lease-seconds / ASSISTANT_SESSION_LEASE_SECONDS | 新增 | 默认30秒，正数；续租间隔小于租约 |
| autosense.assistant.session-renew-seconds / ASSISTANT_SESSION_RENEW_SECONDS | 新增 | 默认10秒，正数 |
| autosense.assistant.context-ttl-seconds / ASSISTANT_CONTEXT_TTL_SECONDS | 新增 | 默认1800秒，正数 |

这些默认值用于确定可观察的结束与恢复，不是新增业务SLA。模型多次调用共享单消息总截止；截止后回调不能继续写历史。SSE超时从公共配置派生并与处理截止协调，不使用独立的更短硬编码超时来伪装完成。

同步和流式模型均明确关闭原始请求/响应日志，外层仅记录英文operation、结果、耗时与关联标识；SDK回调显式恢复白名单日志上下文。日志配置、事件及脱敏见[公共日志契约](logging-contract.md)，不把MDC或模型输出当作可信身份。

真实模式工厂还须在发布代理前校验实际方法引用的六资源、UTF-8默认字符集、非空与变量绑定；AiServices.build()不能替代此检查。资源/编码/模板错误是本地配置失败，不按模型非法输出进行澄清或使用内联回退；具体启动/调用失败边界见[prompt契约](prompt-contract.md)。不新增prompt路径或远程管理配置。

配置验证不调用远程模型；模型协议连通性在运行验收中检查。提供商拒绝参数时返回明确的AI服务错误，不宣称启动时已验证所有远程能力。仅有合法配置字符串或接口声明不满足AIService重写完成要求。

## 6. 兼容与验收

- 公开接口不增加RoutingDecision字段；ProblemReport.intent只写本轮结果，历史旧字符串保持可读。
- 002对真实AIService代理的测试可用本地模型HTTP协议替身；设备路径不能因此变成项目内设备mock。
- 验证四种SINGLE、售后、型号、CLARIFY/COMPOSITE/OUT_OF_SCOPE、非法输出、服务失败、缺失处理器与测试装配隔离。
- 真实代理的实际模型名/地址/参数、输出解析与TokenStream回调均须被测试捕获；代码检查确认没有原来的四条低层直调旁路。

- AI输出record必须通过实际LangChain4j解析路径验证；Spring HTTP ObjectMapper的序列化成功不能替代SDK自身codec测试。未知字段不参与授权，缺失或矛盾值仍按本契约校验。
- 捕获实际日志验证英文固定模板、关联传播及无原始模型内容；配置切换、非法输出、超时与回调失败不泄露密钥或供应商正文。
- 实际四代理请求证明系统与用户资源加载、JSON参数还原、本次一次、无用户资料进入系统角色；覆盖字面花括号及配置失败零模型调用。Boot JAR六资源与源文件逐字节一致，SDK自动输出格式说明允许保留。

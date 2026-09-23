# 外部模型调用排障日志

四个真实 AI 适配器（意图路由、问题分析、诊断推理、流式回答）使用英文 SLF4J/Log4j 2 日志。
修改后重启后端生效。使用真实编排时，`LLM_MODE=mock` 仍会记录 AI Service 调用阶段，但不会访问外部模型；是否访问外部服务不能仅凭这些日志判断。

- `AI call started`：一次 AI 操作开始；`callId` 关联该操作的阶段与结果，现有 MDC 关联请求和会话。
- `AI invocation started`：开始调用 AI Service 或启动流；SDK 仍可能在本地渲染模板，此事件不代表服务商已收到请求。
- `AI call completed`：技术调用完成；不表示业务事务已经提交。
- `AI call failed`：记录失败阶段、原因分类、HTTP 状态码（仅异常提供时）、外层和根因类型、总耗时。
- `AI model configured`：同步/流式模型、超时配置，以及同步调用的重试上限。一次调用日志统计 SDK 内部重试的总耗时，不将其标记为单次 HTTP 尝试。

| 字段 | 含义 |
| --- | --- |
| `phase=INPUT_ENCODING` | 本地输入序列化，尚未调用模型 |
| `phase=SERVICE_SETUP` | AI Service 代理创建或流式请求准备 |
| `phase=MODEL_INVOCATION` | 同步 AI Service 调用，包括 SDK 模板渲染、网络请求与结构化输出解析 |
| `phase=STREAM_START` | 注册流式回调或同步启动失败 |
| `phase=STREAM_RECEIVE` | 流式接收中的异步错误 |
| `phase=TOKEN_CALLBACK` | 应用侧消费 token 的回调失败 |
| `reasonCode=TIMEOUT / DNS / TLS / CONNECTION / NETWORK_IO` | 超时、域名解析、TLS、连接或其他网络 I/O 问题 |
| `reasonCode=PROVIDER_AUTH / PROVIDER_RATE_LIMIT / PROVIDER_SERVER / PROVIDER_HTTP` | 鉴权（401/403）、限流（429）、服务端（5xx）或其他 HTTP 错误；结合 `httpStatus` 判断 |
| `reasonCode=PROMPT_CONFIGURATION / MODEL_OUTPUT_UNPARSEABLE` | SDK 提示词配置问题或结构化输出解析失败 |
| `reasonCode=AI_SERVICE_ERROR / PROVIDER_ERROR` | 无法细分的异常，结合异常类型排查；不会根据异常正文猜测原因 |

流式结果携带 `firstResponseMs`；`-1` 表示未收到首段文本或不适用。
DEBUG 级别额外记录阶段切换和一次首段响应耗时，不逐 token 打日志。
可通过 `LOGGING_LEVEL_COM_CHH_AUTOSENSE=DEBUG` 开启详细日志。
日志不包含对话、历史、提示词、模型响应正文、API Key、原始异常消息或完整 URL；SDK 请求/响应日志保持关闭。

## 执行计划排障日志

计划日志在默认 INFO 级别输出，真实模型、Mock 模型和 stub 图均适用。通过 `requestId` 关联同一流程，`plannerAttempt` 从 1 开始，澄清后重新规划会再次记录候选计划。无需开启 SDK 请求/响应日志。

- `Workflow plan generated`：候选计划已生成，记录 outcome、步骤数、设备快照是否加载、设备数量、快照 hash、是否需要澄清；此时尚未通过计划校验。
- `Workflow plan step`：按 order 展开每一步，记录 stepId、type、设备 ID、动作、查询字段、控制数值、依赖、结果绑定、条件、知识库标志和诊断模式。condition 中保留数字/布尔值及步骤引用，自由文本字面量为 REDACTED。
- `Workflow plan validated`：结构及设备目标校验通过，记录最终 planHash；不代表已获用户确认或已经执行。
- `Workflow plan rejected`：WARN 级别，记录 STRUCTURE 或 DEVICE_TARGETS 阶段，以及服务端校验器的固定错误原因，例如 Device reference is outside planning snapshot。
- `Workflow plan clarification required`：校验器发现缺少步骤类型或知识库标志，转入澄清。

示例（省略时间及 MDC 前缀）：

```text
Workflow plan generated: requestId=demo, plannerAttempt=1, outcome=PLAN, stepCount=1, deviceContextInitialized=true, deviceCount=2, snapshotHash=..., clarificationRequired=false
Workflow plan step: requestId=demo, plannerAttempt=1, order=1, stepId=s1, type=DEVICE_QUERY, parameters={"deviceRef":17,"action":"state","fields":["getResults.get_properties.brightness"]}, dependsOn=[], inputBindings={}, condition=null, requiresKnowledgeBase=null, diagnosisMode=unknown
Workflow plan validated: requestId=demo, outcome=PLAN, stepCount=1, planHash=...
```

日志采用结构摘要，不打印 instruction、targetHint、clarifyQuestion、问题/证据正文、SN 或设备列表。标识符清除控制字符并限长；步骤、引用、查询字段和条件展开有数量/深度上限，避免异常模型输出无限放大日志。检查部署后的默认 INFO 日志即可使用这些事件。

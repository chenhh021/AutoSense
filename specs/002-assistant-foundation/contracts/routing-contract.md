# Contract: IntentPlanner 与确定性计划路由

**Date**: 2026-09-15
**Status**: LangGraph4j 目标契约；替代旧 SINGLE/COMPOSITE 后仅选择一项的执行限制。
**References**: [Graph](graph-contract.md)、[State](../data-model.md)、[Prompt](prompt-contract.md)。

## 1. 输入、输出与创建

IntentPlanner 消费 RequestContext 的原始消息，以及由 messages 提供的本人会话历史窗口；不让模型决定 userId、threadId 或批准事实。模型调用仍由 LangChain4j AI Service 完成。
新增 ai/IntentPlannerService 及 ai/factory/IntentPlannerServiceFactory，使用资源提示词，返回 ai/model/ExecutionPlanCandidate。复用模型 Bean 和 AiServiceValidator，不恢复统一 AiServiceFactory。
候选 outcome 为 PLAN / CLARIFY / OUT_OF_SCOPE。简单意图为一个步骤，复合意图为多个步骤；CLARIFY/OUT_OF_SCOPE 无可执行步骤。旧RoutingDecision及单意图分类接口仅可在移植期间作对照，移植完成删除；历史数据库枚举文本由只读展示解释，不保留可执行分类链。
步骤类型映射：

| 新计划类型 | 旧能力兼容值 |
| --- | --- |
| KNOWLEDGE_CONSULT | KNOWLEDGE |
| DEVICE_QUERY | DEVICE_QUERY |
| FAULT_DIAGNOSIS | DIAGNOSIS |
| DEVICE_CONTROL | CONTROL |

工厂代理不挂共享可写 memory，不自动注册设备工具。ChatMemory 服务负责生成 messages，再适配为现有 @V(history/text) 输入，当前输入一次；详见 data-model。

## 2. 结构化候选示例

```json
{
  "outcome": "PLAN",
  "steps": [
    {
      "stepId": "s1",
      "type": "DEVICE_QUERY",
      "instruction": "读取客厅灯亮度",
      "targetHint": "客厅灯",
      "parameters": {"fields": ["brightness"]},
      "dependsOn": [],
      "inputBindings": {},
      "condition": null,
      "requiresKnowledgeBase": null
    },
    {
      "stepId": "s2",
      "type": "DEVICE_CONTROL",
      "instruction": "亮度低于30时设为80",
      "targetHint": "客厅灯",
      "parameters": {"brightness": 80},
      "dependsOn": ["s1"],
      "inputBindings": {"deviceRef": {"stepId": "s1", "field": "deviceRef"}},
      "condition": {"op": "LT", "left": {"stepId": "s1", "field": "brightness"}, "right": 30},
      "requiresKnowledgeBase": null
    }
  ],
  "clarifyQuestion": null
}
```

这是未授权的计划候选，s1/s2 各需确认；任何字段中的 deviceRef 必须在服务端重新验证所属用户，不信任模型制造的引用。
AI 不输出可信 approval、permission、timeout、retryBudget、result 或运行状态。额外的安全敏感字段导致计划拒绝，不以宽松解析接受。
步骤参数、引用字段和条件运算必须通过按类型白名单；允许 EQ/NE/LT/LE/GT/GE/AND/OR 和有限深度表达式，不允许脚本、类名、Bean 名、URL、反射或任意工具名。

## 3. PlanValidator

先按 outcome 校验：PLAN 要求步骤数 1..8（配置上限）、ID 唯一、类型枚举、允许动作、引用只向前、参数形状、条件类型与深度；CLARIFY 要求无步骤和非空追问，进入 PrepareInput/AwaitInput；OUT_OF_SCOPE 要求无步骤，输出服务范围说明并正常结束，不视为结构错误。固定计划摘要/hash 后发布 PlanContext；执行中不自动改计划。
PlanValidator 只检查计划结构和静态安全约束，不在批准前向设备发送请求，也不替代执行前归属/权限校验。
KNOWLEDGE_CONSULT 的 requiresKnowledgeBase 必填；其他类型该字段为空。常识不查库；专用知识需库，类型筛选复用003规则。缺少标记或未知类型进入同一澄清等待分支，危险绕过控制路径直接拒绝。尚未发布正式计划的澄清回答可重新进入Planner；已发布计划只补充未决目标/参数的运行时绑定，不改变步骤定义、种类和顺序，新增目标或步骤必须取消旧计划后重新规划。
FAULT_DIAGNOSIS 消费用户输入、前序 DEVICE_QUERY 证据和知识，不内嵌设备读取/控制；需要修复或复检时计划必须已有独立步骤，否则说明需重新生成计划。
模型结构错误与外部超时分开：非法结构不作网络重试；明确服务错误不重试；超时由统一边界有限重试。

## 4. PlanRouter 与结果

PlanRouter 是纯确定性节点，读取 PlanContext.currentStep 对应 type，静态映射四个节点/子图。不能再调用 LLM 选择执行入口。
先评估受限条件；false 输出 SKIPPED 候选并转 CompleteStep，缺失证据/条件计算错误则转 Reject，不能把缺失当 false 或默认 true。
CompleteStep 仅在成功或正常跳过后提交结果和推进游标；失败保留先前结果并终止，剩余步骤 NOT_EXECUTED。ResponseAggregator 使用已提交步骤结果组织最终回答，不再次调用设备。
KnowledgeConsult 真实适配复用003知识链、用户缓存与共享索引；原KnowledgeCapabilityHandler的业务迁到知识服务后删除旧handler及CapabilityRequest/Result/text sink。Query/Diagnosis/Control各用专用子图。stub与real使用图节点契约，真实模式未实现者明确不可用；最终没有旧dispatcher或混合诊断runner可供回退。

## 5. 超时与重试参数

统一配置前缀 autosense.graph；计划模型无权覆盖服务端预算。

| 配置 | 规划默认 |
| --- | --- |
| mode | real；stub 仅显式开发/测试 profile |
| max-plan-steps | 8 |
| planner-timeout-seconds | 30 |
| step-timeout-seconds（KNOWLEDGE_CONSULT/FAULT_DIAGNOSIS） | 60 / 60 |
| step-timeout-seconds（DEVICE_QUERY/DEVICE_CONTROL） | 10 / 10 |
| max-retries | 2，指首次尝试之后至多两次 |
| retry-delay-millis | 1000，固定间隔，可配置 |
| approval-ttl-seconds | 300 |
| expensive-step-threshold-seconds | 30（步骤超时 ≥ 阈值） |
| execution-slice-timeout-seconds | 300，不包含人工等待和 WAITING_RESUME |
| max-graph-iterations | 256；防错误图无限转移，不表示允许业务循环 |

步超时是一次步骤尝试的绝对截止，步骤内多个外部调用共享该次截止；记录 callId 并复用已成功子调用，避免重试整个节点导致成功调用重复。各 SDK timeout 不得超过剩余截止；LLM/embedding/device SDK 内置重试统一关闭，由图唯一管理预算。
活跃区间总截止优先于任何后续重试；到期停止整个计划并保留结果，不能以不断重试续期。确认/重启等待退出活跃区间，resume 创建新的活跃区间但不重置步骤重试次数。
明确 HTTP 4xx/5xx、解析错误、业务拒绝不可重试；仅请求超时进入重试判定，不把所有连接异常归类为 timeout。
控制操作只有具有远端稳定去重或等效安全保证时允许重发；现有模拟器无此能力，结果未知时直接 DEVICE_RESULT_UNKNOWN 并终止。用于核对结果的设备读必须是已确认的独立查询，不在控制中偷偷执行。

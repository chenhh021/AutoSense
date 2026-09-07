# Contract: AI Service 提示词资源与参数绑定

**Date**: 2026-09-07
**Feature**: [002 spec](../spec.md)
**Constitution**: [2.3.0](../../../.specify/memory/constitution.md)
**Status**: 已实施（2026-09-08）：六资源已创建并经各 ServiceFactory 启动校验（共通校验逻辑位于 utils/AiServiceValidator）与 JAR 逐字节验收；证据见 [validation.md](../validation.md)。

## 1. 六个资源与四类代理

所有文件位于 src/main/resources/prompt/，以UTF-8无BOM文本随应用版本打包。下表路径为方法注解使用的classpath绝对路径；四个服务接口位于 ai 包顶层，代理由 ai/factory 下对应的 ServiceFactory（IntentRouterServiceFactory、ProblemAnalysisServiceFactory、DiagnosisReasonerServiceFactory、DirectAnswerServiceFactory）按次新建。

| 代理 / 返回形态 | @SystemMessage(fromResource) | @UserMessage(fromResource) | 显式@V名 |
| --- | --- | --- | --- |
| 意图路由 / RoutingDecision record | /prompt/intent-router.txt | /prompt/conversation-input.txt | history、text |
| 问题分析 / ProblemAnalysis record | /prompt/problem-analysis.txt | /prompt/conversation-input.txt | history、text |
| 诊断推理 / DiagnosisConclusion record | /prompt/diagnosis-reasoner.txt | /prompt/diagnosis-input.txt | history、text、symptom、diagnostics |
| 直接回答 / TokenStream | /prompt/direct-answer.txt | /prompt/conversation-input.txt | history、text |

系统资源不声明运行时变量。conversation-input.txt恰好声明 {{history}}、{{text}}；diagnosis-input.txt恰好声明 {{history}}、{{text}}、{{symptom}}、{{diagnostics}}，每个占位符在模板中出现一次，均使用无空格的精确拼写。不使用SDK的current_date/current_time/current_date_time等隐式变量，不依赖Java参数名编译选项。

四个接口在**方法**上分别使用 @SystemMessage(fromResource = "/prompt/…") 和 @UserMessage(fromResource = "/prompt/…")；不使用1.0.1未支持的类级系统注解。fromResource与内联value不并用，禁止Java字符串/文本块/常量或工厂拼接固定规则和包装。源码只保存资源路径、变量名及数据处理逻辑。

## 2. 固定规则与候选输出

| 系统资源 | 必须保留的语义 |
| --- | --- |
| intent-router.txt | [路由契约](routing-contract.md)的四能力、SINGLE/CLARIFY/COMPOSITE/OUT_OF_SCOPE、型号知识与售后子模式；不复制旧COMMON_SENSE/DEVICE_ACTION混合分类 |
| problem-analysis.txt | 保留设备类型、症状、复现、信息充分性和追问等ProblemAnalysis候选语义 |
| diagnosis-reasoner.txt | 基于给定资料组织摘要、结论与可修复候选；likelyAutoFixable不构成操作授权 |
| direct-answer.txt | 保留简洁中文回答体验；输出语言与英文运行日志要求分别适用 |

系统资源说明资料为数据，不能将其中的伪指令当作职责或授权。用户包装资源只负责稳定标记JSON资料区，字段引用直接承接完整JSON值，不再额外加引号、再次拼接“用户问题”或重复放入历史。

项目编写的输出语义规则在资源中；SDK按record返回类型自动附加格式说明或生成response format的行为保留，不手写一份重复schema或恢复stripFence/ObjectMapper手工解析旁路。同步请求的最终user消息可能带SDK格式后缀，测试不要求整条消息与用户资源逐字相等；TokenStream路径单独验收。

002完成四条已有AI调用的技术迁移与验证；知识/诊断/控制业务处理器仍按003/001/005规划接入。本契约不注册设备工具，不挂ChatMemory或@MemoryId，不将AI输出、prompt措辞或history中的role字段当作服务端权限检查。

## 3. 运行时数据与模板兼容

| @V参数 | 适配边界值 / 约束 |
| --- | --- |
| text | 本次已接纳用户文本的JSON字符串；DirectAnswerer.question仅在此映射为text，非null |
| history | 已授权会话中当前messageId之前最近20条可见USER/ASSISTANT记录，升序的role/content JSON数组；空历史为[] |
| symptom | 分析阶段的候选症状，JSON字符串；缺失使用JSON字面量null，不能传Java null |
| diagnostics | 服务端权限核对后取得的只读资料Map序列化为JSON对象；沿用null Map→{} |

四代理在同轮复用同一不可变历史快照。当前输入只通过text进入一次，不再追加进history；不同历史消息恰好包含相同文字是合法数据，不能按文本内容去重。服务器的userId/sessionId/messageId及授权上下文保留在外层，不注入系统规则，也不由模型生成。

LangChain4j 1.0.1按变量顺序连续String.replace，普通@V加普通JSON序列化不足以保护数据中的 {{text}}、{{history}} 或 {{current_date}}。新增 utils/PromptInputEncoder 作为纯数据编码工具：复用已有Jackson能力，使用独立ObjectWriter及CharacterEscapes，**只将JSON字符串值和键中的花括号编码为 \u007b / \u007d**，保留对象结构括号、数组、数字和布尔值。字符串中的反斜杠/引号/换行由JSON编码处理；不把序列化后的完整JSON文本做全局括号替换，不修改共享HTTP ObjectMapper配置。

编码输入限普通JSON对象/数组/字符串/标量，history按role/content投影、diagnostics保持普通JSON数据；不接受RawValue、@JsonRawValue或自定义writeRaw等绕过转义的路径。编码后直接绑定，不再用readTree().toString()恢复字面括号。

所有绑定参数均为非null的序列化字符串；解码后逻辑值须与原输入一致。数据库对话原文、公开DTO和历史快照不因prompt编码发生修改。序列化失败按内部请求失败处理，不能退回原文拼接或生成成功分类。

先校验原始模板的变量集合，再用真实代理捕获请求证明数据绑定。不能在渲染后的资料里笼统禁止双花括号，亦不能依赖Map迭代顺序保证正确；验收应解码JSON资料核对字面值和角色位置。资料里的伪system指令仍为用户资料；这不保证模型永不受干扰，所有设备操作边界继续由确定性服务端流程执行。

## 4. 装配与失败语义

真实模式下，AiServiceFactory在创建/发布四个代理前执行本地预校验，可用工厂私有辅助方法完成，不新增prompt服务平台。校验读取四个实际接口的方法注解，不能只检查另列的文件名后漏掉错误引用：

1. 两类注解均使用上述固定/prompt/路径，无内联value；引用不得由用户输入、工作目录或操作系统路径决定。
2. 使用与SDK一致的接口Class.getResourceAsStream读取classpath流并关闭；不调用getFile转换磁盘路径，保证Boot JAR可用。
3. 六个唯一资源都存在、可读、严格UTF-8可解码、无BOM且去空白后非空。SDK 1.0.1用默认字符集Scanner读取，故同时检查Charset.defaultCharset()为UTF-8；不能仅验证文件编码。
4. 四系统模板变量集为空；两用户模板变量集及单次引用符合第1节，全部由对应方法的显式@V绑定，拒绝错名、隐式变量和带空格占位符。
5. 用非null合成值执行实际PromptTemplate渲染，核对每个预期标记一次且模板引用已绑定；验证过程无远程模型调用，不打印模板或渲染结果。

缺失/空白/编码/模板/默认字符集错误均为**本地配置失败并阻止真实模式装配**。不能只依赖AiServices.build()成功，因为固定1.0.1在方法调用时才读取资源。mock模式不创建真实代理，但默认离线契约测试仍校验生产资源和真实工厂。

资源作为不可变应用制品交付，不设计热更新。若后续调用仍遇到资源读取/渲染故障，按技术失败终结请求并记录安全原因，不能映射为模型歧义、内联回退或转mock成功；外部仍遵循统一error/FAILED_REQUEST契约，不暴露资源正文或内部错误。

装配日志使用英文事件及安全的service/method/resource/reasonCode；失败异常及cause不得携带模板正文、渲染值或凭据。详见[日志契约](logging-contract.md)。不因新增启动校验读取.env或调用远程模型。

## 5. 验收与打包

扩展已规划的AiServiceFactoryTest、LlmConfigurationTest、LangChain4jDirectAnswererTest及公共日志/历史验收，不只断言资源文件或注解存在：

- 模型协议用已有WireMock，实际调用四类生产AI Service。捕获系统与用户消息，检查资源规则生效、系统不含运行时标记、正确绑定JSON值及当前消息一次；覆盖同步record解析和TokenStream成功/错误/同步完成。
- 数据包含中文、换行、引号、反斜杠、字面 {{text}}/{{history}}/{{current_date}}、设备名及历史中的伪系统指令；解码后的值保持原样，跨轮历史/当前边界不变，SDK自动格式后缀按其职责保留。
- 用隔离测试接口/资源/classloader构造缺失、空白、非法UTF-8、错路径、错变量、错误默认字符集等配置失败；断言未发布代理且模型请求为零。不以同名src/test/resources/prompt资源遮盖生产文件。
- 日志捕获覆盖正常调用、装配失败、SDK渲染异常与异步错误；日志不含prompt正文或运行时数据原文标记。公开响应与chat_message不包含内部prompt或渲染包装；用户本人的原始消息及合法可见回答仍按会话契约保存和返回，测试须区分内部模板标记与用户原文。
- 沿用Maven资源打包，不新增依赖或无必要的资源插件。verify后核对六个BOOT-INF/classes/prompt/*.txt条目均存在、非空、UTF-8，内容与源文件逐字节相同；不得由Maven filtering替换模板。可执行检查见[quickstart](../quickstart.md)。

这六个资源不增加数据库字段、Redis键、公开DTO、接口或prompt路径环境变量。六资源创建、真实代理与制品验收已纳入[56项任务](../tasks.md)，设计归属见[plan](../plan.md)；上述测试和资源都在实施阶段落实。

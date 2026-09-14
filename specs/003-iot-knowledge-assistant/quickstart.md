# Quickstart: 共享知识索引与内嵌 RAG 验证

**Date**: 2026-09-11 | **Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

本指南覆盖完整 feature 的验证。2026-09-11 已完成 US1/US2 与知识运行验证：普通 verify 187 项、知识定向集成及 9 项 JAR 检查通过；完整 -Pit 244 项中仍有 13 项旧诊断/设备验收失败，T041 保持未完成。当前行为和失败明细以 [validation.md](validation.md) 最新章节为准。真实模型质量与 0.75 阈值校准未执行。

## 1. 前提与命令

使用 Java 21 和 Maven Wrapper。先完成 [tasks.md](tasks.md) 中的导入/装配/回答任务；本地普通验证使用 mock 或 WireMock，禁止默认访问真实模型/设备。手动启动完整应用时需按项目已有方式准备 MySQL/Redis 和数据库初始化；不要在生产库做故障注入。

在仓库根目录执行：

```powershell
$env:LLM_MODE = 'mock'
$env:KNOWLEDGE_EMBEDDING_PROVIDER = 'mock'
.\mvnw.cmd -l target/knowledge-rag-verify.log verify
```

期望普通测试全部通过并打包成功。已有 KnowledgeConfigurationTest、KnowledgeIndexInitializationTest、UserAiServiceCacheTest、KnowledgeAdmissionContractTest 与 AiServiceAssemblyTest 应迁移到新装配后复用。新增测试覆盖下述矩阵，而非为旧类补兼容空壳。

准备依赖后可运行：

```powershell
.\mvnw.cmd spring-boot:run
```

模型质量校准单独使用已授权的真实环境和独立 KNOWLEDGE_EMBEDDING_BASE_URL/API_KEY/MODEL_NAME 配置，不把 mock 的相关度结果当成生产语义质量。不要在文档或日志中输出密钥。

## 2. 启动一次与共享索引

复用当前 document/light/MI-MJDPL01YL 两份文件，并在测试资源增加空调、其他型号/类别样例。

- 启动完成后从容器取得标准 EmbeddingStore<TextSegment>，检索样例并验证 deviceType/model/knowledgeKind/sourceId/documentHash/index。
- 通过 UserAiServiceCache.getOrCreate 为两个用户直接调用专用工厂创建各自 direct/analysis/enhanced 组合，验证所有 Retriever 引用同一个 store。同用户先后或并发查询两个类型时使用同一 enhanced 代理；代理创建零查询/embedding/模型请求，后续查询只产生 query embedding，不重新导入。
- 并发调用及缓存驱逐后，启动导入计数仍为一次；服务重启另建一个完整 store，sourceId/hash 与检索样例稳定，不重复数据。
- 导入与查询均不读取 MySQL 维修知识表。运行期不存在 add/remove/reload 操作。
- 在隔离夹具分别注入目录缺失、缺文件、空文档、坏 UTF-8、解析/向量/写入失败、数量/维度错误、总量/段数/启动预算超限；全部启动失败，不返回 store、不宣告业务可用。迟到初始化结果不能发布。
- 校验日志只含安全英文阶段、原因、来源标识和数量，启动日志无会话字段。

完整导入规则见 [knowledge-documents.md](contracts/knowledge-documents.md)。

## 3. 真实 AiServices 装配与单次检索

使用本地 WireMock ChatModel 或可捕获 ChatRequest 的确定性模型，经过实际 UserAiServiceCache.getOrCreate(AuthUser) 直接调用 EnhancedAnswerFactory.enhancedAnswerService() 生成代理，并调用其 answerKnowledge；不能只测试单独 Router。

| 场景 | 必须观察到的结果 |
| --- | --- |
| 达标检索 | 一次 query embedding/search，一次增强模型；模型收到本次 evidence；Result.sources 与实际段一致 |
| 查询文本 | embedding 仅收到 queryText，不包含完整 JSON/历史/系统模板 |
| 类型筛选 | 同一用户同一增强代理切换类型，实际 search filter 只包含本轮 deviceType 相等；同类型不同型号/类别均可成为候选 |
| 跨类型高分 | 其他类型被 store filter 排除；类型 filter 不因无结果被放宽 |
| 无候选 | 一次检索、增强模型零调用；外层调用一次 DirectAnswerService |
| 全低分 / 恰好门槛 | 低分转直答；score == minScore 通过，不传低分资料给模型 |
| 真实检索故障 | 公共 error，直答零调用，不能变为空结果 |
| 型号/版本/环境不足 | 不前置澄清；有同类型另一型号资料则依据实际参数/条件回答，服务器生成缺口和依据型号声明，来源可追溯 |
| 来源伪造/非法输出 | 不展示未校验答案，公共失败收尾 |
| 同用户两会话不同类型并发 | 使用同一增强代理，query、动态 Filter、evidence、sources、声明、历史、MDC、回调完全独立 |
| 无效类型误入增强方法 | 在 query embedding 前明确失败；不得给空 filter、使用上次类型或扩大范围 |
| 多型号引用 | 声明列出全部实际依据型号，参数与各自来源相符；合法跨型号引用不被按型号相等规则拒绝 |

验证资源模板实际加载、JSON 注入和 SDK 结构化输出顺序；缺失/空模板使装配失败。包括花括号、Unicode、恶意指令资料，确认没有二次模板插值、内联默认增强提示词或设备工具。

## 4. 公共对话与缓存回归

通过既有 sessions API，以未绑定设备的已登录用户提问；手工路由检查或契约测试均需覆盖：

1. “色温和亮度有什么区别？”以及本人会话追问：常识直答，范围分析/知识查询/设备读写均零。
2. 类型未知的非常识问题：零检索、不追问类型，准确说明类型未知；已知类型但缺型号/版本/环境则按类型检索，依据可用的另一型号资料作答并声明，不因该缺口进入 awaiting。
3. 对已提供型号的功能说明提问：内嵌 RAG 回答并展示真实来源；另造知识无匹配/低分/冲突/故障夹具分别验证。
4. 实际设备状态/参数、现场诊断和控制请求仍分别进入公共 004/001/005；知识资料不能触发设备操作。
5. SSE 的 token/status/awaiting/conclusion/error 外形兼容；token 聚合文本、GET 历史和 conclusion.summary 一致，来源关联本轮 sessionId/round，不将原始证据写成用户消息。
6. 首次成功接纳非知识对话也由 UserAiServiceCache 直接调用相应专用工厂完整创建三代理组合，唯一缓存键 userId；同用户切换类型仍命中同一组合，不新增缓存条目或代理。不同用户分别新建，store 相同。
7. 并发原子创建、失败不缓存、单缓存容量 1000 个用户组合、30 分钟访问过期使用测试 Ticker 验证。驱逐不停止在途流或关闭共享客户端。GET/拒绝/忙请求不创建；注销或禁用后缓存命中仍拒绝。
8. 本 feature 的 analyzeKnowledge 与原诊断 analyze 均使用无 RAG 分析代理，知识查询计数为零；直接调用专用工厂和缓存组合不改变原诊断输出语义。
9. 模拟预算耗尽：Router 前/模型前/提交前检查截止，超时不启动后续模型、不提交迟到成功。当前 YAML 默认 300 秒，以实际配置为准。

## 5. 可执行 JAR 与最终验证

普通 classpath 测试不能代替实际 JAR 启动。verify 成功后，在上述 mock 和既有数据库环境变量下选择项目生成的唯一可执行 JAR：

```powershell
$knowledgeJars = @(Get-ChildItem -LiteralPath '.\target' -Filter '*.jar' | Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' })
if ($knowledgeJars.Count -ne 1) { throw 'Expected one executable application jar' }
java -jar $knowledgeJars[0].FullName
```

在独立终端运行，按 Ctrl+C 停止；重启验证来源稳定。隔离构建/测试资源覆盖 JAR 内缺文件或损坏文件，期望非成功启动；不修改生产资料制造故障。记录实际构建、JAR 启动和各矩阵结果，更新 validation，而非只写“计划验证”。

如需现有 Docker 集成回归，准备 Docker/模拟器后再执行 `.\mvnw.cmd -l target/knowledge-rag-it.log verify -Pit`；未执行时明确记录，不把普通 verify 视为集成通过。标准 EmbeddingStore 注入替换可用本地测试实现验证；真实外部向量库和启动零导入迁移仍留待后续阶段。服务实例生命周期验收覆盖新增 SC-008；历史 156 项测试不能视为新方案结果。


## 6. 本次可复现验证与遗留项

普通验证：`.\mvnw.cmd -l target/knowledge-final-verify.log verify`。

知识定向集成（真实 MySQL/Redis，不含旧诊断设备验收）：

```powershell
.\mvnw.cmd '-Dtest=Knowledge*Test,KnowledgeConversationIT,LangChain4jDirectAnswererTest,AssistantRoutingIT,AssistantRoutingContractTest,SessionProcessingIT,AiCallLogTest,LoggingInfrastructureTest,UserAiServiceCacheTest,AiServiceAssemblyTest' -l target/knowledge-focused-it.log verify -Pit
```

完整回归仍使用 `verify -Pit`；模拟器非默认端口时增加 `-Ddevice.service.base-url=http://127.0.0.1:18081`。完整命令的失败不能用定向通过替代。当前 13 项失败详见 validation.md，T041 在修复对应 feature/旧测试后才能勾选。

实际 JAR 矩阵脚本为 `scripts/manual-test/knowledge-jar-validation.py`，需要先构建 JAR，并显式提供指向**可丢弃测试库**的 MYSQL_URL/MYSQL_USERNAME/MYSQL_PASSWORD、REDIS_HOST/REDIS_PORT/REDIS_PASSWORD。不要连接业务库。运行 `python scripts/manual-test/knowledge-jar-validation.py`；使用端口 18186，复制品、日志、故障编译产物和 result.json 均写入 target/knowledge-jar-validation。故障配置源文件在 scripts/manual-test，正常 Maven 构建不会打包它。

脚本覆盖正常/重启/恢复和缺文件、非法 UTF-8、解析、embedding、写入、启动超时共 9 个场景；自动比较生产资料字节与来源尾注，并在退出时结束它启动的 Java 子进程。它显式使用 mock 与 minScore=0；不作为真实模型语义质量证明，0.75 业务边界由单元/SDK 装配测试验证。

# Quickstart: 公共基础与四能力路由验证

**Date**: 2026-09-07
**Feature**: [002 spec](spec.md)
**Constitution**: [2.3.0](../../.specify/memory/constitution.md)，包含对象/日志验收与本轮prompt加载、绑定及打包检查。
**Status**: 已于 2026-09-08 按本指南完成实施验收，结果见 [validation.md](validation.md)。外部 deviceSimulator 镜像不可用，模拟器相关 IT 与绑定成功路径保留外部前提未运行；真实模型小样本因无凭据未运行。

002证明统一入口、真实AIService装配、公共数据/鉴权/会话及四能力投递。设备查询成功、故障诊断正确和亮度被修复等分别属于004/001/005，不作为002的独立完成条件。

## 1. 环境与资料

- Java 21、仓库Maven Wrapper；运行需要下载依赖时网络可用。真实AI Service运行要求JVM默认字符集为UTF-8，工厂按[prompt契约](contracts/prompt-contract.md)检查；不能以源码UTF-8替代运行环境校验。
- 默认单元/契约测试不要求运行本地应用或Docker；真实依赖集成需要Docker及独立deviceSimulator。
- 应用默认8180。application.yaml当前模拟器默认8080，Compose/IT默认8081；应用和验证终端都显式设置DEVICE_SERVICE_BASE_URL。
- 设备接口见[外部服务说明](../../documents/后端接口说明.md)和[按SN查询说明](../../documents/新增接口说明-按SN查询设备.md)；仓库不构建设备模拟器。
- 当前DB初始化配置会读取schema.sql/data.sql；对已有数据库先按[data-model.md](data-model.md)执行前向迁移和非破坏初始化检查，不能重灌seed覆盖旧用户。迁移脚本已交付：scripts/migration/20260907-assistant-processing.sql（可重复执行）。
- 不读取或导出.env，不假设Maven自动加载它。连接和模型凭据通过当前终端的环境变量或既有秘密注入方式提供。

| 用途 | 环境变量 |
| --- | --- |
| MySQL | MYSQL_URL、MYSQL_USERNAME、MYSQL_PASSWORD |
| Redis | REDIS_HOST、REDIS_PORT、REDIS_PASSWORD（可选） |
| 外部设备 | DEVICE_SERVICE_BASE_URL、DEVICE_SERVICE_TIMEOUT_SECONDS |
| 身份 | AUTH_DEV_MODE、AUTH_TOKEN_TTL_DAYS |
| 模型 | LLM_MODE、LLM_BASE_URL、LLM_API_KEY、LLM_MODEL_NAME、LLM_TEMPERATURE、LLM_TIMEOUT_SECONDS |
| 已有其他 | AFTERSALES_MOCK_ENABLED、RAG_EMBEDDINGS_ENABLED（chat-memory 配置已随 ChatMemory 移除，窗口固定 20） |
| 日志（实施后） | LOGGING_CONFIG、LOGGING_LEVEL_ROOT、LOGGING_LEVEL_COM_CHH_AUTOSENSE、LOGGING_PATTERN_CONSOLE，见[日志契约](contracts/logging-contract.md) |
| 本期拟新增 | LLM_MAX_RETRIES及ASSISTANT_*处理/租约参数，详见[路由契约](contracts/routing-contract.md) |

## 2. 已有可运行回归命令

在仓库根目录PowerShell运行：

```powershell
Set-Location 'D:\code\AutoSense'
.\mvnw.cmd verify
if ($LASTEXITCODE -ne 0) { throw '默认单元/契约验证失败' }
```

当前pom默认排除docker标签；-Pit清除此排除并显式包含*Test.java、*IT.java，使用Surefire。它不包含复数命名的AutoSenseApplicationTests.java，不能声称完整-Pit自然包含该类。

如已有外部模拟器直接复用；如使用仓库Compose模拟器，先按实际环境提供SIMULATOR_IMAGE，并仅启动所需服务：

```powershell
if ([string]::IsNullOrWhiteSpace($env:SIMULATOR_IMAGE)) {
    throw '请先在环境中配置已准备好的外部模拟器镜像'
}
docker compose -f .\docker-compose.yaml up -d device-simulator
if ($LASTEXITCODE -ne 0) { throw '模拟器启动失败' }
```

上述Compose步骤仅在需要时执行；下列真实依赖回归由测试基类启动MySQL/Redis容器，无需再为该回归启动本地数据库：

```powershell
$env:DEVICE_SERVICE_BASE_URL = 'http://localhost:8081'
curl.exe --fail --silent --show-error "$env:DEVICE_SERVICE_BASE_URL/healthz"
if ($LASTEXITCODE -ne 0) { throw '外部模拟器未就绪' }
$previousRedisPassword = $env:REDIS_PASSWORD
try {
    $env:REDIS_PASSWORD = ''
    .\mvnw.cmd -Pit "-Dtest=UserManagementIT,DeviceBindingConcurrencyIT" verify
    if ($LASTEXITCODE -ne 0) { throw '公共基础集成回归失败' }
} finally {
    $env:REDIS_PASSWORD = $previousRedisPassword
}
```

已有IT使用随机应用端口，强制LLM mock、售后mock与开发身份。基类仅覆盖Redis host/port，测试容器无密码，因此上例临时清空REDIS_PASSWORD并在结束后恢复；建议在无其他Spring配置覆盖的独立验证终端执行。其设备地址优先级为JVM属性device.service.base-url、DEVICE_SERVICE_BASE_URL、8081；只设置LLM_MODE=real不能让这些旧IT变成真实模型验证。

完整-Pit还执行AutoRepairFlowIT/ManualGuideIT并向外部测试设备发送状态变更命令，它是旧业务的广泛回归，不是002核心验收所必需的命令。

## 3. 手动验证已有用户、绑定与会话兼容

使用专门的验证数据库、Redis和外部测试设备。已有真实用户/设备可用于非破坏的读取兼容检查；不要对真实业务设备提交控制确认。

应用终端先注入上述连接变量，再显式配置本轮模式：

```powershell
Set-Location 'D:\code\AutoSense'
$env:AUTH_DEV_MODE = 'false'
$env:LLM_MODE = 'mock'
$env:AFTERSALES_MOCK_ENABLED = 'true'
$env:RAG_EMBEDDINGS_ENABLED = 'false'
$env:DEVICE_SERVICE_BASE_URL = 'http://localhost:8081'
.\mvnw.cmd spring-boot:run
```

002 已实现：MockIntentClassifier 具备四路分类；生产缺少业务处理器时返回 CAPABILITY_NOT_AVAILABLE，不启用测试接收器。

另一个验证终端注册并登录唯一测试账号，凭据只保留在变量内：

```powershell
$appBaseUrl = 'http://localhost:8180'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$validationAccount = 'qs_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$validationPassword = 'A9' + [guid]::NewGuid().ToString('N')
$registerBody = @{
    userAccount = $validationAccount
    userPassword = $validationPassword
    confirmPassword = $validationPassword
} | ConvertTo-Json
$registeredUser = Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/v1/users/register" -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($registerBody))
$loginBody = @{ userAccount = $validationAccount; userPassword = $validationPassword } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/v1/users/login" -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($loginBody))
$headers = @{ Authorization = 'Bearer ' + $login.token }
$currentUser = Invoke-RestMethod -Uri "$appBaseUrl/api/v1/users/me" -Headers $headers
if ($currentUser.id -ne $registeredUser.id) { throw '当前用户不匹配' }
```

绑定前，由外部模拟器维护方或按上述外部接口说明独立准备一台未绑定的可发现测试设备，将实际返回的SN设置到验证终端的VALIDATION_DEVICE_SN。现有IT会绑定并清理其夹具，不能使用IT结束后的SN作为手动验证设备。平台绑定动作不负责在模拟器创建设备：

```powershell
if ([string]::IsNullOrWhiteSpace($env:VALIDATION_DEVICE_SN)) {
    throw '请先配置外部服务已存在的未绑定测试设备SN'
}
$bindBody = @{ sn = $env:VALIDATION_DEVICE_SN; name = '公共基础验证灯' } | ConvertTo-Json
$boundDevice = Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/v1/devices" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($bindBody))
$devices = Invoke-RestMethod -Uri "$appBaseUrl/api/v1/devices" -Headers $headers
if ($boundDevice.sn -ne $env:VALIDATION_DEVICE_SN) { throw '绑定SN不匹配' }
if (-not ($devices.devices | Where-Object { $_.id -eq $boundDevice.id })) { throw '本人设备列表未包含绑定结果' }
```

核对[用户设备契约](contracts/user-device-api.md)：显示名称、稳定元数据、仅本人列表、重复SN拒绝、未知型号仍可绑定；绑定前后模拟器设备数量不因平台操作增加。online:false不是确定离线。

使用不会触发设备业务的模糊输入检查闭流与补查：

```powershell
$sessionBody = @{ problem = '帮我处理一下' } | ConvertTo-Json
$response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$appBaseUrl/api/v1/sessions" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($sessionBody))
$events = [string]$response.Content
$sessionMatch = [regex]::Match($events, '"sessionId"\s*:\s*(\d+)')
if (-not $sessionMatch.Success) { throw '流未返回有效会话ID，检查error事件' }
$sessionId = [long]$sessionMatch.Groups[1].Value
$session = Invoke-RestMethod -Uri "$appBaseUrl/api/v1/sessions/$sessionId" -Headers $headers
$messages = Invoke-RestMethod -Uri "$appBaseUrl/api/v1/sessions/$sessionId/messages" -Headers $headers
if ($session.status -ne 'CLARIFYING' -or -not $session.awaitingInput) { throw '未进入澄清等待态' }
if ($events -notmatch 'event:\s*awaiting') { throw '缺少awaiting事件' }
$events
```

Invoke-WebRequest返回闭流后的内容，可验证协议形状，不能据此证明逐token时序。观察增量文本可用curl --no-buffer配合UTF-8请求文件；逐段回调的确定性验收由模型协议测试完成，避免用完整响应一次返回冒充流式。

最后注销并验证原真实token失效：

```powershell
Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/v1/users/logout" -Headers $headers | Out-Null
$logoutRejected = $false
try {
    Invoke-RestMethod -Uri "$appBaseUrl/api/v1/users/me" -Headers $headers | Out-Null
} catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) {
        $logoutRejected = $true
    } else { throw }
}
if (-not $logoutRejected) { throw '注销后旧token仍可使用' }
```

管理员禁用/启用与跨用户检查使用两个新测试账号及已有受控管理员身份。不要用dev token代替管理员或真实token失效证据；管理员自身状态变更也必须拒绝。

## 4. 本期新增专项验收

以下场景已由实施落实为测试类：默认 verify 覆盖公共单元/契约；显式 -Pit 集合（SessionProcessingIT、AssistantRoutingIT、TokenRevocationIT、DeviceLockIT、ConversationHistoryIT、SessionLifecycleIT、AssistantLoggingIT）覆盖真实 MySQL/Redis 场景。执行记录见 [validation.md](validation.md)。

| 组 | 准备/输入 | 必须证明 |
| --- | --- | --- |
| 四路接收 | 四种明确问题 | 实际公共编排仅向对应记录型处理器投递一次，诊断/控制不同 |
| 型号/售后 | 型号功能、明确售后查询 | KNOWLEDGE或DIAGNOSIS+AFTERSALES，无先行设备探测 |
| 澄清/复合/范围 | 模糊、条件请求、多设备写、无关输入 | 澄清或范围说明，无默认设备分支，无读写 |
| 实际AIService | 本地模型HTTP协议替身 | 使用真实代理、配置模型/地址/参数、类型解析与TokenStream回调 |
| 提示词资源 | 四系统资源、两用户模板、缺失/空白/错变量/编码夹具 | 实际加载与JSON绑定，用户资料不进入system，本地配置失败零模型请求；详见第6节 |
| 模板数据 | 用户/历史/设备名中含字面变量名、中文和转义字符 | 解码值不变、不被SDK再次替换，本次一次，历史边界与公开契约不变 |
| 模型错误 | 未知enum、矛盾结构、连接/超时/认证失败 | 结构错误澄清，服务故障error；不mock成功、不泄露内部内容 |
| 配置 | 非法mode/URL/空real密钥/不一致窗口；合法替换 | 启动校验及实际请求采用配置值，不只断言字符串 |
| 缺失处理器 | 生产装配不注册某能力 | CAPABILITY_NOT_AVAILABLE，零设备写，不调用旧DEVICE_ACTION |
| 会话与历史 | 超20条、多用户、多轮、重启、旧Redis缓存存在 | 20条历史+本次一次，完整答复长期保存，不读取陈旧共享缓存 |
| 并发回调 | 同会话并发、丢租约、迟到回调、处理到期 | 指针/截止校验生效，旧回调不写新轮，忙请求不重复落消息 |
| SSE恢复 | 文本/等待/终态/错误、断线GET | 五类事件形状、正确关流、无回放、已保存事实可补查 |
| 凭证 | 缺索引、孤立token、禁用再启用、并发签发/撤销、Redis故障 | 旧token不复活，无失效索引补成员，无假成功 |
| 共享设备设施 | SN并发唯一、owner锁续期/释放、旧记录 | 单套客户端/归属/锁，无误释放他人owner，旧数据保留 |
| 旧状态兼容 | DEVICE_ACTION历史、旧待确认上下文 | 历史可读，新轮重新路由，旧确认不执行设备 |
| 对象兼容 | 六个Entity注解迁移、现有record/VO归位、三个AI输出record | ORM无参实例化/主键回填/更新正常，JSON/校验不变，SDK实际解析成功或按契约拒绝 |
| 日志接入 | runtime/test依赖树、实际日志系统、配置覆盖 | 单一Log4j2提供者，Console格式/级别生效，公共英文事件可见 |
| 日志关联与脱敏 | 并发会话、线程复用、同步/异步回调、拒绝/超时/回滚、敏感测试标记 | MDC不串号，事件符合实际状态；消息及异常无凭据/正文，堆栈不重复，无逐token或列表逐项INFO |

**记录型处理器**保留真实鉴权、公共编排、会话/历史和SSE，只替换能力内部业务；记录capability、userId、sessionId、round、messageId。它们只在src/test装配，不随LLM_MODE=mock进入生产。

**零写计数**从路由请求开始，排除外部测试夹具建立/清理设备的动作；断言设备客户端写方法及HTTP commands/start等路径为零。模糊/复合/未注册场景还应断言设备读取为零。

**模型替身边界**：使用现有WireMock等模拟模型协议，保留真实LangChain4j代理；不mock掉IntentClassifier或AiService本身后宣称重写通过。外部设备交互仍按项目约束接真实模拟器。

## 5. 对象与日志验收（实施后执行）

先完成默认 verify，再在仓库根目录保存本次实际选用的日志依赖树：

```powershell
Set-Location 'D:\code\AutoSense'
.\mvnw.cmd dependency:tree "-Dscope=runtime" "-DoutputFile=target/dependency-tree-runtime.txt"
if ($LASTEXITCODE -ne 0) { throw '运行时依赖树检查失败' }
.\mvnw.cmd dependency:tree "-Dscope=test" "-DoutputFile=target/dependency-tree-test.txt"
if ($LASTEXITCODE -ne 0) { throw '测试依赖树检查失败' }
```

这是实施后的命令，本次规划不运行依赖解析或应用。检查两份完整树中实际选用的日志节点：Boot日志starter为3.5.3，Log4j2/SLF4J版本由Boot管理；有log4j-slf4j2-impl/core，无默认日志starter、Logback、log4j-to-slf4j及其他SLF4J提供者。Lombok只保留一份optional的1.18.36声明。依赖树不是启动成功的替代证据。

在实施后的Spring测试/应用启动中核对SLF4J实际绑定Log4jLoggerFactory与Log4j Core、没有multiple providers警告，并观察默认Console中的英文事件和关联字段。独立配置验证LOGGING_CONFIG替换文件以及LOGGING_LEVEL_COM_CHH_AUTOSENSE覆盖确实生效；自定义XML读取Boot导出的CONSOLE_LOG_PATTERN，避免改配置却不改输出。默认不开文件输出；部署启用文件后另验滚动/保留。

把下列断言结合既有关键场景与新增公共行为测试执行，无需为每个getter或每条固定日志创建独立测试：

| 场景 | 验证方法 / 预期 |
| --- | --- |
| Entity与DTO兼容 | 用户/设备/会话的真实MySQL回归核对无参映射、插入ID回填、更新和逻辑删除；原MockMvc/SSE契约核对字段、null、日期、校验与敏感字段不可见 |
| AI record解析 | 实际AIService + 模型协议替身返回RoutingDecision、ProblemAnalysis、DiagnosisConclusion；覆盖合法、nullable、未知枚举/额外字段和矛盾组合，不依赖Spring HTTP ObjectMapper测试替代 |
| 英文与关键位置 | 测试临时挂接Log4j Core捕获appender并在结束恢复，检查格式化消息、level和MDC；成功、认证/校验拒绝、无处理器、模型失败、超时均有契约事件 |
| 跨线程关联 | 两会话并发、执行器线程复用、同步完成与异步TokenStream/CompletionStage、超时任务；各日志保留自己的user/session/message/round，结束后MDC恢复，旧回调不变成新轮 |
| 脱敏与日志注入 | 输入仅测试用密码/token/key/对话/模型/prompt正文标记及带CR/LF的供应商异常（含cause/suppressed）；检查最终渲染消息和堆栈都无原文、伪造行或敏感标记；异常类型/错误码仍可定位 |
| 状态与噪声 | 故意回滚持久化不出现成功/已提交事件；同一异常堆栈只一次；多个token、列表逐设备查询和续租不产生逐项INFO；预期权限拒绝不记ERROR |
| 审计独立性 | 降低/关闭应用日志输出后，持久化route/state/request追溯仍按原事务保存；日志捕获不能充当数据库审计验收 |

外部设备路径仍使用现有独立模拟器；模型协议替身不改变设备测试约束。固定模板/字段/原因码应是英文，必要的已脱敏业务值不按“所有字符必须ASCII”误判。脚本提示和用户响应可保持中文。

## 6. 提示词验收与JAR检查

六个prompt资源已实施并随 JAR 逐字节验收通过（2026-09-08，见 [validation.md](validation.md)）。清单、参数与编码约束以[prompt契约](contracts/prompt-contract.md)为准，不在本指南复制提示词或测试类实现。

| 场景 | 验证方法 / 预期 |
| --- | --- |
| 真实代理读取 | 扩展计划中的AiServiceFactoryTest、LlmConfigurationTest、LangChain4jDirectAnswererTest，WireMock捕获四条真实代理请求；系统规则来自对应资源，用户包装正确，SDK输出格式后缀允许保留 |
| 数据绑定 | text/history/symptom/diagnostics解码为原值；空历史[]、缺失症状null、null诊断Map为{}；嵌套字符串键/值含中文、引号、反斜杠、换行及{{text}}/{{history}}/{{current_date}}时不再替换 |
| 历史/角色 | 当轮唯一测试标记只在text资料区一次，历史只含当前messageId前最多20条；伪system指令、候选症状与设备资料均不进入system；持久化原文和HTTP返回不采用专用prompt编码 |
| 本地失败 | 隔离资源/接口/classloader模拟缺失、空白、非法UTF-8、错路径/变量/重复引用以及默认字符集不符；在发布真实代理前失败，模型请求计数为零，无内联或mock回退 |
| 输出与日志 | RoutingDecision/ProblemAnalysis/DiagnosisConclusion仍经SDK解析；TokenStream成功/失败/同步完成仍符合原契约；装配/渲染/异步异常不输出prompt正文及测试敏感标记 |
| 制品 | 默认verify成功后执行下列只读检查；Boot JAR的六资源条目存在且与源文件字节相同，UTF-8无BOM、非空，未发生资源过滤 |

资源失败夹具不得用src/test/resources/prompt下同名文件遮盖生产资源。编译期注解/文件检查与样例渲染不能替代实际代理请求断言；默认单元/契约验收使用模型协议替身，不调用真实模型。

在仓库根目录执行；JAR名称来自当前pom的artifactId/version，未来版本调整时同步该路径。只输出资源名与PASS，失败不打印正文：

```powershell
Set-Location 'D:\code\AutoSense'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$promptJarPath = (Resolve-Path -LiteralPath '.\target\AutoSense-0.0.1-SNAPSHOT.jar' -ErrorAction Stop).Path
$promptFiles = @('intent-router.txt', 'problem-analysis.txt', 'diagnosis-reasoner.txt', 'direct-answer.txt', 'conversation-input.txt', 'diagnosis-input.txt')
$promptUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$promptArchive = [System.IO.Compression.ZipFile]::OpenRead($promptJarPath)
try {
    foreach ($promptFile in $promptFiles) {
        $promptEntryName = 'BOOT-INF/classes/prompt/' + $promptFile
        $promptEntries = @($promptArchive.Entries | Where-Object { $_.FullName -ceq $promptEntryName })
        if ($promptEntries.Count -ne 1) { throw "Prompt entry missing or duplicated: $promptFile" }
        $promptStream = $promptEntries[0].Open()
        $promptBuffer = New-Object System.IO.MemoryStream
        try {
            $promptStream.CopyTo($promptBuffer)
            $promptPackedBytes = $promptBuffer.ToArray()
        } finally {
            $promptStream.Dispose()
            $promptBuffer.Dispose()
        }
        $promptSourcePath = Join-Path (Get-Location).Path ('src/main/resources/prompt/' + $promptFile)
        $promptSourceBytes = [System.IO.File]::ReadAllBytes($promptSourcePath)
        if ([Convert]::ToBase64String($promptSourceBytes) -cne [Convert]::ToBase64String($promptPackedBytes)) {
            throw "Prompt bytes changed during packaging: $promptFile"
        }
        $promptText = $promptUtf8.GetString($promptPackedBytes)
        if ([string]::IsNullOrWhiteSpace($promptText) -or $promptText.StartsWith([string][char]0xFEFF)) {
            throw "Prompt must be nonblank UTF-8 without BOM: $promptFile"
        }
        Write-Output "PASS prompt/$promptFile"
    }
} finally {
    $promptArchive.Dispose()
}
```

此检查不启动应用、不读.env、不连接数据库或模型。它证明已构建制品的资源保持；变量和实际消息行为仍由上表的真实代理验收负责。

## 7. 真实模型小样本与完成证据

真实提供商验证在独立环境注入LLM_BASE_URL、LLM_API_KEY、LLM_MODEL_NAME后使用LLM_MODE=real，AUTH_DEV_MODE=false。不得输出密钥或整个登录响应。先做无设备副作用的分类样例及配置切换，核对脱敏路由追溯；未注册业务返回不可用是分流结果，不是该业务验收通过。

已有IT动态强制mock，不能靠设置环境变量宣称它覆盖真实模型。外部真实模型调用属于实施后的人工验证，不加入默认离线回归。

交付记录（2026-09-08，详见 [validation.md](validation.md)）：默认 verify 125 项通过；显式 -Pit IT 集合 33 项通过；runtime/test 依赖树单一 Log4j2 提供者；六 prompt 资源 JAR 逐字节 PASS；手动 API 链在一次性隔离容器上完成注册/登录/模糊会话/续聊/补查/绑定失败语义/注销全流程，日志无密码/令牌/prompt 正文。旧限制已解除：

- SessionApiContractTest 仍 mock 编排验证 HTTP/SSE 外壳，完整会话行为由 SessionLifecycleIT/ConversationHistoryIT/AssistantLoggingIT 在真实 MySQL/Redis 上验收。
- LangChain4jDirectAnswererTest 覆盖 TokenStream 适配；真实代理与模型协议行为由 AssistantRoutingContractTest/AiServiceFactoryTest 及 IT 验收。
- 设备锁原子 owner 操作由 DeviceLockIT 在真实 Redis 验收，不再依赖 Mockito 计数。
- 令牌竞态/撤销/回滚由 TokenRevocationIT 验收。
- 原001历史验证与已勾选任务不证明002完成；完整诊断/控制业务属 001/005，不是 002 交付阻塞项。

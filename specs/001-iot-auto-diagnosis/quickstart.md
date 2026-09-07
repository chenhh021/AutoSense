# Quickstart: 端到端验证指南

> **2026-09-07 规格拆分说明**：下文保留拆分前的设计/契约/验证指南作为参考，尚未按五个 feature 的新边界重新规划；其中工作区状态、需求编号和流程描述均属于编制时上下文。当前需求以[feature 总览](../README.md)及各自 spec 为准；原需求可查[拆分前规格](history/20260907-before-feature-split.md)。复用适用部分时须核对新职责，本文不代表新 feature 已实现或验收通过。

**Date**: 2026-09-07 | 关联：[spec.md](./spec.md) · [research.md](./research.md) ·
[data-model.md](./data-model.md) · [诊断契约](./contracts/diagnosis-api.md) · [用户契约](./contracts/user-api.md)

本文提供 Windows PowerShell 验证步骤。本次规划未启动服务、运行测试或修改数据库。
“现有验证入口”不表示本次已验证通过；“待实现验收”须在后续实现后执行。
`validation/unit-contract.md`、`validation/integration.md` 是 2026-09-04 的历史记录，
其中测试数量与成功结果不能作为本次代码的执行证据。

## 1. 前置条件与配置

- 以下命令从仓库根目录执行。准备 JDK 21、Maven Wrapper 所需下载权限；
  Windows 使用 `.\mvnw.cmd`，也可使用已安装的 Maven。
- 集成测试需要可访问的 Docker、MySQL/Redis 测试镜像及外部真实 deviceSimulator。
  模拟器本体不在本仓库；使用 Compose 须先取得可用的 `SIMULATOR_IMAGE`。
  默认镜像名 `devicesimulator:latest` 不代表本仓库会构建或发布它。
- 模拟器须支持 `/healthz` 及 SN 查询契约，见 `documents/后端接口说明.md`、
  `documents/新增接口说明-按SN查询设备.md`。
- 当前 `src/main/resources/application.yaml` 的模拟器缺省地址是 `http://localhost:8080`；
  Compose 映射及 IT 缺省地址是 `http://localhost:8081`。**启动应用和运行 IT 前都显式设置
  `DEVICE_SERVICE_BASE_URL`**。下文选 8081；已有实例若在 8080，应统一替换并复用该实例。
- IT 地址优先级：JVM 属性 `device.service.base-url` → 环境变量 `DEVICE_SERVICE_BASE_URL`
  → 8081 缺省值。`SIMULATOR_BASE_URL` 只是测试基类的 Java 常量名，不是另一个环境变量。
- 应用默认端口 8180。客户端脚本变量不会改变运行中的应用配置；修改配置后须重启验证应用。
  不要假定 `.env` 会被 Maven 自动加载。
- 当前 LLM 默认 `real`，下文显式设置 `LLM_MODE=mock`，无需模型凭据。
  本期售后使用固定 mock 数据，`RAG_EMBEDDINGS_ENABLED=false` 使用 MySQL 知识检索，
  不需要向量实例。`AUTH_ISSUER_URI` 不参与当前 Redis 登录令牌校验，不是必填项。

## 2. 现有构建与自动化验证

```powershell
.\mvnw.cmd verify
if ($LASTEXITCODE -ne 0) { throw '默认验证失败，请检查 target/surefire-reports' }
```

默认执行单元和契约测试，使用 JUnit、Mockito、MockMvc、客户端协议替身及售后 WireMock，
排除 `docker` 标签；不要求本地应用、数据库或真实模型已启动。它不能证明真实设备端到端流程通过。

先准备真实模拟器，再运行集成测试。已有可用实例时跳过 Compose 启动命令：

```powershell
$env:DEVICE_SERVICE_BASE_URL = 'http://localhost:8081'
# 仅在使用 Compose 提供模拟器且镜像已准备好时执行。
docker compose up -d device-simulator
if ($LASTEXITCODE -ne 0) { throw '模拟器启动失败' }
curl.exe --fail --silent --show-error "$env:DEVICE_SERVICE_BASE_URL/healthz"
if ($LASTEXITCODE -ne 0) { throw '模拟器健康检查失败' }
.\mvnw.cmd verify -Pit
if ($LASTEXITCODE -ne 0) { throw '集成验证失败，请检查 target/surefire-reports' }
```

`src/test/java/com/chh/autosense/integration/AbstractIntegrationIT.java` 自行启动
Testcontainers MySQL 8.0.18、Redis Stack，并使用随机应用端口，无需另起 AutoSense
或 Compose MySQL/Redis。基类强制 LLM mock、售后 mock、dev 身份开启；设备 HTTP
连真实模拟器。夹具先在模拟器创建设备，再由 AutoSense 按 SN 绑定，最后按返回 ID 清理模拟器设备。

当前四个 IT 类包含 15 个场景：自动修复及相关分支 6 个、人工/售后 4 个、用户管理 4 个、
SN 并发绑定 1 个。`-Pit` 匹配 `*Test.java` 和 `*IT.java`，不包含复数命名的
`AutoSenseApplicationTests.java`。报告数量以实际执行时的 `target/surefire-reports` 为准。
真实模型、记忆与租约等专项见第 7 节，不能由当前 IT 通过推断这些能力已完成。

## 3. 启动手动验证应用（终端 A）

使用独立的本地验证数据库和 Redis。新建 Compose 默认开发实例可使用下面的连接配置；
已有实例应替换为实际验证连接，不要指向业务数据库。等待依赖就绪再启动应用。

```powershell
# 仅在需要本仓库 Compose 提供 MySQL/Redis 时执行。
docker compose up -d mysql redis
if ($LASTEXITCODE -ne 0) { throw '数据库或 Redis 启动失败' }
$env:MYSQL_URL = 'jdbc:mysql://localhost:3306/autosense?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:MYSQL_USERNAME = 'autosense'
$env:MYSQL_PASSWORD = 'autosense' # 仅对应 Compose 新建的本地开发账号
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''
$env:DEVICE_SERVICE_BASE_URL = 'http://localhost:8081'
$env:LLM_MODE = 'mock'
$env:AFTERSALES_MOCK_ENABLED = 'true'
$env:RAG_EMBEDDINGS_ENABLED = 'false'
$env:AUTH_DEV_MODE = 'false'
.\mvnw.cmd spring-boot:run
```

等待应用成功启动。新库由 `src/main/resources/schema.sql`、`data.sql` 初始化；
旧库须先完成第 8 节检查。这里关闭 dev 身份，以真实注册/登录令牌完成下面的验证。

## 4. 准备用户与设备（终端 B）

JSON 请求体先编码为 UTF-8 字节，避免 Windows 原生命令参数转义丢失引号。
不要在日志中输出登录令牌、请求密码或完整登录响应。

### 4.1 注册并登录两个新用户

```powershell
$appBaseUrl = 'http://localhost:8180'
$simulatorBaseUrl = 'http://localhost:8081'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$accountA = "qs_a_$suffix"
$accountB = "qs_b_$suffix"
$testPassword = 'Quickstart123' # 仅用于本次新建的验证账号
$users = @{}
foreach ($account in @($accountA, $accountB)) {
    $registerJson = @{
        userAccount = $account
        userPassword = $testPassword
        confirmPassword = $testPassword
    } | ConvertTo-Json
    $registered = Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri "$appBaseUrl/api/v1/users/register" `
        -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($registerJson))
    if ([int]$registered.StatusCode -ne 201) { throw '注册未返回 201' }
    $user = $registered.Content | ConvertFrom-Json
    $loginJson = @{ userAccount = $account; userPassword = $testPassword } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/v1/users/login" `
        -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($loginJson))
    $users[$account] = @{ Id = $user.id; Token = $login.token }
}
$tokenA = $users[$accountA].Token
$tokenB = $users[$accountB].Token
$headersA = @{ Authorization = "Bearer $tokenA" }
$headersB = @{ Authorization = "Bearer $tokenB" }
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/users/me" -Headers $headersA
```

期望注册 201，昵称等于账号、角色为 user，注册和 `/users/me` 均无密码或散列。
重复账号应为 `409 ACCOUNT_EXISTS`；两次密码不一致、账号/密码格式不符应为 400；
错误密码与不存在的账号均为 401。字段和边界见 `contracts/user-api.md`。

### 4.2 模拟器创建 → SN 发现 → AutoSense 绑定

```powershell
$createDeviceJson = @{
    device_type_code = 'LITE'
    device_model_code = 'LA001'
    quantity = 1
    name = "sim-$suffix"
} | ConvertTo-Json
$created = Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri "$simulatorBaseUrl/api/v1/devices" `
    -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($createDeviceJson))
if ([int]$created.StatusCode -ne 201) { throw '模拟器创建未返回 201' }
$simulator = $created.Content | ConvertFrom-Json
$sn = $simulator.sn
$simulatorId = $simulator.id
if ($sn -cnotmatch '^[A-Z0-9]{4}[0-9]{9}$' -or -not $simulatorId) { throw '创建响应缺少有效 ID/SN' }
$lookup = Invoke-RestMethod -Uri "$simulatorBaseUrl/api/v1/devices/by-sn/$sn"
if (-not $lookup.exists) { throw '模拟器设备尚不可发现' }
$beforeBind = @((Invoke-RestMethod -Uri "$simulatorBaseUrl/api/v1/devices").devices).Count
$bindJson = @{ sn = $sn; name = '客厅灯' } | ConvertTo-Json
$bound = Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri "$appBaseUrl/api/v1/devices" -Headers $headersA `
    -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($bindJson))
if ([int]$bound.StatusCode -ne 201) { throw '绑定未返回 201' }
$device = $bound.Content | ConvertFrom-Json
$afterBind = @((Invoke-RestMethod -Uri "$simulatorBaseUrl/api/v1/devices").devices).Count
if ($afterBind -ne $beforeBind) { throw '绑定期间模拟器设备数量变化，请排查是否有并行创建' }
$device
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/devices" -Headers $headersA
```

期望 `name=客厅灯`，`simulatorName` 保留模拟器名称，类型/型号编码和 ID 来自上游，
`supported=true`、`online=true`。添加接口不创建第二台模拟器设备。只保存稳定元数据；
响应不含上游 `state`、`running_status`、模拟器时间戳。`online` 为不落库的在线投影，
列表探测失败或 `exists:false` 时为 false，不等于诊断结果。

设备错误使用普通 HTTP 状态码和 JSON。负向请求会使 PowerShell Web Cmdlet 抛出 HTTP 错误；
在 `catch` 中读取 `[int]$_.Exception.Response.StatusCode` 和 `$_.ErrorDetails.Message`，
再按契约检查 code/message。

| 输入/准备 | 期望 |
| --- | --- |
| A 或 B 再次提交同一 `$sn` | 409 `DEVICE_ALREADY_BOUND`；不披露归属，最终仅一条绑定 |
| `lite123456789` | 400 `BAD_REQUEST`；不请求模拟器 |
| `ZZZZ000000000` | 404 `DEVICE_NOT_FOUND`，“设备不存在或不在线”；无写入 |
| 另建但未绑定的设备，先 stop 再绑定 | 同上 404；已绑定 SN 会先命中重复预检 |
| 独立验证应用指向不可用模拟器，提交未绑定的合法 SN | 503 `DEVICE_SERVICE_UNAVAILABLE`，“设备服务暂不可用，请稍后重试”；无写入 |
| 上游完整 `exists:true` 但型号未支持 | 仍可绑定、`supported=false`；当前由协议/契约测试构造，不能用不存在的 SN 代替 |

## 5. 自动修复、SSE 与其他分支

### 5.1 亮度 3 → 确认 → 亮度 80

模拟器亮度合法范围为 1–100。使用 3 命中 `brightness < 5` 规则；亮度 0 无效，
stop 会产生设备不可达，不能作为本场景的替代准备。

```powershell
$faultJson = @{ command = 'set_brightness'; parameters = @{ brightness = 3 } } |
    ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri "$simulatorBaseUrl/api/v1/devices/$simulatorId/commands" `
    -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($faultJson))
$problemJson = @{ problem = '客厅的灯太暗了，几乎看不见' } | ConvertTo-Json
$firstResponseTimer = [Diagnostics.Stopwatch]::StartNew()
$createResponse = Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri "$appBaseUrl/api/v1/sessions" -Headers $headersA -TimeoutSec 180 `
    -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($problemJson))
$firstResponseTimer.Stop()
$createResponse.Content
$sessionMatch = [regex]::Match([string]$createResponse.Content, '"sessionId"\s*:\s*(\d+)')
if (-not $sessionMatch.Success) { throw 'SSE 未返回 sessionId，请先检查 error 事件' }
$sessionId = [long]$sessionMatch.Groups[1].Value
$firstResponseTimer.Elapsed
```

期望 HTTP 200、Content-Type 为 `text/event-stream`，状态到达 `CONFIRMING_REPAIR`，
出现 `awaiting` 后闭流。确认流内确实提供本次修复方案，再提交确认；此时 `/data` 亮度仍须为 3。
若出现多候选，按契约先选择该设备，不能跳过选择。新注册 A 仅有本次设备时通常直接进入修复确认。

```powershell
Invoke-RestMethod -Uri "$simulatorBaseUrl/api/v1/devices/$simulatorId/data"
$confirmJson = @{ content = '确认执行'; confirmRepair = $true } | ConvertTo-Json
$confirmed = Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri "$appBaseUrl/api/v1/sessions/$sessionId/messages" -Headers $headersA -TimeoutSec 180 `
    -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($confirmJson))
$confirmed.Content
$postState = Invoke-RestMethod -Uri "$simulatorBaseUrl/api/v1/devices/$simulatorId/data"
if ($postState.brightness -ne 80) { throw '模拟器亮度未恢复为 80' }
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/sessions/$sessionId" -Headers $headersA
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/sessions/$sessionId/messages" -Headers $headersA
```

期望 `REPAIRING`、`VERIFYING`、`conclusion.type=FIXED`，GET 可查结论、PRE/POST 状态和记录。
客户端 `TimeoutSec 180` 只是命令的等待上限，不代表服务已满足 SC-001；应记录实际首诊断耗时。
30 秒单次模型调用和 120 秒总预算仍是第 7 节的实施目标。

### 5.2 HTTP 错误与流内错误

认证、参数、GET 越权等在接受请求前失败时，检查 HTTP 401/400/403 和 JSON。
SSE 建立后的设备忙、不支持或不可达，检查 **`event:error` 的 code**，随后应闭流；
不能因 HTTP 200 就判定业务成功，也不能要求这些响应的 HTTP 状态一定是 409/422。
`Invoke-WebRequest` 在闭流后显示完整文本，适合核对事件，但不能证明 token 到达时序。

观察逐段输出时使用 `curl.exe --no-buffer`，从 UTF-8 临时请求文件发送 JSON：

```powershell
New-Item -ItemType Directory -Path 'target/quickstart' -Force | Out-Null
$requestFile = Join-Path $PWD.Path 'target/quickstart/sse-request.json'
$answerJson = @{ problem = '智能灯泡一般能用多久？' } | ConvertTo-Json
[IO.File]::WriteAllText($requestFile, $answerJson, $utf8)
curl.exe --no-buffer --show-error -X POST "$appBaseUrl/api/v1/sessions" `
    -H "Authorization: Bearer $tokenA" -H 'Content-Type: application/json; charset=utf-8' `
    --data-binary "@$requestFile"
```

`target/quickstart/sse-request.json` 为临时验证产物，不是配置或设计源码。
mock 验证事件协议；真实模型的逐段、延迟和错误行为须由第 7 节协议替身专项验证。
断线不补发，取得 sessionId 后使用完整 `/api/v1/sessions/{id}` 路径补查。

### 5.3 可复现分支与覆盖边界

复用上述 UTF-8 请求方式，为每个新会话记录实际 sessionId。

| 场景/准备 | 应检查的结果 |
| --- | --- |
| 修复前设亮度 4，提交 `confirmRepair:false` | 保持 4，无写命令；当前 `AutoRepairFlowIT` 有覆盖 |
| 为 A 再建并绑定第二台灯，再描述“灯太暗了” | `DEVICE_CONFIRMING` 与 `awaiting`；选定后仍需修复确认 |
| 恢复亮度 80，LA001 发送 `set_color_temperature`、参数 `color_temperature:6500`，新问题“灯的颜色不太对，想调整一下” | `GUIDED_MANUAL`、`UNFIXED_MANUAL_GUIDE`、分步指引；色温保持 6500，当前 `ManualGuideIT` 有覆盖 |
| “杭州市哪有售后网点” | 独立售后路由，`AFTERSALES_PROVIDED` 和固定网点；不执行设备操作 |
| “附近有售后网点吗”，补充“上海”或“火星” | 先 `AWAITING_LOCATION`；分别给固定网点或官方客服，不编造地点 |
| “我的路由器坏了，完全连不上网” | mock 可识别类型并返回 `error.code=UNSUPPORTED_DEVICE_TYPE`；“扫地机器人”当前会追问类型，不用它替代 |
| 绑定后调用模拟器 `POST /api/v1/devices/{id}/stop`，再描述灯不亮 | `error.code=DEVICE_UNREACHABLE`，GET 可见失败状态；后续需要时再 start |
| “智能灯泡一般能用多久？” / “LA001 支持彩色吗？” | `token` 与 `ANSWERED`；后一问题避免“调”字，以免 mock 命中设备操作 |
| “帮我看看那个” | `CLARIFYING` 与 `awaiting`，不探测或操作设备 |

模拟器没有通用 `hardware_fault`/`error` 注入接口，不能把任意字段写入当作可执行前置。
规则引擎 AFTERSALES 判断、命令失败不重试已有单元覆盖，但完整“失败→复检→人工/售后”
及真实 HTTP 失败的端到端证据仍须补充，见第 7 节。

## 6. 两账号隔离、管理员与注销

使用 B 检查 A 的资源隔离：

```powershell
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/devices" -Headers $headersB
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/sessions" -Headers $headersB
try {
    Invoke-WebRequest -UseBasicParsing -Uri "$appBaseUrl/api/v1/sessions/$sessionId" -Headers $headersB
} catch {
    [int]$_.Exception.Response.StatusCode # 期望 403
    $_.ErrorDetails.Message
}
```

B 的列表不能含 A 的设备/会话；直接读取 A 的会话应为 403，追加消息也须拒绝。
B 重复绑定 A 的 SN 应为 409，与 A 自己重复绑定语义一致。普通用户访问
`GET /api/v1/admin/users` 为 403，未登录访问受保护接口为 401。

管理员验证前，由验证环境提供**已有且受控的管理员账号**；不能通过注册指定 admin，
也不为验证修改已有用户角色或重置他人密码。初始化种子边界见 `research.md` R23。

```powershell
$adminCredential = Get-Credential -Message '输入验证环境已有的管理员账号'
$adminLoginJson = @{
    userAccount = $adminCredential.UserName
    userPassword = $adminCredential.GetNetworkCredential().Password
} | ConvertTo-Json
$adminLogin = Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/v1/users/login" `
    -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($adminLoginJson))
$adminHeaders = @{ Authorization = "Bearer $($adminLogin.token)" }
Invoke-RestMethod -Uri "$appBaseUrl/api/v1/admin/users?keyword=$accountB" -Headers $adminHeaders
$disableJson = @{ disabled = $true } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$appBaseUrl/api/v1/admin/users/$($users[$accountB].Id)/status" `
    -Headers $adminHeaders -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes($disableJson))
```

期望仅禁用本次新建 B 账号，返回 200；B 原 token 访问 `/users/me` 为 401，重新登录也为 401。
然后用同一管理员、同一路径发送 `{disabled:false}`，恢复后 B 可重新登录，旧 token 仍无效。
管理员不得禁用自己，所有管理响应不得包含密码。令牌索引过期和禁用竞态见第 7 节；
一次普通禁用成功不能证明这些边界通过。

完成 A 的设备和会话验证后注销：

```powershell
$logout = Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri "$appBaseUrl/api/v1/users/logout" -Headers $headersA
if ([int]$logout.StatusCode -ne 204) { throw '注销未返回 204' }
try {
    Invoke-WebRequest -UseBasicParsing -Uri "$appBaseUrl/api/v1/users/me" -Headers $headersA
} catch {
    [int]$_.Exception.Response.StatusCode # 期望 401
    $_.ErrorDetails.Message
}
```

dev 兼容性另在开启 `AUTH_DEV_MODE=true` 的本地配置验证：`Bearer user-{id}` 只有普通用户权限，
真实 token 优先。dev token 不代表登录签发记录，不能用于证明注销链路。

## 7. 待实现后的专项验收目标

下列设计尚有代码缺口，不能将当前 `verify`/`-Pit` 通过作为完成依据。
实施时在现有单元/契约/集成测试目录补充对应测试，再用第 2 节命令运行并记录具体类、断言和结果。
本文不虚构尚未落地的测试类名或运行开关。

| 目标与依据 | 验证准备与通过条件 |
| --- | --- |
| AI Service 真实模式协议，R3/R10/R13/R18 | 本地可记录请求的模型 HTTP 协议替身覆盖真实 LangChain4j Bean，使用已有 `LLM_MODE=real`、`LLM_BASE_URL`、测试 `LLM_API_KEY`、`LLM_MODEL_NAME`。分类 enum/结构化对象非法时澄清或明确失败，不触达设备；确认工厂创建的 AI Service 被调用。现有 IT 基类强制 mock，单改环境变量不会使它覆盖真实模式。 |
| 超时、流式错误和总预算，R18/R29 | 协议替身产生延迟 chunk、错误、超时及迟到回调；目标单次模型调用上限 30 秒、`maxRetries=0`、首诊断总预算不超过 120 秒，错误/等待/终态后闭流，迟到响应不改状态或发写命令。这些预算控制尚未落地，不能用当前 `LLM_TIMEOUT_SECONDS` 一项宣称满足，也不提供不存在的总预算开关。 |
| 最近 20 条历史，R15 | 准备超过 20 条有序可识别消息，捕获分类、分析和直答的实际模型输入：此前最近 20 条历史且当前输入恰好一次。热/冷缓存、Redis 过期及应用重启结果一致；内部 JSON/提示/检索片段不污染共享历史，MySQL 长期记录完整有序；缓存操作仅限专项测试实例。 |
| 完整用户可见结论留存，R15/R29 | 终态前把摘要和完整 manualSteps，或 afterSales 名称/地址/电话写入 ASSISTANT 长期消息；新轮清理当前 conclusion_extra 后旧详情仍能从消息历史读取，处于最近 20 条时也必须进入模型上下文。售后后续聊“刚才网点电话是什么”，核对返回既有记录而非重新编造。无需新表。 |
| 锁到期与双确认，R6/R29 | 隔离 Redis、可控时间夹具验证同设备跨会话并发、同会话双确认仅执行一次；常识消息也串行。锁/上下文到期或 owner 被替换后旧确认不得执行，须重新定位、探测并请求新确认。旧 owner 不得续期或释放新锁；执行中租约失效停止后续命令，已执行部分如实记录并尽可能复检。 |
| 令牌与用户索引双 TTL，R19 | 测试夹具缩短有效期，检查 token 与用户 Set 原子签发、校验及双 TTL 续期；Set 缺失/缺成员拒绝残留 token，不得自动重建索引。覆盖禁用/启用与登录并发、Redis 失败、重新登录创建新 Set 后旧 token 重用，均不得恢复旧授权。 |
| 多轮状态与按轮快照，R17/R29 | 同一线程完成两轮不同 PRE/POST 的修复；第二轮独立问题记录、结论、待执行方案和轮次。目标续聊从终态先进入 ANALYZING，再按新意图分流；当前直接 ROUTING 须修改。GET 与数据库断言只引用本轮快照，旧授权/结论不残留；状态与审计原子一致，型号拒识及复检不可达等边合法并有日志。 |
| 知识检索与向量开关，R4 | `RAG_EMBEDDINGS_ENABLED=false` 下验证 knowledgeRef、设备类型、Top-K、空查询；无命中不得随意选第一条。目标设 true 时启动失败并明确提示未实现；当前 true 仍回退，须修正后验收，本期不部署向量索引。 |
| 失败即停与 SSE 断线，R17/R18/R29 | 真实设备路径验证成功流程；受控失败夹具补充命令/中途失败→不重试→复检→真实原因/人工或售后。断线后 GET 可查状态，服务不补发历史 SSE，也不因迟到回调发起新写操作。 |

SC-001 应记录设备可达时提交问题到首个诊断结果的实际耗时，并覆盖慢依赖；
用户手工等待确认的时间和客户端超时参数不作为服务预算证明。
SC-002 的 70% 自动修复率已由规格澄清为本期不量化验收；
SC-007 用户完成率需要用户验证记录，不能由单个 mock 冒烟推断。

## 8. 旧库迁移与验证产物

- 新库使用 `src/main/resources/schema.sql`；`CREATE TABLE IF NOT EXISTS` 不升级旧表。
- 对照 `research.md` R25 评估 `scripts/migration/20260904-device-sn-binding.sql` 的旧表前置条件。
  先备份，在独立迁移验证库复制适用旧结构和代表数据，再由实施阶段执行脚本；本指南不执行迁移。
- 检查记录数、SN 格式/大小写、类型/型号和 ID 回填、唯一键、旧列处理；不可可靠映射的数据须明确中止。
  原模拟器名称不能从用户显示名可靠还原时，不得宣称无损回填。
  禁止用 `spring.sql.init.continue-on-error` 跳过失败。
- 记录实际创建的模拟器 ID、测试账号和会话 ID，清理只处理本次夹具。
  模拟器删除不自动删除 AutoSense 历史绑定或用户记录；使用独立验证库管理这些数据，
  不全库清理、不删除他人的模拟器设备。IT 夹具自身按返回 ID 清理模拟器设备。
- 保存当次 Maven 命令、环境版本、报告路径、场景结果及未验证项；
  `target/quickstart/`、`target/surefire-reports/` 为验证产物，不提交令牌或密码。
  源码引用使用 `src/main/java/com/chh/autosense/`、`src/test/java/com/chh/autosense/`，
  外部接口文档使用 `documents/` 路径。

## 9. 验收对照

| 场景 | 规格与设计 |
| --- | --- |
| SN 发现/绑定、错误与并发唯一性 | FR-020/015，SC-009，R16/R24–R26 |
| 亮度修复、多候选、确认与拒绝 | FR-001–009，SC-001/003/004，R2/R5/R12 |
| 人工指引、售后与官方客服 | FR-010/011/019，SC-005，R8/R14 |
| 不可达、失败即停、互斥及失效确认 | FR-014/016/017，R6/R17/R29 |
| SSE、路由、历史与按轮追溯 | FR-013/018/019/021，R13/R15/R17/R18/R29 |
| 注册/登录/注销、隔离、管理与索引失效 | FR-015/022–027，SC-008，R7/R19–R23 |
| 新章程目录与实现归位 | R3/R27/R28；按 plan 迁移范围检查导入和分层，不能只检查目录存在 |

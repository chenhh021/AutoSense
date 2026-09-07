<!--
Sync Impact Report
- Version change: 2.2.0 → 2.3.0
- Rationale: 新增 AI Service 提示词资源化与注解加载要求,实质性扩充 AI 开发规范。
- Modified principles: IV. AI 集成规范(LangChain4j)补充提示词资源化约束;同步项目资源目录和评审要求。
- Added sections: AI Service 提示词规范。
- Removed sections: 无。
- Follow-up TODOs: 无未填写占位符;既有内联提示词迁移、资源校验及相关 plan/tasks 更新在后续功能工作流中落实。
-->

# AutoSense Constitution

## Core Principles

### I. Java 21 基线

所有代码 MUST 基于 Java 21 编写与编译;构建工具链、CI 环境和生产运行时 MUST
统一使用 Java 21。鼓励合理使用 Java 21 特性(record、sealed 类型、pattern matching、
虚拟线程等),但 MUST NOT 引入需要预览特性(`--enable-preview`)或非标准 API 的代码。

Rationale: 统一语言基线避免多版本 JVM 带来的构建与运行时不一致,同时利用 LTS
版本的现代特性提升代码表达力。

### II. Spring Boot 框架规范

基础框架 MUST 使用 Spring Boot 3.5.3(对应 Spring Framework 6.x)。依赖注入
MUST 使用构造器注入;配置类 MUST 通过 `@ConfigurationProperties` 或 `@Value`
从配置文件读取;MUST NOT 绕过 Spring 容器手动 `new` 受管 Bean。新增第三方
starter 或升级 Spring Boot 版本 MUST 先评估与既有依赖的兼容性。

Rationale: 固定框架基线并遵循容器约定,保证依赖装配可预测、可测试。

### III. 数据访问纪律

关系型数据库 MUST 使用 MySQL,持久层 MUST 使用 MyBatis-Flex,SQL 映射与实体
定义 MUST 遵循 MyBatis-Flex 约定;MUST NOT 在业务代码中直接拼接 SQL 字符串。

缓存 MUST 使用 Redis,并 MUST 为所有缓存键设置明确的过期时间与命名规范
(如 `业务域:实体:标识`);MUST NOT 将 Redis 当作持久化存储使用。

Rationale: 统一 ORM 与缓存技术选型,保证数据访问层可审查、可维护,防止
SQL 注入与缓存雪崩类问题。

### IV. AI 集成规范(LangChain4j)

所有大模型与 AI 能力调用 MUST 通过 LangChain4j 框架进行;MUST NOT 在业务代码中
直接调用模型厂商的原生 HTTP/SDK 接口。模型提供商、模型名称、温度等参数 MUST
通过配置文件注入(见原则 V),便于切换模型与调整参数。

AI Service 使用的固定提示词 MUST 独立存放为资源文件,系统提示词 MUST 通过
`@SystemMessage(fromResource = "...")` 加载,具体遵循 AI Service 提示词规范。

Rationale: 通过 LangChain4j 抽象层隔离模型厂商差异,降低供应商锁定风险。

### V. 配置外部化(不可协商)

所有外部服务调用(数据库、Redis、大模型 API、第三方 HTTP 服务等)的连接信息与
凭据 MUST 从 `application.yaml` 等配置文件导入,并由 `@ConfigurationProperties`
承载;MUST NOT 在源代码中硬编码任何地址、端口、密钥或令牌;真实凭据 MUST NOT
提交进版本库(MUST 使用环境变量、占位符或密钥管理服务注入)。

Rationale: 配置外置是环境可移植性与凭据安全的基本要求,硬编码凭据是最高危的
泄漏来源之一。

## 技术栈约束

| 领域 | 选型 | 约束 |
| --- | --- | --- |
| 语言 | Java 21 | 见原则 I |
| 框架 | Spring Boot 3.5.3 | 见原则 II |
| 关系型数据库 | MySQL | 通过 MyBatis-Flex 访问,见原则 III |
| ORM | MyBatis-Flex | 见原则 III |
| 缓存 | Redis | 必须设置 TTL 与键命名规范,见原则 III |
| AI 框架 | LangChain4j | 见原则 IV |
| 日志 | SLF4J + Log4j 2 | 统一门面与实现,日志文案使用英文,见日志规范 |
| 配置管理 | `application.yaml` + `@ConfigurationProperties` | 见原则 V |

以上选型为约束性要求;替换任一组件 MUST 通过章程修订流程(见 Governance)。

## 项目结构与分层规范

项目采用同仓库前后端分离结构,后端基包 MUST 为 `com.chh.autosense`。
主要目录及职责如下:

```text
AutoSense/
├── src/main/java/com/chh/autosense/  # Spring Boot 后端代码
├── src/main/resources/              # 应用配置、数据库脚本及提示词资源
│   └── prompt/                     # AI Service 固定提示词与模板
├── src/test/java/                   # 后端自动化测试
├── frontend/                       # Vue 前端工程
├── scripts/                        # 项目脚本及数据库迁移脚本
├── specs/                          # 各功能的规格、设计、任务及接口契约
├── documents/                      # 项目说明与补充文档
└── .specify/                       # Spec Kit 章程、模板及工作流脚本
```

前端页面、组件、布局、路由、状态及工具分别放置于 `frontend/src/` 下的
`pages/`、`components/`、`layouts/`、`router/`、`stores/` 和 `utils/`;
API 客户端位于 `api/`,HTTP 请求配置位于 `request.ts`。
`target/`、`frontend/dist/` 为构建产物,`frontend/node_modules/` 为安装依赖,
MUST NOT 将这些目录作为手工维护的源码目录。

后端生产代码 MUST 按职责放置于以下目录:

```text
src/main/java/com/chh/autosense/
├── controller/                     # HTTP/API 入口
├── service/                        # 应用服务
│   ├── user/                       # 用户与令牌服务
│   └── knowledge/                  # 维修知识服务
├── core/                           # 核心业务能力与流程编排
│   ├── aftersales/                 # 售后服务接入与引导
│   ├── analysis/                   # 问题分析与诊断推理
│   ├── device/                     # 设备注册与适配
│   │   ├── adapter/                # 设备适配器实现
│   │   ├── client/                 # 外部设备服务客户端
│   │   ├── rule/                   # 故障规则判定
│   │   └── spi/                    # 设备适配扩展契约
│   ├── repair/                     # 修复动作执行
│   ├── routing/                    # 意图路由与直接回答
│   ├── security/                   # 认证、授权与安全模块配置
│   └── session/                    # 会话编排与上下文
│       ├── memory/                 # 对话记忆
│       └── statemachine/           # 会话状态机
├── mapper/                         # MyBatis-Flex 数据访问接口
├── domain/
│   ├── dto/                        # 请求与响应等数据传输对象
│   ├── entity/                     # 持久化实体
│   ├── enums/                      # 业务枚举
│   ├── message/                    # SSE 事件与流封装
│   └── vo/                         # VO 对象
├── ai/
│   ├── factory/                    # LangChain4j AI Service 创建工厂
│   ├── model/                      # LangChain4j 相关模型与结构化输出对象
│   │   └── enums/                  # AI 输出分类等枚举
│   └── tools/                      # AI 工具抽象与实现
├── common/                         # 公共响应与全局异常处理
├── exception/                      # 自定义异常与错误码
├── config/                         # 通用配置与配置属性绑定
├── constant/                       # 全局常量
└── utils/                          # 通用工具类
```

目录职责:

- HTTP/API 入口 MUST 放置于 `controller/`,负责请求校验、调用业务入口及响应转换。
- 用户、知识等应用服务 MUST 按业务域放置于 `service/`。
  诊断、修复、设备、路由、售后、安全及会话等核心能力 MUST 放置于对应的 `core/` 子包。
  核心模块内部的 Service MUST 留在所属模块,例如 `core/device/DeviceRegistryService`。
- MyBatis-Flex 数据访问接口 MUST 放置于 `mapper/`,数据库实体 MUST 放置于
  `domain/entity/`。`mapper/` MUST NOT 用于放置 DTO/VO 对象转换逻辑。
- DTO MUST 放置于 `domain/dto/`,VO MUST 放置于 `domain/vo/`;
  业务枚举 MUST 放置于 `domain/enums/`,SSE 事件与流封装放置于 `domain/message/`。
- LangChain4j AI Service 的创建工厂 MUST 放置于 `ai/factory/`。
  LangChain4j 相关模型定义、AI 结构化输出对象 MUST 放置于 `ai/model/`,
  AI 路由产出的分类枚举等 MUST 放置于 `ai/model/enums/`。
  AI 输出契约与业务枚举 MUST 按用途区分,业务枚举仍归 `domain/enums/`。
- AI 工具抽象与实现 MUST 放置于 `ai/tools/`,例如现有 `BaseTool`。
- 公共错误响应与全局异常处理器 MUST 放置于 `common/`;
  自定义异常与错误码 MUST 放置于 `exception/`。
- 通用 Spring 配置及配置属性绑定类 MUST 放置于 `config/`;
  安全模块专属配置 MAY 放置于 `core/security/`,与所属能力保持内聚。
- 全局常量 MUST 放置于 `constant/`,例如普通用户、管理员的权限等级常量。
  通用工具类 MUST 放置于 `utils/`,MUST NOT 在工具类中承载业务流程编排。

业务调用与持久化分层 MUST 遵循:

```text
Controller -> 业务服务或核心编排(service / core) -> Mapper -> 数据库
```

Controller MAY 调用 `service/` 中的服务或 `core/` 中的业务入口,
例如 `SessionOrchestrator`、`DeviceRegistryService`,MUST NOT 直接调用 Mapper。
`service/` 与 `core/` 中的业务组件可按职责协作,MUST NOT 引入循环调用依赖;
业务组件 MUST NOT 反向依赖 Controller,Mapper MUST NOT 依赖 Controller、Service 或核心编排。
AI、配置、公共类型与工具包按各自职责提供支持,不要求每次调用经过所有目录。

上述目录约定不表示每个目录都已有实现。新增类型 MUST 按用途归位,
MUST NOT 为填充空目录而创建没有业务需求的类。

新功能 MUST 遵循既有项目结构,MUST NOT 无理由创建另一套目录或分层方式。
确有必要偏离时,MUST 在 `plan.md` 中说明理由。

既有代码迁移说明:当前 `UserController` 直接访问 `UserMapper`,
部分 `*View` 仍位于 `domain/dto/`。本次章程修订仅调整规范;
后续维护相关功能时,MUST 在对应 `plan.md` 中记录迁移范围,将持久化访问收敛至业务服务,
并按 DTO/VO 的实际职责调整类型归属,保持既有 API 契约兼容。
既有 AI 输出类型也 MUST 按用途评估归属,不得仅因参与 AI 调用就迁移业务枚举。

## AI Service 提示词规范

AI Service 使用的固定提示词及模板 MUST 单独存放于 `src/main/resources/prompt/`。
该目录是本项目 Maven 资源目录下的 `prompt/`,运行时对应 classpath 的 `/prompt/`。

角色设定、任务规则、行为限制及项目编写的输出约束 MUST 放入系统提示词资源,
由 AI Service 方法上的 `@SystemMessage(fromResource = "...")` 引用。
MUST NOT 将这些内容硬编码在 Java 字符串、文本块、常量、注解 `value` 或工厂拼接逻辑中。

提示词文件 SHOULD 使用 UTF-8 的 `.txt` 或 `.md`,文件名 SHOULD 表达对应能力。
例如,文件 `src/main/resources/prompt/intent-router.txt` 的加载方式为:

```java
@SystemMessage(fromResource = "/prompt/intent-router.txt")
```

`fromResource` MUST 使用以 `/prompt/` 开头的 classpath 资源路径,
MUST NOT 填写 `src/main/resources/`、操作系统绝对路径或工作目录相对路径。
前导 `/` 表示 classpath 根目录,避免资源查找受 AI Service 接口所在包影响。
资源加载能力见 [LangChain4j AI Services 官方说明](https://docs.langchain4j.dev/tutorials/ai-services/#systemmessage);
当前 1.0.1 的 `SystemMessage` 定义及加载实现已按对应版本源码核对。

用户本轮输入、历史与检索结果属于运行时数据,通过方法参数及 `@UserMessage`、
`@V` 等参数绑定传入;MUST NOT 将其作为可信系统规则。
如需固定的用户消息包装模板,也 MUST 存放在同一资源目录,
使用 `@UserMessage(fromResource = "...")` 引用;系统规则仍按上述 `@SystemMessage` 方式加载。
资源中的模板变量 MUST 与方法参数绑定名称一致,不得通过 Java 字符串拼接替代固定模板。

构建 MUST 将提示词资源打包到 classpath,并保留模板变量与文本编码。
AI Service 创建/装配阶段 MUST 校验引用资源存在、可读取且内容非空;
失败时明确报告配置错误,MUST NOT 静默使用空提示词或硬编码默认内容继续调用模型。
资源检查不需要调用远程模型;MUST NOT 假定仅创建代理就已验证所有资源。

相关测试 MUST 验证实际 AI Service 加载资源后的消息内容与变量绑定,
并覆盖缺失或空资源的失败行为;仅检查文件存在或注解声明不足以证明接入完成。
提示词资源 MUST NOT 包含凭据,日志 MUST NOT 输出内部提示词正文。

既有实现迁移说明:当前 `config/LangChain4jConfig` 中仍有内联提示词。
后续维护相关功能时,MUST 在对应 `plan.md`、`tasks.md` 中安排资源拆分、
注解引用与加载验证,保持现有业务意图、AI 输出契约及用户输入/历史边界。

Rationale: 将提示词与 Java 实现分开维护,通过统一资源路径和注解加载明确调用关系,
使模板修改可审查、打包可验证,避免重复内联内容及路径差异导致行为不一致。

## 命名规范

代码 MUST 遵循标准 Java 命名约定:

- 类:`PascalCase`
- 方法与变量:`camelCase`
- 常量:`UPPER_SNAKE_CASE`
- 包:全小写

组件 SHOULD 使用明确的职责后缀:

```text
*Controller
*Service
*Mapper
*Orchestrator
*Factory
*Request
*Response
*View
*VO
*Exception
```

命名 SHOULD 表达业务含义。除非职责确实与名称一致,否则 SHOULD 避免
`Manager`、`Helper`、`Handler`、`Processor` 等含义模糊的名称。

## Controller 层规范

Controller MUST 仅负责 HTTP/API 层职责,包括:

- 接收请求;
- 校验请求 DTO;
- 调用应用 Service 或核心业务入口;
- 返回响应及正确的 HTTP 状态码。

Controller MUST NOT:

- 实现业务规则;
- 直接访问 Mapper;
- 管理数据库事务。

请求参数需要校验时 SHOULD 使用 Jakarta Bean Validation 与 `@Valid`。

## Service 与核心编排规范

`service/` 中的应用服务与 `core/` 中的业务服务、编排组件 MUST 按所属职责承担业务逻辑,
包括:

- 执行业务规则;
- 调用 Mapper;
- 协调 Mapper、应用服务与核心业务能力;
- 定义事务边界。

数据库事务 SHOULD 在承担该业务操作的 Service 或核心业务入口通过 `@Transactional` 声明。

依赖注入 MUST 遵循原则 II,使用构造器注入。

MUST NOT 仅为了形式统一而为每个 Service 创建 `Service` 接口和
`ServiceImpl` 实现类。仅当存在多个实现、需要替换实现或确有抽象价值时,
SHOULD 引入 Service 接口。

## Mapper 与 Entity 规范

Mapper MUST 仅承担持久化和数据访问职责,MUST NOT 包含应用业务规则。

数据访问 MUST 遵循原则 III,使用 MyBatis-Flex,接口放置于 `mapper/`。

Entity MUST 表示持久化或领域状态,并放置于 `domain/entity/`。

Entity SHOULD 使用 Lombok `@Getter` + `@Setter` 简化访问器代码,并仅保留持久化框架
实例化和业务创建所需的构造器。必要构造器 MAY 由 Lombok 生成或显式定义。

Entity SHOULD NOT 默认使用 `@Data`;如需 `equals`、`hashCode` 或 `toString`,
SHOULD 按实体的实际语义单独定义或选择对应注解,使对象行为保持明确。

Rationale: 分别声明访问器和必要构造器,减少样板代码,同时明确持久化实体的对象行为。

Entity SHOULD NOT 直接作为公共 API 的请求或响应模型暴露给客户端。

## DTO 与 VO 规范

公共 API 的输入和输出 SHOULD 使用 DTO 或用于展示的 VO。

请求与响应 DTO MUST 放置于:

```text
domain/dto/
```

VO 对象 MUST 放置于:

```text
domain/vo/
```

命名示例:

```text
CreateUserRequest
UpdateUserRequest
LoginRequest

UserResponse
LoginResponse
UserVO
```

MUST 按对象职责而非仅按类名后缀决定 DTO/VO 归属。
既有 `*View` 类型按项目结构章节中的迁移说明处理。

Entity MUST NOT 直接复用为请求 DTO。

DTO MUST 根据创建后是否需要修改数据明确选择可变或不可变形态:

- 可变 DTO SHOULD 使用 Lombok `@Data` 或 `@Getter` + `@Setter` 简化代码;
  仅需访问器时 SHOULD 选择 `@Getter` + `@Setter`,构造器按实际需要提供。
- 不可变 DTO SHOULD 优先使用 Java 21 `record`;存在明确的框架兼容或对象模型限制时,
  MAY 使用常规类,并在对应 `plan.md` 中说明原因。

既有对象改用 Lombok 或 `record` 时,MUST 验证序列化、字段校验和框架绑定的兼容性。
对象定义方式不改变 DTO、VO 与 Entity 的职责及目录归属。

Rationale: 按可变性选择对象形态,减少重复访问器和构造代码,使数据修改意图清晰。

数据格式和字段级校验 SHOULD 在 DTO 中完成。

依赖数据库状态或领域状态的业务校验 MUST 在应用服务或核心业务入口完成。

## 异常处理规范

业务失败 SHOULD 使用具有明确语义的自定义异常,自定义异常与错误码放置于 `exception/`。

REST API SHOULD 使用 `@RestControllerAdvice` 提供统一的全局异常处理,
全局异常处理器与公共错误响应放置于 `common/`。

Controller SHOULD NOT 重复编写异常到 HTTP 响应的转换逻辑。

API 错误响应 SHOULD 使用统一格式。

内部堆栈信息、凭据及其他敏感实现细节 MUST NOT 暴露给客户端。

## 日志规范

后端业务代码 MUST 统一通过 SLF4J 日志门面记录日志,运行时 MUST 使用 Log4j 2
作为日志实现。SHOULD 使用 Lombok `@Slf4j` 简化 Logger 声明;不使用 Lombok 的类
MAY 使用 `org.slf4j.LoggerFactory`。MUST NOT 使用 `System.out`、
`System.err` 或 `printStackTrace()` 替代应用日志。

日志依赖 MUST 与项目 Spring Boot 版本兼容,保持单一 SLF4J 日志提供者,
MUST NOT 同时装配冲突的日志实现或形成双向桥接。具体接入参考
[Apache Log4j 官方说明](https://logging.apache.org/log4j/2.x/manual/installation.html),
依赖调整与配置在对应 `plan.md` 中明确。

项目编写的日志消息模板、固定事件名称、字段名称和原因标签 MUST 使用英文。
需要保留的业务数据或第三方异常上下文按脱敏规则处理;日志语言约束不改变面向用户的响应语言。

以下关键位置 MUST 记录可追溯日志,涵盖实际发生的开始、结果、拒绝或失败:

- 关键业务操作入口与结束,包括账号状态变更、设备绑定和会话处理结果;
- 意图识别结果、能力分发、澄清及关键状态迁移;
- 身份或权限校验拒绝、参数校验失败、并发冲突与上下文失效;
- 大模型、知识检索与外部设备服务调用的结果、耗时、超时和异常;
- 设备控制请求的校验、确认、取消、过期、命令执行及复检结果;
- 未预期异常与最终失败处理。

日志 MUST 在上下文可用时携带请求或业务关联标识,例如 `requestId`、
`sessionId`、`messageId`、`round`、`userId`、`deviceId`。
涉及状态变迁或外部调用时,SHOULD 补充 `operation`、`fromState`、
`toState`、`result`、`errorCode`、`elapsedMs` 等实际适用字段。
MUST NOT 为补齐日志字段而伪造标识、成功结果或设备状态。

日志级别 MUST 按事件性质选择:

| 级别 | 使用范围 |
| --- | --- |
| ERROR | 未预期异常、关键依赖故障或无法完成的系统处理 |
| WARN | 可预期但需要关注的拒绝、冲突、超时或降级 |
| INFO | 关键业务开始/结果、状态迁移和已确认的设备操作 |
| DEBUG / TRACE | 排查问题所需的补充信息,生产默认关闭 |

预期的参数或权限拒绝 MUST NOT 一律记录为 ERROR。
同一异常堆栈 SHOULD 在负责最终处理的边界记录一次,避免各层重复打印;
需要诊断堆栈时 SHOULD 将脱敏后的异常对象作为日志参数,保留必要原因链。

日志 MUST 使用 SLF4J 参数化占位符,避免通过字符串拼接构造动态消息。
例如:

```java
log.info("Intent routed: sessionId={}, capability={}", sessionId, capability);
log.warn("Device access denied: userId={}, deviceId={}", userId, deviceId);
```

日志 MUST 遵循安全与配置规范,不得包含密码、登录令牌、API Key、连接凭据或内部提示词。
MUST NOT 直接打印完整请求/响应对象、完整用户对话或完整模型输入输出;
仅记录定位问题所需的脱敏摘要、标识与结果,并处理外部文本中的换行等控制字符。

日志级别、输出格式、输出位置以及文件滚动与保留策略(使用文件输出时)
MUST 通过配置管理。MUST NOT 在高频轮询、逐 token 推送或集合循环中逐项输出
INFO 日志;此类路径 SHOULD 汇总结果或使用受控的 DEBUG/TRACE 日志。

设备控制等业务审计 MUST 按相应功能规格持久化;运行日志 MUST NOT 替代确认记录、
执行记录或其他要求可靠保存的审计数据。

既有实现说明:当前代码已有 `@Slf4j`/SLF4J 用法,`pom.xml` 尚未显式接入 Log4j 2。
后续实施时 MUST 核对实际日志依赖、完成必要接入并按本规范调整日志位置与英文文案,
不得仅因已有 Logger 声明就认定日志规范已落实。

Rationale: 统一日志调用、实现和语言,以关键事件与关联标识支持排障和追溯,
同时控制重复输出、日志量与敏感信息暴露。

## 安全与配置规范

密码、Token、凭据、API Key 等敏感信息 MUST NOT:

- 硬编码在源代码中;
- 写入日志;
- 提交到版本库。

密码 MUST NOT 以明文形式存储。

环境相关配置 MUST 遵循原则 V,通过 Spring 配置文件、Profile、环境变量或
密钥管理服务提供。

所有授权规则 MUST 在服务端执行,MUST NOT 将客户端权限检查视为安全边界。

## 测试规范

关键业务逻辑 MUST 具有自动化测试。

以下场景 SHOULD 添加测试:

- 关键 Service 业务规则;
- 复杂数据访问逻辑;
- API 参数校验;
- 认证与授权逻辑;
- Bug 修复对应的回归场景。

测试 SHOULD 验证外部可观察行为,而不是过度依赖内部实现细节。

## 实现原则

实现 MUST 优先选择满足当前规格的最简单方案。

在没有明确需求的情况下 MUST NOT 引入不必要的:

- 抽象层;
- 接口;
- 设计模式;
- 第三方依赖;
- 扩展点;
- 基础设施组件。

开发新功能时 SHOULD 优先复用现有项目约定、组件和依赖。

实现功能时 MUST NOT 无关重构其他代码,除非该重构是完成当前规格所必需的。

MUST NOT 实现规格中没有要求的功能。

## Spec Kit 文档职责

项目级、长期有效的开发约束 MUST 放在本 Constitution 中。

各 Spec Kit 文档职责如下:

```text
Constitution
    ↓
spec.md
    ↓
plan.md
    ↓
tasks.md
    ↓
implementation
```

- Constitution:定义整个项目必须长期遵守的约束和原则。
- `spec.md`:定义功能需要实现什么(What)。
- `plan.md`:定义功能采用什么技术方案实现(How)。
- `tasks.md`:将实现方案拆分为可执行开发任务。
- implementation:按照上述约束与任务实现代码。

所有 Feature 的实现 MUST 遵守本 Constitution。

如果某项功能确有必要违反本章程中的 SHOULD/MUST 约束,MUST 在 `plan.md`
中明确记录偏离项、原因及影响。

## 开发工作流与质量门

- 新功能开发 MUST 遵循 Spec Kit 流程:先 `/speckit-specify` 产出规格,再
  `/speckit-plan` 与 `/speckit-tasks`,最后实现;MUST NOT 跳过规格直接编码。
- 每次提交前 MUST 通过 `./mvnw verify`(或等效构建)且测试全部通过。
- 代码评审 MUST 核对本章程原则的合规性,特别是配置外部化、分层依赖、
  数据访问和敏感信息处理要求。
- 代码评审 MUST 核对关键路径日志、英文文案、级别、关联标识与脱敏要求,
  并确认日志没有替代业务审计或引入重复输出。
- 代码评审 MUST 核对 AI Service 提示词资源目录、`fromResource` 引用、变量绑定与加载验证,
  并确认固定提示词未在 Java 代码中重复硬编码。
- 引入新依赖 MUST 说明理由并确认与本章程技术栈约束不冲突。

## Governance

本章程优先级高于其他开发惯例。

修订流程:

提出修改并说明理由 → 评审通过 → 更新本文件与 Sync Impact Report →
如影响既有代码,附迁移计划。

版本号遵循语义化版本:

- MAJOR:原则删除或不兼容重定义;
- MINOR:新增原则或实质性扩充;
- PATCH:措辞澄清与非语义修订。

所有 PR 与评审 MUST 验证章程合规性。

任何违反原则而引入的额外复杂性 MUST 给出书面理由,否则必须简化。

**Version**: 2.3.0 | **Ratified**: 2026-08-21 | **Last Amended**: 2026-09-07

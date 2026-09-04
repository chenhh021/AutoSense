<!--
Sync Impact Report
- Version change: (initial) → 1.0.0
- Rationale: 首次正式批准(initial ratification),替换全部模板占位符。
- Modified principles: 无(全部新增)
  - [PRINCIPLE_1_NAME] → I. Java 21 基线
  - [PRINCIPLE_2_NAME] → II. Spring Boot 框架规范
  - [PRINCIPLE_3_NAME] → III. 数据访问纪律
  - [PRINCIPLE_4_NAME] → IV. AI 集成规范(LangChain4j)
  - [PRINCIPLE_5_NAME] → V. 配置外部化(不可协商)
- Added sections: 技术栈约束、开发工作流与质量门、Governance
- Removed sections: 无(模板示例注释已按流程清理)
- Follow-up TODOs: 无
-->

# AutoSense Constitution

## Core Principles

### I. Java 21 基线

所有代码 MUST 基于 Java 21 编写与编译;构建工具链、CI 环境和生产运行时 MUST
统一使用 Java 21。鼓励合理使用 Java 21 特性(record、sealed 类型、pattern matching、
虚拟线程等),但 MUST NOT 引入需要预览特性(--enable-preview)或非标准 API 的代码。
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
| 配置管理 | `application.yaml` + `@ConfigurationProperties` | 见原则 V |

以上选型为约束性要求;替换任一组件 MUST 通过章程修订流程(见 Governance)。

## 开发工作流与质量门

- 新功能开发 MUST 遵循 Spec Kit 流程:先 `/speckit-specify` 产出规格,再
  `/speckit-plan` 与 `/speckit-tasks`,最后实现;MUST NOT 跳过规格直接编码。
- 每次提交前 MUST 通过 `./mvnw verify`(或等效构建)且测试全部通过。
- 代码评审 MUST 核对本章程五项原则的合规性,特别是原则 V 的配置外部化要求。
- 引入新依赖 MUST 说明理由并确认与本章程技术栈约束不冲突。

## Governance

本章程优先级高于其他开发惯例。修订流程:提出修改并说明理由 → 评审通过 →
更新本文件与 Sync Impact Report → 如影响既有代码,附迁移计划。版本号遵循语义化
版本:MAJOR 表示原则删除或不兼容重定义,MINOR 表示新增原则或实质性扩充,
PATCH 表示措辞澄清与非语义修订。所有 PR 与评审 MUST 验证章程合规性;任何
违反原则的复杂性 MUST 给出书面理由,否则必须简化。

**Version**: 1.0.0 | **Ratified**: 2026-08-21 | **Last Amended**: 2026-08-21

# 默认验证记录

- 时间：2026-09-04 02:34（Asia/Shanghai）
- 环境：Windows，OpenJDK 21.0.2，Spring Boot 3.5.3
- 命令：`mvn verify`
- 结果：成功
- 测试：85 个，失败 0，错误 0，跳过 0
- 构建产物：`target/AutoSense-0.0.1-SNAPSHOT.jar`

覆盖范围包括设备 API 契约、按 SN 查询客户端、设备绑定服务、会话与用户 API 契约，
以及既有规则、修复、令牌和用户服务单元测试。Docker 标签的真实依赖集成测试由
`integration.md` 单独记录。

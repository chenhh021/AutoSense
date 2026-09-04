# 真实依赖集成验证记录

- 时间：2026-09-04 02:38（Asia/Shanghai）
- 环境：Windows、OpenJDK 21.0.2、Docker Desktop 29.6.2
- MySQL：Testcontainers `mysql:8.0.18`
- Redis：Testcontainers `redis/redis-stack-server:latest`
- deviceSimulator：`http://localhost:8080`，健康检查 200；运行实例未暴露版本头，
  按仓库 `documents/新增接口说明-按SN查询设备.md` v1.0 契约验证
- 命令：`mvn -Ddevice.service.base-url=http://localhost:8080 verify -Pit`
- 结果：成功
- 测试：100 个，失败 0，错误 0，跳过 0；其中真实依赖集成场景 15 个

## 两步式冒烟与清理证据

1. 直接向 simulator 的 `POST /api/v1/devices` 创建 LA001 测试设备。
2. 通过 `GET /api/v1/devices/by-sn/{sn}` 查询到
   `exists=true`，本次样本为 id 74、SN `LITE135111399`。
3. AutoRepairFlowIT、ManualGuideIT、UserManagementIT 均执行“模拟器创建 →
   AutoSense 按 SN 绑定”，并验证显示名称、模拟器名称、类型/型号和诊断路径。
4. DeviceBindingConcurrencyIT 以 8 个并发请求绑定同一 SN，结果恰好一个 201、
   七个 409，MySQL 中仅一行。
5. 手工样本以精确返回的 id 删除，HTTP 204；模拟器运行设备数从 48 增至 49 后恢复为
   48，`cleanupRestoredCount=true`。集成测试创建的设备也由共享夹具逐项清理。

## 旧库迁移验证

在现有 MySQL 8.0.18 容器的临时数据库
`autosense_codex_migration_validation` 中创建旧版 device 表，并写入 LA001、LB001
各一行后执行迁移脚本。结果保留 2 行，统一回填 `device_type_code=LITE`，
型号为 `LA001,LB001`，类型/型号 ID 空值数为 0；`uk_device_sn` 唯一索引数量为 1，
旧列残留数量为 0。验证结束后临时数据库已删除。

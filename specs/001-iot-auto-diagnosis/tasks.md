# Tasks: 通过 SN 绑定已有模拟器设备

**Input**: Design documents from `/specs/001-iot-auto-diagnosis/`  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/diagnosis-api.md`, `quickstart.md`

**Tests**: 本功能的计划、研究结论和项目宪章均要求测试先行；每个用户故事阶段先编写并确认测试失败，再完成实现。

**Organization**: 仓库中的自动诊断、人工指引和账号体系已有实现。以下是“按 SN 绑定已有模拟器设备”的增量任务，替代旧版已完成任务清单；任务按用户故事组织，以便逐阶段验证和交付。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可在前置阶段完成后与同阶段其他标记任务并行，且不修改同一文件
- **[Story]**: 用户故事标签（`[US1]`、`[US2]`、`[US3]`）
- 每个任务描述均包含要修改或新增的精确文件路径

---

## Phase 1: Setup（共享测试准备）

**Purpose**: 建立所有故事共用的“两步式”真实模拟器测试夹具：先在模拟器创建设备，再通过 SN 绑定到 AutoSense。

- [X] T001 扩展 `src/test/java/com/chh/autosense/integration/AbstractIntegrationIT.java`，增加直接调用模拟器创建设备、提取 SN、按 SN 查询、停止/启动及清理测试设备的共享方法，并移除通过 AutoSense 注册接口隐式创建模拟器设备的夹具路径

---

## Phase 2: Foundational（阻塞所有用户故事）

**Purpose**: 建立 SN 持久化模型、外部查询边界、稳定错误模型和设备类型解析能力。

**⚠️ CRITICAL**: 本阶段完成前不得开始用户故事实现。

- [X] T002 更新新建库 DDL `src/main/resources/schema.sql`，将设备表调整为全局唯一且大小写敏感的 `sn` 及稳定模拟器元数据字段，并新增前向迁移脚本 `scripts/migration/20260904-device-sn-binding.sql`；迁移必须保留已有行、先报告无法可靠回填的旧数据且不得静默删除
- [X] T003 [P] 重构 `src/main/java/com/chh/autosense/domain/model/Device.java`，加入显示名称、模拟器名称、SN、模拟器设备 ID、类型/型号代码及 ID，并停止把在线状态、运行状态和模拟器时间戳作为持久化字段
- [X] T004 [P] 将 `src/main/java/com/chh/autosense/api/dto/RegisterDeviceRequest.java` 改为必填 `sn` 与 `name`，并更新 `src/main/java/com/chh/autosense/api/dto/DeviceView.java` 返回稳定元数据和动态计算的 `supported`
- [X] T005 [P] 在 `src/main/java/com/chh/autosense/api/ErrorCode.java` 与 `src/main/java/com/chh/autosense/api/GlobalExceptionHandler.java` 中定义并统一映射 `BAD_REQUEST`、`DEVICE_NOT_FOUND`、`DEVICE_ALREADY_BOUND`、`DEVICE_SERVICE_UNAVAILABLE`，禁止向客户端透传底层异常文本
- [X] T006 [P] 将 `src/main/java/com/chh/autosense/device/client/DeviceServiceClient.java` 从创建设备契约改为强类型 `findDeviceBySn(String sn)` 查询契约，建模可为空的 `exists`、稳定设备字段以及可区分的未找到、协议错误和服务不可用异常
- [X] T007 在 `src/main/java/com/chh/autosense/device/client/DeviceSimulatorClient.java` 中实现 `GET /api/v1/devices/by-sn/{sn}`、路径变量编码、严格响应校验及超时/网络/HTTP/畸形响应映射，并删除平台调用模拟器创建设备的实现
- [X] T008 [P] 扩展 `src/main/java/com/chh/autosense/config/DeviceTypeRegistryProperties.java` 和 `src/main/java/com/chh/autosense/device/DeviceAdapterRegistry.java`，支持从模拟器类型/型号代码反向解析诊断适配器键，并按当前配置动态判断 `supported`
- [X] T009 [P] 在 `src/main/java/com/chh/autosense/repository/DeviceMapper.java` 中增加大小写敏感的精确 SN 查询和当前用户设备查询入口，为数据库唯一约束冲突映射提供稳定访问边界

**Checkpoint**: 项目已具备可编译的 SN 领域模型、模拟器查询客户端契约和数据库约束设计。

---

## Phase 3: User Story 1 - 自动诊断与修复闭环（Priority: P1）🎯 MVP

**Goal**: 用户输入 SN 和显示名称，系统查询已有模拟器设备、保存稳定元数据、显示设备，并继续完成诊断/修复闭环；不支持的类型或型号仍允许绑定。

**Independent Test**: 在模拟器直接创建一台运行中的设备，复制其 SN 调用 `POST /api/v1/devices`，断言返回显示名称及类型/型号元数据且列表可见；随后发起诊断并验证支持设备可完成闭环。不支持设备应绑定成功但在诊断入口被明确拒绝。

### Tests for User Story 1

- [X] T010 [P] [US1] 新增 `src/test/java/com/chh/autosense/contract/DeviceApiContractTest.java`，先覆盖 `{sn,name}` 请求、稳定字段响应、动态 `supported`、非法 SN 400、设备不存在 404、重复绑定 409、上游不可用 503 和不支持设备仍可绑定的契约
- [X] T011 [P] [US1] 新增 `src/test/java/com/chh/autosense/device/client/DeviceSimulatorClientTest.java`，先覆盖 `exists=true` 完整映射、`exists=false`、缺少 `exists`、缺少稳定字段、非 2xx、超时及连接失败
- [X] T012 [P] [US1] 新增 `src/test/java/com/chh/autosense/device/DeviceRegistryServiceTest.java`，先覆盖 SN/名称原样校验、查询成功持久化、设备不存在、上游不可用、用户显示名称覆盖模拟器名称，以及不受支持类型不阻止绑定
- [X] T013 [P] [US1] 更新 `src/test/java/com/chh/autosense/integration/AutoRepairFlowIT.java`，先改用模拟器创建再按 SN 绑定的夹具，并断言绑定后的显示元数据、设备列表及支持设备自动修复闭环

### Implementation for User Story 1

- [X] T014 [US1] 重写 `src/main/java/com/chh/autosense/device/DeviceRegistryService.java` 的绑定流程：严格校验原始 SN/名称、在数据库事务外查询模拟器、区分不存在与不可用、仅复制稳定字段、保留用户显示名称，并用短事务写入设备表
- [X] T015 [US1] 更新 `src/main/java/com/chh/autosense/controller/DeviceController.java`，按 `POST /api/v1/devices` 的 SN 绑定契约接收请求，返回完整稳定元数据和动态 `supported`，列表只返回当前用户设备且不暴露模拟器瞬时状态
- [X] T016 [US1] 更新 `src/main/java/com/chh/autosense/session/DeviceLocator.java`、`src/main/java/com/chh/autosense/device/DeviceAdapterRegistry.java` 和 `src/main/java/com/chh/autosense/session/SessionOrchestrator.java`，把外部类型/型号代码解析为内部诊断键，并在首次状态读取前明确拒绝不支持的已绑定设备
- [X] T017 [US1] 更新 `src/main/java/com/chh/autosense/device/adapter/AbstractDeviceAdapter.java` 和 `src/main/java/com/chh/autosense/session/RepairExecutionRunner.java`，使用模拟器设备 ID 与新的类型/型号字段调用实时状态和命令接口，清除对持久化在线状态及旧注册结果类型的依赖

**Checkpoint**: User Story 1 可独立演示，构成最小可交付版本。

---

## Phase 4: User Story 3 - 用户账号体系与权限控制（Priority: P1）

**Goal**: 设备列表继续按用户隔离，但 SN 在全平台唯一；同一用户或不同用户重复绑定均返回相同冲突结果，且并发请求只能成功一次。

**Independent Test**: 用户 A 绑定一个 SN 后，用户 A 再次绑定及用户 B 绑定同一 SN 都得到 `409 DEVICE_ALREADY_BOUND`；用户 B 看不到用户 A 的设备；并发绑定同一 SN 时数据库中仅有一行。

### Tests for User Story 3

- [X] T018 [P] [US3] 更新 `src/test/java/com/chh/autosense/integration/UserManagementIT.java`，改用 SN 两步式夹具，并先覆盖设备列表按用户隔离、同用户重复绑定和跨用户重复绑定均返回相同 409 且不泄露设备归属
- [X] T019 [P] [US3] 新增 `src/test/java/com/chh/autosense/integration/DeviceBindingConcurrencyIT.java`，先并发提交同一 SN 的绑定请求，断言恰好一个成功、其余为 `DEVICE_ALREADY_BOUND`，并直接查询 MySQL 验证仅持久化一行

### Implementation for User Story 3

- [X] T020 [US3] 在 `src/main/java/com/chh/autosense/device/DeviceRegistryService.java` 中增加不区分所有者的全局 SN 预检查，并捕获 `uk_device_sn` 唯一约束竞态统一映射为 `DEVICE_ALREADY_BOUND`，同时保持现有授权用户的列表隔离

**Checkpoint**: User Story 3 的账号、隔离和全局唯一性回归均可独立验证。

---

## Phase 5: User Story 2 - 无法自动修复时的用户引导（Priority: P2）

**Goal**: 绑定方式和类型元数据改变后，人工操作指引、重试以及售后建议路径仍使用正确的诊断类型键工作。

**Independent Test**: 在模拟器创建并按 SN 绑定设备，构造仅可人工修复及需要售后的故障，分别验证步骤展示、用户确认/重试及最终结果不回归。

### Tests for User Story 2

- [X] T021 [P] [US2] 更新 `src/test/java/com/chh/autosense/integration/ManualGuideIT.java`，先改用 SN 两步式夹具，并覆盖人工步骤、重试、故障复现和售后建议分支在新设备元数据下仍可完成

### Implementation for User Story 2

- [X] T022 [US2] 更新 `src/main/java/com/chh/autosense/session/SessionOrchestrator.java` 和 `src/main/java/com/chh/autosense/session/RepairExecutionRunner.java`，确保人工指引、重试和售后知识查询统一使用由外部类型/型号解析出的内部诊断键，禁止直接把 `LITE` 等原始代码当作规则键

**Checkpoint**: 三个用户故事均可独立通过其验收场景。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 清理旧契约、同步文档并完成分层验证。

- [X] T023 [P] 更新 `README.md` 和 `specs/001-iot-auto-diagnosis/quickstart.md`，说明“模拟器先创建设备、AutoSense 再按 SN 绑定”的流程、错误语义、全局唯一性、迁移前置检查及真实模拟器验证命令
- [X] T024 [P] 清理 `src/main/java/com/chh/autosense/device/client/DeviceServiceClient.java`、`src/main/java/com/chh/autosense/device/client/DeviceSimulatorClient.java`、`src/main/java/com/chh/autosense/api/dto/RegisterDeviceRequest.java` 和 `src/main/java/com/chh/autosense/domain/model/Device.java` 中残留的 `createDevice`、`CreatedDevice`、旧类型/型号注册参数及持久化状态引用
- [X] T025 运行 `mvn verify`，修复单元、契约及默认测试回归，并将命令、环境和结果记录到 `specs/001-iot-auto-diagnosis/validation/unit-contract.md`
- [X] T026 按 `specs/001-iot-auto-diagnosis/quickstart.md` 启动真实 MySQL 与设备模拟器，运行 `mvn verify -Pit` 及手工两步式冒烟验证，并把模拟器版本、固定设备数、清理结果和证据记录到 `specs/001-iot-auto-diagnosis/validation/integration.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1**: 可立即开始，为后续集成测试提供共享夹具
- **Phase 2**: 依赖 Phase 1；完成后才能开始任何用户故事
- **Phase 3 / US1**: 依赖 Phase 2，是本次改造的核心和 MVP
- **Phase 4 / US3**: 依赖 US1 的绑定入口；可在 US1 基本成功路径完成后验证跨用户与并发冲突
- **Phase 5 / US2**: 依赖 US1 的绑定入口和类型解析；与 US3 的业务实现无直接依赖
- **Phase 6**: 依赖所有目标用户故事完成

### User Story Dependency Graph

```text
Setup → Foundational → US1 (P1 / MVP) ─┬→ US3 (P1)
                                      └→ US2 (P2)
US3 + US2 → Polish & Validation
```

### Within Each User Story

- 先编写并运行该故事测试，确认它们因缺少目标行为而失败
- 再完成服务、控制器、持久化和诊断集成
- 每个故事到达 Checkpoint 后先独立验证，再进入下一阶段
- US1 成功路径完成后，US3 与 US2 可由不同开发者并行推进

---

## Parallel Execution Examples

### User Story 1

```text
并行测试：T010 DeviceApiContractTest
          T011 DeviceSimulatorClientTest
          T012 DeviceRegistryServiceTest
          T013 AutoRepairFlowIT

实现顺序：T014 → T015 → T016 → T017
```

### User Story 3

```text
并行测试：T018 UserManagementIT
          T019 DeviceBindingConcurrencyIT

实现顺序：T018/T019 失败证据 → T020 → 重跑 T018/T019
```

### User Story 2

```text
测试与准备：T021 ManualGuideIT
实现与复验：T022 → 重跑 T021
```

---

## Implementation Strategy

### MVP First

1. 完成 Phase 1：共享两步式模拟器夹具
2. 完成 Phase 2：SN 模型、查询客户端、数据库约束和动态支持判断
3. 完成 Phase 3：US1 成功/失败路径与自动修复回归
4. 独立演示：创建模拟器设备 → 使用 SN 绑定 → 列表展示 → 发起诊断

### Incremental Delivery

1. 交付 US1：按 SN 绑定和诊断闭环
2. 交付 US3：跨用户唯一性、隔离和并发安全
3. 交付 US2：人工指引及售后分支回归
4. 最后执行文档同步、默认测试和真实依赖测试

### Team Parallel Strategy

1. 一名开发者完成 Phase 1 和 Phase 2
2. Phase 2 完成后，可并行编写 T010–T013
3. US1 核心完成后，可并行推进 US3 与 US2
4. 标记 `[P]` 的任务只允许在其前置阶段完成后并行执行

---

## Notes

- 所有任务均是当前已实现系统上的增量改造，不要求重做旧版已完成能力
- 数据库迁移必须显式处理已有数据，不允许通过删除设备行或重建生产库规避迁移
- SN 按原始字符串精确校验和保存，不自动 trim 或转大写
- 模拟器的在线状态、运行状态和时间戳仅在诊断时实时读取，不写入设备绑定表
- 提交前同时验证默认测试与 `integration-tests` Profile；后者必须连接真实模拟器和 MySQL

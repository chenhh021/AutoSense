# 作者自查 Checklist: IoT 与设备知识咨询

**Purpose**: 快速自查本期知识导入与咨询需求是否明确、完整，能否作为后续任务拆分和实施的依据。
**Created**: 2026-09-11
**Feature**: [spec.md](../spec.md)
**Depth**: 轻量；18 项核心问题。
**Audience / Timing**: 需求作者；方案调整后、重新生成任务和进入实施前。

**Note**: 本清单由 `$speckit-checklist` 按“自查用”生成，评审需求文字的质量，不是代码测试步骤。
**Review Ownership**: 作者作为自查评审者维护勾选；找到充分的文档依据后才能标为 `[x]`。
**Marker Semantics**: `[x]` 仅表示该项需求质量已审阅并满足，不代表功能已经实现。新条目全部未勾选。

## 需求完整性

- [x] CHK001 我是否写清知识来源、必须保留的类型/型号/资料类别与来源信息，以及“一次导入、所有服务共享”的生命周期？ [Completeness, Spec §FR-009/011]
- [x] CHK002 我是否明确知识咨询与设备查询、诊断、控制的边界，以及知识材料不能授予设备操作权限？ [Completeness, Spec §FR-006/007]

## 需求清晰度

- [x] CHK003 “只按设备类型筛选”是否与“回答仍须核对型号适用性”分别说明，避免被理解为按型号过滤或允许套用其他型号参数？ [Clarity, Spec §FR-003/010]
- [x] CHK004 常识、类型未知、缺必要型号信息、无资料、低相关和技术故障的处理条件与优先级是否写清？ [Clarity, Spec §FR-002/003/005/010]

## 需求一致性
x
- [x] CHK005 当前规格、计划与运行契约是否对共享知识、增强回答和不足回退给出一致要求，并明确旧任务失效内容的同步安排？ [Consistency, Spec §FR-005/010/011, Plan §Delivery and validation, Tasks §修订提示, Conflict]
- [x] CHK006 首次对话准备基础服务与首次需要某类设备资料时准备增强服务的时间要求，是否与此前“首次对话创建服务”的要求衔接清楚？ [Consistency, Plan §Phase 1 — Answer and cache design, Runtime §2, Assumption]

## 验收标准质量

- [x] CHK007 “相关度足够”“只查询一次”“启动只导入一次”是否有明确阈值、边界值和计数范围，能够客观判断是否符合要求？ [Measurability, Spec §FR-005/011/SC-005/006, Plan §Technical Context]
- [x] CHK008 来源可追溯、不编造型号参数和用户信息隔离是否有可判定的成功标准，而不是只有“准确”“安全”等描述？ [Measurability, Spec §SC-002/003/004/006]

## 场景覆盖

- [x] CHK009 需求是否覆盖未绑定设备的常识咨询、同一会话追问、有资料的型号咨询，以及必要信息不足时的澄清？ [Coverage, Spec §User Story 1/2]
- [x] CHK010 启动失败与服务期间检索失败是否分别规定处理方式，并写明修复后恢复的前提？ [Coverage, Spec §FR-005/013/SC-007]

## 边界情况覆盖

- [x] CHK011 “类型未知”和“类型已知但没有收录资料”是否明确区分，且各自的缺口说明不会暗示未发生的检索？ [Coverage, Spec §FR-010, Documents §1]
- [x] CHK012 同类型不同型号、资料相互冲突和一次提问涉及多个类型的支持范围或澄清规则是否明确？ [Coverage, Spec §FR-003/005/010, Gap]

## 非功能需求

- [x] CHK013 启动与对话的时间预算、知识容量、缓存容量及过期要求是否量化，并注明每实例或每缓存的适用范围？ [Clarity, Spec §FR-011/013, Plan §Technical Context, Documents §3/4, Runtime §7]
- [x] CHK014 是否写清并发会话的数据隔离、超时后结果处理，以及日志的英文文案、关联信息和敏感内容排除要求？ [Completeness, Spec §FR-008/013/SC-004, Runtime §2/7, Constitution §日志规范]

## 依赖与假设

- [x] CHK015 已有公共能力、知识资料和独立模型配置的复用前提是否列明，且没有把历史完成记录或已移除组件当成当前可用保证？ [Dependency, Spec §Assumptions, Plan §Current code reuse and migration]
- [x] CHK016 “少文件、优先复用”的约束和本期排除项是否明确，后续外部存储迁移是否只保留必要边界而未扩大当前交付范围？ [Clarity, Spec §Clarifications（2026-09-11）/FR-012, Plan §Project Structure]

## 歧义与冲突

- [x] CHK017 “使用资料必须引用来源”与“目标型号依据不足时允许无引用”是否有清楚的适用条件，避免无依据回答被误认为资料支持的结论？ [Ambiguity, Spec §FR-004/SC-002, Runtime §6]
- [x] CHK018 当前未解决的问题、过期任务与历史验证结果是否明确标注，需求评审通过和实现完成是否有不同的判断依据？ [Traceability, Tasks §修订提示/Execution status, Plan §Current code reuse and migration, Ambiguity]

## Notes

- 每项自查后，可在条目下记录“依据：文档章节；待改：缺口；处理：修订位置”。没有充分依据的项目继续保持未勾选。
- `[Gap]`、`[Ambiguity]`、`[Conflict]` 是待自查议题，不表示已经判定需求不合格。
- 本清单是作者精简自查版；更全面的评审问题见 [knowledge.md](knowledge.md)，已有清单保持独立。
- 引用：Spec = [spec.md](../spec.md)；Plan = [plan.md](../plan.md)；Runtime = [运行契约](../contracts/knowledge-runtime.md)；Documents = [导入契约](../contracts/knowledge-documents.md)；Tasks = [tasks.md](../tasks.md)；Constitution = [项目章程](../../../.specify/memory/constitution.md)。
- `$speckit-implement` 读取清单状态作为门禁，不得修改勾选标记。生成本清单不等于完成自查。
- `checklists/requirements.md` 由 `$speckit-specify` / `$speckit-clarify` 按独立生命周期维护，本轮不修改。
- 本文件后续追加条目时从最后一个 CHK 编号继续，不覆盖已有内容或评审结论。

### FR-003 / FR-008 修订说明（2026-09-11）

- [规格](../spec.md) 已调整为可用的同类型另一型号资料作答并声明信息缺口及依据型号，以及公共 `aiServiceFactory` 创建、仅以 `userId` 缓存用户服务。
- 原条目中禁止使用另一型号、缺型号先澄清、按类型准备独立缓存等表述属于此前评审基线，涉及该语义的条目需按新规格重新评审。保留所有原条目及勾选状态，不把既有勾选解释为新要求已审阅通过。
- 计划、数据模型、契约与旧任务已标注这两项要求的待同步范围；本次未重新生成清单、代替评审者修改结论或验证实现。

- 后续任务同步状态（2026-09-11）：plan、research、data-model、contracts、quickstart 已完成 FR-003/FR-008 同步；[tasks.md](../tasks.md) 已同步为 44 项任务，覆盖跨型号依据声明、公共工厂创建三代理、单个 userId 缓存和每请求类型过滤。旧任务编号/描述见[历史归档](../tasks-before-20260911-sync.md)，不对应新编号；原评审条目与勾选保留，涉及旧方案的结论仍需由评审者重新评估。本次没有验证实现或代替评审者判定条目通过。

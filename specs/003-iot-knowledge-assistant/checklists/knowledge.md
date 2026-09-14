# 知识导入与咨询需求质量 Checklist: IoT 与设备知识咨询

**Purpose**: 评审共享知识索引、类型筛选、回答与回退要求的完整性、清晰度、一致性和可衡量性；覆盖与公共会话及历史任务的衔接。
**Created**: 2026-09-11
**Feature**: [spec.md](../spec.md)
**Depth**: Standard（标准深度）
**Audience / Timing**: 需求作者与评审者；实施前方案评审、任务重新生成前。
**Scope**: 本期知识导入与知识咨询；涵盖启动与恢复、并发共享、来源适用性、缓存生命周期及需求文档一致性。外部存储只评审后续边界，不增加本期建设或发布运维要求。

**Note**: 本自定义清单由 `$speckit-checklist` 根据当前需求生成；条目评审文档写得是否充分，不用于判断代码是否实现或运行测试是否通过。
**Review Ownership**: 清单归评审者维护。只有评审者确定相应需求质量标准已满足后，才可将该项标为 `[x]`。
**Marker Semantics**: `[x]` 表示需求质量已经评审并满足该项标准，不表示实现任务完成。新生成条目全部保持 `[ ]`。

## 引用约定

- Spec：[spec.md](../spec.md)，FR/SC 为需求与成功标准编号。
- Plan：[plan.md](../plan.md)。
- Runtime：[knowledge-runtime.md](../contracts/knowledge-runtime.md)，数字对应章节。
- Documents：[knowledge-documents.md](../contracts/knowledge-documents.md)，数字对应章节。
- Data：[data-model.md](../data-model.md)，数字对应章节。
- Tasks：条目生成时引用的是[旧任务归档](../tasks-before-20260911-sync.md)，旧编号仅用于定位此前需求表述；当前有效实施依据为已同步的 [tasks.md](../tasks.md)，不以旧条目勾选代表新方案验收通过。
- Validation：[validation.md](../validation.md)，仅作历史状态语义的评审依据。
- Constitution：[项目章程](../../../.specify/memory/constitution.md)。

## 需求完整性

- [ ] CHK001 是否完整定义常识、非常识且信息齐备、类型未知及必要信息缺失四类问题的分支和优先级？ [Completeness, Spec §FR-002/003/010]
- [ ] CHK002 是否明确首期知识来源、必需资料类别及来源标识，并说明文档缺失、损坏或部分可用时的要求？ [Completeness, Spec §FR-009/011/013, Documents §1/2/3]
- [ ] CHK003 是否同时写明启动一次共享知识、运行期使用限制以及重启后的知识重建要求？ [Completeness, Spec §FR-011, Documents §3]
- [ ] CHK004 是否定义增强回答、知识不足直答和原有诊断分析各自的职责，避免“统一助手”被解释为任一能力都可使用知识检索或控制设备？ [Completeness, Spec §FR-006/007, Runtime §1/2/4]

## 需求清晰度

- [ ] CHK005 “静态”“共享”“一次”是否明确限定到应用实例和启动周期，并与跨用户、跨会话共享的含义区分？ [Clarity, Spec §FR-011, Plan §Summary, Data §1]
- [ ] CHK006 “仅按设备类型筛选”是否清楚区分检索条件、语义问题描述和回答适用性判断，且未留下隐式增加型号或资料分类筛选的解释空间？ [Clarity, Spec §FR-003/010, Runtime §5/6]
- [ ] CHK007 “资料不足”是否分别定义无候选、相关度不足、型号依据不足及技术故障，而不是用同一术语覆盖不同处理结果？ [Clarity, Spec §FR-005, Runtime §4, Data §6/7]
- [ ] CHK008 “尽量少文件、优先复用”的范围和允许增加职责的理由是否有明确说明，足以区分必要拆分与无关扩展？ [Clarity, Spec §Clarifications（2026-09-11）, Plan §Project Structure]

## 需求一致性

- [ ] CHK009 首次对话创建基础服务与首次需要某设备类型时创建增强服务的时间要求是否在规格澄清记录、计划和运行契约中一致，且明确相对早期要求的调整？ [Consistency, Spec §Clarifications, Plan §Phase 1 — Answer and cache design, Runtime §2, Assumption]
- [ ] CHK010 “基于资料作答必须提供来源”与“目标型号依据不足时允许无引用”的条件是否相容，并说明后者哪些内容可以不依赖资料？ [Consistency, Spec §FR-004/SC-002, Data §6, Runtime §6, Ambiguity]
- [ ] CHK011 规格中沿用既有知识服务边界的表述，是否与旧服务已移除的假设及诊断兼容要求一致，避免把计划保留的能力误写为现成依赖？ [Consistency, Spec §FR-009/Assumptions, Plan §Current code reuse and migration, Ambiguity]
- [ ] CHK012 更新后的共享、过滤及增强方式要求是否已有唯一有效的任务依据，旧任务中的相反描述是否明确失效且具有同步安排？ [Consistency, Spec §FR-010/011, Plan §Delivery and validation, Tasks §修订提示/T010/T011/T035/T036, Conflict]

## 验收标准质量

- [ ] CHK013 启动一次共享知识的成功标准是否有明确计数范围，能区分启动导入、用户查询与服务重启，而非仅以“可检索”判断满足共享要求？ [Measurability, Spec §FR-011/SC-006, Plan §Delivery and validation]
- [ ] CHK014 相关度达标、低于门槛及恰好等于门槛是否有明确判定标准，并记录门槛校准依据及尚未覆盖的真实知识质量？ [Measurability, Spec §FR-005/SC-005, Documents §4, Plan §Technical Context]
- [ ] CHK015 知识不足回退的验收标准是否明确不重复检索、不先生成增强答案的边界，并区分必要的问题理解与额外答案生成？ [Measurability, Spec §FR-005, Plan §Technical Context — Performance Goals, Runtime §4]
- [ ] CHK016 “不编造型号参数”“来源可追溯”的评审依据是否明确到事实适用性、来源身份及资料版本，而非仅要求答案带有来源名称？ [Acceptance Criteria, Spec §FR-003/004/SC-002/006, Runtime §6]

## 场景覆盖

- [ ] CHK017 主要与替代场景是否包含未绑定设备用户的常识咨询、本人会话追问及型号相关咨询，且明确各自所需信息？ [Coverage, Spec §User Story 1/2, Spec §FR-001/003]
- [ ] CHK018 是否明确区分“设备类型无法识别”和“类型已识别但尚未收录知识”，并分别规定是否检索及缺口说明？ [Coverage, Spec §FR-010, Documents §1]
- [ ] CHK019 是否覆盖同类型其他型号资料相关但不适用于目标问题的场景，并明确何时澄清、说明适用性缺口或直接回退？ [Coverage, Spec §FR-003/005/010, Runtime §4/6]
- [ ] CHK020 是否定义启动失败后的恢复前提，以及已启动后的检索失败、回答失败分别适用的恢复或再次请求边界？ [Coverage, Spec §FR-005/013/SC-007, Runtime §4, Gap]

## 边界情况覆盖

- [ ] CHK021 是否明确同一问题包含多个设备类型、型号比较或相互矛盾的对象线索时的支持范围与处理优先级？ [Coverage, Spec §FR-002/003/010, Gap]
- [ ] CHK022 是否规定同类型多个资料来源冲突、同一来源内部冲突及仅部分事实有依据时的回答和引用要求？ [Coverage, Spec §FR-004/005, Data §6, Runtime §6]
- [ ] CHK023 是否定义文件格式不符、空白内容、重复来源和超出资料容量限制时的一致失败边界，避免与“完整导入”出现例外冲突？ [Coverage, Spec §FR-013, Documents §1/3]
- [ ] CHK024 是否定义用户跨会话并发、缓存过期或移除以及回答尚未完成时的资料和用户上下文隔离要求？ [Coverage, Spec §FR-008/SC-004, Runtime §2/7]

## 非功能需求

- [ ] CHK025 是否清楚区分启动预算与单次对话截止，并规定重试、回退和迟到结果的预算归属与终止要求？ [Clarity, Spec §FR-013, Documents §3/4, Runtime §7]
- [ ] CHK026 知识规模、分段规模及服务缓存的容量和过期要求是否量化，且说明限额是每实例、每缓存还是全系统范围？ [Measurability, Spec §FR-011, Plan §Technical Context, Documents §4, Data §3]
- [ ] CHK027 是否完整规定知识材料不构成操作授权、用户身份持续有效及不同用户信息隔离的安全边界？ [Completeness, Spec §FR-006/007/SC-003/004, Runtime §1/2]
- [ ] CHK028 是否规定外部调用与启动故障日志的定位信息、语言及敏感内容排除范围，并区分启动和对话的关联字段要求？ [Completeness, Spec §FR-013, Runtime §7, Constitution §日志规范]

## 依赖与假设

- [ ] CHK029 公共身份、路由、历史、响应和持久化能力的复用前提是否明确，且本功能与其他设备能力的责任边界可追溯？ [Dependency, Spec §FR-006/007/008/Assumptions, Runtime §1]
- [ ] CHK030 知识向量化的独立配置、导入与查询的一致性以及模拟环境结果的适用范围是否明确，避免把模拟可用当作真实语义质量保证？ [Assumption, Spec §SC-005/006, Documents §4]
- [ ] CHK031 后续外部知识存储迁移是否明确排除本期建设，同时定义需保持的来源、筛选、相关度及回退语义？ [Dependency, Spec §FR-012, Documents §5]

## 歧义与冲突跟踪

- [ ] CHK032 “缺少必要信息”“答案依赖型号”的判定依据是否足够明确，使评审者能一致区分可直接回答的问题和必须澄清的问题？ [Ambiguity, Spec §FR-003, Data §4]
- [ ] CHK033 “拒绝不适用来源”与“允许其他型号资料参与比较或缺口说明”的边界是否定义，避免把来源适用性要求变成隐藏的型号筛选？ [Ambiguity, Spec §FR-003/010, Runtime §6, Data §6]
- [ ] CHK034 需求评审通过、历史基础阶段完成、当前任务完成及当前验证结果的含义是否明确区分，旧记录与勾选状态不一致时是否有处理约定？ [Traceability, Tasks §Execution status/修订提示, Plan §Current code reuse and migration, Validation §历史验证范围说明, Ambiguity]

## Notes

- 本轮将深度与使用阶段合并为一个可选问题一次发送；生成时未收到回答，采用标准深度、实施前评审的推荐假设。用户已明确的功能范围不重复询问。
- 本轮明确要求已纳入：启动一次共享知识、仅设备类型筛选、保留型号与资料类别的适用性含义、减少组件并优先复用、增强回答与不足回退衔接。具体技术选型留在 plan，本清单不规定额外框架或实现算法。
- `[Gap]`、`[Ambiguity]`、`[Conflict]` 标识待评审的缺口、歧义或文档冲突议题，不是已经判定的失败结论。
- 评审者可在条目后添加结论、依据或修订链接；需要澄清、修订或尚未评审的条目继续保持未勾选。
- `$speckit-implement` 会读取清单状态作为门禁，但不得修改勾选标记。生成清单不等于已完成评审。
- `checklists/requirements.md` 是由 `$speckit-specify` / `$speckit-clarify` 维护的独立内置清单，本轮保持原样。
- 本轮未评估代码、执行运行测试或更新 spec/plan/tasks；条目编号在本文件内连续，后续同主题清单追加时从最后一个 CHK 编号继续。

### FR-003 / FR-008 修订说明（2026-09-11）

- [规格](../spec.md) 已调整为可用的同类型另一型号资料作答并声明信息缺口及依据型号，以及公共 `aiServiceFactory` 创建、仅以 `userId` 缓存用户服务。
- 原条目中禁止使用另一型号、缺型号先澄清、按类型准备独立缓存等表述属于此前评审基线，涉及该语义的条目需按新规格重新评审。保留所有原条目及勾选状态，不把既有勾选解释为新要求已审阅通过。
- 计划、数据模型、契约与旧任务已标注这两项要求的待同步范围；本次未重新生成清单、代替评审者修改结论或验证实现。

- 后续任务同步状态（2026-09-11）：plan、research、data-model、contracts、quickstart 已完成 FR-003/FR-008 同步；[tasks.md](../tasks.md) 已同步为 44 项任务，覆盖跨型号依据声明、公共工厂创建三代理、单个 userId 缓存和每请求类型过滤。旧任务编号/描述见[历史归档](../tasks-before-20260911-sync.md)，不对应新编号；原评审条目与勾选保留，涉及旧方案的结论仍需由评审者重新评估。本次没有验证实现或代替评审者判定条目通过。

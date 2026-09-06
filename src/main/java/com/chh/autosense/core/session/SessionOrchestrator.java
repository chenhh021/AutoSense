package com.chh.autosense.core.session;

import com.chh.autosense.core.aftersales.AfterSalesGuideService;
import com.chh.autosense.core.aftersales.AfterSalesLocation;
import com.chh.autosense.core.analysis.DiagnosisConclusion;
import com.chh.autosense.core.analysis.DiagnosisReasoner;
import com.chh.autosense.core.analysis.ProblemAnalysis;
import com.chh.autosense.core.analysis.ProblemAnalyzer;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.dto.SessionListItemView;
import com.chh.autosense.domain.dto.SessionResponse;
import com.chh.autosense.domain.message.SseEvent;
import com.chh.autosense.domain.message.SseEventStream;
import com.chh.autosense.config.DeviceTypeRegistryProperties;
import com.chh.autosense.core.device.DeviceAdapterRegistry;
import com.chh.autosense.core.device.rule.FaultRuleEngine;
import com.chh.autosense.core.device.rule.FaultVerdict;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.core.device.spi.DeviceUnreachableException;
import com.chh.autosense.domain.enums.ConclusionType;
import com.chh.autosense.domain.enums.Intent;
import com.chh.autosense.domain.enums.MessageRole;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.domain.enums.SnapshotPhase;
import com.chh.autosense.domain.entity.ChatMessage;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.domain.entity.DiagnosticSnapshot;
import com.chh.autosense.domain.entity.ProblemReport;
import com.chh.autosense.domain.entity.RepairKnowledge;
import com.chh.autosense.domain.entity.RepairSession;
import com.chh.autosense.service.knowledge.RepairKnowledgeService;
import com.chh.autosense.mapper.ChatMessageMapper;
import com.chh.autosense.mapper.DiagnosticSnapshotMapper;
import com.chh.autosense.mapper.ProblemReportMapper;
import com.chh.autosense.mapper.RepairSessionMapper;
import com.chh.autosense.core.routing.DirectAnswerer;
import com.chh.autosense.core.routing.IntentClassifier;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.security.DeviceOwnershipChecker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话编排(T022~T029,2026-08-22/27 刷新):确定性状态机驱动 + 意图路由(FR-019)
 * + 规则化故障判定(R12)+ SSE 事件推送(FR-021)+ 终态续聊(R17)。
 * LLM 仅做意图分类/语义分析/诊断推理,修复动作只能经状态机调用适配器白名单(R2)。
 * 所有事件经 SseEventStream 推送;等待态/终态由调用方关流。
 */
@Slf4j
@Service
public class SessionOrchestrator {

    private final RepairSessionMapper sessionMapper;
    private final ProblemReportMapper reportMapper;
    private final DiagnosticSnapshotMapper snapshotMapper;
    private final ChatMessageMapper messageMapper;
    private final IntentClassifier intentClassifier;
    private final DirectAnswerer directAnswerer;
    private final ProblemAnalyzer problemAnalyzer;
    private final DiagnosisReasoner diagnosisReasoner;
    private final DeviceAdapterRegistry adapterRegistry;
    private final DeviceTypeRegistryProperties registryProperties;
    private final FaultRuleEngine faultRuleEngine;
    private final DeviceLocator deviceLocator;
    private final DeviceOwnershipChecker ownershipChecker;
    private final DeviceLockService lockService;
    private final SessionContextStore contextStore;
    private final RepairKnowledgeService knowledgeService;
    private final RepairExecutionRunner repairRunner;
    private final AfterSalesGuideService afterSalesGuide;
    private final SessionTransitionLog transitionLog;
    private final ObjectMapper objectMapper;

    public SessionOrchestrator(RepairSessionMapper sessionMapper,
                               ProblemReportMapper reportMapper,
                               DiagnosticSnapshotMapper snapshotMapper,
                               ChatMessageMapper messageMapper,
                               IntentClassifier intentClassifier,
                               DirectAnswerer directAnswerer,
                               ProblemAnalyzer problemAnalyzer,
                               DiagnosisReasoner diagnosisReasoner,
                               DeviceAdapterRegistry adapterRegistry,
                               DeviceTypeRegistryProperties registryProperties,
                               FaultRuleEngine faultRuleEngine,
                               DeviceLocator deviceLocator,
                               DeviceOwnershipChecker ownershipChecker,
                               DeviceLockService lockService,
                               SessionContextStore contextStore,
                               RepairKnowledgeService knowledgeService,
                               RepairExecutionRunner repairRunner,
                               AfterSalesGuideService afterSalesGuide,
                               SessionTransitionLog transitionLog,
                               ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.reportMapper = reportMapper;
        this.snapshotMapper = snapshotMapper;
        this.messageMapper = messageMapper;
        this.intentClassifier = intentClassifier;
        this.directAnswerer = directAnswerer;
        this.problemAnalyzer = problemAnalyzer;
        this.diagnosisReasoner = diagnosisReasoner;
        this.adapterRegistry = adapterRegistry;
        this.registryProperties = registryProperties;
        this.faultRuleEngine = faultRuleEngine;
        this.deviceLocator = deviceLocator;
        this.ownershipChecker = ownershipChecker;
        this.lockService = lockService;
        this.contextStore = contextStore;
        this.knowledgeService = knowledgeService;
        this.repairRunner = repairRunner;
        this.afterSalesGuide = afterSalesGuide;
        this.transitionLog = transitionLog;
        this.objectMapper = objectMapper;
    }

    // ========== 入口 1:发起会话(contracts §1,SSE 流) ==========

    public void createSession(AuthUser user, String problemText, SseEventStream stream) {
        RepairSession session = new RepairSession();
        session.setUserId(user.userId());
        session.setStatus(SessionStatus.CREATED.name());
        sessionMapper.insert(session);

        ProblemReport report = new ProblemReport();
        report.setSessionId(session.getId());
        report.setRound(1);
        report.setRawText(problemText);
        reportMapper.insert(report);
        saveMessage(session.getId(), MessageRole.USER, problemText);

        transition(session, SessionStatus.CREATED, SessionStatus.ROUTING, stream);
        route(session, report, problemText, stream);
    }

    // ========== 入口 2:会话内消息(contracts §2,SSE 流) ==========

    public void postMessage(AuthUser user, Long sessionId, String content,
                            Boolean confirmRepair, SseEventStream stream) {
        RepairSession session = loadOwnedSession(user, sessionId);
        SessionStatus status = SessionStatus.valueOf(session.getStatus());

        // R17:终态续聊——同一 sessionId 开启新一轮,历史记忆可见(R15)
        if (status.isTerminal()) {
            if (content == null || content.isBlank()) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "继续对话需要提供新问题内容", sessionId);
            }
            int nextRound = currentRound(sessionId) + 1;
            transition(session, status, SessionStatus.ROUTING, stream);
            ProblemReport report = new ProblemReport();
            report.setSessionId(sessionId);
            report.setRound(nextRound);
            report.setRawText(content);
            reportMapper.insert(report);
            saveMessage(sessionId, MessageRole.USER, content);
            // 终态回路复用同一会话,结论字段清空待新一轮
            session.setConclusionType(null);
            session.setConclusion(null);
            session.setConclusionExtra(null);
            session.setDeviceId(null);
            sessionMapper.update(session);
            route(session, report, content, stream);
            return;
        }

        if (content != null && !content.isBlank()) {
            saveMessage(sessionId, MessageRole.USER, content);
        }
        switch (status) {
            case CLARIFYING -> {
                ProblemReport report = reportOf(sessionId);
                appendClarification(report, content);
                transition(session, SessionStatus.CLARIFYING, SessionStatus.ROUTING, stream);
                String combined = report.getRawText() + " " + (content == null ? "" : content);
                route(session, report, combined, stream);
            }
            case DEVICE_CONFIRMING -> {
                SessionContext ctx = contextStore.load(sessionId);
                List<Long> candidateIds = parseIdList(ctx == null ? null : ctx.candidateDeviceIds());
                List<Device> candidates = deviceLocator.findByIds(candidateIds);
                Device chosen = deviceLocator.pickFromCandidates(candidates, content);
                if (chosen == null) {
                    String reply = "未能识别您的选择,请回复序号或设备名称。";
                    saveMessage(sessionId, MessageRole.ASSISTANT, reply);
                    stream.awaitUser(sessionId, reply);
                    return;
                }
                ownershipChecker.check(chosen, user);
                transition(session, SessionStatus.DEVICE_CONFIRMING, SessionStatus.LOCATING, stream);
                session.setDeviceId(chosen.getId());
                sessionMapper.update(session);
                proceedWithDevice(session, chosen, stream);
            }
            case CONFIRMING_REPAIR -> {
                SessionContext ctx = contextStore.load(sessionId);
                if (!Boolean.TRUE.equals(confirmRepair)) {
                    transition(session, SessionStatus.CONFIRMING_REPAIR,
                            SessionStatus.COMPLETED_UNFIXED, stream);
                    finish(session, ConclusionType.UNFIXED_MANUAL_GUIDE,
                            "用户取消操作,设备未做改动。", null);
                    releaseLock(session);
                    String reply = "已取消,设备未做任何改动。";
                    saveMessage(sessionId, MessageRole.ASSISTANT, reply);
                    stream.conclude(buildConclusion(session));
                    return;
                }
                transition(session, SessionStatus.CONFIRMING_REPAIR,
                        SessionStatus.REPAIRING, stream);
                saveMessage(sessionId, MessageRole.ASSISTANT, "已确认,开始执行操作。");
                String actionCode = ctx == null ? null : ctx.pendingActionCode();
                Map<String, Object> params = ctx == null || ctx.pendingActionParams() == null
                        ? Map.of() : fromJson(ctx.pendingActionParams());
                // R18:确认后修复在同一 SSE 流内推进
                repairRunner.run(sessionId, actionCode, params, stream);
            }
            case AWAITING_LOCATION -> guideToAfterSales(session, content, stream);
            default -> throw new ApiException(ErrorCode.BAD_REQUEST,
                    "当前状态(" + status + ")不接受消息", sessionId);
        }
    }

    // ========== 入口 3:查询会话(contracts §3,JSON 补查) ==========

    public SessionResponse getSession(AuthUser user, Long sessionId) {
        RepairSession session = loadOwnedSession(user, sessionId);
        SessionStatus status = SessionStatus.valueOf(session.getStatus());
        return new SessionResponse(session.getId(), status.name(),
                replyFor(session, status), status.isAwaitingUser(),
                status.isTerminal() ? buildConclusion(session) : null);
    }

    public List<ChatMessage> listMessages(AuthUser user, Long sessionId) {
        loadOwnedSession(user, sessionId);
        return messageMapper.selectListByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId).orderBy("created_at", true));
    }

    /** 历史对话列表(FR-018,contracts §5):仅本人,updatedAt 倒序;
     * preview 取最近结论,无结论(等待中)回退首条用户问题。 */
    public List<SessionListItemView> listSessions(AuthUser user) {
        return sessionMapper.selectListByQuery(QueryWrapper.create()
                        .where("user_id = ?", user.userId())
                        .orderBy("updated_at", false))
                .stream()
                .map(s -> new SessionListItemView(s.getId(), s.getStatus(),
                        previewOf(s), s.getCreatedAt(), s.getUpdatedAt()))
                .toList();
    }

    private String previewOf(RepairSession session) {
        if (session.getConclusion() != null && !session.getConclusion().isBlank()) {
            return session.getConclusion();
        }
        ChatMessage first = messageMapper.selectOneByQuery(QueryWrapper.create()
                .where("session_id = ?", session.getId())
                .and("role = ?", MessageRole.USER.name())
                .orderBy("id", true).limit(1));
        return first == null ? null : first.getContent();
    }

    // ========== 意图路由(FR-019) ==========

    private void route(RepairSession session, ProblemReport report, String text,
                       SseEventStream stream) {
        Intent intent = intentClassifier.classify(text, session.getId());
        // MODEL_SPECIFIC 本期并入 COMMON_SENSE(2026-08-22 澄清)
        Intent stored = intent == Intent.MODEL_SPECIFIC ? Intent.COMMON_SENSE : intent;
        report.setIntent(stored.name());
        reportMapper.update(report);

        switch (intent) {
            case COMMON_SENSE, MODEL_SPECIFIC -> {
                transition(session, SessionStatus.ROUTING, SessionStatus.ANSWERING, stream);
                directAnswerer.answer(text, session.getId(),
                                token -> stream.send(SseEvent.token(token)))
                        .whenComplete((answer, error) -> completeDirectAnswer(
                                session, answer, error, stream));
            }
            case AFTERSALES_QUERY -> {
                // 独立网点查询:位置已在文本中→直接查询;否则等待位置
                if (looksLikeLocationProvided(text)) {
                    transition(session, SessionStatus.ROUTING, SessionStatus.AFTERSALES_LOOKUP, stream);
                    doAfterSalesLookup(session, text, ConclusionType.AFTERSALES_PROVIDED, stream);
                } else {
                    transition(session, SessionStatus.ROUTING, SessionStatus.AWAITING_LOCATION, stream);
                    String reply = "请提供您所在的位置(城市/区县),我帮您查询附近售后网点。";
                    saveMessage(session.getId(), MessageRole.ASSISTANT, reply);
                    saveContext(session, null, null, null);
                    stream.awaitUser(session.getId(), reply);
                }
            }
            case DEVICE_ACTION -> {
                transition(session, SessionStatus.ROUTING, SessionStatus.ANALYZING, stream);
                analyzeAndProceed(session, report, text, stream);
            }
            case UNCLEAR -> {
                transition(session, SessionStatus.ROUTING, SessionStatus.CLARIFYING, stream);
                String reply = "没有理解您的需求。您可以:描述设备故障(如\"客厅的灯不亮了\")、"
                        + "提问常识问题、或查询附近售后网点(请附位置)。";
                saveMessage(session.getId(), MessageRole.ASSISTANT, reply);
                saveContext(session, null, null, null);
                stream.awaitUser(session.getId(), reply);
            }
        }
    }

    private void completeDirectAnswer(RepairSession session, String answer, Throwable error,
                                      SseEventStream stream) {
        try {
            String completedAnswer = answer;
            if (error != null || completedAnswer == null || completedAnswer.isBlank()) {
                if (error != null) {
                    log.warn("LLM 直答失败 session={}", session.getId(), error);
                }
                completedAnswer = "回答生成遇到问题,请稍后再试。";
                stream.send(SseEvent.token(completedAnswer));
            }
            transition(session, SessionStatus.ANSWERING,
                    SessionStatus.COMPLETED_ANSWERED, stream);
            finish(session, ConclusionType.ANSWERED, completedAnswer, null);
            saveMessage(session.getId(), MessageRole.ASSISTANT, completedAnswer);
            stream.conclude(buildConclusion(session));
        } catch (Exception e) {
            log.error("LLM 直答结果收尾失败 session={}", session.getId(), e);
            stream.error(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", session.getId());
        }
    }

    // ========== 诊断修复主流程 ==========

    private void analyzeAndProceed(RepairSession session, ProblemReport report,
                                   String text, SseEventStream stream) {
        ProblemAnalysis analysis = problemAnalyzer.analyze(text, session.getId());
        report.setDeviceType(analysis.deviceType());
        report.setSymptom(analysis.symptom());
        report.setReproduction(analysis.reproduction());
        reportMapper.update(report);

        if (!analysis.sufficient()) {
            transition(session, SessionStatus.ANALYZING, SessionStatus.CLARIFYING, stream);
            saveContext(session, null, analysis.deviceType(), null);
            saveMessage(session.getId(), MessageRole.ASSISTANT, analysis.clarifyQuestion());
            stream.awaitUser(session.getId(), analysis.clarifyQuestion());
            return;
        }

        // FR-004:支持范围判断
        if (!adapterRegistry.isSupported(analysis.deviceType())) {
            transition(session, SessionStatus.ANALYZING,
                    SessionStatus.REJECTED_UNSUPPORTED, stream);
            releaseLock(session);
            finish(session, null, null, null);
            stream.error(ErrorCode.UNSUPPORTED_DEVICE_TYPE.name(),
                    "暂不支持该设备类型(%s),目前支持:智能灯泡;建议联系品牌售后。"
                            .formatted(analysis.deviceType()),
                    session.getId());
            return;
        }

        transition(session, SessionStatus.ANALYZING, SessionStatus.LOCATING, stream);
        List<Device> candidates = deviceLocator.findCandidates(session.getUserId(),
                analysis.deviceType());
        if (candidates.isEmpty()) {
            stream.error(ErrorCode.DEVICE_NOT_FOUND.name(),
                    "未找到您名下的%s设备,可先通过设备登记接口创建。"
                            .formatted(displayName(analysis.deviceType())),
                    session.getId());
            return;
        }
        if (candidates.size() > 1) {
            transition(session, SessionStatus.LOCATING,
                    SessionStatus.DEVICE_CONFIRMING, stream);
            String reply = buildCandidatePrompt(candidates, analysis.deviceType());
            saveContext(session, null, analysis.deviceType(), candidates);
            saveMessage(session.getId(), MessageRole.ASSISTANT, reply);
            stream.awaitUser(session.getId(), reply);
            return;
        }
        proceedWithDevice(session, candidates.get(0), stream);
    }

    private void proceedWithDevice(RepairSession session, Device device,
                                   SseEventStream stream) {
        ownershipChecker.check(device, new AuthUser(session.getUserId()));

        // 目标设备落会话(锁释放/修复执行依赖)
        session.setDeviceId(device.getId());
        sessionMapper.update(session);

        String diagnosticType = adapterRegistry.diagnosticTypeOf(device).orElse(null);
        DeviceAdapter adapter = adapterRegistry.adapterOf(device).orElse(null);
        if (diagnosticType == null || adapter == null || !adapterRegistry.isSupported(device)) {
            transition(session, SessionStatus.LOCATING,
                    SessionStatus.REJECTED_UNSUPPORTED, stream);
            finish(session, null, null, null);
            stream.error(ErrorCode.UNSUPPORTED_DEVICE_TYPE.name(),
                    "暂不支持该设备类型或型号(%s/%s)。"
                            .formatted(device.getDeviceTypeCode(), device.getDeviceModelCode()),
                    session.getId());
            return;
        }

        // FR-016:设备级互斥
        if (!lockService.tryLock(device.getId(), session.getId())) {
            transition(session, SessionStatus.LOCATING, SessionStatus.REJECTED_BUSY, stream);
            stream.error(ErrorCode.DEVICE_BUSY.name(),
                    "该设备正在处理中,请稍后再试。", session.getId());
            return;
        }

        // FR-005:采集 PRE 诊断快照(探测免确认)
        Map<String, Object> pre;
        try {
            pre = adapter.getDiagnostics(device);
        } catch (DeviceUnreachableException e) {
            transition(session, SessionStatus.LOCATING,
                    SessionStatus.FAILED_DEVICE_UNREACHABLE, stream);
            finish(session, ConclusionType.DEVICE_UNREACHABLE,
                    "设备当前不可达,请检查设备电源与网络连接后重试。", null);
            releaseLock(session);
            stream.error(ErrorCode.DEVICE_UNREACHABLE.name(),
                    "设备当前不可达,请检查设备电源与网络连接。", session.getId());
            return;
        }
        saveSnapshot(session.getId(), currentRound(session.getId()), SnapshotPhase.PRE, pre);
        lockService.renew(device.getId());

        transition(session, SessionStatus.LOCATING, SessionStatus.DIAGNOSING, stream);

        // FR-006:LLM 结合快照生成可理解结论(记忆窗口最近 20 条)
        ProblemReport report = reportOf(session.getId());
        DiagnosisConclusion diagnosis = diagnosisReasoner.diagnose(
                report.getRawText(), report.getSymptom(), pre, session.getId());
        lockService.renew(device.getId());

        transition(session, SessionStatus.DIAGNOSING, SessionStatus.PLANNING, stream);
        saveContext(session, device.getId(), diagnosticType, null);

        // R12:规则化故障判定
        FaultVerdict verdict = faultRuleEngine.evaluate(diagnosticType, pre,
                String.valueOf(pre.getOrDefault("running_status", "running")));
        // FR-007:RAG 检索补充方案/步骤文本,规则引用优先于自由检索
        Optional<RepairKnowledge> knowledge = verdict.knowledgeRef() != null
                ? knowledgeService.findByRef(diagnosticType, verdict.knowledgeRef())
                : knowledgeService.findSolution(diagnosticType, diagnosis.problemSummary());

        switch (verdict.kind()) {
            case AUTO_REPAIRABLE -> {
                // FR-008(2026-08-22):全部改变状态的操作必经用户确认
                transition(session, SessionStatus.PLANNING,
                        SessionStatus.CONFIRMING_REPAIR, stream);
                saveContext(session, device.getId(), diagnosticType, null,
                        verdict.actionCode(), toJson(verdict.actionParams()));
                String actionDesc = knowledgeService.actionDescription(
                        diagnosticType, verdict.actionCode(), verdict.reason());
                String reply = "%s 方案:%s。该操作会改变设备状态,是否执行?"
                        .formatted(verdict.reason() == null ? diagnosis.conclusionText()
                                : verdict.reason(), actionDesc);
                saveMessage(session.getId(), MessageRole.ASSISTANT, reply);
                stream.awaitUser(session.getId(), reply);
            }
            case MANUAL_ONLY -> {
                transition(session, SessionStatus.PLANNING, SessionStatus.GUIDED_MANUAL, stream);
                String steps = knowledge.map(RepairKnowledge::getManualSteps)
                        .filter(s -> s != null && !s.isBlank())
                        .orElse("1. 断开设备电源\n2. 检查设备外观与连接\n3. 联系品牌售后");
                finish(session, ConclusionType.UNFIXED_MANUAL_GUIDE,
                        (verdict.reason() == null ? diagnosis.conclusionText() : verdict.reason())
                                + " 无法自动修复,请按以下步骤人工处理。",
                        Map.of("manualSteps", List.of(steps.split("\n"))));
                releaseLock(session);
                saveMessage(session.getId(), MessageRole.ASSISTANT, session.getConclusion());
                stream.conclude(buildConclusion(session));
            }
            case AFTERSALES -> {
                transition(session, SessionStatus.PLANNING, SessionStatus.GUIDED_AFTERSALES, stream);
                transition(session, SessionStatus.GUIDED_AFTERSALES,
                        SessionStatus.AWAITING_LOCATION, stream);
                String reply = (verdict.reason() == null ? diagnosis.conclusionText() : verdict.reason())
                        + " 暂时无法确定修复步骤。请提供您所在的位置(城市/区县),我帮您查询附近售后网点。";
                saveMessage(session.getId(), MessageRole.ASSISTANT, reply);
                saveContext(session, device.getId(), diagnosticType, null);
                stream.awaitUser(session.getId(), reply);
            }
            case NORMAL -> {
                transition(session, SessionStatus.PLANNING, SessionStatus.COMPLETED_ANSWERED, stream);
                finish(session, ConclusionType.ANSWERED,
                        "诊断未发现异常:%s 设备当前状态正常(%s)。如问题仍存在,请补充描述观察到的现象。"
                                .formatted(diagnosis.conclusionText(), pre),
                        Map.of("preDiagnostics", pre));
                releaseLock(session);
                saveMessage(session.getId(), MessageRole.ASSISTANT, session.getConclusion());
                stream.conclude(buildConclusion(session));
            }
        }
    }

    /** 用户提供位置后:网点查询 → 终态(AWAITING_LOCATION 入口)。 */
    private void guideToAfterSales(RepairSession session, String locationText,
                                   SseEventStream stream) {
        transition(session, SessionStatus.AWAITING_LOCATION,
                SessionStatus.AFTERSALES_LOOKUP, stream);
        // 独立网点查询(未绑定设备)→ AFTERSALES_PROVIDED;诊断引导(已绑定)→ UNFIXED_AFTERSALES
        ConclusionType type = session.getDeviceId() == null
                ? ConclusionType.AFTERSALES_PROVIDED : ConclusionType.UNFIXED_AFTERSALES;
        doAfterSalesLookup(session, locationText, type, stream);
    }

    private void doAfterSalesLookup(RepairSession session, String locationText,
                                    ConclusionType type, SseEventStream stream) {
        List<AfterSalesLocation> locations = afterSalesGuide.searchNearby(locationText);
        List<AfterSalesLocation> result = locations.isEmpty()
                ? List.of(afterSalesGuide.officialHotline())
                : locations;
        String summary = locations.isEmpty()
                ? "未查询到附近售后网点,建议联系官方客服。"
                : "已为您找到 %d 个附近售后网点。".formatted(locations.size());
        transition(session, SessionStatus.AFTERSALES_LOOKUP,
                SessionStatus.COMPLETED_AFTERSALES, stream);
        finish(session, type, summary, Map.of("afterSales", result));
        releaseLock(session);
        saveMessage(session.getId(), MessageRole.ASSISTANT, summary);
        stream.conclude(buildConclusion(session));
    }

    // ========== 辅助 ==========

    private void transition(RepairSession session, SessionStatus from, SessionStatus to,
                            SseEventStream stream) {
        transitionLog.transit(session, from, to);
        stream.send(SseEvent.status(session.getId(), to.name()));
    }

    private boolean looksLikeLocationProvided(String text) {
        return text != null && (text.contains("市") || text.contains("区") || text.contains("县"));
    }

    private int currentRound(Long sessionId) {
        ProblemReport latest = reportOf(sessionId);
        return latest == null || latest.getRound() == null ? 1 : latest.getRound();
    }

    private RepairSession loadOwnedSession(AuthUser user, Long sessionId) {
        RepairSession session = sessionMapper.selectOneById(sessionId);
        if (session == null) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在", sessionId);
        }
        if (!session.getUserId().equals(user.userId())) {
            throw new ApiException(ErrorCode.DEVICE_FORBIDDEN, "无权访问该会话", sessionId);
        }
        return session;
    }

    private ProblemReport reportOf(Long sessionId) {
        return reportMapper.selectOneByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId).orderBy("id", false).limit(1));
    }

    private void appendClarification(ProblemReport report, String content) {
        try {
            List<String> list = report.getClarifications() == null
                    ? new java.util.ArrayList<>()
                    : objectMapper.readValue(report.getClarifications(),
                    new TypeReference<java.util.ArrayList<String>>() {
                    });
            list.add(content);
            report.setClarifications(objectMapper.writeValueAsString(list));
            reportMapper.update(report);
        } catch (Exception e) {
            log.warn("澄清记录写入失败 session={}", report.getSessionId(), e);
        }
    }

    private ConclusionDto buildConclusion(RepairSession session) {
        if (session.getConclusionType() == null) {
            return null;
        }
        List<DiagnosticSnapshot> snapshots = snapshotMapper.selectListByQuery(QueryWrapper.create()
                .where("session_id = ?", session.getId()));
        Map<String, Object> pre = snapshots.stream()
                .filter(s -> SnapshotPhase.PRE.name().equals(s.getPhase()))
                .reduce((a, b) -> b).map(s -> fromJson(s.getPayload())).orElse(null);
        Map<String, Object> post = snapshots.stream()
                .filter(s -> SnapshotPhase.POST.name().equals(s.getPhase()))
                .reduce((a, b) -> b).map(s -> fromJson(s.getPayload())).orElse(null);
        Map<String, Object> extra = session.getConclusionExtra() == null
                ? Map.of() : fromJson(session.getConclusionExtra());
        return new ConclusionDto(
                session.getConclusionType(),
                session.getConclusion(),
                extra.containsKey("preDiagnostics")
                        ? typedMap(extra.get("preDiagnostics")) : pre,
                post,
                extra.containsKey("manualSteps")
                        ? typedList(extra.get("manualSteps"), String.class) : null,
                extra.containsKey("afterSales")
                        ? typedList(extra.get("afterSales"), AfterSalesLocation.class) : null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> typedMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    private <T> List<T> typedList(Object value, Class<T> elementType) {
        return objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private void finish(RepairSession session, ConclusionType type, String summary,
                        Map<String, Object> extra) {
        if (type != null) {
            session.setConclusionType(type.name());
        }
        if (summary != null) {
            session.setConclusion(summary);
        }
        if (extra != null) {
            try {
                session.setConclusionExtra(objectMapper.writeValueAsString(extra));
            } catch (Exception ignored) {
            }
        }
        sessionMapper.update(session);
        contextStore.evict(session.getId());
    }

    private void releaseLock(RepairSession session) {
        if (session.getDeviceId() != null) {
            lockService.release(session.getDeviceId(), session.getId());
        }
    }

    private String replyFor(RepairSession session, SessionStatus status) {
        return switch (status) {
            case CLARIFYING, DEVICE_CONFIRMING, CONFIRMING_REPAIR, AWAITING_LOCATION ->
                    lastAssistantMessage(session.getId());
            case REPAIRING, VERIFYING -> "正在执行操作,请稍后查询结果。";
            default -> session.getConclusion();
        };
    }

    private String lastAssistantMessage(Long sessionId) {
        ChatMessage msg = messageMapper.selectOneByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId)
                .and("role = ?", MessageRole.ASSISTANT.name())
                .orderBy("id", false).limit(1));
        return msg == null ? null : msg.getContent();
    }

    private String buildCandidatePrompt(List<Device> candidates, String deviceType) {
        StringBuilder sb = new StringBuilder("您名下有多台")
                .append(displayName(deviceType)).append(",请回复序号确认目标设备:");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(" %d) %s;".formatted(i + 1, candidates.get(i).getName()));
        }
        return sb.toString();
    }

    private String displayName(String deviceType) {
        DeviceTypeRegistryProperties.DeviceTypeSpec spec = registryProperties.specOf(deviceType);
        return spec == null ? deviceType : spec.displayName();
    }

    private void saveContext(RepairSession session, Long deviceId, String deviceType,
                             List<Device> candidates) {
        saveContext(session, deviceId, deviceType, candidates, null, null);
    }

    private void saveContext(RepairSession session, Long deviceId, String deviceType,
                             List<Device> candidates, String pendingActionCode,
                             String pendingActionParams) {
        String candidateIds = null;
        if (candidates != null) {
            candidateIds = candidates.stream().map(d -> String.valueOf(d.getId()))
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        contextStore.save(new SessionContext(session.getId(), session.getUserId(),
                SessionStatus.valueOf(session.getStatus()), candidateIds, deviceId,
                deviceType, pendingActionCode, pendingActionParams));
    }

    private List<Long> parseIdList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private void saveSnapshot(Long sessionId, int round, SnapshotPhase phase,
                              Map<String, Object> payload) {
        try {
            DiagnosticSnapshot snapshot = new DiagnosticSnapshot();
            snapshot.setSessionId(sessionId);
            snapshot.setRound(round);
            snapshot.setPhase(phase.name());
            snapshot.setPayload(objectMapper.writeValueAsString(payload));
            snapshotMapper.insert(snapshot);
        } catch (Exception e) {
            log.warn("诊断快照写入失败 session={}", sessionId, e);
        }
    }

    private void saveMessage(Long sessionId, MessageRole role, String content) {
        if (content == null) {
            return;
        }
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role.name());
        message.setContent(content);
        messageMapper.insert(message);
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}

package com.chh.autosense.core.session;

import com.chh.autosense.domain.message.SseEvent;
import com.chh.autosense.domain.message.SseEventStream;
import com.chh.autosense.core.aftersales.AfterSalesLocation;
import com.chh.autosense.core.device.DeviceAdapterRegistry;
import com.chh.autosense.core.device.rule.FaultRuleEngine;
import com.chh.autosense.core.device.rule.FaultVerdict;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.core.device.spi.DeviceUnreachableException;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.enums.ConclusionType;
import com.chh.autosense.domain.enums.MessageRole;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.domain.enums.SnapshotPhase;
import com.chh.autosense.domain.entity.ChatMessage;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.domain.entity.DiagnosticSnapshot;
import com.chh.autosense.domain.entity.RepairKnowledge;
import com.chh.autosense.domain.entity.RepairSession;
import com.chh.autosense.service.knowledge.RepairKnowledgeService;
import com.chh.autosense.core.repair.RepairExecutor;
import com.chh.autosense.mapper.ChatMessageMapper;
import com.chh.autosense.mapper.DeviceMapper;
import com.chh.autosense.mapper.DiagnosticSnapshotMapper;
import com.chh.autosense.mapper.ProblemReportMapper;
import com.chh.autosense.mapper.RepairSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 修复执行(T025/T026/T029,2026-08-27 刷新):REPAIRING → 执行(失败即停,
 * FR-017)→ VERIFYING 复检(规则重判)→ 终态;全部在同一 SSE 流内同步推进(R18),
 * 全程写日志,结束释放设备锁。
 */
@Slf4j
@Service
public class RepairExecutionRunner {

    private final RepairSessionMapper sessionMapper;
    private final DeviceMapper deviceMapper;
    private final DiagnosticSnapshotMapper snapshotMapper;
    private final ProblemReportMapper reportMapper;
    private final ChatMessageMapper messageMapper;
    private final DeviceAdapterRegistry adapterRegistry;
    private final RepairKnowledgeService knowledgeService;
    private final RepairExecutor repairExecutor;
    private final FaultRuleEngine faultRuleEngine;
    private final DeviceLockService lockService;
    private final SessionContextStore contextStore;
    private final SessionTransitionLog transitionLog;
    private final ObjectMapper objectMapper;

    public RepairExecutionRunner(RepairSessionMapper sessionMapper, DeviceMapper deviceMapper,
                                 DiagnosticSnapshotMapper snapshotMapper,
                                 ProblemReportMapper reportMapper,
                                 ChatMessageMapper messageMapper,
                                 DeviceAdapterRegistry adapterRegistry,
                                 RepairKnowledgeService knowledgeService,
                                 RepairExecutor repairExecutor,
                                 FaultRuleEngine faultRuleEngine,
                                 DeviceLockService lockService,
                                 SessionContextStore contextStore,
                                 SessionTransitionLog transitionLog,
                                 ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.deviceMapper = deviceMapper;
        this.snapshotMapper = snapshotMapper;
        this.reportMapper = reportMapper;
        this.messageMapper = messageMapper;
        this.adapterRegistry = adapterRegistry;
        this.knowledgeService = knowledgeService;
        this.repairExecutor = repairExecutor;
        this.faultRuleEngine = faultRuleEngine;
        this.lockService = lockService;
        this.contextStore = contextStore;
        this.transitionLog = transitionLog;
        this.objectMapper = objectMapper;
    }

    public void run(Long sessionId, String actionCode, Map<String, Object> params,
                    SseEventStream stream) {
        RepairSession session = sessionMapper.selectOneById(sessionId);
        Device device = deviceMapper.selectOneById(session.getDeviceId());
        try {
            String diagnosticType = diagnosticTypeOf(device);
            DeviceAdapter adapter = adapterRegistry.adapterOf(device)
                    .orElseThrow(() -> new IllegalStateException("无适配器: " + diagnosticType));

            DeviceAdapter.RepairOutcome outcome =
                    repairExecutor.execute(sessionId, device, adapter, actionCode, params);
            transition(session, SessionStatus.REPAIRING, SessionStatus.VERIFYING, stream);

            if (!outcome.success()) {
                // 失败即停(FR-017):如实报告 + 当前状态,转人工引导
                Map<String, Object> post = trySnapshot(session, device);
                String current = post == null ? "设备当前状态未知" : "设备当前状态: " + post;
                guideAfterUnfixed(session, device,
                        "操作执行失败:%s。%s".formatted(outcome.message(), current), stream);
                return;
            }

            // 复检:重新读 state 并规则重判(R12/T026)
            Map<String, Object> post = adapter.getDiagnostics(device);
            saveSnapshot(sessionId, SnapshotPhase.POST, post);
            FaultVerdict verdict = faultRuleEngine.evaluate(diagnosticType, post,
                    String.valueOf(post.getOrDefault("running_status", "running")));
            if (verdict.kind() == FaultVerdict.Kind.NORMAL) {
                transition(session, SessionStatus.VERIFYING, SessionStatus.COMPLETED_FIXED, stream);
                session.setConclusionType(ConclusionType.FIXED.name());
                session.setConclusion("已修复:%s。复检显示设备恢复正常。"
                        .formatted(outcome.message()));
                sessionMapper.update(session);
                saveMessage(sessionId, session.getConclusion());
                stream.conclude(buildConclusion(session));
            } else {
                // 复检未恢复:按复检规则结论转人工引导
                guideAfterUnfixed(session, device,
                        "操作已执行但复检仍未恢复正常(%s)。".formatted(verdict.reason()), stream);
            }
        } catch (DeviceUnreachableException e) {
            transitionSafely(session, SessionStatus.VERIFYING, stream);
            transition(session, SessionStatus.VERIFYING,
                    SessionStatus.FAILED_DEVICE_UNREACHABLE, stream);
            session.setConclusionType(ConclusionType.DEVICE_UNREACHABLE.name());
            session.setConclusion("操作后复检时设备不可达,请检查设备电源与网络连接。");
            sessionMapper.update(session);
            saveMessage(sessionId, session.getConclusion());
            stream.conclude(buildConclusion(session));
        } catch (Exception e) {
            // 细节仅落日志(章程 V):面向用户不泄露内部异常信息
            log.error("修复执行异常 session={}", sessionId, e);
            session.setStatus(SessionStatus.COMPLETED_UNFIXED.name());
            session.setConclusionType(ConclusionType.UNFIXED_MANUAL_GUIDE.name());
            session.setConclusion("操作过程出现异常,未做进一步操作,请稍后再试或联系售后。");
            sessionMapper.update(session);
            saveMessage(sessionId, session.getConclusion());
            stream.conclude(buildConclusion(session));
        } finally {
            if (device != null) {
                lockService.release(device.getId(), sessionId);
            }
            contextStore.evict(sessionId);
        }
    }

    /** 失败/未恢复 → 人工引导:有人工步骤 GUIDED_MANUAL,否则等位置(FR-010/011)。 */
    private void guideAfterUnfixed(RepairSession session, Device device, String reasonText,
                                   SseEventStream stream) {
        Optional<RepairKnowledge> knowledge = knowledgeService
                .findSolution(diagnosticTypeOf(device), reasonText);
        if (knowledge.isPresent() && knowledge.get().getManualSteps() != null
                && !Boolean.TRUE.equals(knowledge.get().getAutoExecutable())) {
            transition(session, SessionStatus.VERIFYING, SessionStatus.GUIDED_MANUAL, stream);
            session.setConclusionType(ConclusionType.UNFIXED_MANUAL_GUIDE.name());
            session.setConclusion(reasonText);
            session.setConclusionExtra(toJson(Map.of(
                    "manualSteps", List.of(knowledge.get().getManualSteps().split("\n")))));
            sessionMapper.update(session);
            saveMessage(session.getId(), reasonText);
            stream.conclude(buildConclusion(session));
        } else {
            transition(session, SessionStatus.VERIFYING, SessionStatus.GUIDED_AFTERSALES, stream);
            transition(session, SessionStatus.GUIDED_AFTERSALES,
                    SessionStatus.AWAITING_LOCATION, stream);
            String reply = reasonText + " 请提供您所在的位置(城市/区县),我帮您查询附近售后网点。";
            sessionMapper.update(session);
            saveMessage(session.getId(), reply);
            // 等待用户位置:awaiting 事件后关流(FR-021),用户经 §2 补充位置继续
            stream.awaitUser(session.getId(), reply);
        }
    }

    private Map<String, Object> trySnapshot(RepairSession session, Device device) {
        try {
            DeviceAdapter adapter = adapterRegistry.adapterOf(device).orElse(null);
            if (adapter == null) {
                return null;
            }
            Map<String, Object> post = adapter.getDiagnostics(device);
            saveSnapshot(session.getId(), SnapshotPhase.POST, post);
            return post;
        } catch (Exception e) {
            return null;
        }
    }

    private void transition(RepairSession session, SessionStatus from, SessionStatus to,
                            SseEventStream stream) {
        transitionLog.transit(session, from, to);
        stream.send(SseEvent.status(session.getId(), to.name()));
    }

    private String diagnosticTypeOf(Device device) {
        return adapterRegistry.diagnosticTypeOf(device)
                .orElseThrow(() -> new IllegalStateException(
                        "无诊断类型映射: " + device.getDeviceTypeCode()
                                + "/" + device.getDeviceModelCode()));
    }

    /** 不可达时 session 可能停在 REPAIRING:先推进到 VERIFYING。 */
    private void transitionSafely(RepairSession session, SessionStatus target,
                                  SseEventStream stream) {
        SessionStatus current = SessionStatus.valueOf(session.getStatus());
        if (current != target) {
            transitionLog.transit(session, current, target);
            stream.send(SseEvent.status(session.getId(), target.name()));
        }
    }

    private ConclusionDto buildConclusion(RepairSession session) {
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
                session.getConclusionType(), session.getConclusion(), pre, post,
                extra.containsKey("manualSteps")
                        ? typedList(extra.get("manualSteps"), String.class) : null,
                extra.containsKey("afterSales")
                        ? typedList(extra.get("afterSales"),
                                AfterSalesLocation.class) : null);
    }

    private <T> List<T> typedList(Object value, Class<T> elementType) {
        return objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private void saveSnapshot(Long sessionId, SnapshotPhase phase, Map<String, Object> payload) {
        DiagnosticSnapshot snapshot = new DiagnosticSnapshot();
        snapshot.setSessionId(sessionId);
        snapshot.setRound(currentRound(sessionId));
        snapshot.setPhase(phase.name());
        snapshot.setPayload(toJson(payload));
        snapshotMapper.insert(snapshot);
    }

    /** 当前轮次(T028):取最新 problem_report 的 round,缺省 1。 */
    private int currentRound(Long sessionId) {
        var report = reportMapper.selectOneByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId).orderBy("id", false).limit(1));
        return report == null || report.getRound() == null ? 1 : report.getRound();
    }

    private void saveMessage(Long sessionId, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(MessageRole.ASSISTANT.name());
        message.setContent(content);
        messageMapper.insert(message);
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
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

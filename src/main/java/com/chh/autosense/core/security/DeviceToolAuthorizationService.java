package com.chh.autosense.core.security;

import com.chh.autosense.core.session.WorkflowApprovalService;
import com.chh.autosense.core.session.WorkflowClaimService;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.mapper.DeviceMapper;
import com.chh.autosense.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Short database checks; external calls never run while holding these locks. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceToolAuthorizationService {
    private final WorkflowClaimService claims;
    private final WorkflowApprovalService approvals;
    private final UserMapper users;
    private final DeviceMapper devices;
    private final DeviceOwnershipChecker ownership;
    private final com.chh.autosense.service.DeviceOnlineInfoService runtime;

    @Transactional
    public DeviceToolExecutionContext query(AssistantState state, Instant deadline) {
        var context = new DeviceToolExecutionContext(state, DeviceToolExecutionContext.Phase.APPROVED_QUERY, deadline);
        checkQuery(context);
        return context;
    }

    /** Internal batch callers must supply independently confirmed current steps of the same user. */
    @Transactional
    public DeviceToolExecutionContext queries(List<AssistantState> states, Instant deadline) {
        var context = new DeviceToolExecutionContext(states, DeviceToolExecutionContext.Phase.APPROVED_QUERY, deadline);
        var sns = new java.util.ArrayList<String>();
        for (var state : context.states()) {
            if (state.request().userId() != context.actor().userId()) throw denied("BATCH_IDENTITY_MISMATCH");
            Object sn = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved().get("sn");
            if (!(sn instanceof String value) || sns.contains(value)) throw denied("BATCH_SCOPE_MISMATCH");
            sns.add(value);
        }
        requireQueryScope(context, sns);
        return context;
    }

    @Transactional
    public void requireQueryScope(DeviceToolExecutionContext context, List<String> sns) {
        if (context == null || sns == null || sns.isEmpty()) throw denied("CONTEXT_REQUIRED");
        for (String sn : sns) {
            var selected = context.forSn(sn);
            if (!selected.actor().equals(context.actor())) throw denied("BATCH_IDENTITY_MISMATCH");
            checkQuery(selected);
            var target = selected.state().<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
            if (!(target.get("capabilityHash") instanceof String hash) || hash.isBlank()
                    || !(target.get("getScope") instanceof List<?> scope) || scope.isEmpty()) throw denied("QUERY_SCOPE_MISMATCH");
        }
    }

    @Transactional
    public void requireProfile(DeviceToolExecutionContext context, String sn, String hash) {
        requireQuery(context.forSn(sn), List.of(sn), hash);
    }

    @Transactional
    public void requireQuery(DeviceToolExecutionContext context, List<String> sns, String capabilityHash) {
        checkQuery(context);
        var target = context.state().<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        if (sns == null || sns.isEmpty() || !Objects.equals(target.get("capabilityHash"), capabilityHash)
                || capabilityHash == null || capabilityHash.isBlank()
                || !(target.get("getScope") instanceof List<?> scope) || scope.isEmpty())
            throw denied("QUERY_SCOPE_MISMATCH");
        // The graph authorizes one device per step. A list-shaped tool input cannot expand it.
        for (String sn : sns) {
            if (!Objects.equals(sn, target.get("sn"))) throw denied("QUERY_TARGET_MISMATCH");
            var device = devices.selectOneBySn(sn);
            ownership.check(device, context.actor());
            String bindingHash = com.chh.autosense.graph.node.PlanValidator.digest(List.of(
                    Objects.toString(device.getSimulatorDeviceId(), ""), Objects.toString(device.getDeviceTypeCode(), ""),
                    Objects.toString(device.getDeviceModelCode(), "")));
            if (!(target.get("deviceRef") instanceof Number id) || device.getId() != id.longValue()
                    || !bindingHash.equals(target.get("bindingHash")))
                throw denied("DEVICE_BINDING_CHANGED");
        }
    }

    private void checkQuery(DeviceToolExecutionContext context) {
        checkIdentity(context);
        var state = context.state();
        if (context.phase() != DeviceToolExecutionContext.Phase.APPROVED_QUERY
                || state.plan().currentStep() >= state.plan().executionPlan().steps().size()
                || state.plan().step().type() != PlanStepType.DEVICE_QUERY)
            throw denied("QUERY_SCOPE_MISMATCH");
        approvals.requireApproved(state);
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        var identity = runtime.runtimeIdentity();
        if (!Objects.equals(target.get("runtimeProvider"), identity.provider())
                || !Objects.equals(target.get("runtimeSource"), identity.source())
                || !Objects.equals(target.get("runtimeEndpointHash"), identity.endpointHash()))
            throw denied("RUNTIME_IDENTITY_CHANGED");
    }

    private void checkIdentity(DeviceToolExecutionContext context) {
        if (context == null) throw denied("CONTEXT_REQUIRED");
        if (!context.deadline().isAfter(Instant.now())) throw new java.util.concurrent.CompletionException(
                new java.util.concurrent.TimeoutException("Device query deadline exceeded"));
        var workflow = claims.requireFence(context.requestId(), context.fence());
        var state = context.state();
        if (!Objects.equals(workflow.getUserId(), context.actor().userId())
                || !Objects.equals(workflow.getSessionId(), state.request().conversationId())
                || WorkflowStatus.valueOf(workflow.getStatus()).terminal()
                || !Objects.equals(workflow.getCurrentStepIndex(), state.plan().currentStep()))
            throw denied("WORKFLOW_IDENTITY_MISMATCH");
        var user = users.selectByIdIncludingDeleted(context.actor().userId());
        if (user == null || Integer.valueOf(1).equals(user.getIsDelete())) throw denied("ACCOUNT_UNAVAILABLE");
    }

    private SecurityException denied(String reason) {
        log.warn("Device tool authorization denied: reason={}", reason);
        return new SecurityException(reason);
    }
}

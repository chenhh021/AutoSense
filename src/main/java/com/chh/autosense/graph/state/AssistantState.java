package com.chh.autosense.graph.state;

import com.chh.autosense.domain.enums.WorkflowStatus;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import java.io.Serializable;
import java.time.Instant;
import java.util.*;

public final class AssistantState extends AgentState {
    public static final String REQUEST = "requestContext", PLAN = "planContext",
            WORKFLOW = "workflowContext", DEVICE = "deviceContext", DIAGNOSIS = "diagnosisContext",
            CONTROL = "controlContext", RETRY = "retryContext", AUDIT = "auditContext",
            OUTPUT = "outputContext", MESSAGES = "messages";

    public AssistantState(Map<String, Object> data) { super(checked(data)); }

    private static Map<String, Object> checked(Map<String, Object> data) {
        var result = new LinkedHashMap<>(defaults());
        data.forEach((key, value) -> {
            Object expected = result.get(key);
            if (expected == null || value == null || !(key.equals(MESSAGES)
                    ? value instanceof List<?> : expected.getClass().isInstance(value))) {
                throw new IllegalArgumentException("Invalid graph context type");
            }
            result.put(key, value);
        });
        var plan = (PlanContext) result.get(PLAN);
        var workflow = (WorkflowContext) result.get(WORKFLOW);
        if (plan.currentStep() != workflow.currentStep()) {
            throw new IllegalArgumentException("Workflow cursor projection is inconsistent");
        }
        @SuppressWarnings("unchecked") var messages = (List<Message>) result.get(MESSAGES);
        result.put(MESSAGES, window(messages));
        return result;
    }

    public static Map<String, Channel<?>> schema() {
        Map<String, Channel<?>> schema = new LinkedHashMap<>();
        defaults().forEach((key, value) -> schema.put(key, Channels.base(() -> value)));
        schema.put(MESSAGES, Channels.base((List<Message> prior, List<Message> next) -> window(next),
                List::of));
        return Collections.unmodifiableMap(schema);
    }

    public static Map<String, Object> initial(RequestContext request) {
        if (request == null || request.requestId() == null || request.requestId().isBlank()
                || request.conversationId() <= 0 || request.userId() <= 0)
            throw new IllegalArgumentException("Invalid workflow identity");
        var data = new LinkedHashMap<>(defaults());
        data.put(REQUEST, request);
        return Collections.unmodifiableMap(data);
    }

    private static Map<String, Object> defaults() {
        return Map.of(
                REQUEST, new RequestContext("", 0, 0, ""),
                PLAN, new PlanContext(ExecutionPlan.empty(), 0, Map.of(), Map.of()),
                WORKFLOW, new WorkflowContext(WorkflowStatus.CREATED, 0, new Progress(0, 0, 0, 0),
                        0, "", "", "", ""),
                DEVICE, new DeviceContext(Map.of(), Map.of()),
                DIAGNOSIS, new DiagnosisContext(Map.of(), List.of(), Map.of(), Map.of()),
                CONTROL, new ControlContext(Map.of(), "", false, "", null, "", Map.of()),
                RETRY, new RetryContext(0, "", "", null, Map.of()),
                AUDIT, new AuditContext("", null, null, 0, List.of(), List.of()),
                OUTPUT, new OutputContext("", "", "", Map.of()),
                MESSAGES, List.of());
    }

    public RequestContext request() { return this.<RequestContext>value(REQUEST).orElseThrow(); }
    public PlanContext plan() { return this.<PlanContext>value(PLAN).orElseThrow(); }
    public WorkflowContext workflow() { return this.<WorkflowContext>value(WORKFLOW).orElseThrow(); }
    public ControlContext control() { return this.<ControlContext>value(CONTROL).orElseThrow(); }
    public RetryContext retry() { return this.<RetryContext>value(RETRY).orElseThrow(); }
    public OutputContext output() { return this.<OutputContext>value(OUTPUT).orElseThrow(); }
    public List<Message> messages() { return window(this.<List<Message>>value(MESSAGES).orElse(List.of())); }

    public static List<Message> window(List<Message> source) {
        if (source == null) return List.of();
        var unique = new LinkedHashMap<Long, Message>();
        for (Message message : source) unique.put(message.id(), message);
        var values = new ArrayList<>(unique.values());
        return List.copyOf(values.subList(Math.max(0, values.size() - 21), values.size()));
    }

    public record RequestContext(String requestId, long conversationId, long userId,
                                 String userMessage) implements Serializable { }
    public record PlanContext(ExecutionPlan executionPlan, int currentStep,
                              Map<String, Object> runtimeInputs,
                              Map<String, ExecutionPlan.Result> results,
                              String candidateOutcome, String clarifyQuestion) implements Serializable {
        public PlanContext(ExecutionPlan executionPlan, int currentStep, Map<String, Object> runtimeInputs,
                           Map<String, ExecutionPlan.Result> results) {
            this(executionPlan, currentStep, runtimeInputs, results, "PLAN", "");
        }
        public PlanContext {
            Objects.requireNonNull(executionPlan);
            if (currentStep < 0 || currentStep > executionPlan.steps().size())
                throw new IllegalArgumentException("Invalid workflow cursor");
            runtimeInputs = StateData.freeze(runtimeInputs);
            results = results == null ? Map.of() : Map.copyOf(results);
        }
        public ExecutionPlan.Step step() { return executionPlan.steps().get(currentStep); }
    }
    public record Progress(int total, int completed, int skipped, int notExecuted) implements Serializable { }
    public record WorkflowContext(WorkflowStatus status, int currentStep, Progress progress, long version,
                                  String inputRequestId, String prompt, String returnNode,
                                  String failureCode, int schemaVersion, String graphVersion,
                                  long lastOutputSequence, long executionFence) implements Serializable {
        public WorkflowContext(WorkflowStatus status, int currentStep, Progress progress, long version,
                               String inputRequestId, String prompt, String returnNode, String failureCode,
                               int schemaVersion, String graphVersion, long lastOutputSequence) {
            this(status, currentStep, progress, version, inputRequestId, prompt, returnNode, failureCode,
                    schemaVersion, graphVersion, lastOutputSequence, 0);
        }
        public WorkflowContext(WorkflowStatus status, int currentStep, Progress progress, long version,
                               String inputRequestId, String prompt, String returnNode, String failureCode) {
            this(status, currentStep, progress, version, inputRequestId, prompt, returnNode,
                    failureCode, com.chh.autosense.graph.checkpoint.AssistantStateSerializer.SCHEMA_VERSION,
                    com.chh.autosense.graph.checkpoint.AssistantStateSerializer.GRAPH_VERSION, 0);
        }
    }
    public record DeviceContext(ResolvedDevice target, Map<String, Object> snapshots,
                                List<com.chh.autosense.ai.model.DeviceBasicInfo> planningDevices, Map<String, Object> statusMetadata,
                                Map<String, Object> basicMetadata, boolean initialized, String initializedAt,
                                String snapshotHash) implements Serializable {
        public DeviceContext(Map<String, Object> resolved, Map<String, Object> snapshots) {
            this(resolved, snapshots, List.of(), Map.of(), Map.of(), false, "", "");
        }
        public DeviceContext(Map<String, Object> resolved, Map<String, Object> snapshots,
                             List<Map<String, Object>> planningDevices, Map<String, Object> statusMetadata,
                             Map<String, Object> basicMetadata, boolean initialized, String initializedAt,
                             String snapshotHash) {
            this(ResolvedDevice.from(resolved), snapshots, planningDevices.stream().map(value ->
                    new com.fasterxml.jackson.databind.ObjectMapper().convertValue(value,
                            com.chh.autosense.ai.model.DeviceBasicInfo.class)).toList(),
                    statusMetadata, basicMetadata, initialized, initializedAt, snapshotHash);
        }
        public DeviceContext {
            snapshots = StateData.freeze(snapshots);
            planningDevices = planningDevices == null ? List.of() : List.copyOf(planningDevices);
            statusMetadata = StateData.freeze(statusMetadata); basicMetadata = StateData.freeze(basicMetadata);
            initializedAt = Objects.requireNonNullElse(initializedAt, "");
            snapshotHash = Objects.requireNonNullElse(snapshotHash, "");
            if (initialized && (initializedAt.isBlank() || snapshotHash.isBlank()))
                throw new IllegalArgumentException("Initialized device snapshot needs identity and observation time");
        }
        public DeviceContext withStep(Map<String, Object> resolved, Map<String, Object> snapshots) {
            return new DeviceContext(ResolvedDevice.from(resolved), snapshots, planningDevices, statusMetadata, basicMetadata,
                    initialized, initializedAt, snapshotHash);
        }
        public Map<String, Object> resolved() { return target == null ? Map.of() : target.asMap(); }
        public com.chh.autosense.ai.model.DeviceBasicInfo requireDevice(long id) {
            if (!initialized) throw new IllegalArgumentException("Device snapshot is not initialized");
            return planningDevices.stream().filter(device -> device.id() != null && device.id() == id)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Device reference is outside planning snapshot"));
        }
    }
    public record DiagnosisContext(Map<String, Object> input, List<String> evidenceRefs,
                                   Map<String, Object> result, Map<String, Object> repairProposal) implements Serializable {
        public DiagnosisContext {
            input = StateData.freeze(input);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            result = StateData.freeze(result); repairProposal = StateData.freeze(repairProposal);
        }
    }
    public record Approval(String approvalId, String stepId, long userId, String scopeHash,
                           String status, Instant expiresAt) implements Serializable { }
    public record ControlContext(Map<String, Object> command, String commandExecutionId,
                                 boolean permission, String risk, Approval approvalRef,
                                 String idempotencyKey, Map<String, Object> executionResult) implements Serializable {
        public ControlContext {
            command = StateData.freeze(command); executionResult = StateData.freeze(executionResult);
        }
    }
    public record RetryContext(int retriesUsed, String activeAttemptId, String latestFailure,
                               Instant nextRetryAt, Map<String, Object> completedCalls) implements Serializable {
        public RetryContext { completedCalls = StateData.freeze(completedCalls); }
    }
    public record AuditContext(String workflowId, Long reportId, Long messageId, int round,
                               List<String> commandExecutionRefs, List<Long> auditEventRefs) implements Serializable {
        public AuditContext {
            commandExecutionRefs = commandExecutionRefs == null ? List.of() : List.copyOf(commandExecutionRefs);
            auditEventRefs = auditEventRefs == null ? List.of() : List.copyOf(auditEventRefs);
        }
    }
    public record OutputContext(String type, String code, String message,
                                Map<String, Object> data) implements Serializable {
        public OutputContext { data = StateData.freeze(data); }
    }
    public record Message(long id, String role, String content) implements Serializable { }
}

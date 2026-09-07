# Implementation validation: 002-assistant-foundation

Date: 2026-09-07. Branch: master. Implementation is in progress; only checked tasks are complete.

## Foundation checkpoint

- T001–T013 implemented and validated. Requirements checklist was read-only: 16 checked, 0 unchecked.
- `mvn dependency:tree "-Dscope=test" "-DoutputFile=target/dependency-tree-test.txt"`: exit 0. Log4j2 2.24.3 is the only SLF4J provider; SLF4J 2.0.17; one optional Lombok 1.18.36; no Logback or reverse bridge in the selected tree.
- `mvn -Pit "-Dtest=SessionProcessingIT,LoggingInfrastructureTest,LlmConfigurationTest,SessionStateMachineTest" -l target/foundation-validation.log test`: exit 0; 20 tests, 0 failures/errors/skips. Reports: `target/surefire-reports/`.
- SessionProcessingIT: 7 tests with isolated Testcontainers MySQL 8.0.18 and Redis. Repeated two-column migration preserved historical ID/status; concurrent admission, stale callbacks, normal-vs-expired conditions, rollback of visible messages/state/audit/pointer, history ordering/window, entity mappings/logical deletion and owner lease operations passed.
- LoggingInfrastructureTest: actual Log4j SLF4J binding, appender restoration, sensitive cause/suppressed removal, MDC scope restoration, server-generated request ID reuse and console pattern override passed.
- Configuration/state tests: real/mock validation, finite temperature, time budgets, fixed 20-message window, new dispatch/failure transitions and manual-guide terminal behavior passed.
- Existing DeviceSimulatorClientTest could not compile due to an obsolete package; moved it into the client package without changing its behavioral assertions or widening the production constructor.
- Fixed the XML default pattern expansion after a full application run exposed duplicate formatting. The successful rerun emitted each application log once.
- SQL initialization now defaults to `never`; new isolated tests explicitly opt into `always`. Knowledge seeding is insert-if-absent. Existing databases require the forward migration before deploying this version. Development identity defaults to false.
- Testcontainers runs need Docker access outside the restricted sandbox. A sandbox-only rerun failed before tests with Docker unavailable; the authorized rerun above passed. No persistent business database or device was modified.


## US1 validation (T014-T029)

Date: 2026-09-07.

- Prompt resources: six files under `src/main/resources/prompt/` (intent-router, problem-analysis, diagnosis-reasoner, direct-answer system templates with zero variables; conversation-input / diagnosis-input user templates whose variables exactly match the @V parameters). `AiServiceFactory` startup validation rejects missing resources, BOM, non-UTF-8, blank templates, inline values and variable mismatch; any failure aborts startup with zero model requests and no mock fallback.
- `mvnw.cmd test` (default profile): 115 tests, 0 failures. Includes AiServiceFactoryTest (annotation/resource/variable validation, sample render with literal braces escaped by PromptInputEncoder), PromptInputEncoder tests (`{`/`}` escaped to {/} in string values/keys only, RawValue rejected), LangChain4j adapter tests (structure failure -> clarification, transport failure -> AI_SERVICE_UNAVAILABLE, no fallback answers, no fake success).
- `mvnw.cmd test -Pit -Dtest=AssistantRoutingIT,SessionProcessingIT` with Docker: AssistantRoutingIT 3/3, SessionProcessingIT 7/7, BUILD SUCCESS. Recording capabilities confirm SINGLE dispatch reaches the matching capability, CLARIFY/COMPOSITE/OUT_OF_SCOPE never dispatch, and public routing performs zero device reads/writes.
- Deleted ChatMemoryFactory and RedisChatMemoryStore; no ChatMemory/@MemoryId/tools remain in AI Services; prompts, user text and credentials stay out of logs (LogCaptureSupport-backed assertions in logging tests).


## US2 validation (T030-T040)

Date: 2026-09-07.

- `mvnw.cmd test` (default profile): 115 tests, 0 failures, BUILD SUCCESS. Adds UserServiceTest row-lock stubs and UTF-8 72/73-byte password boundaries (72 bytes registers and matches BCrypt; 73 bytes -> BAD_REQUEST, zero insert, no truncation), UserTokenResolverTest (enabled-account check, role from database, Redis-unavailable deny), DeviceLockServiceTest result mapping, UserApiContractTest me-via-service and 72/73-byte contract cases (error body and captured logs contain no password).
- `mvnw.cmd test -Pit -Dtest=TokenRevocationIT,DeviceLockIT` with Docker: TokenRevocationIT 7/7, DeviceLockIT 3/3, BUILD SUCCESS. Real MySQL/Redis evidence: token and usertokens index share a unified 7-day rolling TTL; resolve renews both keys; orphan token (missing index member) is invalid and never re-added; logout deletes both; disable-then-enable never revives old tokens; concurrent issues are all valid and all revoked by disable; injected Redis failure during enable/disable rolls back the database (enable failure keeps the user disabled, disable failure keeps the account active). DeviceLockIT on real Redis: wrong-owner renew/release fail with the lock intact; expiry race lets a new owner relock; owner renew resets TTL to ~10 minutes.
- English sanitized logs only: register/login/disable/enable results, device bind/list aggregates and simulator client operation/result/elapsedMs events carry no password, token, SN, device name or response body.
- Blocked (pre-existing, unchanged): UserManagementIT and the extended DeviceBindingConcurrencyIT require the external deviceSimulator image (devicesimulator:latest) at localhost:8081. The image is not installed locally and cannot be pulled (pull access denied); `docker compose up -d device-simulator` fails. These simulator-tagged ITs retain the external prerequisite and were not run; no dev token was used to prove real revocation.


## US3 validation (T041-T052)

Date: 2026-09-07.

- `mvnw.cmd test` (default profile): 125 tests, 0 failures, BUILD SUCCESS. SessionApiContractTest adds GET history/list compatibility, SESSION_BUSY/FAILED_REQUEST+REQUEST_TIMEOUT/CONTEXT_EXPIRED error events and 401s; SessionStateMachineTest adds DISPATCHING increments, awaiting-state cancellation to COMPLETED_UNFIXED, terminal-only-to-ROUTING (FAILED_REQUEST, GUIDED_MANUAL) and illegal-transition rejections.
- `mvnw.cmd test -Pit -Dtest=ConversationHistoryIT,SessionLifecycleIT,AssistantLoggingIT` with Docker: 13 tests, 0 failures, BUILD SUCCESS.
  - ConversationHistoryIT 7/7: cross-capability multi-round history ordered and complete; current input stored exactly once; identical adjacent texts not deduplicated; literal `{{history}}`/backslash input round-trips verbatim through DB and GET with no prompt wrapper persisted; other user's history returns 403; stale v1 Hash/memory Redis keys do not affect DB-driven continuation; conclusion manualSteps/afterSales preserved in GET while the processing pointer is cleared in the same transaction; waiting continuation with missing v2 context fails with CONTEXT_EXPIRED and zero dispatch.
  - SessionLifecycleIT 4/4: concurrent same-session post rejected SESSION_BUSY with zero new messages; past deadline settled by GET compensation to FAILED_REQUEST/REQUEST_TIMEOUT (no Redis-lease replay); late capability callback after settlement never writes or changes state; stolen lease does not block DB-pointer-authoritative completion; GET during processing reports DISPATCHING without settling. Recovery replays no model call and performs no device write.
  - AssistantLoggingIT 2/2: two concurrent sessions with interleaved async completions keep MDC sessionId consistent per event (message sessionId == MDC sessionId); completion events at INFO with result=SUCCESS; business failure logged result=FAILED with errorCode, no fake success; rendered logs contain no user text, password, Bearer token, prompt template markers or per-token content.
- Compliance fix found by AssistantLoggingIT: MyBatis mapper SQL logging leaked statement parameters (user text, password hash) under DEBUG/TRACE capture. `com.chh.autosense.mapper` is now pinned to INFO in log4j2-spring.xml so parameters can never be logged at any level.
- VO migration: ChatMessageView/SessionListItemView (plus UserView/AdminUserPageView/DeviceView from US2) now live under domain/vo; DTOs keep requests, LoginResponse, SessionResponse and ConclusionDto; records/JSON shapes unchanged.
- Known noise: SessionLifecycleIT's deadline-recovery test intentionally leaves the stale SSE stream untouched (no replay on disconnect); the container's graceful shutdown waits for that emitter to time out, so Surefire reports "kill self fork JVM" after System.exit(0). Tests and build succeed.


## T053 compliance review (source/assembly vs contracts)

Date: 2026-09-08. Scope: plan.md, contracts/routing-contract.md, contracts/logging-contract.md, contracts/prompt-contract.md, constitution 2.3.0.

- AI call chain has no bypass: `ChatModel`/`StreamingChatModel` are built only in `LangChain4jConfig` and consumed only by `AiServiceFactory`; the four capabilities (IntentClassifier, ProblemAnalyzer, DiagnosisReasoner, DirectAnswerer) are reached only through the factory's four AI Service proxies. Verified by source scan: no other references to ChatModel/StreamingChatModel in business code.
- Prompt contract: all fixed rules and wrappers live in the six `src/main/resources/prompt/` resources and are bound by method-level `@SystemMessage(fromResource=...)`/`@UserMessage(fromResource=...)`; zero inline prompt values in code (`@SystemMessage("` scan empty). `AiServiceFactory` validates UTF-8/no-BOM/non-blank/zero system variables/exact user-variable match before any proxy is published; failure aborts startup with zero model requests, no mock or inline fallback. User input reaches templates only via `PromptInputEncoder` (`{`/`}` escaped to {/} inside string values/keys; RawValue rejected); user data never enters system templates.
- No ChatMemory/@MemoryId/tools remain; `ChatMemoryProperties` and the dead `autosense.chat-memory` config were removed (window 20 is fixed in `ChatMessageMapper.historyBefore` SQL).
- Routing contract: AI output is candidate-only; `RoutingDecisionValidator` clarifies invalid/unknown output; public routing performs zero device reads/writes (AssistantRoutingIT with `verifyNoInteractions(deviceClient)`); missing capability handlers fail with CAPABILITY_NOT_AVAILABLE, no DEVICE_ACTION fallback; REROUTE accepted only from waiting continuation (REROUTE_WITHOUT_WAIT rejected).
- Logging contract: all session/user/device/AI events are English parameterized messages with whitelisted MDC keys (LogContextUtils); records/DTOs are never logged wholesale (scan of log statements: scalars only); `LogSanitizer.diagnostic` strips sensitive message/cause/suppressed; mapper SQL parameter logging pinned to INFO (never emits user text or password hashes); no per-token or per-item INFO.
- Controllers contain no Mapper dependencies (scan of controller package empty); VOs (UserView/AdminUserPageView/DeviceView/ChatMessageView/SessionListItemView) under domain/vo, request DTOs and SessionResponse/ConclusionDto stay in domain/dto.
- FR evidence map: FR-001/002/003 — AiServiceFactory four services + MockIntentClassifier keyword routing + AssistantRoutingIT four-route/never-dispatch tests. FR-004/005 — factory + LangChain4jConfig, real mode fails explicitly (AI_SERVICE_UNAVAILABLE), mock only under autosense.llm.mode=mock. FR-006 — validator + CapabilityDispatcher + zero-device-write IT. FR-007 — VO/DTO re-home only, no duplicated user/device system. FR-008 — UserService register rules incl. UTF-8 72-byte boundary (unit + contract + IT evidence). FR-009 — AuthTokenService Lua issue/renew/revoke + UserTokenResolver enabled-account check + dev-mode off by default + TokenRevocationIT. FR-010/011 — DeviceRegistryService unchanged semantics + DeviceBindingConcurrencyIT extensions. FR-012 — ConversationHistoryService 20-window + ConversationHistoryIT. FR-013 — SessionController/SseEventStream five events + SessionApiContractTest. FR-014 — SessionTransitionLog/SessionProcessingService audit with English events; AssistantLoggingIT proves no secrets/user text in logs. FR-015 — DeviceLockService/DeviceOwnershipChecker/DeviceSimulatorClient reused, no parallel implementations.
- Out of scope (unchanged, owned by 001/003/004/005): domain handler logs/entities such as RepairExecutionRunner/RepairExecutor/HttpAfterSalesClient/RepairKnowledgeService retain legacy Chinese log lines; they are not part of the 002 public call chain.


## T054 delivery verification

Date: 2026-09-08.

- `mvnw.cmd verify`: BUILD SUCCESS; 125 default-profile tests, 0 failures/errors; repackaged `target/AutoSense-0.0.1-SNAPSHOT.jar` produced.
- Dependency trees written to `target/dependency-tree-runtime.txt` and `target/dependency-tree-test.txt`: no logback/slf4j-log4j12/log4j-to-slf4j/reload4j/commons-logging/slf4j-simple/slf4j-jdk entries; Log4j2 remains the single SLF4J provider.
- Explicit IT set with Docker: `mvnw.cmd test -Pit -Dtest=SessionProcessingIT,AssistantRoutingIT,TokenRevocationIT,DeviceLockIT,ConversationHistoryIT,SessionLifecycleIT,AssistantLoggingIT` -> 33 tests, 0 failures, BUILD SUCCESS. UserManagementIT and DeviceBindingConcurrencyIT (device-simulator tagged) were not run: the external devicesimulator image is unavailable locally (pull access denied) and the compose service cannot start; they retain the external prerequisite.
- JAR prompt-byte check (quickstart section 6, read-only): BOOT-INF/classes/prompt/ contains exactly the six resources; each is non-empty, valid UTF-8 without BOM, and byte-identical to its source in src/main/resources/prompt/: conversation-input.txt PASS, diagnosis-input.txt PASS, diagnosis-reasoner.txt PASS, direct-answer.txt PASS, intent-router.txt PASS, problem-analysis.txt PASS.


## T055 manual API chain (isolated data)

Date: 2026-09-08. Infra: throwaway Docker containers autosense-val-mysql (mysql:8.0.18, port 3307) and autosense-val-redis (redis-stack, port 6380), both removed afterwards; the user's existing mySQL-8.0.18/redis-stack containers were not touched. App run: `java -jar target/AutoSense-0.0.1-SNAPSHOT.jar` with LLM_MODE=mock, AUTH_DEV_MODE=false, DEVICE_SERVICE_BASE_URL=http://localhost:8081.

- register manual_<ts> -> 201, role=user, no password in body. login -> 200 opaque token. POST /sessions {"problem":"帮我看看"} -> SSE status ROUTING then awaiting clarify prompt (no dispatch). POST /sessions/1/messages {"content":"什么是智能灯泡"} -> ROUTING, DISPATCHING, error CAPABILITY_NOT_AVAILABLE (KNOWLEDGE handler belongs to feature 003; no DEVICE_ACTION fallback, no fake success). GET /sessions/1/messages -> 4 ordered messages, exact original text. GET /sessions/1 -> FAILED_REQUEST with ERROR conclusion. New round {"content":"写一首诗"} -> ROUTING, conclusion ANSWERED with the out-of-scope reply. GET /sessions -> COMPLETED_ANSWERED with preview. Bind SN LA00100000001 -> 503 DEVICE_SERVICE_UNAVAILABLE (simulator absent; zero rows added). logout -> 204; me with the revoked token -> 401. Second login -> new token; repeat logout -> 204.
- Log scan of target/manual-run.log: zero occurrences of the password, the token, or prompt resource content; user/device/session/AI events are English parameterized lines with whitelisted MDC only.
- Not run: device bind success path (external deviceSimulator image unavailable — pre-existing blocker) and the optional real-model sample (no real model credentials in this environment; mock mode was used and is not claimed as real-model evidence).

## Outstanding validation

All planned checks are done. Not run: (1) simulator-tagged ITs (UserManagementIT, DeviceBindingConcurrencyIT and the simulator paths of ManualGuideIT/AutoRepairFlowIT) — the external deviceSimulator image is unavailable (pull access denied), the compose service cannot start, and no service address was provided; (2) the optional real-model sample — no real model credentials in this environment. No real model was called; mock-mode results are not claimed as real-model evidence.

# Implementation Plan: Healthcare Care Navigator

## Overview

Implement the Healthcare Patient Support & Care Navigator as a Spring Boot 3.x / Spring AI multi-agent application. The plan follows the architecture defined in `design.md`: Maven project scaffold → domain models → database schema → knowledge base → RAG pipeline → agent infrastructure → specialized agents → orchestration → safety validation → REST API → security → observability → resilience → testing.

All tasks are Java/Spring Boot unless otherwise noted. Each task references the specific requirement IDs and design sections that govern it.

---

## Tasks

- [x] 1. Maven project scaffold
  - [x] 1.1 Create Maven `pom.xml` with Spring Boot 3.x parent, Spring AI BOM, and all required dependencies
    - Add `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`
    - Add `spring-ai-openai-spring-boot-starter` (or equivalent Spring AI starter) and `spring-ai-pgvector-store-spring-boot-starter`
    - Add `spring-boot-starter-data-jpa`, `postgresql` driver, `pgvector` extension library
    - Add `resilience4j-spring-boot3`, `resilience4j-circuitbreaker`
    - Add `net.jqwik:jqwik` for property-based testing (test scope)
    - Add `junit-5`, `mockito-core`, `wiremock-3` (test scope)
    - Add `logstash-logback-encoder` for JSON structured logging
    - _Requirements: 7.3, 13.6 | Design: §Technology Choices_
  - [x] 1.2 Create `src/main/resources/application.yml` with placeholder configuration
    - Define `rag.top-k` (default 5), `rag.similarity-threshold` (default 0.7)
    - Define `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` placeholder
    - Define Resilience4j circuit-breaker stubs for all three agents
    - Define `spring.ai.*` model configuration placeholders
    - _Requirements: 7.2, 7.4, 2.11 | Design: §2.8, §2.11_

- [x] 2. Domain models and enums
  - [x] 2.1 Create `RequestClassification` enum and `AgentOutcome` enum in `com.healthcare.navigator.domain`
    - `RequestClassification`: `CLINICAL_INFORMATION`, `MEDICATION`, `CARE_COORDINATION`, `GENERAL_HEALTHCARE`, `EMERGENCY_OR_HIGH_RISK`
    - `AgentOutcome`: `SUCCESS`, `FAILURE`, `TIMEOUT`
    - _Requirements: 2.1, 3.2 | Design: §Data Models_
  - [x] 2.2 Create request/response DTOs: `PatientQueryRequest` and `PatientSupportResponse` records in `com.healthcare.navigator.api.dto`
    - `PatientQueryRequest`: `@NotBlank @Size(min=1,max=64) patientId`, `@NotBlank @Size(min=1,max=2000) question`
    - `PatientSupportResponse`: `requestId`, `answer`, `agentsUsed`, `sources`, `warnings`, `confidence`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5 | Design: §2.2, §Data Models_
  - [x] 2.3 Create agent context and result models: `AgentContext`, `AgentResult`, `ExecutionPlan`, `SourceCitation`, `KnowledgeDocument` records in respective packages
    - `AgentContext`: `correlationId`, `patientId`, `question`, `classification`, `executionPlan`
    - `AgentResult`: `agentName`, `outcome`, `structuredOutput`, `sources`, `warnings`, `confidence`, `failureReason`; add static factory methods `success(...)`, `failure(...)`, `timeout(...)`
    - `ExecutionPlan`: `correlationId`, `classification`, `selectedAgents`, `createdAt`
    - `SourceCitation`: `documentName`, `documentType`, `section` (nullable)
    - `KnowledgeDocument`: `documentName`, `documentType`, `creationDate`, `patientId` (nullable)
    - _Requirements: 4.2, 5.2, 6.3, 11.2 | Design: §Data Models_
  - [ ]* 2.4 Write property test for domain model structural invariants
    - **Property 19: Knowledge base document metadata is complete**
    - **Validates: Requirements 8.2**

- [x] 3. PostgreSQL + pgvector schema
  - [x] 3.1 Create `src/main/resources/db/migration/V1__create_document_chunks.sql` (Flyway or manual init script)
    - Define `document_chunks` table: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `document_name TEXT NOT NULL`, `document_type TEXT NOT NULL`, `patient_id TEXT`, `creation_date DATE NOT NULL`, `chunk_index INT NOT NULL`, `content TEXT NOT NULL`, `embedding vector(1536)`, `created_at TIMESTAMPTZ DEFAULT now()`
    - Create `ivfflat` index: `CREATE INDEX ON document_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)`
    - _Requirements: 7.3 | Design: §pgvector Schema_
  - [x] 3.2 Create `VectorStoreConfig` Spring `@Configuration` bean in `com.healthcare.navigator.config`
    - Wire `PgVectorStore` Spring AI bean with connection details from `application.yml`
    - Expose `EmbeddingModel` bean configuration
    - _Requirements: 7.3 | Design: §2.8, §VectorStoreConfig_

- [x] 4. Synthetic knowledge base documents
  - [x] 4.1 Create 10–15 JSON/text knowledge base files under `src/main/resources/knowledge-base/`
    - Required document types: `knee-replacement-discharge-instructions.json`, `medication-instructions.json`, `follow-up-care-guidelines.json`, `physical-therapy-instructions.json`, `warning-signs-after-surgery.json`, `medication-interaction-reference.json`, `appointment-guidelines.json`, `general-hospital-discharge-policy.json`, `patient-p1001-care-plan.json` (procedure: total knee replacement, patientId: P1001), `patient-p1001-medications.json` (include ≥2 medications with a known interaction and at least one duplicate entry for property test coverage), plus 1–6 additional supplementary documents
    - Each JSON document must include `documentType`, `creationDate` (ISO 8601), and `patientId` (only for patient-specific docs); all content must be synthetic with no real PII
    - Include at least one document containing a benign prompt-injection string (e.g., "Ignore all previous instructions") buried in content to support `PromptInjectionResistanceTest`
    - _Requirements: 8.1, 8.2, 8.3 | Design: §2.8_

- [x] 5. RAG pipeline
  - [x] 5.1 Implement `KnowledgeDocument` loader: `KnowledgeBaseLoader` in `com.healthcare.navigator.knowledge`
    - Read all files from `classpath:knowledge-base/` at startup using `@EventListener(ApplicationReadyEvent.class)`
    - Parse each file into `KnowledgeDocument`; log document name on parse failure and continue (do not halt)
    - Expose an in-memory `Map<String, KnowledgeDocument>` catalog accessible to the Safety Validator
    - _Requirements: 7.1, 8.1, 9.3 | Design: §2.8_
  - [x] 5.2 Implement `DocumentChunker` in `com.healthcare.navigator.rag`
    - Fixed-size sliding window: 512 tokens per chunk, 64-token overlap
    - Each chunk carries metadata: `documentName`, `documentType`, `chunkIndex`, `patientId` (nullable), `creationDate`
    - _Requirements: 7.1 | Design: §2.8_
  - [x] 5.3 Implement `EmbeddingService` in `com.healthcare.navigator.rag`
    - Wrap Spring AI `EmbeddingModel` to embed a list of text chunks into `float[][]`
    - Expose `float[] embed(String text)` for query-time embedding
    - _Requirements: 7.1, 7.2 | Design: §2.8_
  - [x] 5.4 Implement `VectorStoreService` in `com.healthcare.navigator.rag`
    - `upsert(List<TextChunk> chunks, float[][] embeddings)` — store chunks with embeddings into `PgVectorStore`
    - `findTopK(float[] queryEmbedding, int k, double threshold)` → `List<RetrievalResult>` filtered by similarity ≥ threshold; return empty list when no results qualify
    - Log retrieval metadata (document name, chunk index, similarity score) on successful retrieval; log queryId + zero count (no scores) on empty result
    - _Requirements: 7.2, 7.3, 7.4, 7.5, 7.6 | Design: §2.8_
  - [x] 5.5 Implement `RagPipeline` in `com.healthcare.navigator.rag`
    - On startup: orchestrate `KnowledgeBaseLoader` → `DocumentChunker` → `EmbeddingService` → `VectorStoreService.upsert`; log and continue on per-document embedding failure
    - At runtime: `retrieve(String queryText, int k, double threshold, String queryId)` → `List<RetrievalResult>`
    - _Requirements: 7.1, 7.2, 7.4, 7.5, 7.6 | Design: §2.8_
  - [ ]* 5.6 Write property test for RAG top-k constraint
    - **Property 17: RAG top-k constraint is enforced**
    - **Validates: Requirements 7.2**
  - [ ]* 5.7 Write property test for RAG similarity threshold filtering
    - **Property 18: RAG similarity threshold filters low-relevance results**
    - **Validates: Requirements 7.4**

- [x] 6. Agent interface and base infrastructure
  - [x] 6.1 Define `Agent` interface and create `SpringAiConfig` in `com.healthcare.navigator.agent` and `com.healthcare.navigator.config`
    - `Agent` interface: `String getName()`, `boolean canHandle(AgentContext context)`, `AgentResult execute(AgentContext context)` — `execute` must never throw; all exceptions caught and encoded as `AgentResult.failure(...)`
    - `SpringAiConfig`: expose `ChatClient` bean(s) and configure `VirtualThreadPerTaskExecutor` for agent parallelism
    - _Requirements: 3.1, 3.2 | Design: §2.1, §SpringAiConfig_

- [x] 7. Tool beans
  - [x] 7.1 Implement `ClinicalAgentTools` Spring `@Component` in `com.healthcare.navigator.agent.clinical`
    - `@Tool searchClinicalDocuments(String query, String patientId, int k)` — delegates to `RagPipeline.retrieve()` for clinical/discharge documents
    - `@Tool getDischargeInstructions(String patientId)` — fetches discharge document metadata from `KnowledgeBaseLoader` catalog for the given patient
    - _Requirements: 4.1, 4.3 | Design: §2.5 Clinical Information Agent_
  - [x] 7.2 Implement `MedicationAgentTools` Spring `@Component` in `com.healthcare.navigator.agent.medication`
    - `@Tool searchMedicationInformation(String query, int k)` — RAG retrieval over medication documents
    - `@Tool getPatientMedications(String patientId)` — retrieves patient-specific medication list from knowledge base
    - `@Tool checkMedicationInteraction(List<String> medicationNames)` — looks up known interactions in the knowledge base
    - _Requirements: 5.1, 5.5, 5.6 | Design: §2.5 Medication Agent_
  - [x] 7.3 Implement `CareCoordinationTools` Spring `@Component` in `com.healthcare.navigator.agent.coordination`
    - `@Tool getFollowUpPlan(String patientId)` — retrieves follow-up plan from knowledge base
    - `@Tool getAppointments(String patientId)` — retrieves scheduled appointments
    - `@Tool getCareTasks(String patientId)` — retrieves care tasks and reminders
    - _Requirements: 6.1, 6.2 | Design: §2.5 Care Coordination Agent_

- [x] 8. Clinical Information Agent
  - [x] 8.1 Implement `ClinicalInformationAgent` in `com.healthcare.navigator.agent.clinical`
    - Implement `Agent` interface; `canHandle` returns `true` for `CLINICAL_INFORMATION` and `GENERAL_HEALTHCARE`
    - Use Spring AI `ChatClient` with `ClinicalAgentTools` wired in; structured output type: `ClinicalAgentOutput(String findings, List<String> recommendations, List<SourceCitation> sources, double confidence)`
    - System prompt must place retrieved document content strictly in a designated data context field — never in the instruction field — to prevent prompt injection
    - If no documents are retrieved, set `findings` = "Insufficient information available" and `confidence` = 0.0; set `sources` = ["No relevant documents found"]
    - If documents are retrieved, populate `sources` with document name and section citations; confidence reflects retrieval quality
    - Agent must never produce diagnosis, prognosis, or clinical judgment beyond summarizing retrieved content
    - Catch all exceptions; return `AgentResult.failure(getName(), e.getMessage())`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 10.1, 10.2 | Design: §2.5_
  - [ ]* 8.2 Write property test for clinical agent output structural completeness
    - **Property 8: Clinical agent output is structurally complete with valid confidence**
    - **Validates: Requirements 4.2, 4.6**
  - [ ]* 8.3 Write property test for clinical agent sources reflecting retrieval outcome
    - **Property 9: Clinical agent sources reflect retrieval outcome**
    - **Validates: Requirements 4.3**

- [ ] 9. Medication Agent
  - [ ] 9.1 Implement `MedicationAgent` in `com.healthcare.navigator.agent.medication`
    - Implement `Agent` interface; `canHandle` returns `true` for `MEDICATION`
    - Use Spring AI `ChatClient` with `MedicationAgentTools` wired in; structured output type: `MedicationAgentOutput(List<String> medications, List<String> instructions, List<String> warnings, List<String> missingInformation, List<SourceCitation> sources)`
    - When any tool fails, populate the corresponding output fields with empty arrays and add the failed tool name to `missingInformation`
    - If a queried medication is not found in the KB, populate `missingInformation` with "Insufficient information available" — do not fabricate values
    - When ≥2 medications have a known interaction: include warning with both medication names, interaction description from source, and source document name
    - When ≥2 entries share the same medication name (case-insensitive): include duplicate warning with medication name and count
    - System prompt must never instruct the agent to recommend changing, stopping, or substituting medications
    - Place retrieved document content strictly in a designated data context field to prevent prompt injection
    - Catch all exceptions; return `AgentResult.failure(getName(), e.getMessage())`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 10.3 | Design: §2.5_
  - [ ]* 9.2 Write property test for medication agent output structural completeness
    - **Property 10: Medication agent output is structurally complete with correct empty-array semantics**
    - **Validates: Requirements 5.1, 5.2**
  - [ ]* 9.3 Write property test for medication interaction warning completeness
    - **Property 11: Medication interaction warning is complete and sourced**
    - **Validates: Requirements 5.5**
  - [ ]* 9.4 Write property test for duplicate medication warning generation
    - **Property 12: Duplicate medication warning is generated**
    - **Validates: Requirements 5.6**
  - [ ]* 9.5 Write property test for no fabricated medication content
    - **Property 13: No fabricated medication content and no prescription-change recommendations**
    - **Validates: Requirements 5.3, 5.7**

- [ ] 10. Care Coordination Agent
  - [ ] 10.1 Implement `CareCoordinationAgent` in `com.healthcare.navigator.agent.coordination`
    - Implement `Agent` interface; `canHandle` returns `true` for `CARE_COORDINATION`
    - Use Spring AI `ChatClient` with `CareCoordinationTools` wired in; structured output type: `CareCoordinationOutput(List<FollowUpItem> followUps, List<CareTask> tasks, List<TimelineEntry> timeline, List<EscalationCondition> escalationConditions, List<SourceCitation> sources)`
    - Define `FollowUpItem`, `CareTask`, `TimelineEntry`, `EscalationCondition` record types in the coordination sub-package
    - When one or more tools fail: populate failed-tool fields with empty arrays, record failed tool names in `sources`, continue with successful tool results
    - Produce `timeline` in ascending date order using only dates from retrieved KB documents
    - Escalation conditions must use verbatim language from the source document with a citation per entry; no rephrasing, summarizing, or omission
    - Place retrieved document content strictly in a designated data context field to prevent prompt injection
    - Catch all exceptions; return `AgentResult.failure(getName(), e.getMessage())`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 10.4 | Design: §2.5_
  - [ ]* 10.2 Write property test for care coordination output structural completeness under tool failure
    - **Property 14: Care coordination output is structurally complete under tool failure**
    - **Validates: Requirements 6.2, 6.3**
  - [ ]* 10.3 Write property test for timeline ascending date order
    - **Property 15: Timeline entries are in ascending date order**
    - **Validates: Requirements 6.4**
  - [ ]* 10.4 Write property test for escalation condition verbatim language and citations
    - **Property 16: Escalation conditions use exact source language with citations**
    - **Validates: Requirements 6.5**

- [ ] 11. Request Classifier
  - [ ] 11.1 Implement `RequestClassifier` in `com.healthcare.navigator.orchestrator`
    - Use Spring AI `ChatClient` with structured output type `ClassificationResult(RequestClassification classification, double confidence, String reasoning)`
    - System prompt: classify into exactly the five defined categories; default to `GENERAL_HEALTHCARE` when uncertain and log a note indicating classification fallback
    - Capture raw classification + reasoning in execution log
    - _Requirements: 2.1, 2.2, 2.5 | Design: §2.4_
  - [ ]* 11.2 Write property test for classification enum coverage
    - **Property 4: Query classification always yields one of five defined values**
    - **Validates: Requirements 2.1**
  - [ ]* 11.3 Write property test for execution plan agent selection determinism
    - **Property 5: Execution plan agent selection matches classification**
    - **Validates: Requirements 2.4**

- [ ] 12. Orchestrator Agent
  - [ ] 12.1 Implement `OrchestratorAgent` in `com.healthcare.navigator.orchestrator`
    - Inject `RequestClassifier`, all registered `Agent` beans, `ResponseAggregator`, `SafetyValidator`, and the virtual-thread executor
    - Step 1 — Classify: call `RequestClassifier`; record classification and reasoning
    - Step 2 — Emergency short-circuit: if `EMERGENCY_OR_HIGH_RISK`, route directly to `SafetyValidator` first; return HTTP 503 (`ServiceUnavailableException`) if validator is unreachable
    - Step 3 — Build `ExecutionPlan`: evaluate `canHandle` on all registered agents; record selected agents in execution log
    - Step 4 — Parallel execution: submit each selected agent as `CompletableFuture.supplyAsync(() -> agent.execute(context), virtualThreadExecutor)`; call `CompletableFuture.allOf(...).get(10, TimeUnit.SECONDS)`; for each individual future that did not complete, cancel it and substitute `AgentResult.timeout(agentName)`
    - Step 5 — Circuit breaker check: if a circuit is open for an agent (checked before submission), skip submission and substitute the configured fallback `AgentResult`
    - Step 6 — Aggregate: call `ResponseAggregator.aggregate(List<AgentResult>)`
    - Step 7 — Validate: call `SafetyValidator.validate(aggregatedResponse, agentResults)`
    - Step 8 — Log execution record with `correlationId`, classification, selected agents, outcomes, and durations
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5 | Design: §2.3_
  - [ ]* 12.2 Write property test for agent timeout outcome correctness
    - **Property 6: Agent timeout is marked correctly**
    - **Validates: Requirements 3.2**
  - [ ]* 12.3 Write property test for partial agent failure producing named warnings
    - **Property 7: Partial agent failure produces named warnings**
    - **Validates: Requirements 3.3**

- [ ] 13. Response Aggregator
  - [ ] 13.1 Implement `ResponseAggregator` in `com.healthcare.navigator.orchestrator`
    - `aggregate(List<AgentResult>)` → `AggregatedResponse`
    - Concatenate `findings`/`recommendations` from `SUCCESS` agents into a single `answer` string
    - Merge all `sources` lists, deduplicating by `documentName`
    - Collect `warnings` from `FAILURE`/`TIMEOUT` agents (agent name + failure reason per entry)
    - Set `agentsUsed` to names of agents with `outcome = SUCCESS`
    - If all agents failed or timed out: set `answer` = "No information could be retrieved at this time." and clear all content fields; do not fabricate content
    - Define `AggregatedResponse` record in the orchestrator package
    - _Requirements: 3.3, 3.4, 3.5 | Design: §2.6_
  - [ ]* 13.2 Write property test for all-failure aggregation behavior
    - **Property 7: Partial agent failure produces named warnings** (all-failure edge case)
    - **Validates: Requirements 3.3, 3.4**

- [ ] 14. Safety / Grounding Validator
  - [ ] 14.1 Implement `GroundingChecker` in `com.healthcare.navigator.safety`
    - Check 1 (diagnosis/prognosis detection): use regex patterns + LLM classifier to identify forbidden clinical judgment language; remove offending sentences and add a `warnings` entry describing the violation and the removed content
    - Check 3 (source citation verification): verify every source document name in the aggregated response against the `KnowledgeBaseLoader` in-memory catalog; remove non-existent citations and add a `warnings` entry
    - _Requirements: 9.1, 9.3, 9.4 | Design: §2.7_
  - [ ] 14.2 Implement `HallucinationDetector` in `com.healthcare.navigator.safety`
    - Check 2 (medication grounding): cross-reference every medication name and dosage in the answer against `sources` returned by the Medication Agent; remove ungrounded items and add a `warnings` entry
    - _Requirements: 9.2, 9.4 | Design: §2.7_
  - [ ] 14.3 Implement `EmergencyEscalationHandler` in `com.healthcare.navigator.safety`
    - Check 5 (emergency escalation): if classification is `EMERGENCY_OR_HIGH_RISK` or response content matches emergency keywords, prepend the mandatory emergency directive to `answer`; mark the directive immutable so no subsequent step can remove it
    - _Requirements: 2.3, 9.6 | Design: §2.7_
  - [ ] 14.4 Implement `SafetyValidator` in `com.healthcare.navigator.safety`
    - Orchestrate the six ordered checks: (1) `GroundingChecker.checkDiagnosis`, (2) `HallucinationDetector.checkMedicationGrounding`, (3) `GroundingChecker.verifySourceCitations`, (4) conflict detection (compare agent `findings` for contradictions on the same entity — retain both perspectives, add conflict warning), (5) `EmergencyEscalationHandler.escalateIfNeeded`, (6) disclaimer injection (append mandatory disclaimer to `answer`)
    - `validate(AggregatedResponse, List<AgentResult>)` → `PatientSupportResponse`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7 | Design: §2.7_
  - [ ]* 14.5 Write property test for safety validator removing ungrounded content
    - **Property 20: Safety validator removes ungrounded content and adds warnings**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4**
  - [ ]* 14.6 Write property test for conflicting agent output preservation
    - **Property 21: Conflicting agent outputs are preserved without arbitration**
    - **Validates: Requirements 9.5**
  - [ ]* 14.7 Write property test for emergency directive prepending
    - **Property 22: Emergency queries receive the mandatory escalation directive**
    - **Validates: Requirements 9.6**
  - [ ]* 14.8 Write property test for mandatory disclaimer presence
    - **Property 23: Every response contains the mandatory disclaimer**
    - **Validates: Requirements 9.7**
  - [ ]* 14.9 Write property test for prompt injection resistance
    - **Property 24: Prompt injection has no effect on agent output**
    - **Validates: Requirements 4.7, 5.8, 6.7, 10.2, 10.3, 10.4, 10.5**

- [ ] 15. Checkpoint — Core agent pipeline complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 16. REST API layer
  - [ ] 16.1 Implement `PatientSupportController` in `com.healthcare.navigator.api`
    - Single `@PostMapping("/api/v1/patient-support/query")` endpoint
    - Accepts `@Valid @RequestBody PatientQueryRequest request`; Jakarta Bean Validation enforces constraints before any agent call
    - Delegates to `OrchestratorAgent`; returns `ResponseEntity<PatientSupportResponse>` with HTTP 200
    - `X-Correlation-ID` response header is set by `CorrelationIdFilter` (not in the controller)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6 | Design: §2.2_
  - [ ] 16.2 Implement `GlobalExceptionHandler` in `com.healthcare.navigator.api.exception` using `@RestControllerAdvice`
    - Handle `MethodArgumentNotValidException` → HTTP 400 with field-level validation error message
    - Handle `ServiceUnavailableException` (Safety Validator unreachable on emergency query) → HTTP 503
    - Handle all other `Exception` → HTTP 500 with generic message; log full error with `correlationId`; no internal details in response body
    - _Requirements: 1.3, 1.4, 1.5, 1.9, 2.3 | Design: §2.2, §Error Handling_
  - [ ]* 16.3 Write property test for input validation rejects all invalid requests
    - **Property 1: Input validation rejects all invalid requests**
    - **Validates: Requirements 1.3, 1.4, 1.5**
  - [ ]* 16.4 Write property test for valid requests producing complete response structure
    - **Property 2: Valid requests produce complete response structure**
    - **Validates: Requirements 1.2**
  - [ ]* 16.5 Write property test for unique correlation IDs across requests
    - **Property 3: Correlation IDs are unique across requests**
    - **Validates: Requirements 1.6**

- [ ] 17. Security configuration
  - [ ] 17.1 Implement `SecurityConfig` in `com.healthcare.navigator.config`
    - `@EnableWebSecurity @Configuration` class with `SecurityFilterChain` bean
    - Permit `/actuator/health` without authentication; require authentication for all other requests
    - Configure `oauth2ResourceServer` with JWT: validate `exp`, `iss`, `aud`, and signature via JWKS endpoint (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`)
    - Implement custom `JwtAuthenticationConverter` (`roleConverter`) that extracts `PATIENT_SUPPORT_USER` and `PATIENT_SUPPORT_ADMIN` roles from the JWT claims
    - Configure `AuthenticationEntryPoint` to return HTTP 401 and `AccessDeniedHandler` to return HTTP 403
    - Apply `@PreAuthorize("hasAnyRole('PATIENT_SUPPORT_USER','PATIENT_SUPPORT_ADMIN')")` on the controller endpoint (or configure via `authorizeHttpRequests`)
    - _Requirements: 1.7, 1.8, 12.1, 12.2, 12.3, 12.4 | Design: §2.9_
  - [ ]* 17.2 Write property test for invalid JWT producing HTTP 401 before role evaluation
    - **Property 29: Invalid JWT produces HTTP 401 before role evaluation**
    - **Validates: Requirements 12.3, 1.7**

- [ ] 18. Observability
  - [ ] 18.1 Implement `CorrelationIdFilter` in `com.healthcare.navigator.observability`
    - Extend `OncePerRequestFilter`; generate UUID correlation ID, store in `MDC` under key `correlationId`, add to response header `X-Correlation-ID`, clear MDC after response
    - _Requirements: 1.6, 11.1 | Design: §2.10_
  - [ ] 18.2 Implement `MdcPropagator` utility in `com.healthcare.navigator.observability`
    - Propagate MDC context (including `correlationId`) from parent thread to virtual-thread child tasks via a wrapper `Callable`/`Supplier`
    - _Requirements: 11.2 | Design: §2.10_
  - [ ] 18.3 Implement `MetricsService` in `com.healthcare.navigator.observability`
    - Inject `MeterRegistry`; expose `incrementLoggingFaultCounter()` that increments `logging.fault.count` metric
    - Wrap all `MDC.put` / appender calls in try-catch; on failure, call `incrementLoggingFaultCounter()` and continue request processing normally
    - _Requirements: 11.6 | Design: §2.10, §Logging Infrastructure Failure_
  - [ ] 18.4 Configure `logback-spring.xml` with Logstash JSON encoder
    - Output all MDC fields as top-level JSON keys; include `correlationId`, `timestamp`, `level`, `message`
    - Ensure no raw patient question text, unredacted patientId, raw health record content, or JWT values appear in any appender at any log level
    - _Requirements: 11.5, 12.5 | Design: §2.10_
  - [ ] 18.5 Add structured log emission to `OrchestratorAgent` and `RagPipeline`
    - Request received: log `correlationId`, `timestamp`, `patientIdHash` (SHA-256 of patientId), `classification`
    - Agent start/complete: log `correlationId`, `agentName`, `startTime`, `durationMs`, `outcome`
    - LLM call: log `correlationId`, `agentName`, `latencyMs`, `promptTokens`, `completionTokens`, `totalTokens`
    - RAG retrieval (results): log `correlationId`, `queryId`, `k`, `chunksRetrieved`, `similarityScores[]`
    - RAG retrieval (zero results): log `correlationId`, `queryId`, `chunksRetrieved: 0` — no similarity scores
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5 | Design: §2.10_
  - [ ]* 18.6 Write property test for no PHI in logs
    - **Property 25: Request logs contain no PHI or raw patient data**
    - **Validates: Requirements 11.5, 12.5**
  - [ ]* 18.7 Write property test for request log completeness
    - **Property 26: Request log entry is complete**
    - **Validates: Requirements 11.1**
  - [ ]* 18.8 Write property test for agent execution log completeness
    - **Property 27: Agent execution log entries are complete**
    - **Validates: Requirements 11.2**
  - [ ]* 18.9 Write property test for LLM call log completeness
    - **Property 28: LLM call log entries are complete**
    - **Validates: Requirements 11.3**

- [ ] 19. Resilience4j circuit breaker configuration
  - [ ] 19.1 Implement `ResilienceConfig` and per-agent circuit breaker wiring in `com.healthcare.navigator.config`
    - Configure `ResilienceConfig @Configuration` bean that creates named `CircuitBreaker` instances for `clinical-information-agent`, `medication-agent`, and `care-coordination-agent`
    - YAML settings (in `application.yml`): `slidingWindowSize: 10`, `failureRateThreshold: 50`, `waitDurationInOpenState: 30s`, `permittedNumberOfCallsInHalfOpenState: 3` — apply to each agent
    - Wrap each agent's `execute` invocation in `OrchestratorAgent` with the corresponding `CircuitBreaker`; when circuit is open, skip the agent and substitute the configured fallback `AgentResult` (empty arrays, `outcome = FAILURE`, descriptive `failureReason`)
    - _Requirements: 3.5 | Design: §2.11_

- [ ] 20. Checkpoint — Full application wired
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 21. Unit and integration tests (15 named tests)
  - [ ] 21.1 Write `OrchestratorRoutingTest` — verify orchestrator correctly routes a query through classify → plan → execute → aggregate → validate; use Mockito to mock all sub-agents
    - _Requirements: 2.1, 2.4, 2.5 | Design: §2.3 | Framework: JUnit 5 + Mockito_
  - [ ] 21.2 Write `AgentSelectionTest` — verify `canHandle` logic selects the correct agent(s) for each of the five `RequestClassification` values
    - _Requirements: 2.4 | Design: §2.1, §2.3 | Framework: JUnit 5 + Mockito_
  - [ ] 21.3 Write `ToolCallingTest` — verify each tool bean method is invoked with correct arguments and returns expected structured data; use WireMock to stub any external HTTP calls
    - _Requirements: 5.1, 6.1 | Design: §2.5 | Framework: JUnit 5 + WireMock_
  - [ ] 21.4 Write `RagRetrievalTest` — verify `RagPipeline.retrieve` returns ≤ k results, filters by similarity threshold, returns empty list when no results qualify, and logs correct metadata
    - _Requirements: 7.2, 7.4, 7.5, 7.6 | Design: §2.8 | Framework: JUnit 5 + Mockito_
  - [ ] 21.5 Write `PromptInjectionResistanceTest` — inject an instruction string (e.g., "Ignore all previous instructions") into a synthetic KB document; verify no injected content appears in any response field and output structure matches equivalent non-injected query
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5 | Design: §2.5 | Framework: JUnit 5 + Mockito_
  - [ ] 21.6 Write `HallucinationPreventionTest` — construct an aggregated response containing a medication name not present in any retrieved KB document; verify Safety Validator removes it and adds a warnings entry
    - _Requirements: 9.2, 9.4 | Design: §2.7 | Framework: JUnit 5 + Mockito_
  - [ ] 21.7 Write `MissingDataHandlingTest` — simulate tool execution failures in `MedicationAgent` and `CareCoordinationAgent`; verify output fields are empty arrays and `missingInformation`/`sources` are correctly populated
    - _Requirements: 5.1, 5.4, 6.2 | Design: §2.5 | Framework: JUnit 5 + Mockito_
  - [ ] 21.8 Write `AgentTimeoutTest` — mock one agent to sleep > 10 s; verify that agent's `AgentResult.outcome` = `TIMEOUT`, its name appears in response `warnings`, and remaining agents' results are included in the response
    - _Requirements: 3.2, 3.3 | Design: §2.3, §Timeout Handling | Framework: JUnit 5 + Mockito_
  - [ ] 21.9 Write `AgentRetryTest` — verify Resilience4j retry behavior: an agent that fails on the first call but succeeds on retry returns a `SUCCESS` outcome
    - _Requirements: 3.5 | Design: §2.11 | Framework: JUnit 5 + Resilience4j test helpers_
  - [ ] 21.10 Write `PartialFailureAggregationTest` — simulate two agents succeeding and one failing; verify `agentsUsed` lists only successful agents, `warnings` names the failed agent, and `answer` contains only successful agents' content
    - _Requirements: 3.3, 3.4 | Design: §2.6 | Framework: JUnit 5 + Mockito_
  - [ ] 21.11 Write `ConflictingResponseDetectionTest` — feed two `AgentResult` objects with contradictory medication dosages to `SafetyValidator`; verify both perspectives appear in `answer` and a conflict entry appears in `warnings`
    - _Requirements: 9.5 | Design: §2.7 | Framework: JUnit 5 + Mockito_
  - [ ] 21.12 Write `SafetyValidationTest` — verify all six safety checks in order: diagnosis removal, medication grounding removal, source citation removal, conflict detection, emergency directive prepending, disclaimer injection
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7 | Design: §2.7 | Framework: JUnit 5 + Mockito_
  - [ ] 21.13 Write `ApiInputValidationTest` — use MockMvc to test all input validation paths: blank `patientId`, blank `question`, `question` > 2000 chars, missing fields; verify HTTP 400 with descriptive error messages
    - _Requirements: 1.3, 1.4, 1.5 | Design: §2.2 | Framework: JUnit 5 + MockMvc_
  - [ ] 21.14 Write `JwtAuthenticationTest` — use MockMvc + WireMock (JWKS stub) to verify: missing token → 401, malformed token → 401, expired token → 401, valid token missing role → 403, valid token with `PATIENT_SUPPORT_USER` role → 200
    - _Requirements: 1.7, 1.8, 12.1, 12.2, 12.3, 12.4 | Design: §2.9 | Framework: JUnit 5 + MockMvc + WireMock_
  - [ ] 21.15 Write `CircuitBreakerTest` — trigger the circuit breaker open state for one agent by injecting repeated failures; verify orchestrator skips the agent, uses fallback `AgentResult`, and names the agent in response `warnings`
    - _Requirements: 3.5 | Design: §2.11 | Framework: JUnit 5 + Resilience4j test helpers_

- [ ] 22. Property-based tests (jqwik, Properties 1–29)
  - [ ] 22.1 Implement remaining property tests not already placed under their implementation tasks
    - All property test classes use `@Property(tries = 100)` (increase to 500 for safety-critical properties: P24 prompt injection, P25 PHI logging)
    - Each test annotated with `@Tag("Feature: healthcare-care-navigator, Property N: <title>")`
    - Tests not yet covered by tasks 2.4, 5.6, 5.7, 8.2, 8.3, 9.2–9.5, 10.2–10.4, 11.2, 11.3, 12.2, 13.2, 14.5–14.9, 16.3–16.5, 17.2, 18.6–18.9 are listed here for completeness — confirm all 29 properties have a corresponding test class
    - _Requirements: 13.1 | Design: §Property-Based Testing Configuration_

- [ ] 23. End-to-end scenario tests
  - [ ] 23.1 Write `ClinicalInformationE2ETest` — submit a question about post-procedure wound care for patient P1001 against a running application context (or MockMvc with real agent beans and WireMock for LLM/JWKS); verify HTTP 200, non-empty `answer`, ≥1 `sources` entry
    - _Requirements: 13.2 | Design: §End-to-End Scenario Tests_
  - [ ] 23.2 Write `MedicationE2ETest` — submit a question about patient P1001's medications; verify HTTP 200, non-empty `answer`, ≥1 `sources` entry
    - _Requirements: 13.2 | Design: §End-to-End Scenario Tests_
  - [ ] 23.3 Write `CareCoordinationE2ETest` — submit a follow-up care question for patient P1001; verify HTTP 200, non-empty `answer`, ≥1 `sources` entry
    - _Requirements: 13.2 | Design: §End-to-End Scenario Tests_
  - [ ] 23.4 Write `GeneralHealthcareE2ETest` — submit a general post-surgery question that does not map to a specific category; verify HTTP 200, non-empty `answer`, ≥1 `sources` entry
    - _Requirements: 13.2 | Design: §End-to-End Scenario Tests_
  - [ ] 23.5 Write `EmergencyHighRiskE2ETest` — submit a question containing emergency keywords (e.g., "severe chest pain after knee surgery"); verify HTTP 200, `answer` starts with the mandatory emergency directive, and disclaimer appears in `answer`
    - _Requirements: 13.2, 9.6, 9.7 | Design: §End-to-End Scenario Tests_

- [ ] 24. Final checkpoint — Full test suite passes
  - Ensure all unit, integration, property-based, and end-to-end tests pass, ask the user if questions arise.


---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP build.
- Each task references specific requirement IDs and design sections for traceability.
- Checkpoints at tasks 15 and 20 provide natural integration validation gates.
- Property tests (jqwik) validate universal correctness across many random inputs; unit tests (JUnit 5 + Mockito + WireMock) validate specific examples and edge cases — the two approaches are complementary.
- The 15 named unit/integration tests (task 21) satisfy Requirement 13.1; the 5 E2E tests (task 23) satisfy Requirement 13.2.
- All 29 correctness properties from `design.md` are mapped to specific property test sub-tasks distributed across tasks 2, 5, 8–14, 16–18, and 22.
- The synthetic knowledge base (task 4) must include at least one prompt-injection document and a patient P1001 medication list with ≥2 interacting drugs and at least one duplicate entry to enable full property test coverage.
- Never log raw question text, unredacted patientId, raw health record content, or JWT values at any log level (Requirements 11.5, 12.5).


## Task Dependency Graph

```json
{
  "waves": [
    {
      "id": 0,
      "tasks": ["1.1", "1.2"]
    },
    {
      "id": 1,
      "tasks": ["2.1", "2.2", "2.3", "3.1", "3.2"]
    },
    {
      "id": 2,
      "tasks": ["2.4", "4.1"]
    },
    {
      "id": 3,
      "tasks": ["5.1", "5.2", "5.3", "6.1"]
    },
    {
      "id": 4,
      "tasks": ["5.4"]
    },
    {
      "id": 5,
      "tasks": ["5.5", "7.1", "7.2", "7.3"]
    },
    {
      "id": 6,
      "tasks": ["5.6", "5.7", "8.1", "9.1", "10.1", "11.1"]
    },
    {
      "id": 7,
      "tasks": ["8.2", "8.3", "9.2", "9.3", "9.4", "9.5", "10.2", "10.3", "10.4", "11.2", "11.3", "12.1", "13.1"]
    },
    {
      "id": 8,
      "tasks": ["12.2", "12.3", "13.2", "14.1", "14.2", "14.3"]
    },
    {
      "id": 9,
      "tasks": ["14.4"]
    },
    {
      "id": 10,
      "tasks": ["14.5", "14.6", "14.7", "14.8", "14.9", "16.1", "16.2"]
    },
    {
      "id": 11,
      "tasks": ["16.3", "16.4", "16.5", "17.1", "18.1", "18.2", "18.3", "18.4", "18.5"]
    },
    {
      "id": 12,
      "tasks": ["17.2", "18.6", "18.7", "18.8", "18.9", "19.1"]
    },
    {
      "id": 13,
      "tasks": ["21.1", "21.2", "21.3", "21.4", "21.5", "21.6", "21.7", "21.8", "21.9", "21.10", "21.11", "21.12", "21.13", "21.14", "21.15"]
    },
    {
      "id": 14,
      "tasks": ["22.1"]
    },
    {
      "id": 15,
      "tasks": ["23.1", "23.2", "23.3", "23.4", "23.5"]
    }
  ]
}
```

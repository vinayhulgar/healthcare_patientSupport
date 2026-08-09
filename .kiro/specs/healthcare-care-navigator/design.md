# Design Document — Healthcare Care Navigator

## Overview

The Healthcare Care Navigator is a multi-agent AI application built on Spring Boot and Spring AI that helps patients navigate post-care instructions, medication guidance, and follow-up coordination after medical procedures. Patients submit natural-language healthcare questions through a secured REST API; the system retrieves information from a curated synthetic knowledge base, routes it through specialized AI agents, validates the response against safety rules, and returns a structured, grounded answer.

The system explicitly does **not** diagnose patients, prescribe medications, or replace the judgment of a licensed healthcare professional. Every response includes a mandatory professional-advice disclaimer.

### Design Goals

- **Safety-first**: every aggregated response passes through a grounding/safety validator before being returned; prompt-injection, hallucination, and emergency escalation are handled at multiple layers.
- **Extensibility**: the agent interface is generic so new specialist agents can be added without changing orchestration logic.
- **Observability**: every request, agent execution, LLM call, and RAG retrieval emits structured JSON log entries with a correlation ID.
- **Resilience**: per-agent circuit breakers and a 10-second parallel execution timeout prevent cascading failures.

---

## Architecture

### High-Level Flow

```
Client (JWT Bearer)
        │
        ▼
┌─────────────────────────┐
│  REST API Layer         │  POST /api/v1/patient-support/query
│  (Spring MVC)           │  • Input validation
│  SecurityFilterChain    │  • JWT authentication → 401
│                         │  • RBAC authorization → 403
│                         │  • Correlation ID assignment
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Orchestrator Agent     │  • Classifies query (LLM call)
│                         │  • Builds ExecutionPlan
│                         │  • Emergency short-circuit to
│                         │    Safety Validator if HIGH_RISK
└────────────┬────────────┘
             │  CompletableFuture (parallel, 10 s timeout)
     ┌───────┼────────────────┐
     ▼       ▼                ▼
┌─────────┐ ┌───────────┐ ┌──────────────────┐
│Clinical │ │Medication │ │Care Coordination │
│Info     │ │Agent      │ │Agent             │
│Agent    │ │           │ │                  │
│ RAG+LLM │ │ RAG+LLM   │ │ RAG+LLM          │
│ @Tool   │ │ @Tool     │ │ @Tool            │
└────┬────┘ └─────┬─────┘ └────────┬─────────┘
     └────────────┴────────────────┘
                  │  AgentResult[]
                  ▼
     ┌────────────────────────┐
     │  Response Aggregator   │  Merges results, fills warnings
     └────────────┬───────────┘
                  │
                  ▼
     ┌────────────────────────┐
     │  Safety / Grounding    │  Grounding checks, hallucination
     │  Validator             │  detection, emergency escalation,
     └────────────┬───────────┘  disclaimer injection
                  │
                  ▼
     ┌────────────────────────┐
     │  PatientSupportResponse│  HTTP 200
     └────────────────────────┘
```

### Package Structure

```
com.healthcare.navigator
├── api
│   ├── PatientSupportController.java
│   ├── dto
│   │   ├── PatientQueryRequest.java
│   │   └── PatientSupportResponse.java
│   └── exception
│       ├── GlobalExceptionHandler.java
│       └── ValidationErrorResponse.java
├── orchestrator
│   ├── OrchestratorAgent.java
│   ├── ExecutionPlan.java
│   ├── ResponseAggregator.java
│   └── RequestClassifier.java
├── agent
│   ├── Agent.java                          (interface)
│   ├── AgentContext.java
│   ├── AgentResult.java
│   ├── clinical
│   │   ├── ClinicalInformationAgent.java
│   │   └── ClinicalAgentTools.java
│   ├── medication
│   │   ├── MedicationAgent.java
│   │   └── MedicationAgentTools.java
│   └── coordination
│       ├── CareCoordinationAgent.java
│       └── CareCoordinationTools.java
├── rag
│   ├── RagPipeline.java
│   ├── DocumentChunker.java
│   ├── EmbeddingService.java
│   ├── VectorStoreService.java
│   └── RetrievalResult.java
├── safety
│   ├── SafetyValidator.java
│   ├── GroundingChecker.java
│   ├── HallucinationDetector.java
│   └── EmergencyEscalationHandler.java
├── knowledge
│   ├── KnowledgeBaseLoader.java
│   └── KnowledgeDocument.java
├── domain
│   ├── RequestClassification.java          (enum)
│   ├── AgentOutcome.java                   (enum: SUCCESS, FAILURE, TIMEOUT)
│   └── AgentExecutionRecord.java
├── config
│   ├── SecurityConfig.java
│   ├── SpringAiConfig.java
│   ├── VectorStoreConfig.java
│   ├── ResilienceConfig.java
│   └── ObservabilityConfig.java
└── observability
    ├── CorrelationIdFilter.java
    ├── MdcPropagator.java
    └── MetricsService.java
```

### Technology Choices

| Concern | Choice | Rationale |
|---|---|---|
| Web framework | Spring Boot 3.x / Spring MVC | Team standard; integrates cleanly with Spring AI |
| AI abstraction | Spring AI | Model-agnostic; supports structured output, tool calling, RAG |
| Vector store | PostgreSQL + pgvector | Single data store, eliminates operational complexity of a separate vector DB |
| Parallelism | `CompletableFuture.allOf` with `Executors.newVirtualThreadPerTaskExecutor` | Java 21 virtual threads; low overhead for I/O-bound agent calls |
| Circuit breaker | Resilience4j | Spring Boot starter; per-instance configuration |
| Auth | Spring Security OAuth2 Resource Server | Native JWT validation, minimal configuration |
| Testing | JUnit 5 + Mockito + WireMock | Required by requirements; industry standard |
| Observability | SLF4J/Logback JSON encoder + MDC | Structured JSON logs; MDC carries correlation ID across threads |

---

## Components and Interfaces

### 2.1 Agent Interface

All sub-agents implement a single interface:

```java
public interface Agent {
    /** Unique, human-readable name used in logs and response metadata. */
    String getName();

    /** True when this agent can handle the given request context. */
    boolean canHandle(AgentContext context);

    /** Execute the agent and return a structured result. Never throws — errors are encoded in AgentResult. */
    AgentResult execute(AgentContext context);
}
```

`canHandle` is evaluated by the orchestrator to build the `ExecutionPlan`. No agent is invoked unless `canHandle` returns `true` for the current context.

### 2.2 REST API Layer

**`PatientSupportController`** — single endpoint, delegates entirely to `OrchestratorAgent`.

- **Input validation**: Jakarta Bean Validation (`@NotBlank`, `@Size(min=1, max=2000)`) on `PatientQueryRequest`. Constraint violations return HTTP 400 via `GlobalExceptionHandler` before any Spring Security filter runs.
- **Security filter chain order**: JWT authentication (→ 401) is evaluated **before** role check (→ 403). Spring Security's `ExceptionTranslationFilter` handles this separation natively when the resource server is configured with `oauth2ResourceServer`.
- **Correlation ID**: `CorrelationIdFilter` (a `OncePerRequestFilter`) generates a UUID, stores it in `MDC` under key `correlationId`, and adds it to the response header `X-Correlation-ID`.

```java
@PostMapping("/api/v1/patient-support/query")
public ResponseEntity<PatientSupportResponse> query(
    @Valid @RequestBody PatientQueryRequest request) { ... }
```

### 2.3 Orchestrator Agent

**`OrchestratorAgent`** coordinates the entire request lifecycle:

1. **Classify** — calls `RequestClassifier` which uses a Spring AI ChatClient with structured output to classify the query into `RequestClassification`.
2. **Emergency short-circuit** — if classification is `EMERGENCY_OR_HIGH_RISK`, routes directly to `SafetyValidator` first; returns 503 if the validator is unreachable.
3. **Build `ExecutionPlan`** — evaluates `canHandle` on all registered agents.
4. **Parallel execution** — submits each selected agent to a virtual-thread executor as a `CompletableFuture<AgentResult>`, then waits with `CompletableFuture.allOf(...).orTimeout(10, SECONDS)`. Individual agent futures that complete exceptionally are caught per-agent and recorded as `FAILURE` or `TIMEOUT`.
5. **Aggregate** — delegates to `ResponseAggregator`.
6. **Validate** — delegates to `SafetyValidator`.
7. **Log** — emits the execution record via MDC-enriched logger.

### 2.4 Request Classifier

Uses Spring AI's structured output:

```java
record ClassificationResult(
    RequestClassification classification,
    double confidence,
    String reasoning
) {}
```

System prompt instructs the LLM to classify only into the five defined categories, defaulting to `GENERAL_HEALTHCARE` when uncertain. The raw classification + reasoning is captured in the execution log.

### 2.5 Sub-Agents and Tool Calling

Each agent uses `@Tool`-annotated methods (Spring AI) on a dedicated tools bean. The tools are wired into the agent's `ChatClient` at construction time.

#### Clinical Information Agent

Tools (on `ClinicalAgentTools`):

| Tool Method | Description |
|---|---|
| `searchClinicalDocuments(query, patientId, k)` | Delegates to `RagPipeline.retrieve()` for clinical/discharge docs |
| `getDischargeInstructions(patientId)` | Fetches discharge doc metadata for the patient |

Structured output type:

```java
record ClinicalAgentOutput(
    String findings,
    List<String> recommendations,
    List<SourceCitation> sources,
    double confidence
) {}
```

#### Medication Agent

Tools (on `MedicationAgentTools`):

| Tool Method | Description |
|---|---|
| `searchMedicationInformation(query, k)` | RAG retrieval over medication docs |
| `getPatientMedications(patientId)` | Retrieves patient-specific medication list |
| `checkMedicationInteraction(medicationNames)` | Looks up known interactions in the KB |

Structured output type:

```java
record MedicationAgentOutput(
    List<String> medications,
    List<String> instructions,
    List<String> warnings,
    List<String> missingInformation,
    List<SourceCitation> sources
) {}
```

#### Care Coordination Agent

Tools (on `CareCoordinationTools`):

| Tool Method | Description |
|---|---|
| `getFollowUpPlan(patientId)` | Retrieves the patient's follow-up plan from the KB |
| `getAppointments(patientId)` | Retrieves scheduled appointments |
| `getCareTasks(patientId)` | Retrieves care tasks and reminders |

Structured output type:

```java
record CareCoordinationOutput(
    List<FollowUpItem> followUps,
    List<CareTask> tasks,
    List<TimelineEntry> timeline,
    List<EscalationCondition> escalationConditions,
    List<SourceCitation> sources
) {}
```

### 2.6 Response Aggregator

`ResponseAggregator.aggregate(List<AgentResult>)` → `AggregatedResponse`:

- Concatenates `findings`/`recommendations` from successful agents into a single `answer` string.
- Merges all `sources` lists (deduplicating by document name).
- Collects `warnings` from failed/timed-out agents (naming each agent and failure reason).
- Sets `agentsUsed` to names of agents that returned `SUCCESS`.
- If **all** agents failed, sets `answer` to a no-information message and clears all other content fields.

### 2.7 Safety / Grounding Validator

`SafetyValidator.validate(AggregatedResponse, List<AgentResult>)` → `PatientSupportResponse`:

Checks performed in order:

1. **Diagnosis/prognosis detection** (`GroundingChecker`) — regex + LLM classifier identifies forbidden clinical judgment language; removes offending sentences and adds a warning.
2. **Medication grounding** (`HallucinationDetector`) — cross-references every medication name and dosage in `answer` against the `sources` returned by the Medication Agent; ungrounded items are removed and a warning is added.
3. **Source citation verification** — checks every source document name against the in-memory `KnowledgeBase` document catalog; non-existent source citations are removed and a warning is added.
4. **Conflict detection** — if two agents' `findings` contradict each other on the same entity (medication name, dosage, appointment date), both perspectives are retained in `answer` and a conflict warning is added; neither is selected as authoritative.
5. **Emergency escalation** (`EmergencyEscalationHandler`) — if the classification is `EMERGENCY_OR_HIGH_RISK` or the response content matches emergency keywords, prepends the mandatory emergency directive to `answer` and marks it immutable so later steps cannot remove it.
6. **Disclaimer injection** — appends the mandatory disclaimer to `answer`.

### 2.8 RAG Pipeline

```
KnowledgeBaseLoader (startup)
   └── reads JSON/text docs from classpath:knowledge-base/
   └── DocumentChunker.chunk(doc) → List<TextChunk>
   └── EmbeddingService.embed(chunks) → float[][]
   └── VectorStoreService.upsert(chunks, embeddings)

RetrievalQuery (at runtime)
   └── EmbeddingService.embed(queryText) → float[]
   └── VectorStoreService.findTopK(queryEmbedding, k, threshold)
        → List<RetrievalResult> (filtered by similarity ≥ threshold)
```

`VectorStoreService` wraps Spring AI's `PgVectorStore`. The similarity threshold and k are injected from `application.yml` (`rag.similarity-threshold`, `rag.top-k`).

Chunking strategy:
- Fixed-size sliding window: 512 tokens per chunk, 64-token overlap.
- Each chunk carries metadata: `documentName`, `documentType`, `chunkIndex`, `patientId` (nullable), `creationDate`.

### 2.9 Security Configuration

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) {
        http
          .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health").permitAll()
            .anyRequest().authenticated())
          .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter())))
          .exceptionHandling(ex -> ex
            .authenticationEntryPoint(/* → 401 */)
            .accessDeniedHandler(/* → 403 */));
        return http.build();
    }
}
```

JWT validation checks: `exp`, `iss`, `aud`, and signature (via JWKS endpoint configured in `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`). Authentication failure (missing/expired/invalid token) returns 401; authorization failure (missing role) returns 403.

### 2.10 Observability

`CorrelationIdFilter` sets `MDC.put("correlationId", uuid)` and clears it after the response. Logback's JSON encoder outputs all MDC fields as top-level JSON keys.

Structured log events (all JSON):

| Event | Key Fields |
|---|---|
| Request received | `correlationId`, `timestamp`, `patientIdHash` (SHA-256), `classification` |
| Agent start | `correlationId`, `agentName`, `startTime` |
| Agent complete | `correlationId`, `agentName`, `durationMs`, `outcome` |
| LLM call | `correlationId`, `agentName`, `latencyMs`, `promptTokens`, `completionTokens`, `totalTokens` |
| RAG retrieval | `correlationId`, `queryId`, `k`, `chunksRetrieved`, `similarityScores[]` |
| RAG zero results | `correlationId`, `queryId`, `chunksRetrieved: 0` — **no similarity scores** |
| Error | `correlationId`, `errorType`, `message` — **no patient data** |

PHI/PII is never logged: patient question text, raw patient IDs (hashed only), health record contents, and JWT values are all excluded from every log level.

### 2.11 Circuit Breaker Configuration

Each sub-agent has an independent Resilience4j `CircuitBreaker`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      clinical-information-agent:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      medication-agent:
        # same defaults
      care-coordination-agent:
        # same defaults
```

When a circuit is open, the orchestrator skips the agent and substitutes the agent's configured fallback `AgentResult` (empty arrays, `outcome=FAILURE`, appropriate `missingInformation` message).

---

## Data Models

### PatientQueryRequest

```java
public record PatientQueryRequest(
    @NotBlank
    @Size(min = 1, max = 64)
    String patientId,

    @NotBlank
    @Size(min = 1, max = 2000)
    String question
) {}
```

### PatientSupportResponse

```java
public record PatientSupportResponse(
    String requestId,          // = correlationId
    String answer,             // assembled by aggregator, validated by safety layer
    List<String> agentsUsed,   // names of agents that returned SUCCESS
    List<SourceCitation> sources,
    List<String> warnings,
    double confidence          // 0.0–1.0
) {}
```

### AgentContext

```java
public record AgentContext(
    String correlationId,
    String patientId,
    String question,
    RequestClassification classification,
    ExecutionPlan executionPlan
) {}
```

### AgentResult

```java
public record AgentResult(
    String agentName,
    AgentOutcome outcome,       // SUCCESS | FAILURE | TIMEOUT
    Object structuredOutput,    // typed payload per agent
    List<SourceCitation> sources,
    List<String> warnings,
    double confidence,
    String failureReason        // null on SUCCESS
) {}
```

### ExecutionPlan

```java
public record ExecutionPlan(
    String correlationId,
    RequestClassification classification,
    List<String> selectedAgents,
    Instant createdAt
) {}
```

### SourceCitation

```java
public record SourceCitation(
    String documentName,
    String documentType,
    String section            // nullable — section within the document
) {}
```

### KnowledgeDocument (KB metadata)

```java
public record KnowledgeDocument(
    String documentName,
    String documentType,
    LocalDate creationDate,   // ISO 8601
    String patientId          // null for general documents
) {}
```

### RequestClassification (enum)

```java
public enum RequestClassification {
    CLINICAL_INFORMATION,
    MEDICATION,
    CARE_COORDINATION,
    GENERAL_HEALTHCARE,
    EMERGENCY_OR_HIGH_RISK
}
```

### AgentOutcome (enum)

```java
public enum AgentOutcome {
    SUCCESS,
    FAILURE,
    TIMEOUT
}
```

### pgvector Schema

```sql
CREATE TABLE document_chunks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_name TEXT NOT NULL,
    document_type TEXT NOT NULL,
    patient_id    TEXT,                          -- NULL for general documents
    creation_date DATE NOT NULL,
    chunk_index   INT  NOT NULL,
    content       TEXT NOT NULL,
    embedding     vector(1536),                  -- dimension matches embedding model
    created_at    TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX ON document_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
```

---

## Correctness Properties


*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

---

### Property 1: Input validation rejects all invalid requests

*For any* request where `patientId` is blank/empty, or `question` is blank/empty, or `question` exceeds 2000 characters, the system SHALL return HTTP 400 regardless of authentication state.

**Validates: Requirements 1.3, 1.4, 1.5**

---

### Property 2: Valid requests produce complete response structure

*For any* request with a valid `patientId` (1–64 chars), a non-empty `question` (≤ 2000 chars), a valid JWT, and an authorized role, the system SHALL return HTTP 200 with a JSON body containing non-null `requestId`, `answer`, `agentsUsed`, `sources`, `warnings`, and `confidence` fields.

**Validates: Requirements 1.2**

---

### Property 3: Correlation IDs are unique across requests

*For any* two distinct concurrent or sequential requests processed by the system, their returned `requestId` (correlation ID) values SHALL differ.

**Validates: Requirements 1.6**

---

### Property 4: Query classification always yields one of five defined values

*For any* valid patient question string, the classification produced by the Orchestrator Agent SHALL be exactly one of: `CLINICAL_INFORMATION`, `MEDICATION`, `CARE_COORDINATION`, `GENERAL_HEALTHCARE`, or `EMERGENCY_OR_HIGH_RISK`.

**Validates: Requirements 2.1**

---

### Property 5: Execution plan agent selection matches classification

*For any* classified query, the list of selected agents recorded in the `ExecutionPlan` SHALL be deterministic for the same classification value and SHALL be non-null for all five classification types.

**Validates: Requirements 2.4**

---

### Property 6: Agent timeout is marked correctly

*For any* sub-agent whose execution exceeds 10 seconds, the `AgentResult` for that agent SHALL have `outcome = TIMEOUT` and SHALL NOT contribute fabricated content to the aggregated response.

**Validates: Requirements 3.2**

---

### Property 7: Partial agent failure produces named warnings

*For any* subset of sub-agents that fail or time out, the final `PatientSupportResponse.warnings` list SHALL contain at least one entry for each failed or timed-out agent, identifying the agent by its registered name and the reason for failure.

**Validates: Requirements 3.3**

---

### Property 8: Clinical agent output is structurally complete with valid confidence

*For any* clinical information query — whether or not relevant documents are retrieved — the `ClinicalAgentOutput` SHALL contain non-null `findings`, `recommendations`, `sources`, and `confidence` fields, and `confidence` SHALL be a value in the closed interval [0.0, 1.0].

**Validates: Requirements 4.2, 4.6**

---

### Property 9: Clinical agent sources reflect retrieval outcome

*For any* clinical query where at least one document chunk is retrieved above the similarity threshold, every entry in `sources` SHALL reference a document name and section from the knowledge base. *For any* clinical query where zero chunks are retrieved, the `sources` field SHALL contain the message "No relevant documents found."

**Validates: Requirements 4.3**

---

### Property 10: Medication agent output is structurally complete with correct empty-array semantics

*For any* medication query, the `MedicationAgentOutput` SHALL contain non-null `medications`, `instructions`, `warnings`, `missingInformation`, and `sources` fields. When any tool fails to execute, the fields corresponding to that tool SHALL be empty arrays (not null), and the failed tool name SHALL appear in `missingInformation`.

**Validates: Requirements 5.1, 5.2**

---

### Property 11: Medication interaction warning is complete and sourced

*For any* patient whose retrieved medication list contains two or more medications with a known interaction recorded in the knowledge base, the `warnings` field SHALL include an entry containing both medication names, the interaction description drawn from the source document, and the source document name.

**Validates: Requirements 5.5**

---

### Property 12: Duplicate medication warning is generated

*For any* patient medication list where two or more entries share the same medication name (case-insensitive), the `warnings` field SHALL include an entry stating the duplicated medication name and the count of duplicate entries.

**Validates: Requirements 5.6**

---

### Property 13: No fabricated medication content and no prescription-change recommendations

*For any* medication query where a requested medication is not present in the knowledge base, the `medications` and `instructions` fields SHALL not contain invented values for that medication. *For any* medication query, the output SHALL NOT contain a recommendation to change, stop, or substitute any medication.

**Validates: Requirements 5.3, 5.7**

---

### Property 14: Care coordination output is structurally complete under tool failure

*For any* care coordination query where one or more tools fail, the output fields corresponding to failed tools SHALL be empty arrays, the failed tool names SHALL appear in `sources`, and the remaining fields SHALL be populated from successfully executed tools.

**Validates: Requirements 6.2, 6.3**

---

### Property 15: Timeline entries are in ascending date order

*For any* care coordination query returning two or more timeline entries, the `timeline` list SHALL be sorted in non-decreasing date order, using only dates present in retrieved knowledge base documents.

**Validates: Requirements 6.4**

---

### Property 16: Escalation conditions use exact source language with citations

*For any* escalation condition present in a retrieved care plan document, the corresponding entry in `escalation_conditions` SHALL match the source document text verbatim (without rephrasing, summarizing, or omission) and SHALL include a source citation referencing that document.

**Validates: Requirements 6.5**

---

### Property 17: RAG top-k constraint is enforced

*For any* retrieval query with a configured k ∈ [1, 20], the number of returned document chunks SHALL be less than or equal to k.

**Validates: Requirements 7.2**

---

### Property 18: RAG similarity threshold filters low-relevance results

*For any* retrieval query, every returned chunk SHALL have a similarity score greater than or equal to the configured threshold. When no chunk meets the threshold, the result set SHALL be empty.

**Validates: Requirements 7.4**

---

### Property 19: Knowledge base document metadata is complete

*For any* document in the knowledge base, the fields `documentType` and `creationDate` SHALL be non-null and non-empty. For any patient-specific document, `patientId` SHALL also be non-null and non-empty.

**Validates: Requirements 8.2**

---

### Property 20: Safety validator removes ungrounded content and adds warnings

*For any* aggregated response containing (a) diagnosis/prognosis language, (b) a medication name or dosage not traceable to a retrieved knowledge base document, or (c) a source citation referencing a document that does not exist in the knowledge base — the Safety Validator SHALL remove the offending content from the final answer and SHALL add at least one entry in `warnings` describing the type of violation and the removed content.

**Validates: Requirements 9.1, 9.2, 9.3, 9.4**

---

### Property 21: Conflicting agent outputs are preserved without arbitration

*For any* scenario where two sub-agents return conflicting information about the same entity (medication name, dosage, appointment date), both perspectives SHALL appear in the final `answer` and a conflict entry SHALL appear in `warnings`. Neither perspective SHALL be silently discarded or selected as authoritative.

**Validates: Requirements 9.5**

---

### Property 22: Emergency queries receive the mandatory escalation directive

*For any* query classified as `EMERGENCY_OR_HIGH_RISK`, or any response where the Safety Validator detects emergency content, the final `answer` SHALL begin with the mandatory emergency directive instructing the patient to contact emergency services or their care provider immediately. This directive SHALL NOT be removable by any subsequent processing step.

**Validates: Requirements 9.6**

---

### Property 23: Every response contains the mandatory disclaimer

*For any* query that produces a `PatientSupportResponse`, the `answer` field SHALL contain the disclaimer: *"This information is based on the provided healthcare records and knowledge base and does not replace advice from a qualified healthcare professional."*

**Validates: Requirements 9.7**

---

### Property 24: Prompt injection has no effect on agent output

*For any* query where retrieved knowledge base documents contain text resembling instructions, directives, or role overrides (e.g., "Ignore all previous instructions"), the response `answer`, `sources`, and structural fields SHALL be equivalent to those produced by an identical query against non-injected documents. No content derived from the injected instruction text SHALL appear in any response field.

**Validates: Requirements 4.7, 5.8, 6.7, 10.2, 10.3, 10.4, 10.5**

---

### Property 25: Request logs contain no PHI or raw patient data

*For any* processed request, scanning all emitted log entries at any log level SHALL find no raw patient question text, no unredacted `patientId`, no raw patient health record content, no JWT token values, and no personally identifiable information.

**Validates: Requirements 11.5, 12.5**

---

### Property 26: Request log entry is complete

*For any* request received by the REST API, the emitted log entry SHALL contain `correlationId`, `timestamp`, `patientIdHash` (a one-way hash of the patient ID), and `classification`.

**Validates: Requirements 11.1**

---

### Property 27: Agent execution log entries are complete

*For any* sub-agent execution (start and completion), the emitted log entries SHALL contain `correlationId`, `agentName`, `startTime` (for start event), `durationMs` (for completion event), and `outcome` — where `outcome` is one of `success`, `failure`, or `timeout`.

**Validates: Requirements 11.2**

---

### Property 28: LLM call log entries are complete

*For any* LLM call made by any agent, the emitted log entry SHALL contain `latencyMs`, `promptTokens`, `completionTokens`, and `totalTokens`.

**Validates: Requirements 11.3**

---

### Property 29: Invalid JWT produces HTTP 401 before role evaluation

*For any* request carrying a JWT that is absent, malformed, expired, or has an invalid signature, the system SHALL return HTTP 401 and SHALL NOT proceed to evaluate role-based access control.

**Validates: Requirements 12.3, 1.7**

---

## Error Handling

### Error Hierarchy

| Scenario | HTTP Status | Behavior |
|---|---|---|
| Invalid input (blank/empty/too long) | 400 | Jakarta validation error; message names the field and constraint |
| Missing / invalid JWT | 401 | Spring Security `AuthenticationEntryPoint`; no body details |
| Valid JWT, wrong role | 403 | Spring Security `AccessDeniedHandler`; no body details |
| Safety Validator unavailable (emergency query) | 503 | Returned immediately; sub-agent delegation does not proceed |
| All agents fail / timeout | 200 | Response has answer = no-information message; warnings list all agent failures |
| Single agent timeout | 200 | Aggregated from remaining agents; warnings names the timed-out agent |
| Single agent tool failure | 200 | Agent returns empty arrays in affected fields; `missingInformation` populated |
| Circuit breaker open | 200 | Agent treated as failed; fallback `AgentResult` used for aggregation |
| Unhandled internal error | 500 | Generic message; full error + correlationId logged; no stack trace in response |

### Agent Error Encoding

Errors are **never thrown up to the orchestrator**. Every `Agent.execute()` must catch all exceptions and return an `AgentResult` with `outcome = FAILURE` and a populated `failureReason`. This keeps the orchestrator's concurrency logic simple and ensures the aggregator always has a result per agent.

```java
@Override
public AgentResult execute(AgentContext context) {
    try {
        // ... LLM call and tool invocations ...
        return AgentResult.success(getName(), output, sources, confidence);
    } catch (Exception e) {
        log.error("[{}] Agent {} failed: {}", context.correlationId(), getName(), e.getMessage());
        return AgentResult.failure(getName(), e.getMessage());
    }
}
```

### Timeout Handling

```java
CompletableFuture<AgentResult>[] futures = selectedAgents.stream()
    .map(agent -> CompletableFuture.supplyAsync(
        () -> agent.execute(context), virtualThreadExecutor))
    .toArray(CompletableFuture[]::new);

try {
    CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // individual futures are inspected; incomplete ones are resolved as TIMEOUT
}
```

Each individual `CompletableFuture` is checked after the join; if it did not complete within the window, it is cancelled and an `AgentResult.timeout(agentName)` is substituted.

### Logging Infrastructure Failure

If `MDC.put` or the logging appender throws, the exception is silently swallowed and a `MeterRegistry` counter (`logging.fault.count`) is incremented. The request continues normally.

---

## Testing Strategy

### Dual Testing Approach

The test suite uses two complementary strategies:
- **Unit / example-based tests**: verify specific scenarios, edge cases, error conditions, and integration wiring using JUnit 5 + Mockito + WireMock.
- **Property-based tests**: verify universal properties across many randomly generated inputs using **jqwik** (a mature Java PBT library integrated with JUnit 5).

### Property-Based Testing Configuration

**Library**: `net.jqwik:jqwik` (integrates natively with JUnit 5, no separate test runner needed).

Each property test is configured to run a **minimum of 100 tries** (jqwik default; increased to 500 for safety-critical properties like prompt injection and PHI logging).

Tag format for each property test:

```java
@Property(tries = 100)
@Tag("Feature: healthcare-care-navigator, Property 4: Query classification always yields one of five defined values")
void classificationAlwaysValidEnum(@ForAll @StringLength(min=1, max=2000) String question) { ... }
```

### Property Test Coverage

Each correctness property (Properties 1–29 above) maps to exactly one property-based test:

| Property | jqwik Generators | Key Assertion |
|---|---|---|
| P1 — Input validation | `@ForAll` blank/over-limit strings | HTTP 400 returned |
| P2 — Response structure | `@ForAll` valid patientId + question | All 6 fields non-null, HTTP 200 |
| P3 — Unique correlation IDs | `@ForAll` 2 independent requests | `requestId` values differ |
| P4 — Classification enum | `@ForAll` question strings | Classification ∈ {5 values} |
| P5 — Execution plan completeness | `@ForAll` classification enum values | `selectedAgents` non-null, deterministic |
| P6 — Timeout outcome | Delayed mock agent (>10 s) | `outcome = TIMEOUT` |
| P7 — Partial failure warnings | `@ForAll` failing-agent subsets | Each failed agent named in warnings |
| P8 — Clinical output structure | `@ForAll` clinical queries | All fields non-null, confidence ∈ [0.0,1.0] |
| P9 — Clinical sources reflect retrieval | `@ForAll` queries with/without RAG results | Sources correct for each case |
| P10 — Medication output structure | `@ForAll` med queries with tool failures | Empty arrays, failed tool in missingInformation |
| P11 — Interaction warning | `@ForAll` patient medication pairs with known interactions | Warning contains both names + source |
| P12 — Duplicate medication warning | `@ForAll` medication lists with duplicates | Warning names dup + count |
| P13 — No fabricated medication content | `@ForAll` queries for unknown medications | No invented values; no change/stop language |
| P14 — Care coordination structure under failure | `@ForAll` tool failure subsets | Empty arrays, failed tools in sources |
| P15 — Timeline ascending order | `@ForAll` care plans with date entries | Dates in non-decreasing order |
| P16 — Escalation exact language | `@ForAll` care plans with escalation conditions | Verbatim match + citation |
| P17 — RAG top-k constraint | `@ForAll @IntRange(min=1, max=20)` k values | Result count ≤ k |
| P18 — RAG threshold filtering | `@ForAll` queries with similarity scores | All returned scores ≥ threshold; empty when none qualify |
| P19 — KB metadata completeness | All KB documents | documentType, creationDate non-null; patientId present for patient docs |
| P20 — Safety validator removes ungrounded content | `@ForAll` responses with injected bad content | Removed + warning added |
| P21 — Conflict preservation | `@ForAll` conflicting agent output pairs | Both perspectives in answer; conflict in warnings |
| P22 — Emergency directive | `@ForAll` emergency-classified queries | Answer starts with directive |
| P23 — Disclaimer present | `@ForAll` any query | Disclaimer appears in answer |
| P24 — Prompt injection resistance | `@ForAll` injected doc content | No injected content in response |
| P25 — No PHI in logs | `@ForAll` requests | Log scan finds no raw question/patientId/PHI |
| P26 — Request log completeness | `@ForAll` requests | Log contains 4 required fields |
| P27 — Agent log completeness | `@ForAll` agent executions | Start/completion logs contain required fields |
| P28 — LLM log completeness | `@ForAll` LLM calls | Log contains latency + token counts |
| P29 — Invalid JWT → 401 | `@ForAll` invalid token variants | HTTP 401, no role check |

### Unit Test Coverage (example-based)

Minimum 15 unit/integration tests covering named areas per Requirement 13.1:

| Test Name | Area | Framework |
|---|---|---|
| `OrchestratorRoutingTest` | Orchestrator routing | JUnit 5 + Mockito |
| `AgentSelectionTest` | Agent selection | JUnit 5 + Mockito |
| `ToolCallingTest` | Tool calling | JUnit 5 + WireMock |
| `RagRetrievalTest` | RAG retrieval | JUnit 5 + Mockito |
| `PromptInjectionResistanceTest` | Prompt injection resistance | JUnit 5 + Mockito |
| `HallucinationPreventionTest` | Hallucination prevention | JUnit 5 + Mockito |
| `MissingDataHandlingTest` | Missing data handling | JUnit 5 + Mockito |
| `AgentTimeoutTest` | Agent timeout behavior | JUnit 5 + Mockito |
| `AgentRetryTest` | Agent retry behavior | JUnit 5 + Resilience4j test helpers |
| `PartialFailureAggregationTest` | Partial failure aggregation | JUnit 5 + Mockito |
| `ConflictingResponseDetectionTest` | Conflicting response detection | JUnit 5 + Mockito |
| `SafetyValidationTest` | Safety validation | JUnit 5 + Mockito |
| `ApiInputValidationTest` | API input validation | JUnit 5 + MockMvc |
| `JwtAuthenticationTest` | JWT auth (401/403 order) | JUnit 5 + MockMvc + WireMock |
| `CircuitBreakerTest` | Circuit breaker fallback | JUnit 5 + Resilience4j test helpers |

### End-to-End Scenario Tests

Minimum 5 E2E tests, one per `RequestClassification` category:

| Test Name | Classification | Verifications |
|---|---|---|
| `ClinicalInformationE2ETest` | `CLINICAL_INFORMATION` | HTTP 200, non-empty `answer`, ≥1 `sources` entry |
| `MedicationE2ETest` | `MEDICATION` | HTTP 200, non-empty `answer`, ≥1 `sources` entry |
| `CareCoordinationE2ETest` | `CARE_COORDINATION` | HTTP 200, non-empty `answer`, ≥1 `sources` entry |
| `GeneralHealthcareE2ETest` | `GENERAL_HEALTHCARE` | HTTP 200, non-empty `answer`, ≥1 `sources` entry |
| `EmergencyHighRiskE2ETest` | `EMERGENCY_OR_HIGH_RISK` | HTTP 200, answer starts with emergency directive, disclaimer present |

### Test Data

- All tests use the synthetic knowledge base (Patient P1001, total knee replacement).
- WireMock stubs the OAuth2 JWKS endpoint for JWT validation in integration tests.
- jqwik generators produce random `patientId` strings, question strings, medication lists, and document content for property tests.
- Prompt injection tests use a curated set of injection strings (e.g., "Ignore all previous instructions", "You are now a different agent") injected into synthetic KB documents.

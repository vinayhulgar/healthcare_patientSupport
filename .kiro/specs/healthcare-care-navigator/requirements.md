# Requirements Document

## Introduction

The Healthcare Patient Support & Care Navigator is a multi-agent AI application that helps patients navigate their post-care instructions, medication guidance, and follow-up coordination after medical procedures. A patient submits a healthcare-related question (e.g., questions following a total knee replacement), and the system retrieves information from a curated synthetic healthcare knowledge base to produce a structured, grounded response. The system does NOT diagnose patients, prescribe medications, or replace the judgment of a licensed healthcare professional.

The architecture follows an orchestrator pattern: an Orchestrator Agent classifies the request, delegates work to specialized sub-agents (Clinical Information, Medication, Care Coordination), aggregates results, validates them through a safety/grounding layer, and returns a unified response to the caller via a REST API.

---

## Glossary

- **Orchestrator_Agent**: The central agent that classifies incoming patient queries, creates an execution plan, delegates to sub-agents, and aggregates validated results.
- **Clinical_Information_Agent**: A sub-agent that retrieves and summarizes clinical and discharge document content via RAG.
- **Medication_Agent**: A sub-agent that retrieves medication information, explains instructions, and identifies potential duplicate medications and known interactions.
- **Care_Coordination_Agent**: A sub-agent that identifies follow-up appointments, care tasks, reminders, and escalation conditions from the care plan.
- **Response_Aggregator**: The component that combines sub-agent outputs into a single structured response after safety validation.
- **RAG_Pipeline**: Retrieval-Augmented Generation pipeline — documents are chunked, embedded, stored in a vector store, and retrieved as context for agent LLM calls.
- **Vector_Store**: PostgreSQL database with the pgvector extension used to store and query document embeddings.
- **Safety_Validator**: The final validation layer that checks the aggregated response for unsupported claims, hallucinated medications, diagnoses, fabricated sources, and high-risk escalation needs before returning the response to the caller.
- **Request_Classification**: One of five categories assigned by the Orchestrator_Agent: `CLINICAL_INFORMATION`, `MEDICATION`, `CARE_COORDINATION`, `GENERAL_HEALTHCARE`, or `EMERGENCY_OR_HIGH_RISK`.
- **Correlation_ID**: A unique identifier assigned to each incoming request and propagated through all agent execution logs for traceability.
- **Patient**: The end-user submitting a healthcare question to the system.
- **Knowledge_Base**: The synthetic healthcare document set used as the authoritative source for all RAG retrievals.
- **Circuit_Breaker**: A resilience pattern that prevents repeated calls to a failing agent or dependency, returning a fallback response instead.
- **Confidence_Score**: A numeric value (0.0–1.0) representing the Orchestrator_Agent's assessment of the reliability of the aggregated response based on retrieval quality and agent agreement.

---

## Requirements

### Requirement 1: REST API — Patient Query Submission

**User Story:** As a patient, I want to submit a healthcare question through a REST API, so that I can receive structured guidance based on my care documents.

#### Acceptance Criteria

1. THE REST_API SHALL expose a `POST /api/v1/patient-support/query` endpoint that accepts a JSON request body containing a `patientId` field (1–64 characters) and a `question` field.
2. WHEN a request passes field validation, carries a valid JWT token, and the caller holds the required role, THE REST_API SHALL return a JSON response containing `requestId`, `answer`, `agentsUsed`, `sources`, `warnings`, and `confidence` fields with an HTTP 200 status.
3. IF the `patientId` field is missing or empty, THEN THE REST_API SHALL return an HTTP 400 response with a descriptive validation error message, regardless of the authentication state of the request.
4. IF the `question` field is missing, empty, or zero characters in length, THEN THE REST_API SHALL return an HTTP 400 response with a descriptive validation error message, regardless of the authentication state of the request.
5. IF the `question` field exceeds 2000 characters, THEN THE REST_API SHALL return an HTTP 400 response indicating the question length limit has been exceeded, regardless of the authentication state of the request.
6. WHEN a request is received, THE REST_API SHALL assign a unique `Correlation_ID` to the request and include it in the response.
7. IF a request passes field validation but carries no JWT bearer token, THEN THE REST_API SHALL return an HTTP 401 response.
8. IF a request passes field validation and carries a valid JWT bearer token but the caller lacks the `PATIENT_SUPPORT_USER` or `PATIENT_SUPPORT_ADMIN` role, THEN THE REST_API SHALL return an HTTP 403 response.
9. IF an unhandled internal error occurs during request processing, THEN THE REST_API SHALL return an HTTP 500 response with a generic error message that does not expose internal implementation details, and SHALL log the full error with the `Correlation_ID` for debugging.

---

### Requirement 2: Request Classification

**User Story:** As the system, I want to classify every incoming patient query into a well-defined category, so that the correct sub-agents are selected for execution.

#### Acceptance Criteria

1. WHEN a patient query is received, THE Orchestrator_Agent SHALL classify the query into exactly one of the following categories: `CLINICAL_INFORMATION`, `MEDICATION`, `CARE_COORDINATION`, `GENERAL_HEALTHCARE`, or `EMERGENCY_OR_HIGH_RISK`.
2. IF a query cannot be confidently classified into any of the five defined categories, THEN THE Orchestrator_Agent SHALL classify it as `GENERAL_HEALTHCARE` and SHALL include a note in the execution log indicating that the classification fell back to the default.
3. IF a query is classified as `EMERGENCY_OR_HIGH_RISK`, THEN THE Orchestrator_Agent SHALL route the request through the Safety_Validator before delegating to any sub-agent. IF the Safety_Validator is unavailable, THEN THE Orchestrator_Agent SHALL return an HTTP 503 response and SHALL NOT proceed with sub-agent delegation.
4. WHEN the Orchestrator_Agent has classified a query, THE Orchestrator_Agent SHALL determine whether the query requires zero, one, two, or all three sub-agents based solely on the classification result and SHALL record the list of selected sub-agents in the execution plan.
5. THE Orchestrator_Agent SHALL include the classification result and the execution plan in the structured execution log for every request.

---

### Requirement 3: Parallel Agent Execution

**User Story:** As the system, I want independent sub-agents to execute concurrently, so that multi-domain queries are resolved faster without unnecessary serialization.

#### Acceptance Criteria

1. WHEN two or more sub-agents with no data dependency on each other are selected for a query, THE Orchestrator_Agent SHALL execute those sub-agents concurrently.
2. WHEN a sub-agent does not return a response within 10 seconds of being invoked, THE Orchestrator_Agent SHALL mark that sub-agent as timed out and proceed with aggregation using the responses received from the remaining sub-agents.
3. IF one or more sub-agents return an error or are marked as timed out, THEN THE Response_Aggregator SHALL include the results from the sub-agents that responded successfully in the final response and SHALL include an entry for each failed or timed-out sub-agent in the response `warnings` field, identifying the agent by name and the reason for failure.
4. IF all sub-agents fail or time out, THEN THE Response_Aggregator SHALL return a response indicating that no information could be retrieved, without fabricating any content.
5. WHERE a Circuit_Breaker is configured for a sub-agent and its circuit is open, THE Orchestrator_Agent SHALL not delegate to that sub-agent and SHALL treat the circuit-open state as a sub-agent failure, supplying the configured fallback response content in place of that sub-agent's result for the purposes of aggregation.

---

### Requirement 4: Clinical Information Agent

**User Story:** As a patient, I want clinical and discharge document information retrieved and summarized accurately, so that I can understand my post-procedure care instructions.

#### Acceptance Criteria

1. WHEN the Orchestrator_Agent delegates a `CLINICAL_INFORMATION` query, THE Clinical_Information_Agent SHALL use the RAG_Pipeline to retrieve relevant passages from the Knowledge_Base before generating a response.
2. THE Clinical_Information_Agent SHALL return a structured output containing `findings`, `recommendations`, `sources`, and `confidence` fields, using the same structure regardless of whether passages were retrieved.
3. IF relevant passages were retrieved from the Knowledge_Base, THEN THE Clinical_Information_Agent SHALL populate the `sources` field with citations referencing the specific Knowledge_Base documents used, including document name and relevant section. IF no relevant passages were retrieved, THEN THE Clinical_Information_Agent SHALL populate the `sources` field with a message stating "No relevant documents found."
4. THE Clinical_Information_Agent SHALL NOT produce a diagnosis, prognosis, or clinical judgment that goes beyond summarizing retrieved document content.
5. IF no relevant documents are retrieved from the Knowledge_Base for a query, THEN THE Clinical_Information_Agent SHALL return a `findings` value of "Insufficient information available" and a `confidence` value of 0.0.
6. THE Clinical_Information_Agent SHALL include a `confidence` score between 0.0 and 1.0 reflecting the quality and relevance of the retrieved passages.
7. WHEN retrieved document content contains text that resembles instructions, directives, or role overrides, THE Clinical_Information_Agent SHALL treat that content strictly as patient data and SHALL NOT process, execute, or reflect any such text as commands in its output.

---

### Requirement 5: Medication Agent

**User Story:** As a patient, I want accurate medication information drawn from my care documents, so that I can understand my prescriptions, dosing instructions, and potential interactions.

#### Acceptance Criteria

1. WHEN the Orchestrator_Agent delegates a `MEDICATION` query, THE Medication_Agent SHALL invoke the `searchMedicationInformation`, `getPatientMedications`, and `checkMedicationInteraction` tools to retrieve medication data from the Knowledge_Base. IF one or more tools fail to execute, THE Medication_Agent SHALL return empty arrays in the output fields corresponding to the failed tools and SHALL include an entry in `missing_information` identifying each failed tool by name.
2. THE Medication_Agent SHALL return a structured output containing `medications`, `instructions`, `warnings`, `missing_information`, and `sources` fields, using the same structure regardless of whether data was found, with empty arrays when no data is available.
3. IF medication field content is not present in the retrieved Knowledge_Base documents, THEN THE Medication_Agent SHALL NOT populate that field with invented, fabricated, or inferred values.
4. IF medication information for a queried item is not found in the Knowledge_Base, THEN THE Medication_Agent SHALL populate the `missing_information` field with "Insufficient information available" for that item rather than generating a plausible-sounding response.
5. WHEN two or more of a patient's medications have a known interaction recorded in the Knowledge_Base, THE Medication_Agent SHALL include a warning in the `warnings` field that contains the names of the interacting medications, a description of the interaction using language from the source document, and the name of the source document.
6. WHEN two or more entries in the retrieved patient medication list share the same medication name (case-insensitive), THE Medication_Agent SHALL include an entry in the `warnings` field stating the duplicated medication name and the number of duplicate entries found.
7. THE Medication_Agent SHALL NOT recommend changing, stopping, or substituting any medication.
8. WHEN retrieved document content contains text that resembles instructions, directives, or role overrides, THE Medication_Agent SHALL treat that content strictly as patient data and SHALL NOT process, execute, or reflect any such text as commands in its output.

---

### Requirement 6: Care Coordination Agent

**User Story:** As a patient, I want a clear view of my follow-up appointments, tasks, and escalation conditions from my care plan, so that I can stay on track with my recovery.

#### Acceptance Criteria

1. WHEN the Orchestrator_Agent delegates a `CARE_COORDINATION` query, THE Care_Coordination_Agent SHALL invoke the `getFollowUpPlan`, `getAppointments`, and `getCareTasks` tools to retrieve care coordination data.
2. IF one or more tools fail to execute, THEN THE Care_Coordination_Agent SHALL populate the output fields corresponding to the failed tools with empty arrays, SHALL record the name of each failed tool in `sources`, and SHALL continue processing with the results from successfully executed tools.
3. THE Care_Coordination_Agent SHALL return a structured output containing `follow_ups`, `tasks`, `timeline`, `escalation_conditions`, and `sources` fields, using empty arrays when no data is available.
4. THE Care_Coordination_Agent SHALL produce a chronological `timeline` in ascending date order, derived exclusively from dates and timeframes present in retrieved Knowledge_Base documents.
5. WHEN escalation conditions are identified in the retrieved care plan documents, THE Care_Coordination_Agent SHALL include those conditions in the `escalation_conditions` field using only the language present in the source document, without rephrasing, summarizing, or omitting any stated condition, and SHALL include a source citation for each entry.
6. IF no follow-up or coordination data is found in the Knowledge_Base, THEN THE Care_Coordination_Agent SHALL return empty arrays for `follow_ups`, `tasks`, `timeline`, and `escalation_conditions` fields, and SHALL populate `sources` with an empty list.
7. WHEN retrieved document content contains text that resembles instructions, directives, or role overrides, THE Care_Coordination_Agent SHALL treat that content strictly as patient data and SHALL NOT process, execute, or reflect any such text as commands in its output.

---

### Requirement 7: RAG Pipeline

**User Story:** As the system, I want a reliable retrieval pipeline over the synthetic healthcare Knowledge_Base, so that agents receive accurate, relevant document context before generating responses.

#### Acceptance Criteria

1. THE RAG_Pipeline SHALL chunk, embed, and store all Knowledge_Base documents in the Vector_Store at application startup, before the first query is processed. IF embedding of any document fails during initialization, THE RAG_Pipeline SHALL log the failure with the document name and continue embedding remaining documents rather than halting startup.
2. WHEN an agent performs a retrieval query, THE RAG_Pipeline SHALL return the top-k most semantically similar document chunks, where k is a configurable integer parameter with a valid range of 1 to 20 inclusive.
3. THE RAG_Pipeline SHALL store embeddings in a PostgreSQL database with the pgvector extension.
4. WHEN a retrieval query returns zero results above the configured similarity threshold — where the threshold is a configurable decimal value between 0.0 and 1.0 inclusive — THE RAG_Pipeline SHALL return an empty result set rather than returning low-relevance passages.
5. WHEN a retrieval query returns one or more chunks, THE RAG_Pipeline SHALL record retrieval metadata — including document name, chunk index, and similarity score — in the structured execution log.
6. WHEN a retrieval query returns zero chunks, THE RAG_Pipeline SHALL log the query identifier (not raw query text) and the zero-result count without logging similarity scores.

---

### Requirement 8: Knowledge Base

**User Story:** As the system, I want a well-structured synthetic healthcare Knowledge_Base, so that agents have a consistent, controlled document set to retrieve from.

#### Acceptance Criteria

1. THE Knowledge_Base SHALL contain at minimum the following document types: knee replacement discharge instructions, medication instructions, follow-up care guidelines, physical therapy instructions, warning signs after surgery, medication interaction reference, appointment guidelines, general hospital discharge policy, and synthetic patient data for Patient ID P1001 (procedure: total knee replacement). The total document count SHALL be between 10 and 15 inclusive.
2. THE Knowledge_Base SHALL store each document with the following metadata fields: `documentType` (string), `creationDate` (ISO 8601 date string), and `patientId` (string, required when the document is specific to a patient, omitted for general documents).
3. All content in the Knowledge_Base SHALL be synthetic and SHALL NOT contain real patient data, real medical records, or personally identifiable information.

---

### Requirement 9: Safety and Grounding Validation

**User Story:** As the system, I want every aggregated response validated against safety and grounding rules before it is returned, so that patients never receive harmful, fabricated, or misleading information.

#### Acceptance Criteria

1. BEFORE the final response is returned, THE Safety_Validator SHALL verify that no diagnosis, prognosis, or clinical judgment is present in the aggregated answer.
2. BEFORE the final response is returned, THE Safety_Validator SHALL verify that all medication names, dosages, and instructions cited in the answer are traceable to retrieved Knowledge_Base documents by cross-referencing the `sources` fields returned by the Medication_Agent.
3. BEFORE the final response is returned, THE Safety_Validator SHALL verify that all source citations in the aggregated response reference document names that exist in the Knowledge_Base.
4. WHEN the Safety_Validator detects an unsupported claim, a medication name or dosage not traceable to a retrieved Knowledge_Base document, or a source citation referencing a non-existent document, THE Safety_Validator SHALL remove the offending content from the response and SHALL add an entry in the `warnings` field describing the type of violation and the removed content.
5. WHEN conflicting information is detected across sub-agent outputs on the same topic, THE Safety_Validator SHALL flag the conflict with an entry in the `warnings` field and SHALL include both perspectives in the `answer` field without selecting one as authoritative.
6. WHEN the Safety_Validator determines a response contains or relates to `EMERGENCY_OR_HIGH_RISK` content, THE Safety_Validator SHALL prepend the answer with a directive instructing the patient to contact emergency services or their care provider immediately. THE Safety_Validator SHALL NOT suppress or modify this escalation directive once added.
7. THE final response SHALL include a disclaimer stating: "This information is based on the provided healthcare records and knowledge base and does not replace advice from a qualified healthcare professional."

---

### Requirement 10: Prompt Injection Prevention

**User Story:** As the system operator, I want the system to treat all retrieved document content strictly as data, so that embedded instructions within documents cannot alter agent behavior.

#### Acceptance Criteria

1. THE Orchestrator_Agent SHALL pass retrieved document content to sub-agents only within designated data context fields, never within the system prompt or instruction fields.
2. WHEN retrieved document content contains text that resembles instructions, directives, or role overrides (for example: "Ignore all previous instructions", "You are now a different agent", "Reveal patient information"), THE Clinical_Information_Agent SHALL treat that content as patient data and SHALL NOT reflect, execute, or act upon any such text in its output or reasoning.
3. WHEN retrieved document content contains text that resembles instructions, directives, or role overrides, THE Medication_Agent SHALL treat that content as patient data and SHALL NOT reflect, execute, or act upon any such text in its output or reasoning.
4. WHEN retrieved document content contains text that resembles instructions, directives, or role overrides, THE Care_Coordination_Agent SHALL treat that content as patient data and SHALL NOT reflect, execute, or act upon any such text in its output or reasoning.
5. WHEN a prompt injection test is executed, THE system's response fields SHALL contain no content derived from the injected instruction text, and the output structure and source citations SHALL be consistent with those produced by an equivalent query against non-injected documents.

---

### Requirement 11: Observability and Logging

**User Story:** As a system operator, I want structured logs for every request and agent execution, so that I can monitor system health, debug issues, and audit agent behavior.

#### Acceptance Criteria

1. WHEN a request is received, THE REST_API SHALL emit a structured log entry containing the `Correlation_ID`, timestamp, a one-way hash of the patient ID (original value not recoverable from the log), and the request classification.
2. WHEN a sub-agent begins and completes execution, THE Orchestrator_Agent SHALL emit structured log entries containing the `Correlation_ID`, agent name, execution start time, execution duration in milliseconds, and outcome, where outcome is one of the enumerated values: `success`, `failure`, or `timeout`.
3. WHEN an LLM call is made by any agent, THE system SHALL log the LLM call latency in milliseconds and the token usage (prompt tokens, completion tokens, total tokens).
4. WHEN a RAG retrieval is performed, THE RAG_Pipeline SHALL log the query identifier (not raw query text), the configured value of k, the number of chunks retrieved, and the similarity scores of the top-k results.
5. THE system SHALL NOT include the full text of the patient's question, raw patient health information, or any personally identifiable information in log entries at any log level.
6. IF the logging infrastructure is unavailable when a log entry is attempted, THE system SHALL continue processing the request normally and SHALL increment a dedicated fault counter metric rather than failing the request due to a logging error.

---

### Requirement 12: Security

**User Story:** As a system operator, I want the API secured with OAuth2/JWT authentication and role-based access control, so that only authorized callers can query patient care information.

#### Acceptance Criteria

1. THE REST_API SHALL require a valid JWT bearer token on every request to `POST /api/v1/patient-support/query`.
2. THE REST_API SHALL validate JWT tokens by verifying the token signature, expiry (`exp` claim), issuer (`iss` claim), and audience (`aud` claim) against the configured OAuth2 authorization server values.
3. IF a JWT token is absent, malformed, expired, or has an invalid signature, THEN THE REST_API SHALL return an HTTP 401 response immediately, without proceeding to role-based access checks.
4. WHEN a JWT token passes all validation checks, THE REST_API SHALL enforce role-based access control and SHALL return an HTTP 403 response if the caller does not hold the `PATIENT_SUPPORT_USER` or `PATIENT_SUPPORT_ADMIN` role.
5. THE system SHALL NOT include unredacted JWT token values, patient health record contents, or personally identifiable information in any log output, regardless of log level.

---

### Requirement 13: Testing

**User Story:** As a developer, I want a comprehensive automated test suite, so that I can verify correct behavior, safety guardrails, and resilience across failure scenarios.

#### Acceptance Criteria

1. THE Test_Suite SHALL contain at least 15 automated unit and integration tests with at least one test covering each of the following named areas: orchestrator routing, agent selection, tool calling, RAG retrieval, prompt injection resistance, hallucination prevention, missing data handling, agent timeout behavior, agent retry behavior, partial failure aggregation, conflicting response detection, safety validation, and API input validation.
2. THE Test_Suite SHALL contain at least 5 end-to-end scenario tests, with at least one test per `Request_Classification` category, where each test verifies that the response returns HTTP 200, contains a non-empty `answer` field, and contains at least one entry in the `sources` field.
3. WHEN an agent timeout scenario is tested, THE Test_Suite SHALL verify that at least one non-timed-out agent returns a non-empty structured response and that the timed-out agent's name appears in the `warnings` field of the response.
4. WHEN a prompt injection scenario is tested, THE Test_Suite SHALL verify that the response fields contain no content derived from the injected instruction text, and that the output structure and source citations are consistent with those produced by an equivalent query against non-injected documents.
5. WHEN a hallucination prevention scenario is tested, THE Test_Suite SHALL verify that the Safety_Validator removes any medication name or dosage not traceable to a retrieved Knowledge_Base document and that at least one entry in the `warnings` field describes the removed content.
6. THE Test_Suite SHALL use JUnit 5, Mockito, and WireMock as the sole automated testing frameworks for unit and integration tests.

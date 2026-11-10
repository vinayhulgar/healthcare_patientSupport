# Healthcare Care Navigator

Healthcare Care Navigator is a Spring Boot 3 application that provides patient-facing post-care support using a multi-agent AI design. It classifies patient questions, routes them to the most relevant agent(s), retrieves grounded clinical knowledge from a vector store, and returns a safety-validated answer.

This project is designed for healthcare support scenarios such as post-operative education, medication questions, wound care, pain management, follow-up scheduling, and emergency escalation.

## Overview

The application combines:

- Spring Boot and Java 21
- Spring AI for OpenAI chat and embeddings
- PostgreSQL + pgvector for vector search and retrieval
- A multi-agent orchestration layer
- Retrieval-augmented generation (RAG) over healthcare knowledge documents
- JWT-based security and role checks
- Resilience patterns such as circuit breakers

## Key capabilities

- Classifies incoming patient questions into categories like clinical guidance, medication questions, care coordination, or emergency/high-risk cases
- Selects the appropriate specialist agents for each request
- Retrieves relevant patient and policy information from a local knowledge base
- Produces grounded responses with citations and source references
- Applies safety validation before returning results
- Protects the API with OAuth2/JWT authentication
- Exposes a health endpoint for monitoring

## System design

The project follows a layered architecture:

- API layer: request validation and HTTP handling
- Orchestration layer: coordinates the workflow and manages agent execution
- Agent layer: specialist components for clinical, medication, and coordination tasks
- RAG layer: chunks documents, embeds text, and retrieves relevant context
- Safety layer: validates output for risky or emergency situations
- Data layer: PostgreSQL with pgvector for persisted embeddings and metadata

### Main components

- `src/main/java/com/healthcare/navigator/api/PatientSupportController.java`
  - Exposes the patient support HTTP endpoint
- `src/main/java/com/healthcare/navigator/orchestrator/OrchestratorAgent.java`
  - Coordinates agent execution and safety checks
- `src/main/java/com/healthcare/navigator/agent/...`
  - Specialized agent implementations
- `src/main/java/com/healthcare/navigator/rag/...`
  - Embedding, chunking, and vector retrieval logic
- `src/main/java/com/healthcare/navigator/config/SecurityConfig.java`
  - JWT and authorization configuration
- `src/main/resources/knowledge-base/`
  - Domain-specific care guidance and patient data JSON documents

## Technology stack

- Java 21
- Maven
- Spring Boot 3.3.5
- Spring AI 1.0.0
- OpenAI API integration
- PostgreSQL
- pgvector
- Spring Security with OAuth2 resource server
- Resilience4j
- JPA / Hibernate
- Actuator

## Project structure

```text
.
├── pom.xml
├── README.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/healthcare/navigator/
│   │   │       ├── agent/
│   │   │       ├── api/
│   │   │       ├── config/
│   │   │       ├── domain/
│   │   │       ├── knowledge/
│   │   │       ├── observability/
│   │   │       ├── orchestrator/
│   │   │       ├── rag/
│   │   │       └── safety/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── logback-spring.xml
│   │       └── knowledge-base/
│   └── test/
└── target/
```

## Prerequisites

Before running the application, ensure you have:

- JDK 21+
- Maven 3.9+
- PostgreSQL instance running
- OpenAI API key
- A configured JWT issuer / JWKS endpoint for authentication

## Configuration

Application settings are defined in `src/main/resources/application.yml`.

Required environment variables:

```bash
export OPENAI_API_KEY="your-openai-api-key"
export DB_URL="jdbc:postgresql://localhost:5432/healthcare_navigator"
export DB_USERNAME="postgres"
export DB_PASSWORD="postgres"
export JWK_SET_URI="https://your-auth-provider.example.com/.well-known/jwks.json"
export JWT_ISSUER_URI="https://your-auth-provider.example.com/"
```

Defaults are provided in the YAML file, but you should override them for your environment.

## Database setup

The app expects a PostgreSQL database and pgvector support. The default database name is:

```text
healthcare_navigator
```

Create the database if needed:

```bash
createdb healthcare_navigator
```

If you are using a different database name or host, update `DB_URL` accordingly.

## Build

```bash
mvn clean package
```

## Run locally

```bash
mvn spring-boot:run
```

Or run the packaged jar:

```bash
java -jar target/healthcare-care-navigator-0.0.1-SNAPSHOT.jar
```

The app starts on the default Spring Boot port:

```text
http://localhost:8080
```

## API endpoints

### Health check

```http
GET /actuator/health
```

This endpoint is publicly accessible.

### Patient support query

```http
POST /api/v1/patient-support/query
Authorization: Bearer <jwt>
Content-Type: application/json
```

Example request body:

```json
{
  "patientId": "p1001",
  "question": "What should I do if I have severe pain after my knee replacement?"
}
```

Example response:

```json
{
  "requestId": "abc123",
  "answer": "Please contact your care team...",
  "agentsUsed": ["clinical", "medication"],
  "sources": [
    {
      "title": "Pain Management Guidelines",
      "url": ""
    }
  ],
  "warnings": [],
  "confidence": 0.92
}
```

## Security

The application is configured as an OAuth2 JWT resource server.

- Public endpoint: `/actuator/health`
- All other requests require a valid JWT
- JWT roles are mapped to Spring Security authorities
- Access is restricted to users with the required patient support role

## Notes on the healthcare domain

This project is a reference implementation and should be used with appropriate clinical review and governance controls. It is intended to provide decision support and patient guidance, not replace licensed clinician judgement.

The knowledge base includes structured clinical and policy documents for post-care guidance, medication instructions, follow-up care, and warning signs after surgery.

## License

This project does not currently declare a license. Add a license file if you intend to distribute or publish the project.

## Next steps

Possible enhancements include:

- adding a proper database migration workflow
- integrating a real patient identity provider
- exposing a dashboard or admin endpoints
- adding more domain-specific agents and policies
- adding end-to-end tests and contract tests

---

This README provides a high-level overview of the project and should be expanded as the application evolves.

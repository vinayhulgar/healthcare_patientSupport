package com.healthcare.navigator.orchestrator;

import com.healthcare.navigator.agent.AgentResult;
import com.healthcare.navigator.agent.clinical.ClinicalInformationAgent.ClinicalAgentOutput;
import com.healthcare.navigator.agent.coordination.CareCoordinationAgent.CareCoordinationOutput;
import com.healthcare.navigator.agent.coordination.CareTask;
import com.healthcare.navigator.agent.coordination.FollowUpItem;
import com.healthcare.navigator.agent.medication.MedicationAgent.MedicationAgentOutput;
import com.healthcare.navigator.domain.AgentOutcome;
import com.healthcare.navigator.domain.SourceCitation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates the results produced by all sub-agents into a single {@link AggregatedResponse}.
 *
 * <p>Aggregation rules:
 * <ul>
 *   <li>Concatenates findings/recommendations from {@code SUCCESS} agents into a single answer.</li>
 *   <li>Merges all {@code sources} lists, deduplicating by {@code documentName}.</li>
 *   <li>Collects warnings from {@code FAILURE}/{@code TIMEOUT} agents (agent name + reason).</li>
 *   <li>Sets {@code agentsUsed} to names of agents with {@code outcome = SUCCESS}.</li>
 *   <li>Computes average confidence from {@code SUCCESS} agents (0.0 if none succeeded).</li>
 *   <li>If <em>all</em> agents failed/timed out: sets {@code answer} to the no-information
 *       sentinel, clears all content fields, but preserves the collected warnings.</li>
 * </ul>
 *
 * <p>Requirements: 3.3, 3.4, 3.5 | Design: §2.6
 */
@Component
public class ResponseAggregator {

    static final String NO_INFORMATION_MESSAGE =
            "No information could be retrieved at this time.";

    /**
     * Aggregates {@code agentResults} into a single {@link AggregatedResponse}.
     *
     * @param agentResults the list of results returned by each sub-agent
     * @return a fully populated (or empty-fallback) {@link AggregatedResponse}
     */
    public AggregatedResponse aggregate(List<AgentResult> agentResults) {
        if (agentResults == null || agentResults.isEmpty()) {
            return new AggregatedResponse(
                    NO_INFORMATION_MESSAGE,
                    List.of(),
                    List.of(),
                    List.of(),
                    0.0);
        }

        List<String> warnings = new ArrayList<>();
        List<String> agentsUsed = new ArrayList<>();
        StringBuilder answerBuilder = new StringBuilder();
        // Use LinkedHashMap to preserve insertion order while deduplicating by documentName
        Map<String, SourceCitation> dedupedSources = new LinkedHashMap<>();
        double totalConfidence = 0.0;
        int successCount = 0;

        for (AgentResult result : agentResults) {
            // Collect warnings from FAILURE / TIMEOUT agents
            if (result.outcome() == AgentOutcome.FAILURE
                    || result.outcome() == AgentOutcome.TIMEOUT) {
                String reason = result.failureReason() != null
                        ? result.failureReason()
                        : "Unknown error";
                warnings.add(result.agentName() + ": " + reason);
            }

            // Process SUCCESS agents
            if (result.outcome() == AgentOutcome.SUCCESS) {
                agentsUsed.add(result.agentName());
                totalConfidence += result.confidence();
                successCount++;

                // Extract text from structured output and append to answer
                String agentText = extractText(result);
                if (agentText != null && !agentText.isBlank()) {
                    if (!answerBuilder.isEmpty()) {
                        answerBuilder.append("\n\n");
                    }
                    answerBuilder.append(agentText);
                }
            }

            // Merge sources from ALL results, deduplicating by documentName
            if (result.sources() != null) {
                for (SourceCitation source : result.sources()) {
                    if (source != null && source.documentName() != null) {
                        dedupedSources.putIfAbsent(source.documentName(), source);
                    }
                }
            }
        }

        // All-failure / no-success case
        if (successCount == 0) {
            return new AggregatedResponse(
                    NO_INFORMATION_MESSAGE,
                    List.of(),
                    List.of(),
                    List.copyOf(warnings),
                    0.0);
        }

        double avgConfidence = totalConfidence / successCount;

        return new AggregatedResponse(
                answerBuilder.toString(),
                List.copyOf(agentsUsed),
                List.copyOf(dedupedSources.values()),
                List.copyOf(warnings),
                avgConfidence);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts a human-readable text summary from a successful agent's structured output.
     *
     * <p>Uses {@code instanceof} pattern matching to handle the three known output types:
     * <ul>
     *   <li>{@link ClinicalAgentOutput} — appends {@code findings} + joined {@code recommendations}</li>
     *   <li>{@link MedicationAgentOutput} — appends joined {@code medications} + {@code instructions}</li>
     *   <li>{@link CareCoordinationOutput} — appends summaries of follow-ups and tasks</li>
     *   <li>Unknown types — falls back to {@code toString()}</li>
     * </ul>
     *
     * @param result a {@code SUCCESS} agent result
     * @return extracted text, or {@code null} if the structured output is null
     */
    private String extractText(AgentResult result) {
        Object output = result.structuredOutput();
        if (output == null) {
            return null;
        }

        if (output instanceof ClinicalAgentOutput clinical) {
            return extractClinicalText(clinical);
        }

        if (output instanceof MedicationAgentOutput medication) {
            return extractMedicationText(medication);
        }

        if (output instanceof CareCoordinationOutput coordination) {
            return extractCareCoordinationText(coordination);
        }

        // Fallback for any unknown output type
        return output.toString();
    }

    /**
     * Extracts text from a {@link ClinicalAgentOutput}.
     * Appends findings followed by a bulleted recommendations list (if any).
     */
    private String extractClinicalText(ClinicalAgentOutput clinical) {
        StringBuilder sb = new StringBuilder();

        if (clinical.findings() != null && !clinical.findings().isBlank()) {
            sb.append(clinical.findings());
        }

        if (clinical.recommendations() != null && !clinical.recommendations().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("Recommendations:\n");
            for (String rec : clinical.recommendations()) {
                if (rec != null && !rec.isBlank()) {
                    sb.append("- ").append(rec).append("\n");
                }
            }
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Extracts text from a {@link MedicationAgentOutput}.
     * Appends the medications list followed by instructions.
     */
    private String extractMedicationText(MedicationAgentOutput medication) {
        StringBuilder sb = new StringBuilder();

        if (medication.medications() != null && !medication.medications().isEmpty()) {
            sb.append("Medications:\n");
            for (String med : medication.medications()) {
                if (med != null && !med.isBlank()) {
                    sb.append("- ").append(med).append("\n");
                }
            }
        }

        if (medication.instructions() != null && !medication.instructions().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("Instructions:\n");
            for (String instr : medication.instructions()) {
                if (instr != null && !instr.isBlank()) {
                    sb.append("- ").append(instr).append("\n");
                }
            }
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Extracts text from a {@link CareCoordinationOutput}.
     * Appends summaries of follow-up appointments and care tasks.
     */
    private String extractCareCoordinationText(CareCoordinationOutput coordination) {
        StringBuilder sb = new StringBuilder();

        if (coordination.followUps() != null && !coordination.followUps().isEmpty()) {
            sb.append("Follow-up Appointments:\n");
            for (FollowUpItem item : coordination.followUps()) {
                if (item != null) {
                    sb.append("- ").append(item.appointmentType());
                    if (item.scheduledDate() != null && !item.scheduledDate().isBlank()) {
                        sb.append(" on ").append(item.scheduledDate());
                    }
                    if (item.location() != null && !item.location().isBlank()) {
                        sb.append(" at ").append(item.location());
                    }
                    sb.append("\n");
                }
            }
        }

        if (coordination.tasks() != null && !coordination.tasks().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("Care Tasks:\n");
            for (CareTask task : coordination.tasks()) {
                if (task != null) {
                    sb.append("- ").append(task.taskName());
                    if (task.description() != null && !task.description().isBlank()) {
                        sb.append(": ").append(task.description());
                    }
                    if (task.frequency() != null && !task.frequency().isBlank()) {
                        sb.append(" (").append(task.frequency()).append(")");
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString().stripTrailing();
    }
}

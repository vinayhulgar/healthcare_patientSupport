package com.healthcare.navigator.agent;

/**
 * Contract that every sub-agent must implement.
 *
 * <p>The orchestrator discovers all {@code Agent} beans in the application context,
 * calls {@link #canHandle} to build the {@link com.healthcare.navigator.orchestrator.ExecutionPlan},
 * and then invokes {@link #execute} concurrently for each selected agent.
 */
public interface Agent {

    /**
     * Returns the unique, human-readable name of this agent.
     * The name is used in structured logs, the execution plan, and the
     * {@link AgentResult#agentName()} field of every result this agent produces.
     *
     * @return a non-null, non-blank agent name
     */
    String getName();

    /**
     * Determines whether this agent can handle the given request context.
     *
     * <p>Evaluated by the orchestrator before any agent is invoked; only agents
     * that return {@code true} are included in the execution plan.
     *
     * @param context the current request context
     * @return {@code true} if this agent should be invoked for the given context
     */
    boolean canHandle(AgentContext context);

    /**
     * Executes this agent against the given request context and returns a structured result.
     *
     * <p><strong>This method must never throw.</strong> All exceptions — including
     * {@link RuntimeException}, {@link java.util.concurrent.TimeoutException}, and any
     * LLM/tool errors — must be caught internally and encoded as
     * {@link AgentResult#failure(String, String)}.
     *
     * @param context the current request context
     * @return a non-null {@link AgentResult} with outcome {@code SUCCESS}, {@code FAILURE},
     *         or {@code TIMEOUT}
     */
    AgentResult execute(AgentContext context);
}

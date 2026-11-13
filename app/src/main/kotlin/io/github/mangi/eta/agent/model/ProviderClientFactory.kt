package io.github.mangi.eta.agent.model

/**
 * Fork: the app has exactly one agent — Indirect, reached through the relay.
 * Every config, old or new, resolves to [IndirectProvider]; the multi-provider
 * dispatch (OpenAI/Anthropic/Gemini, Chat Completions vs Responses) is gone.
 * The provider client files are kept for now but are unreachable; they are
 * deleted in the stage-2 cleanup once the fork build is proven.
 */
internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient =
        IndirectProvider
}

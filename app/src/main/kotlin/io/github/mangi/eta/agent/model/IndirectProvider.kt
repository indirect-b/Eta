package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fork's only provider: Indirect, the dedicated assistant, reached through
 * the relay on the VM with a minimal, non-streaming JSON protocol.
 *
 *   POST {baseUrl}/indirect/chat
 *   Authorization: Bearer <relay token>
 *   Request:  {"messages": [...OpenAI chat messages...],
 *              "tools": [...OpenAI function tools...],
 *              "session_id": "..."}
 *   Response: {"content": "...",
 *              "tool_calls": [{"id": "...", "name": "...", "arguments": "{...}"}],
 *              "finish_reason": "stop" | "tool_calls"}
 *
 * No OpenAI envelope, no SSE streaming, no model/reasoning/custom-body fields:
 * one POST, one JSON object. The relay stores the request, wakes the agent,
 * and returns the agent's reply (a complete OpenAI message object) flattened
 * to the minimal shape above. A 504 means the agent did not reply in time;
 * the loop's retry policy (AgentModelRetry) handles transient failures.
 *
 * The returned [ProviderResponse] uses the same assistant-message shape as the
 * OpenAI chat provider ({role, content, reasoning_content, finish_reason,
 * tool_calls}), so [AgentLoop] needs no changes. Streaming UI events are
 * emitted once, with the full text / each tool call as a single block.
 */
internal object IndirectProvider : AgentProviderClient {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Default relay base URL. The localhost.run tunnel URL rotates, so the user
     *  must update this in settings when it changes; it is only a starting value. */
    const val DEFAULT_BASE_URL = "https://c7dc7873f154ea.app.lhr.life/v1"
    const val MODEL_ID = "indirect"

    override val id: String = "indirect"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = false,
            streamingToolCalls = false,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        val url = ProviderUrls.normalizeBaseUrl(config.baseUrl) + "/indirect/chat"
        val requestJson = JSONObject()
            .put("messages", OpenAiRequestMessages.forChatCompletions(request.messages))
            .put("tools", request.effectiveTools)
            .put("session_id", request.sessionId)
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runController.throwIfCancelled()
        onEvent(ProviderEvent.RequestStarted)
        return AuthorizedProviderHttp.execute(
            client = AgentHttpClient.modelClient(request.purpose, request.forceHttp1),
            providerId = config.providerId,
            authMode = config.authMode,
            request = httpRequest,
            controller = runController,
            binding = request.accountBinding,
        ) { response ->
            onEvent(ProviderEvent.ResponseHeaders(response.code))
            if (!response.isSuccessful) {
                throw AgentModelFailure.http(response.code, response.peekBody(16_384).string())
            }
            val payload = JSONObject(response.body.string())
            val content = payload.optString("content")
            val finishReason = payload.optString("finish_reason").ifBlank {
                if (payload.optJSONArray("tool_calls")?.length() ?: 0 > 0) "tool_calls" else "stop"
            }
            val toolCalls = JSONArray()
            val rawCalls = payload.optJSONArray("tool_calls") ?: JSONArray()
            var contentIndex = 0
            if (content.isNotEmpty()) {
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, contentIndex))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, contentIndex, content))
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = AssistantBlockKind.TEXT,
                        index = contentIndex,
                        content = content,
                    )
                )
                contentIndex++
            }
            for (i in 0 until rawCalls.length()) {
                val raw = rawCalls.optJSONObject(i) ?: continue
                val callId = raw.optString("id").ifBlank { "call_indirect_${request.sessionId}_$i" }
                val name = raw.optString("name")
                val args = when (val a = raw.opt("arguments")) {
                    is JSONObject -> a.toString()
                    is String -> a.ifBlank { "{}" }
                    else -> "{}"
                }
                onEvent(
                    ProviderEvent.BlockStart(
                        kind = AssistantBlockKind.TOOL_CALL,
                        index = contentIndex,
                        blockId = callId,
                        name = name.ifBlank { null },
                    )
                )
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = AssistantBlockKind.TOOL_CALL,
                        index = contentIndex,
                        blockId = callId,
                        name = name.ifBlank { null },
                        content = args,
                    )
                )
                contentIndex++
                toolCalls.put(
                    JSONObject()
                        .put("id", callId)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", name)
                                .put("arguments", args)
                        )
                )
            }
            onEvent(ProviderEvent.Completed(finishReason))
            val assistantMessage = JSONObject()
                .put("role", "assistant")
                .put("content", content)
                .put("reasoning_content", "")
                .put("finish_reason", if (toolCalls.length() > 0) "tool_calls" else finishReason)
                .also { message ->
                    if (toolCalls.length() > 0) message.put("tool_calls", toolCalls)
                }
            ProviderResponse(assistantMessage)
        }
    }

}

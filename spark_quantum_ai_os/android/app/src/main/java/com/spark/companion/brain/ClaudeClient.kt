package com.spark.companion.brain

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Claude, through Anthropic's official Java SDK, is Spark's only brain: it
 * chats, operates the phone with tools and looks through the camera.
 *
 * Thinking blocks are never sent back: Spark rebuilds its system prompt and
 * trims old turns, which would invalidate them, and the API allows dropping
 * all of them.
 */
class ClaudeClient(
    private val apiKey: () -> String,
    model: () -> String,
    onModelSwitch: (String) -> Unit = {},
) : Llm {

    private val models = ModelSwitch({ model().trim().ifEmpty { DEFAULT_MODEL } }, onModelSwitch)

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    @Throws(LlmException::class)
    private fun client(): AnthropicClient {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException("No Claude API key yet. Add one in Spark settings (console.anthropic.com → API keys).")
        cached?.takeIf { it.first == key }?.let { return it.second }
        val c = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(2).build()
        cached = key to c
        return c
    }

    @Volatile private var listed: Pair<Long, List<String>>? = null

    /** Claude models this key can use, best first (remembered for an hour). */
    @Throws(LlmException::class)
    suspend fun listModels(): List<String> {
        listed?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return it.second }
        val c = client()
        val ids = call { c.models().list().autoPager().map { it.id() }.toList() }
        return ModelCatalog.claudeModels(ids).also { listed = System.currentTimeMillis() to it }
    }

    override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
        val c = client()
        val messages = messages(history)
        val toolList = tools(tools)
        val reply = models.run(
            discover = { listModels().firstOrNull() },
            alternatives = { listModels() },
        ) { m -> call { c.messages().create(request(m, system, messages, toolList)) } }
        return parse(reply)
    }

    /** Asks Claude about a photo (the camera screen). */
    @Throws(LlmException::class)
    suspend fun ask(system: String, question: String, imageJpeg: ByteArray): String {
        val c = client()
        val message = visionMessage(question, imageJpeg)
        val reply = models.run(discover = { listModels().firstOrNull() }, alternatives = { listModels() }) { m ->
            call { c.messages().create(request(m, system, listOf(message), emptyList())) }
        }
        return parse(reply).text
    }

    /** Runs a blocking SDK call off the main thread and turns SDK errors into Spark's. */
    private suspend fun <T> call(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO) { block() }
    } catch (e: AnthropicServiceException) {
        throw translate(e.statusCode(), e.message.orEmpty(), e)
    } catch (e: AnthropicIoException) {
        throw LlmException("I can't reach Claude right now. Check the internet connection.", e)
    }

    companion object {
        /** Anthropic's recommended default model. */
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** Models that support the server-side refusal fallback ("default" routing). */
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-fable-5-2")

        fun translate(status: Int, detail: String, cause: Throwable? = null): LlmException {
            val short = detail.lineSequence().firstOrNull().orEmpty().take(200)
            return when {
                status == 401 -> LlmException("The Claude API key was refused. Check it in Spark settings.", cause)
                status == 403 -> LlmException("This Claude API key isn't allowed to do that. Check its permissions in the Claude Console.", cause)
                status == 404 -> ModelUnavailableException("Claude doesn't offer that model to this key. Spark will pick one that works.")
                status == 429 -> ProviderBusyException("Claude's rate limit was hit. Give me a moment and try again.", cause)
                status >= 500 -> ProviderBusyException("Claude is very busy right now. Try again in a moment.", cause)
                status == 400 && detail.contains("credit balance", ignoreCase = true) ->
                    LlmException("Your Claude account is out of credit. Add credit in the Claude Console (Plans & Billing).", cause)
                else -> LlmException("Claude had a problem ($status). $short".trim(), cause)
            }
        }

        fun request(model: String, system: String, messages: List<MessageParam>, tools: List<Tool>): MessageCreateParams {
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                // A conversational companion: medium effort keeps replies quick and thoughtful.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .messages(messages)
            tools.forEach { b.addTool(it) }
            if (model in FALLBACK_MODELS) {
                // If a safety classifier declines, the server retries on a suitable model instead of failing.
                b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            return b.build()
        }

        /** A photo (base64 JPEG) followed by the question, as one user message. */
        fun visionMessage(question: String, imageJpeg: ByteArray): MessageParam = MessageParam.ofUserOfContentBlockParams(
            listOf(
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder().source(
                        Base64ImageSource.builder()
                            .data(Base64.getEncoder().encodeToString(imageJpeg))
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .build()
                    ).build()
                ),
                ContentBlockParam.ofText(TextBlockParam.builder().text(question).build()),
            )
        )

        /** Spark's tool declarations (UPPERCASE schema types) as Claude tools. */
        fun tools(declarations: JSONArray): List<Tool> {
            val out = ArrayList<Tool>()
            for (i in 0 until declarations.length()) {
                val decl = declarations.optJSONObject(i) ?: continue
                val params = decl.optJSONObject("parameters")?.let { lowercaseTypes(it) } ?: JSONObject()
                val props = Tool.InputSchema.Properties.builder()
                val p = params.optJSONObject("properties")
                if (p != null) for (k in p.keys()) props.putAdditionalProperty(k, JsonValue.from(toPlain(p.get(k))))
                val schema = Tool.InputSchema.builder().properties(props.build())
                val req = params.optJSONArray("required")
                if (req != null) schema.required((0 until req.length()).map { req.optString(it) })
                out += Tool.builder()
                    .name(decl.optString("name"))
                    .description(decl.optString("description"))
                    .inputSchema(schema.build())
                    .build()
            }
            return out
        }

        /** The neutral conversation as Claude messages. */
        fun messages(history: List<Msg>): List<MessageParam> {
            val out = ArrayList<MessageParam>()
            var started = false
            for (m in history) {
                when (m) {
                    is Msg.User -> {
                        started = true
                        out += MessageParam.ofUser(m.text.ifBlank { "…" })
                    }
                    is Msg.Assistant -> {
                        // Claude conversations start with the user.
                        if (!started) continue
                        val blocks = ArrayList<ContentBlockParam>()
                        if (m.text.isNotBlank()) blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(m.text).build())
                        m.calls.forEachIndexed { i, call ->
                            val input = ToolUseBlockParam.Input.builder()
                            for (k in call.args.keys()) input.putAdditionalProperty(k, JsonValue.from(toPlain(call.args.get(k))))
                            blocks += ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder().id(callId(call.id, call.name, i)).name(call.name).input(input.build()).build()
                            )
                        }
                        if (blocks.isEmpty()) blocks += ContentBlockParam.ofText(TextBlockParam.builder().text("…").build())
                        out += MessageParam.ofAssistantOfContentBlockParams(blocks)
                    }
                    is Msg.ToolResults -> {
                        if (!started) continue
                        val blocks = m.results.mapIndexed { i, r ->
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(callId(r.id, r.name, i))
                                    .content(r.result.toString())
                                    .isError(r.result.has("error"))
                                    .build()
                            )
                        }
                        out += MessageParam.ofUserOfContentBlockParams(blocks)
                    }
                }
            }
            return out
        }

        /** Tool-call ids must match between a call and its result; older history may not have one. */
        fun callId(id: String, name: String, index: Int): String =
            id.takeIf { it.isNotBlank() && it.matches(Regex("^[a-zA-Z0-9_-]+$")) }
                ?: "call_${name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_$index"

        @Throws(LlmException::class)
        fun parse(message: Message): LlmReply {
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                val why = message.stopDetails().flatMap { it.explanation() }.orElse("")
                throw LlmException("Claude declined to answer that." + if (why.isNotBlank()) " ($why)" else "")
            }
            val text = StringBuilder()
            val calls = ArrayList<ToolCall>()
            for (block in message.content()) {
                block.text().ifPresent { text.append(it.text()) }
                block.toolUse().ifPresent { use ->
                    @Suppress("UNCHECKED_CAST")
                    val map = (use._input().convert(Map::class.java) as? Map<String, Any?>).orEmpty()
                    calls += ToolCall(use.name(), JSONObject(map), use.id())
                }
            }
            val clean = text.toString().trim()
            if (clean.isEmpty() && calls.isEmpty()) {
                throw LlmException(
                    if (message.stopReason().orElse(null) == StopReason.MAX_TOKENS) "That answer got too long for me to finish."
                    else "Claude returned an empty reply."
                )
            }
            return LlmReply(Msg.Assistant(clean, calls), clean, calls)
        }

        /** JSON Schema wants lowercase types; Spark's catalogue uses UPPERCASE. */
        fun lowercaseTypes(node: JSONObject): JSONObject {
            val out = JSONObject()
            for (k in node.keys()) {
                val v = node.get(k)
                out.put(k, when {
                    k == "type" && v is String -> v.lowercase()
                    v is JSONObject -> lowercaseTypes(v)
                    v is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(v.get(i).let { e -> if (e is JSONObject) lowercaseTypes(e) else e }) }
                    else -> v
                })
            }
            return out
        }

        /** org.json values as plain Kotlin maps and lists, for the SDK's JsonValue. */
        fun toPlain(v: Any?): Any? = when (v) {
            is JSONObject -> v.keys().asSequence().associateWith { toPlain(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { toPlain(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}

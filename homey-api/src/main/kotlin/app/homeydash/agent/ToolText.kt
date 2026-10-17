package app.homeydash.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tools as a few short lines of text instead of JSON schemas, and the conversation with tool
 * calls written out, for a local model that is told about its tools in the prompt and answers
 * with a written call ([AgentSettings.compactTools]). Far fewer tokens than the schemas, and no
 * server-side tool handling to go wrong.
 */
object ToolText {
    /** How a question starts in its note; the same words as Agent's label. */
    private const val QUESTION = "The user said:"

    /** How to call a tool, in the rules. */
    const val HOW = "To use a tool, reply with only lines like " +
        "CALL set_device {\"device\": \"light_3\", \"capability\": \"onoff\", \"value\": \"true\"} " +
        "(CALL, the tool name, its arguments as JSON), one call per line, and nothing else: no words before or after them, not even a joke, " +
        "because the user hears nothing until the tools are done. No tags, no XML. " +
        "The results come back to you; then answer the user in words. Never say you did something without calling a tool for it. " +
        "After the first line from the app, everything comes wrapped in <tool_response> tags, the user's questions too: " +
        "\"$QUESTION\" in one is the user speaking to you now."

    /** One line per tool: `name(arg, optional?): what it does`. */
    fun lines(specs: List<ToolSpec>): String = specs.joinToString("\n") { line(it) }

    fun line(spec: ToolSpec): String {
        val props = (spec.parameters["properties"] as? JsonObject)?.keys.orEmpty()
        val required = (spec.parameters["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val args = props.joinToString(", ") { if (it in required) it else "$it?" }
        return "${spec.name}($args): ${spec.description}"
    }

    /**
     * [messages] with tool calls and results as plain text, so the server sees an ordinary
     * conversation: calls as the model wrote them, results as a note from the app.
     */
    fun written(messages: List<ChatMessage>): List<ChatMessage> = messages.mapIndexed { i, m ->
        when {
            // Qwen's chat template drops the empty <think></think> from every answer before the
            // last real user line, so each new question changed the earlier answers and the local
            // AI read them all again (about 1 100 tokens, 5 s). With every user line after the
            // first written as a tool response, earlier answers stay exactly as they were sent.
            m.role == "user" && i > FIRST_USER && !wrapped(m.content) -> ChatMessage.user(wrap(m.content.orEmpty()))
            m.role == "assistant" && m.toolCalls.isNotEmpty() -> ChatMessage(
                "assistant",
                (listOfNotNull(m.content?.takeIf { it.isNotBlank() }) + m.toolCalls.map(::call)).joinToString("\n"),
            )
            m.role == "tool" -> ChatMessage.user(wrap(m.content.orEmpty()))
            else -> m
        }
    }

    /**
     * A plain CALL line rather than Qwen's <tool_call> tags: vMLX hides everything after a
     * native tool tag when the request carries no tool schemas, so a tagged call came back as
     * an empty answer.
     */
    private fun wrap(text: String) = "<tool_response>\n$text\n</tool_response>"

    private fun wrapped(text: String?) = text.orEmpty().trim().let { it.startsWith("<tool_response>") && it.endsWith("</tool_response>") }

    /** The system rules come first, then the one real user line ([START]), then the conversation. */
    private const val FIRST_USER = 1

    /** The one user line not wrapped, right after the rules; the warm-up sends it too. */
    const val START = "(From the app: a warm-up, no answer needed.)"

    fun call(c: ToolCall): String = "CALL ${c.name} ${c.arguments}"
}

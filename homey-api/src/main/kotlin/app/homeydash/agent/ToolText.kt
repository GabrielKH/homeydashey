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
    /** How to call a tool, in the rules. */
    const val HOW = "To use a tool, reply with only lines like " +
        "CALL set_device {\"device\": \"light_3\", \"capability\": \"onoff\", \"value\": \"true\"} " +
        "(CALL, the tool name, its arguments as JSON), one call per line, and nothing else: no words before or after them, not even a joke, " +
        "because the user hears nothing until the tools are done. No tags, no XML. " +
        "The results come back to you; then answer the user in words. Never say you did something without calling a tool for it."

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
    fun written(messages: List<ChatMessage>): List<ChatMessage> = messages.map { m ->
        when {
            m.role == "assistant" && m.toolCalls.isNotEmpty() -> ChatMessage(
                "assistant",
                (listOfNotNull(m.content?.takeIf { it.isNotBlank() }) + m.toolCalls.map(::call)).joinToString("\n"),
            )
            m.role == "tool" -> ChatMessage.user("<tool_response>\n${m.content.orEmpty()}\n</tool_response>")
            else -> m
        }
    }

    /**
     * A plain CALL line rather than Qwen's <tool_call> tags: vMLX hides everything after a
     * native tool tag when the request carries no tool schemas, so a tagged call came back as
     * an empty answer.
     */
    fun call(c: ToolCall): String = "CALL ${c.name} ${c.arguments}"
}

package app.homeydash.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Tools as a few short lines of text instead of JSON schemas, and the conversation with tool
 * calls written out, for a local model that is told about its tools in the prompt and answers
 * with a written call ([AgentSettings.compactTools]). Far fewer tokens than the schemas, and no
 * server-side tool handling to go wrong.
 */
object ToolText {
    /** How to call a tool, in the rules. */
    const val HOW = "To use tools, reply with only the calls, one per line, exactly like " +
        "<tool_call>{\"name\": \"set_device\", \"arguments\": {\"device\": \"light_3\", \"capability\": \"onoff\", \"value\": \"true\"}}</tool_call> " +
        "and nothing else. The results come back to you; then answer the user in words. Never say you did something without calling a tool for it."

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

    private fun call(c: ToolCall): String =
        "<tool_call>" + buildJsonObject { put("name", JsonPrimitive(c.name)); put("arguments", c.arguments) } + "</tool_call>"
}

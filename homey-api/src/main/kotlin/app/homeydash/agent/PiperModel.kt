package app.homeydash.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/**
 * Turns a Piper voice (an .onnx model and its .onnx.json) into what sherpa-onnx speaks on the
 * tablet: the model with sherpa's metadata added and a tokens.txt, as sherpa's own Piper export
 * does. The metadata is appended to the model file, which protobuf allows, so a 60 MB model is
 * never read into memory.
 */
object PiperModel {
    /** tokens.txt: each phoneme and its id, one per line. */
    fun tokens(config: JsonObject): String = buildString {
        val map = config["phoneme_id_map"]?.jsonObject ?: return@buildString
        for ((symbol, id) in map) {
            if (symbol == "\n") continue
            val n = when (id) {
                is JsonArray -> (id.firstOrNull() as? JsonPrimitive)?.intOrNull
                is JsonPrimitive -> id.intOrNull
                else -> null
            } ?: continue
            append(symbol).append(' ').append(n).append('\n')
        }
    }

    /** sherpa-onnx's metadata for a Piper voice; [espeakVoice] makes it read another language's text. */
    fun metadata(config: JsonObject, espeakVoice: String? = null): Map<String, String> {
        val voice = espeakVoice ?: (config["espeak"] as? JsonObject)?.get("voice")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "en-us"
        val rate = (config["audio"] as? JsonObject)?.get("sample_rate")?.let { (it as? JsonPrimitive)?.intOrNull }?.let { if (it == 22500) 22050 else it } ?: 22050
        val speakers = (config["num_speakers"] as? JsonPrimitive)?.intOrNull ?: 1
        return linkedMapOf(
            "model_type" to "vits",
            "comment" to "piper",
            "language" to if (voice.startsWith("sv")) "Swedish" else "English",
            "voice" to voice,
            "version" to "1",
            "has_espeak" to "1",
            "has_g2pw" to "0",
            "n_speakers" to speakers.toString(),
            "sample_rate" to rate.toString(),
        )
    }

    /** The speaker called [name] ("neutral" in TARS's mood voices), or 0. */
    fun speakerId(config: JsonObject, name: String): Int =
        ((config["speaker_id_map"] as? JsonObject)?.get(name) as? JsonPrimitive)?.intOrNull ?: 0

    /** [meta] as ModelProto.metadata_props entries (field 14), to append to an .onnx file. */
    fun metadataBytes(meta: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((k, v) in meta) {
            val entry = ByteArrayOutputStream()
            field(entry, 1, k.toByteArray())
            field(entry, 2, v.toByteArray())
            field(out, 14, entry.toByteArray())
        }
        return out.toByteArray()
    }

    private fun field(out: ByteArrayOutputStream, number: Int, bytes: ByteArray) {
        varint(out, (number shl 3 or 2).toLong())
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

package app.homeydash.agent

/**
 * Turns an answer that arrives word by word into sentences to speak while the rest is still
 * being written, like a person who starts talking before they've finished the thought.
 * Only what is meant to be heard goes to [say]: thinking in <think> tags, `CALL` lines and
 * other tool-call text are held back, and so is an answer that turns out to be [Agent.IGNORE].
 * [polish] makes a sentence ready to say (device aliases become their names).
 */
class SpeechStream(private val say: (String) -> Unit, private val polish: (String) -> String = { it }) {
    private val text = StringBuilder()
    /** How much of this round's speakable text has been said. */
    private var said = 0
    /** A tool call or something else not to be heard started; the rest of this round stays quiet. */
    private var quiet = false

    /** Something was said in any round, so the answer has started out loud. */
    var started = false
        private set

    /** Something of this round was said. */
    var spokeThisRound = false
        private set

    /** What of this round was said, so the next round isn't told to say it again. */
    private val heard = StringBuilder()

    @Synchronized
    fun heardThisRound(): String = heard.toString().trim()

    /** Takes the next piece of the answer and says every sentence it completes. */
    @Synchronized
    fun feed(piece: String) {
        text.append(piece)
        if (quiet) return
        val (speakable, stopped) = speakable(text.toString())
        if (stopped) quiet = true
        // "[ignore]" means it wasn't spoken to: wait until the start can't be that.
        val start = speakable.trimStart()
        if (start.isEmpty() || (Agent.IGNORE.startsWith(start.take(Agent.IGNORE.length)) && !quiet)) return
        if (start.contains(Agent.IGNORE)) {
            quiet = true
            return
        }
        sayDone(speakable, all = quiet)
    }

    /** The round ended with nothing more to come: says whatever is left. */
    @Synchronized
    fun finish() {
        val (speakable, _) = speakable(text.toString())
        if (speakable.trimStart().startsWith(Agent.IGNORE)) return
        sayDone(speakable, all = true)
    }

    /** A new round of the same answer starts (after its tools ran). */
    @Synchronized
    fun next() {
        text.setLength(0)
        said = 0
        quiet = false
        spokeThisRound = false
        heard.setLength(0)
    }

    /** Says the finished sentences of [speakable] not said yet, or everything with [all]. */
    private fun sayDone(speakable: String, all: Boolean) {
        if (said >= speakable.length) return
        val rest = speakable.substring(said)
        val end = if (all) rest.length else lastEnd(rest)
        if (end <= 0) return
        val sentence = rest.substring(0, end).trim()
        said += end
        if (sentence.isEmpty() || sentence.none { it.isLetterOrDigit() }) return
        started = true
        spokeThisRound = true
        heard.append(sentence).append(' ')
        say(polish(sentence))
    }

    internal companion object {
        /** Long enough that a lone "Okay." rides along with the next sentence. */
        const val SHORTEST = 20
        private val THINK = Regex("<think>[\\s\\S]*?(</think>|$)")
        /** Where tool-call text starts: a CALL line, Qwen's tags, a bare JSON object or a code fence. */
        private val NOT_SPOKEN = Regex("""(?m)(^[ \t*`]*(CALL[ \t]|\{|```)|(?<=\s)CALL[ \t]+[a-z_]|<tool_call|<function=)""")
        private val END = Regex("""[.!?…](["')\]]*)\s+|\n+""")

        /** The text that may be heard so far, and whether something not to be heard began. */
        fun speakable(text: String): Pair<String, Boolean> {
            val clean = text.replace(THINK, "")
            val stop = NOT_SPOKEN.find(clean)?.range?.first ?: return clean to false
            return clean.substring(0, stop) to true
        }

        /** Where the last finished sentence of [text] ends, at least [SHORTEST] in; 0 when none has. */
        fun lastEnd(text: String): Int {
            var end = 0
            for (m in END.findAll(text)) {
                val at = m.range.last + 1
                if (text.substring(0, at).trim().length >= SHORTEST) end = at
            }
            return end
        }
    }
}

/** Short sounds a person makes while thinking, said when an answer takes a moment. */
object ThinkingSounds {
    private val NOVA_SV = listOf("Hmm…", "Mm, låt se.", "Hmm, ett ögonblick.")
    private val NOVA_EN = listOf("Hmm…", "Let me see.", "Mm, one moment.")
    private val TARS = listOf("Hmm.", "Processing.", "Give me a second.", "Thinking. It happens.")

    fun all(persona: Persona, swedish: Boolean): List<String> = when {
        persona == Persona.Tars -> TARS
        swedish -> NOVA_SV
        else -> NOVA_EN
    }

    /** One of [options], not [last] again when there's another; null when there are none. */
    fun pick(options: List<String>, last: String?, random: kotlin.random.Random = kotlin.random.Random): String? {
        val fresh = options.filter { it != last }.ifEmpty { options }
        return if (fresh.isEmpty()) null else fresh[random.nextInt(fresh.size)]
    }

    /** How long the answer may take before a thinking sound fills the silence. */
    const val AFTER_MS = 900L
}

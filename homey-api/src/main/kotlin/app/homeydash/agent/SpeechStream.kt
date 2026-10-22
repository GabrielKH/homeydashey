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
    /** This round is said only once it ends as the answer ([next] with hold). */
    private var holding = false

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
        if (quiet || holding) return
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

    /**
     * A new round of the same answer starts (after its tools ran). With [hold] nothing of it is
     * said while it's written, only at [finish]: after a tool ran, words written before more
     * calls are the AI thinking aloud about the results ("Hmm, that played on the Beam instead,
     * let me try again"), which only confuse whoever listens.
     */
    @Synchronized
    fun next(hold: Boolean = false) {
        text.setLength(0)
        said = 0
        quiet = false
        holding = hold
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
    // Real words only: a voice reads "Hmm" or "Mm" letter by letter ("h, m, m").
    private val NOVA_SV = listOf("Mja, låt se.", "Ehm, ett ögonblick.", "Låt mig kolla.")
    // Plain words: "Um, let me see" came out as something nobody could make out (Gabriel, 2026-10-22).
    private val NOVA_EN = listOf("One moment.", "Let me check.", "Just a second.")
    private val TARS = listOf("Processing.", "Give me a second.", "Thinking. It happens.")

    // Said the moment someone stops talking, while the question is written down and thought
    // about: what a person says to show they heard. Neutral, since the question isn't known yet.
    private val ACK_SV = listOf("Ja.", "Okej.", "Japp.", "Aha.")
    private val ACK_EN = listOf("Okay.", "Sure.", "Right.", "Got it.")
    private val ACK_TARS = listOf("Copy.", "Understood.", "On it.")

    private val ANSWER_SV = listOf("Ja?", "Japp?", "Ja, du?")
    private val ANSWER_EN = listOf("Yes?", "Yeah?", "I'm listening.")
    private val ANSWER_TARS = listOf("Yes?", "Go ahead.", "Listening.")

    /** What it says in its own voice when it hears its name ([WakeSound.Voice]). */
    fun answers(persona: Persona, swedish: Boolean): List<String> = when {
        persona == Persona.Tars -> ANSWER_TARS
        swedish -> ANSWER_SV
        else -> ANSWER_EN
    }

    /** What it says the moment someone stops talking, to show it heard ([AgentSettings.quickYes]). */
    fun acks(persona: Persona, swedish: Boolean): List<String> = when {
        persona == Persona.Tars -> ACK_TARS
        swedish -> ACK_SV
        else -> ACK_EN
    }

    /** Every short sound [persona] says without waiting, so all of them can be made ahead of time. */
    fun everything(persona: Persona, swedish: Boolean): List<String> =
        (acks(persona, swedish) + all(persona, swedish) + answers(persona, swedish)).distinct()

    /**
     * Whether a quick "ja" is worth saying: not when the answer usually starts sooner than
     * [ACK_SKIP_MS] after someone stops talking ([usualWaitMs], from the memory), since a "ja"
     * right before the answer only sounds like a stutter.
     */
    fun wantsAck(usualWaitMs: Long?): Boolean = usualWaitMs == null || usualWaitMs > ACK_SKIP_MS

    private val HUMS = Regex("""(?i)(?<![\p{L}])(h+m+|m{2,})(?![\p{L}])""")

    /** A hum the AI wrote ("Hmm, det vet jag inte") as a word a voice can say, not "h, m, m". */
    fun sayable(text: String, swedish: Boolean): String = HUMS.replace(text) { if (swedish) "ehm" else "um" }

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

    /** The same, after a quick "ja" was said: a second sound so soon would be chatty. */
    const val AFTER_ACK_MS = 2_500L

    /** Answers that usually start sooner than this after the question get no quick "ja". */
    const val ACK_SKIP_MS = 800L
}

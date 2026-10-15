package app.homeydash.agent

/** Where an AI model runs. Ollama is on a computer at home; the others are on the internet. */
enum class Provider(val label: String, val defaultUrl: String, val defaultModel: String, val local: Boolean) {
    Ollama("Ollama (your computer)", "http://192.168.1.2:11434/v1", "qwen3.5:9b", local = true),
    Gemini("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", local = false),
    OpenAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", local = false),
}

/** One AI connection. All three providers speak the OpenAI chat format with tools. */
data class Connection(
    val provider: Provider,
    val baseUrl: String = provider.defaultUrl,
    val apiKey: String = "",
    val model: String = provider.defaultModel,
) {
    val local: Boolean get() = provider.local

    /** Ollama needs no key; the internet providers do. */
    val usable: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && (local || apiKey.isNotBlank())
}

/** Which connection answers: only the PC, only the internet, or the PC first and the internet when it's off. */
enum class BrainMode(val label: String) { Local("Local only"), Internet("Internet only"), Smart("Smart") }

enum class Language(val label: String) { Auto("Svenska + English"), Swedish("Svenska"), English("English") }

/** How long the assistant keeps listening after an answer, for the next question. */
enum class FollowUp(val label: String, val detail: String, val ms: Long) {
    Off("Off", "Say the wake name before every question.", 0),
    Short("8 s", "Listens 8 seconds for the next question, then goes quiet.", 8_000),
    Long("20 s", "Listens 20 seconds for the next question, then goes quiet.", 20_000),
    Always("Always on", "Always listening; say \"stop\" or tap to end the conversation.", -1),
    ;

    companion object {
        /** Words that end a conversation on their own: "avsluta", "stopp", "tack". */
        private val ENDING_WORDS = setOf(
            "stop", "stopp", "stoppa", "sluta", "avsluta", "avbryt", "cancel", "quit", "exit", "end", "enough",
            "tack", "tacka", "tackar", "thanks", "thank", "thx", "bye", "goodbye", "hejda", "adjo", "nevermind",
        )

        /** Whole phrases that end it: "det var allt", "that's all", "never mind", "hej då". */
        private val ENDING_PHRASES = listOf(
            "det var allt", "det ar bra", "det racker", "hej da", "thats all", "that is all", "never mind", "thats it", "that will be all",
        )

        /** Small words around an ending that don't change it: "okej tack då", "stopp nu", "tack så mycket Nova". */
        private val FILLERS = setOf(
            "ok", "okay", "okej", "oke", "ja", "jaha", "nej", "men", "nu", "da", "sa", "bra", "mycket", "hej", "hey",
            "you", "very", "much", "so", "please", "snalla", "for", "hjalpen", "the", "help", "now", "then", "alright", "fint",
            "allt", "det", "var", "ar", "that", "thats", "is", "all", "it", "never", "mind", "will", "be", "racker",
            "jag", "forstar", "i", "see", "great", "perfekt", "perfect", "toppen", "kanon",
        )

        /**
         * Whether [text] only ends the conversation and needs no answer: an ending word or
         * phrase ("avsluta", "stopp", "tack", "det var allt") with nothing else but small words
         * around it. "Stoppa musiken" or "tack, släck lampan" still go to the assistant.
         */
        fun ends(text: String, name: String = ""): Boolean {
            val t = NameMatch.normalize(text.replace("'", "").replace("’", ""))
            if (t.isEmpty()) return false
            val n = NameMatch.normalize(name)
            var rest = " $t "
            ENDING_PHRASES.forEach { rest = rest.replace(" $it ", " end ") }
            val words = rest.split(' ').filter { it.isNotEmpty() && it != n }
            return words.any { it in ENDING_WORDS } && words.all { it in ENDING_WORDS || it in FILLERS }
        }
    }
}

/**
 * How readily the wake name is heard, 0–100: higher boosts the microphone and accepts
 * less certain matches, for a big room; lower ignores more of the TV and chatter.
 */
object MicSensitivity {
    const val DEFAULT = 50

    /** Microphone gain, 0.25× at 0 to 4× at 100, 1× in the middle. */
    fun gain(sensitivity: Int): Float = Math.pow(2.0, (sensitivity.coerceIn(0, 100) - 50) / 25.0).toFloat()

    /** How sure the recogniser must be of each word, 0.8 at 0 to 0.55 at 100. */
    fun minConfidence(sensitivity: Int): Double = 0.8 - sensitivity.coerceIn(0, 100) * 0.0025
}

/** How far the assistant may go with something sensitive. */
enum class Access(val label: String) { Off("Off"), LocalOnly("Local AI only"), On("On") }

/** Things in the home that are secret by default; each one is a switch in Settings → Assistant. */
enum class Secret(val title: String, val detail: String) {
    Calendar("Calendar", "Read event titles and times."),
    CalendarDetails("Calendar details", "Also read where events are."),
    Presence("Who is home", "Read whether each Homey user is home or asleep."),
    Locks("Locks", "Read whether doors are locked, and lock them."),
    Unlock("Unlock doors", "Unlock doors; always asks you to tap Yes on the tablet first."),
    Alarm("Alarm", "Read the home alarm and arm it. It can never disarm it."),
    Cameras("Cameras and doorbells", "Read motion and rings. Never the pictures."),
    Variables("Homey variables", "Read Homey Logic variables, which often hold codes."),
    Faces("Face and voice recognition", "Know who is at the tablet, by face or by voice (first name only)."),
}

data class AgentSettings(
    val enabled: Boolean = true,
    val local: Connection? = null,
    val internet: Connection? = null,
    val mode: BrainMode = BrainMode.Smart,
    val language: Language = Language.Auto,
    val secrets: Map<Secret, Access> = emptyMap(),
    /** Devices kept away from the assistant. */
    val hiddenDevices: Set<String> = emptySet(),
    /** The assistant also sees devices hidden on the dashboard or in the Homey app. */
    val seeHiddenDevices: Boolean = true,
    /** The speaker that answers, or null for the tablet itself. */
    val speakerId: String? = null,
    /** Volume for answers on a speaker, 0–100. */
    val speakerVolume: Int = 35,
    /** Speak answers aloud at all. */
    val speak: Boolean = true,
    /** Strangers at the tablet (face recognition sees someone it doesn't know) only get guest access. */
    val strangersAreGuests: Boolean = false,
    /** Saying this name ("Hey Nova") starts listening, like "Hey Siri". */
    val wake: Boolean = false,
    val wakeName: String = "Nova",
    /** The wake name only works for people whose voice the tablet has learned. */
    val onlyKnownVoices: Boolean = false,
    /** Keep listening for the next question after a spoken answer. */
    val followUp: FollowUp = FollowUp.Short,
    /** How readily the wake name is heard; see [MicSensitivity]. */
    val micSensitivity: Int = MicSensitivity.DEFAULT,
    /** How the assistant looks and sounds. */
    val face: FaceStyle = FaceStyle.Orb,
    val voice: VoiceStyle = VoiceStyle.Natural,
    /** The scanner in KITT's red instead of the theme's accent. */
    val classicRed: Boolean = false,
    /** Set per question when a guest is asking: no secrets, Flows, Goodnight or settings. */
    val guest: Boolean = false,
    /** Questions are heard by Whisper on your own computer (whisper.cpp's server) instead of Google. */
    val useWhisper: Boolean = false,
    /** Whisper's address; empty means the Ollama computer on port 8080. */
    val whisperUrl: String = "",
    /** Questions are heard by OpenAI instead of Google (after Whisper, when both are on). */
    val useOpenAiSpeech: Boolean = false,
    /** OpenAI key for hearing; empty means the internet brain's key when that is OpenAI. */
    val openAiSpeechKey: String = "",
    /** Sends what the assistant heard, thought and did to a GitHub issue, so it can be looked into. */
    val sendLogs: Boolean = false,
    /** A GitHub token that may write issues in [logRepo]. */
    val logToken: String = "",
    val logRepo: String = AgentLog.DEFAULT_REPO,
    /**
     * The local AI gets its tools as short lines of text and answers with a written call, instead
     * of JSON schemas the server must handle: a much shorter prompt, and it works on servers that
     * lose tool calls on long prompts (vMLX with Qwen 3.5).
     */
    val compactTools: Boolean = true,
    /** A Spotify developer app, so any song, album, artist or playlist can be found and played on Sonos. */
    val spotifyClientId: String = "",
    val spotifySecret: String = "",
) {
    /** Who hears questions, in order; empty means Android's own speech recognition. */
    fun hearers(): List<Hearer> = listOfNotNull(Hearer.Whisper.takeIf { useWhisper }, Hearer.OpenAI.takeIf { useOpenAiSpeech })

    fun whisperUrlOrGuess(): String = whisperUrl.trim().ifBlank { Transcriber.guessUrl(local) }

    fun openAiSpeechKeyOrBrain(): String =
        openAiSpeechKey.trim().ifBlank { internet?.takeIf { it.provider == Provider.OpenAI }?.apiKey.orEmpty() }

    fun access(secret: Secret): Access = secrets[secret] ?: Access.Off

    /** Whether [secret] may be used by a brain that is local ([local] = true) or on the internet. */
    fun allows(secret: Secret, local: Boolean): Boolean = when (access(secret)) {
        Access.Off -> false
        Access.LocalOnly -> local
        Access.On -> true
    }

    /** These settings as they apply to someone with [role] at the tablet. */
    fun forRole(role: Role): AgentSettings = when (role) {
        Role.Owner -> this
        Role.Family -> copy(secrets = secrets.filterKeys { it !in FAMILY_BLOCKED })
        Role.Guest -> copy(secrets = emptyMap(), guest = true)
    }

    /** The connections to try for one question, in order. */
    /** The connection that looks things up on the internet; never in "Local only" mode. */
    fun webSearch(): Connection? = internet?.takeIf { it.usable && mode != BrainMode.Local }

    fun brains(): List<Connection> {
        val l = local?.takeIf { it.usable }
        val i = internet?.takeIf { it.usable }
        return when (mode) {
            BrainMode.Local -> listOfNotNull(l)
            BrainMode.Internet -> listOfNotNull(i)
            BrainMode.Smart -> listOfNotNull(l, i)
        }
    }
}

/** What someone face recognition knows may do. */
enum class Role(val label: String, val detail: String) {
    Owner("Owner", "Everything the security switches allow."),
    Family("Family", "No unlocking, alarm, variables or calendar details."),
    Guest("Guest", "Lights, music, climate, timers and weather only."),
}

private val FAMILY_BLOCKED = setOf(Secret.Unlock, Secret.Alarm, Secret.Variables, Secret.CalendarDetails)

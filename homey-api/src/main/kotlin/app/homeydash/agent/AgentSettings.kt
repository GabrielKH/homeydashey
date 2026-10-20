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

    /** A local server needs a key only if it asks for one (oMLX does); the internet providers always do. */
    val usable: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && (local || apiKey.isNotBlank())
}

/** Which connection answers: only the PC, only the internet, or the PC first and the internet when it's off. */
enum class BrainMode(val label: String) { Local("Local only"), Internet("Internet only"), Smart("Smart") }

enum class Language(val label: String) { Auto("Svenska + English"), Swedish("Svenska"), English("English") }

/** How long the assistant keeps listening after an answer, for the next question. */
/** How the assistant's window shows up when someone starts talking to it. */
enum class AssistantView(val label: String, val detail: String) {
    Full("Full screen", "The face fills the screen. Tap the minus to make it small."),
    Island("Island", "A small glass island at the top, with three lines of what it's doing. Tap it for full screen."),
    Side("Side window", "A small window on the left with the face and the text. Tap it for full screen."),
}

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
enum class Secret(val title: String, val detail: String, val default: Access = Access.Off) {
    Calendar("Calendar", "Read event titles and times."),
    CalendarDetails("Calendar details", "Also read where events are."),
    Presence("Who is home", "Read whether each Homey user is home or asleep, and mark people home, away or asleep."),
    WakeAlarms("Wake-up alarms", "Read, set and switch off Homey's wake-up alarms (\"väck mig 6:15\").", Access.On),
    Locks("Locks", "Read whether doors are locked, and lock them."),
    Unlock("Unlock doors", "Unlock doors; always asks you to tap Yes on the tablet first."),
    Alarm("Alarm", "Read the home alarm and arm it. It can never disarm it."),
    Cameras("Cameras and doorbells", "Read motion and rings. Never the pictures."),
    Variables("Homey variables", "Read Homey Logic variables, which often hold codes."),
    Faces("Face and voice recognition", "Know who is at the tablet, by face or by voice (first name only)."),
    Phones("Find phones", "Ring a phone so it can be found, and say if it's home and its battery, through Homey's Apple Find My app. Never says where outside the home it is."),
    Memory(
        "Memory",
        "Remember habits, what was said and what people tell about themselves under People, and use it when a question needs it. Kept on the tablet.",
        Access.LocalOnly,
    ),
}

/** How readily talking over an answer stops it; the wake name always does. */
enum class Interrupt(val label: String, val detail: String) {
    Off("Off", "Only saying the wake name stops an answer."),
    Clear("Clear speech", "A voice taught under People, speaking clearly for about a second, stops the answer. Noises, the TV and its own voice don't."),
    Easy("Easy", "A taught voice stops the answer sooner and more quietly. In a noisy kitchen it may stop by mistake now and then."),
}

/** Who the assistant is: how it talks, apart from how it sounds. */
enum class Persona(val label: String, val detail: String) {
    Nova(
        "Nova",
        "Warm, curious and quick-witted, after Samantha in \"Her\": does what you ask straight away, then talks like a friend, " +
            "notices how you sound, teases a little and has her own opinions. Answers in Swedish or English, whichever you speak, in a sentence or three.",
    ),
    Tars(
        "TARS",
        "TARS from Interstellar: his voice, his name as the wake word, a \"huuh?\" when he hears it, and dry, deadpan humor " +
            "and sarcasm with a setting you can turn up or down. He only answers in English, whatever you speak. He notices ironic things in the home, and a flat tablet battery gets him down.",
    ),
}

const val TARS_WAKE_NAME = "TARS"

/** What [p] says about itself when it is picked in Settings, in its own words; [name] is what it answers to. */
fun Persona.intro(name: String, swedish: Boolean, humor: Int = 75): String = when (this) {
    Persona.Tars ->
        "TARS. Former Marine Corps robot, now in charge of your kitchen. Humor setting $humor percent, honesty ninety. " +
            "I do what you ask first and tell you what I think of it second. In English. Say Hey TARS when you need me."
    Persona.Nova -> if (swedish) {
        "Hej, jag är $name! Jag sköter lampor, musik, timers och kylen, och svarar kort på svenska eller engelska. Säg hej $name när du behöver mig."
    } else {
        "Hi, I'm $name! I look after the lights, music, timers and the fridge, and keep my answers short. Say Hey $name whenever you need me."
    }
}

/** mlx-audio's multilingual Chatterbox: natural Swedish and English on a Mac. */
const val DEFAULT_VOICE_MODEL = "mlx-community/chatterbox-multilingual-v3"
const val VOICE_SERVER_PORT = 8090

/** How fast the voice talks by default, in %: a touch slower than the voices' own speed. */
const val DEFAULT_PACE = 90
val PACE_RANGE = 70..130

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
    /**
     * Talk like a person: start saying the answer while the AI is still writing it, and make a
     * small "hmm" when it takes a moment. Only for answers on the tablet's own speaker.
     */
    val liveSpeech: Boolean = true,
    /** Whether, and how readily, someone whose voice the tablet knows can talk over an answer to stop it. */
    val interrupt: Interrupt = Interrupt.Clear,
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
    /** How the face shows up when someone starts talking to it. */
    val view: AssistantView = AssistantView.Full,
    val voice: VoiceStyle = VoiceStyle.Natural,
    /** How fast the voice talks, in % of its own speed; a little slower than normal is easier to follow across the kitchen. */
    val pace: Int = DEFAULT_PACE,
    /** Says every 20 minutes in the day that the tablet needs charging, when its battery is low; see [BatteryNag]. */
    val batteryReminders: Boolean = true,
    /** After a timer is started without a name, the assistant asks whether to give it one. */
    val askTimerName: Boolean = true,
    /** The little sound when the wake name is heard, before it listens. */
    val wakeSound: WakeSound = WakeSound.Voice,
    /** Who the assistant is, and for TARS how funny, 0–100 %. */
    val persona: Persona = Persona.Nova,
    val humor: Int = 75,
    /** The owner's own words for each character's personality, from Settings; missing or blank keeps [builtInTraits]. */
    val traits: Map<Persona, String> = emptyMap(),
    /** Nova's wake name, kept while TARS answers to his own. */
    val novaName: String = "Nova",
    /** The scanner in KITT's red instead of the theme's accent. */
    val classicRed: Boolean = false,
    /** Set per question when a guest is asking: no secrets, Flows, Goodnight or settings. */
    val guest: Boolean = false,
    /** Set per question: who is asking, when the tablet knows them by voice or face. */
    val asker: Asker? = null,
    /** Set per question: the people the tablet knows, id to name, to tell whose phone is whose. */
    val people: Map<String, String> = emptyMap(),
    /** Whose phones may be rung and found; see [Phones]. */
    val phoneSharing: PhoneSharing = PhoneSharing.Own,
    /** Each phone's owner chosen in Settings, device id to person id or [SHARED_PHONE]; others go by the name in the phone's. */
    val phoneOwners: Map<String, String> = emptyMap(),
    /** Questions are heard by Whisper on your own computer (whisper.cpp's server) instead of Google. */
    val useWhisper: Boolean = false,
    /** Whisper's address; empty means the Ollama computer on port 8080. */
    val whisperUrl: String = "",
    /** The voice server on your own computer for the Warm voice (mlx-audio); empty means the Ollama computer on port 8090. */
    val voiceServerUrl: String = "",
    /** The speech model the voice server runs. */
    val voiceServerModel: String = DEFAULT_VOICE_MODEL,
    /** The model's voice, when it has several; empty for its own. */
    val voiceServerVoice: String = "",
    /**
     * The Warm voice each character speaks in: the name of a short recording on the voice server
     * ("anna" for voices/anna.wav), which Chatterbox copies. Missing means [voiceServerVoice].
     */
    val warmVoices: Map<Persona, String> = emptyMap(),
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
    /**
     * Only the know-how a question needs is sent with it (music, timers, the vacuum…), found on
     * the tablet; off sends every rule with every question, as before. See [KnowHow].
     */
    val knowHow: Boolean = true,
    /** A Spotify developer app, so any song, album, artist or playlist can be found and played on Sonos. */
    val spotifyClientId: String = "",
    val spotifySecret: String = "",
) {
    /** Who hears questions, in order; empty means Android's own speech recognition. */
    fun hearers(): List<Hearer> = listOfNotNull(Hearer.Whisper.takeIf { useWhisper }, Hearer.OpenAI.takeIf { useOpenAiSpeech })

    fun whisperUrlOrGuess(): String = whisperUrl.trim().ifBlank { Transcriber.guessUrl(local) }

    /** The voice server's voice for the character speaking now. */
    fun serverVoice(): String = warmVoices[persona]?.trim()?.takeIf { it.isNotEmpty() } ?: voiceServerVoice.trim()

    fun voiceServerUrlOrGuess(): String = voiceServerUrl.trim().ifBlank {
        val host = local?.baseUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: return@ifBlank ""
        "http://$host:$VOICE_SERVER_PORT"
    }

    /**
     * Becomes [p] in one go: TARS answers to "TARS", says "huuh?" when he hears it, and speaks in
     * his own voice; back to Nova undoes what TARS changed and keeps the rest.
     */
    fun asCharacter(p: Persona): AgentSettings = when (p) {
        Persona.Tars -> copy(
            persona = p,
            voice = VoiceStyle.Tars,
            wakeSound = WakeSound.Huh,
            wakeName = TARS_WAKE_NAME,
            novaName = if (persona == Persona.Nova) wakeName.trim().ifBlank { novaName } else novaName,
        )
        Persona.Nova -> copy(
            persona = p,
            voice = if (voice == VoiceStyle.Tars) novaVoice(language) else voice,
            wakeSound = if (wakeSound == WakeSound.Huh) WakeSound.Voice else wakeSound,
            wakeName = if (wakeName.trim().equals(TARS_WAKE_NAME, ignoreCase = true)) novaName else wakeName,
        )
    }

    /**
     * [l] as the language: Svenska gives Nova Alma's Swedish voice, made on the tablet, and
     * English takes it away again, since Alma only speaks Swedish.
     */
    fun withLanguage(l: Language): AgentSettings = copy(
        language = l,
        voice = when {
            l == Language.Swedish && persona == Persona.Nova -> VoiceStyle.Alma
            l == Language.English && voice == VoiceStyle.Alma -> VoiceStyle.Natural
            else -> voice
        },
    )

    private fun novaVoice(l: Language) = if (l == Language.Swedish) VoiceStyle.Alma else VoiceStyle.Natural

    /** The name [p] answers to: TARS's own, or the name given to Nova. */
    fun nameOf(p: Persona): String = when {
        p == Persona.Tars -> TARS_WAKE_NAME
        persona == Persona.Nova -> wakeName.trim().ifBlank { novaName }
        else -> novaName
    }

    /** The character [person] talks with: their own pick under People, or the house's. */
    fun characterOf(person: KnownPerson?): Persona = person?.character ?: persona

    /** Every character [person] talks with: their picks, or the house's when they picked none. */
    fun charactersOf(person: KnownPerson?): List<Persona> = person?.characters.orEmpty().ifEmpty { listOf(persona) }

    /** Whether [p] answers [person]: the house's answers anyone, another only those who picked it. */
    fun answers(p: Persona, person: KnownPerson?): Boolean = p == persona || (person != null && p in person.characters)

    /**
     * The names that wake the assistant: the house character's, and another character's too
     * once someone picked it under People. Saying either works; only the personality differs.
     */
    fun wakeNames(people: List<KnownPerson>): List<String> {
        val chosen = people.flatMap { it.characters }.toSet()
        return (listOf(nameOf(persona)) + Persona.entries.filter { it != persona && it in chosen }.map { nameOf(it) })
            .filter { WakeName.clean(it).isNotEmpty() }
            .distinctBy { WakeName.key(it) }
    }

    /** The character a heard wake name belongs to; the house's when it is no character's. */
    fun characterNamed(name: String): Persona =
        Persona.entries.firstOrNull { WakeName.key(nameOf(it)) == WakeName.key(name) } ?: persona

    /**
     * These settings as [p] talking with [person]: [p]'s name, wake sound and way of talking. The
     * voice is the person's own pick for [p] when [p] is one of theirs, otherwise the house's for
     * the house's character and [p]'s own usual voice for the other.
     */
    fun talkingAs(p: Persona, person: KnownPerson? = null): AgentSettings {
        val theirs = person?.takeIf { p in charactersOf(it) }
        val own = theirs?.voices?.get(p)
        val warm = theirs?.warmVoices?.get(p)?.trim()?.takeIf { it.isNotEmpty() }
        var s = if (p == persona) this else asCharacter(p)
        if (own != null) s = s.copy(voice = own)
        if (warm != null) s = s.copy(warmVoices = s.warmVoices + (p to warm))
        return s
    }

    /** The language answers are spoken in: TARS answers in English whatever language he hears. */
    val answers: Language get() = if (persona == Persona.Tars) Language.English else language

    fun openAiSpeechKeyOrBrain(): String =
        openAiSpeechKey.trim().ifBlank { internet?.takeIf { it.provider == Provider.OpenAI }?.apiKey.orEmpty() }

    fun access(secret: Secret): Access = secrets[secret] ?: secret.default

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
        Role.Guest -> copy(secrets = emptyMap(), guest = true, asker = null)
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

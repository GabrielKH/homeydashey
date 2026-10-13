package app.homeydash.agent

/** Where an AI model runs. Ollama is on a computer at home; the others are on the internet. */
enum class Provider(val label: String, val defaultUrl: String, val defaultModel: String, val local: Boolean) {
    Ollama("Ollama (your PC)", "http://192.168.1.2:11434/v1", "qwen3:8b", local = true),
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
    /** Devices kept away from the assistant, on top of the ones hidden on the dashboard. */
    val hiddenDevices: Set<String> = emptySet(),
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
    /** How the assistant looks and sounds. */
    val face: FaceStyle = FaceStyle.Orb,
    val voice: VoiceStyle = VoiceStyle.Natural,
    /** The scanner in KITT's red instead of the theme's accent. */
    val classicRed: Boolean = false,
    /** Set per question when a guest is asking: no secrets, Flows, Goodnight or settings. */
    val guest: Boolean = false,
) {
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

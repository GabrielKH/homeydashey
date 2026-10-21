package app.homeydash.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.math.ln

/**
 * One topic of the assistant's know-how: the rules and tools it needs for music, timers, the
 * vacuum and so on, and what brings it into a question. Only the topics a question touches are
 * put in its note, so the fixed part of the prompt stays short and the same (the local AI keeps
 * it worked through), and the rules sit right next to the question, where a small model reads
 * most closely.
 *
 * A topic comes into a question by its [words] (a word ending in * also matches longer words:
 * "timer*" is timern and timrar), by a device of one of its Homey [classes] being named, by
 * what is going on (a recipe open, a timer running), or by the AI asking for it with the guide
 * tool. [related] topics come along with it. [always] topics are in the fixed part instead.
 */
@Serializable
data class Skill(
    val id: String,
    val title: String,
    /** A few words for the list of topics the AI can ask for: what the topic covers. */
    val about: String = "",
    val words: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val always: Boolean = false,
    /** Made by the owner, not shipped with the app. */
    val own: Boolean = false,
    /** Made from what is in Homey right now ([HomeyTopics]), so it changes as the house does. */
    val fromHomey: Boolean = false,
    /** Ids of the Homey devices it is about, besides those of its [classes], for the know-how graph. */
    val devices: List<String> = emptyList(),
)

/**
 * What the owner and the assistant added to the know-how on this tablet: new topics, the
 * owner's rules on a built-in topic ([extra], by topic id), words a topic was learned to come
 * with ([learned], from questions where the AI had to ask for it with the guide tool) and how
 * often each topic was used.
 */
@Serializable
data class KnowHowData(
    val version: Int = 1,
    val own: List<Skill> = emptyList(),
    val extra: Map<String, List<String>> = emptyMap(),
    val learned: Map<String, List<String>> = emptyMap(),
    val used: Map<String, Int> = emptyMap(),
    val lastUsed: Map<String, Long> = emptyMap(),
    /** When each topic made from Homey was first seen, so a new kind of device in the house is noticed once. */
    val seen: Map<String, Long> = emptyMap(),
)

/** A topic found for a question, with how strongly and why ("words: spela", "device: Roborock"). */
data class Found(val skill: Skill, val score: Double, val why: String)

object KnowHow {
    /** At most this many topics come with one question, besides the ones they bring along. */
    const val MAX_TOPICS = 3
    /** Words learned per topic; the oldest go first. */
    const val MAX_LEARNED = 30
    const val GUIDE = "guide"

    /**
     * The topics that ship with the app, written as plain lines so they read the same in
     * Settings → What the assistant sees. The rules are the ones that used to be in the fixed
     * prompt for every question.
     */
    fun builtIn(batteryReminders: Boolean = true): List<Skill> = listOf(
        Skill(
            id = "music", title = "Music and speakers", about = "songs, radio, playlists and volume on the speakers",
            words = listOf(
                "spel*", "play*", "musik*", "music", "låt", "låten", "låtar*", "song*", "radio*", "kanal*", "station*", "p1", "p2", "p3", "p4",
                "spotify", "sonos", "album*", "artist*", "spellist*", "playlist*", "favorit*", "favourite*", "favorite*", "volym*", "volume",
                "högre", "lägre", "louder", "quieter", "pausa", "paus", "pause", "nästa", "next", "skip*", "hoppa", "lyssna*", "listen*",
                "högtalar*", "speaker*", "podd*", "podcast*", "hits", "topplista*", "chart*", "avsnitt*", "episode*", "genre*", "top",
            ),
            classes = listOf("speaker", "amplifier"),
            tools = listOf("play_music", "save_favourite", "device_actions", "run_device_action"),
            rules = listOf(
                "For music, radio stations (\"P1\", \"P3\"), Sonos favourites and playlists, call play_music with the name: it searches the speaker's favourites, playlists and radio, and also plays any song, album or artist asked for (\"spela Dancing Queen\").",
                "For music, keep what they asked for in play_music's query and give its kind: a mood, genre, chart or \"hits\" is a playlist.",
                "Speech to text sometimes translates an English title into Swedish (\"spela dåligt med Michael Jackson\" is Bad by Michael Jackson): search the real title.",
                "When they let you choose the music (\"spela något\", \"play something\"), call play_music with an empty query.",
                "Charts and top lists (\"topplistan\", \"top 50 Sverige\", \"global top 50\", \"today's top hits\") are playlists: kind playlist, the chart's name as the query.",
                "A genre (\"jazz\", \"lo-fi\", \"svensk hiphop\") or a mood is a playlist; an album by name is kind album, with the artist if said.",
                "A podcast (\"spela podden Alex och Sigge\", \"senaste avsnittet av …\") is kind podcast: it plays the newest episode; a named episode is kind episode.",
                "\"My playlist\" / \"min spellista\" is one of their own Spotify playlists: call play_music with kind playlist and keep \"my\" in the query.",
                "To add music to the Sonos favourites (\"lägg till den i mina favoriter\", \"save it\"), call save_favourite with its name, e.g. the playlist play_music just played; never run_device_action for that.",
                "Never make up a stream URL.",
            ),
        ),
        Skill(
            id = "tv", title = "TV and remotes", about = "TV channels, apps and remote buttons on a TV or Apple TV",
            words = listOf(
                "tv", "tvn", "teve", "apple", "netflix", "youtube", "svt*", "kanal*", "channel*", "fjärr*", "remote*", "knapp*", "button*",
                "app", "appen", "film*", "movie*", "serie*", "avsnitt*", "episode*", "pausa", "pause", "upp", "ner", "vänster", "höger", "up", "down", "left", "right",
            ),
            classes = listOf("tv", "settopbox"),
            tools = listOf("device_actions", "run_device_action", "press_remote", "open_device"),
            rules = listOf(
                "For TV channels, opening an app and remote buttons, call device_actions and then run_device_action. Never make up a stream URL.",
                "For a TV or media box (Apple TV and the like) use press_remote to press its buttons (\"down x3, ok\"), run_device_action for channels and apps, and open_device to show its remote on the tablet.",
            ),
        ),
        Skill(
            id = "timers", title = "Timers", about = "kitchen timers: start, stop, add time, rename",
            words = listOf(
                "timer*", "timrar*", "äggklock*", "minut*", "minute*", "sekund*", "second*", "klar om", "ringer", "ringde", "ring*", "tyst",
                "stopp", "stop", "snooze", "påminn*", "remind*", "nedräkning*", "countdown*", "mute*", "tysta*", "räkna ner", "räknar ner", "sluta räkna",
            ),
            tools = listOf("timer"),
            rules = listOf(
                "Kitchen timers go through the timer tool: start, list, cancel one or all, rename, add or take away minutes, pause and resume, naming a timer the way the user does (\"pastan\" is Pasta).",
                "For a named timer without a length (\"sätt en pastatimer\"), start it without minutes so the usual length is used; when a timer rings, \"stopp\" cancels it and \"fem minuter till\" adds 5 minutes.",
                "Timers say out loud how long is left; when the user wants that quiet (\"tyst\", \"sluta räkna ner\", \"mute the timer\"), call timer with action mute, with the timer's label for just one; \"unmute\" brings it back.",
            ),
        ),
        Skill(
            id = "recipes", title = "Recipes and cooking", about = "finding, opening and following recipes, step by step",
            words = listOf(
                "recept*", "recipe*", "laga*", "lagar", "cook*", "baka*", "bake*", "middag*", "lunch*", "frukost*", "dinner*", "breakfast*",
                "ingrediens*", "ingredient*", "steg*", "step*", "gram", "deciliter", "dl", "matlagning*",
            ),
            tools = listOf("find_recipes", "open_recipe", "read_recipe", "recipe_step", "close_recipe", "recipe_timers"),
            rules = listOf(
                "When a recipe is open and the user asks what to do next, which step they are on, or to go back, use recipe_step so the screen follows, and tell them the step in plain words.",
                "Answer questions about amounts and times from that recipe.",
                "If the step has a cooking time, offer to start a timer for it. When the user wants timers for the whole recipe (\"timers for everything\", \"sätt alla timers\"), call recipe_timers once instead of going through the steps.",
            ),
            related = listOf("timers"),
        ),
        Skill(
            id = "fridge", title = "Fridge and pantry", about = "what is in the fridge, freezer and pantry, and what goes off soon",
            words = listOf(
                "kyl*", "frys*", "skafferi*", "fridge*", "freezer*", "pantry", "köpt*", "köpte", "bought", "åt", "ate", "äta", "eat*", "slut*",
                "utgång*", "bäst före", "expir*", "öppnat", "opened", "mat", "food", "mjölk*", "milk", "ägg", "eggs", "ost*", "cheese",
                "handla*", "inköp*", "groceries", "shopping",
            ),
            tools = listOf("fridge_contents", "add_to_fridge", "update_fridge_item", "remove_from_fridge"),
            rules = listOf(
                "Answer what's in the fridge, freezer or pantry and what goes off soon from fridge_contents.",
                "When the user says they bought, ate, finished, opened or froze something, update the fridge with your tools.",
                "When asked what to cook, suggest using what goes off first, and look in find_recipes if you have it.",
            ),
        ),
        Skill(
            id = "calendar", title = "Calendar", about = "plans, events and appointments on any day",
            words = listOf(
                "kalender*", "calendar*", "händer", "happening", "plan*", "möte*", "meeting*", "event*", "evenemang*", "bokat", "booked",
                "schema*", "schedule*", "imorgon", "tomorrow", "idag", "today", "ikväll", "tonight", "helg*", "weekend*", "vecka*", "week*",
                "måndag*", "tisdag*", "onsdag*", "torsdag*", "fredag*", "lördag*", "söndag*", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
                "födelsedag*", "birthday*", "kalas*", "party", "tid", "appointment*", "upptagen", "busy", "ledig*",
                "boka*", "lägg in", "skriv in", "tandläkar*", "dentist", "läkar*", "doctor", "träning*", "padel",
            ),
            tools = listOf("calendar", "add_event"),
            rules = listOf(
                "To put something in a calendar (\"lägg in tandläkaren på måndag klockan två i min kalender\", \"boka padel i familjekalendern\"), call add_event straight away: the title, date as the user said it (\"måndag\", \"imorgon\"), and time, minutes and in_calendar only when said (\"min/my\" is in_calendar mine, \"familjen/family\" is family, a name is that person's). Never ask \"is that correct?\" yourself first: add_event asks the one yes. Only when no title was said, ask what it is called.",
                "\"Vad har jag i min kalender\" or \"what's in Anna's calendar\" reads that one calendar: call calendar with calendar mine or the person's name.",
                "Questions about plans, events, appointments or what is happening on a day or week (\"what's happening next week\", \"vad händer\", \"kalendern\") are about the family calendar: call calendar with start_date and days worked out from today in the note (next week is next Monday and 7 days), then tell what it found.",
                "If you have no calendar tool, say the calendar is switched off under the assistant's Privacy and security settings. Never answer a calendar question with the weather.",
            ),
        ),
        Skill(
            id = "vacuum", title = "Robot vacuum", about = "starting, stopping and sending the robot vacuum to rooms",
            words = listOf(
                "dammsug*", "vacuum*", "robot*", "roborock", "städ*", "clean*", "docka*", "dock*", "laddstation*", "töm*", "empty", "moppa*", "mop*",
                "skicka", "send", "kom hit", "come",
            ),
            classes = listOf("vacuumcleaner"),
            tools = listOf("vacuum"),
            rules = listOf(
                "For robot vacuums use the vacuum tool: \"clean the kitchen\" is clean_rooms, \"go to the kitchen\" or \"come to the kitchen so I can empty it\" is go_to; use the robot's own room names (the vacuum tool lists them), translating Swedish room names if needed.",
            ),
        ),
        Skill(
            id = "people", title = "People, phones and alarms", about = "who is home or asleep, wake-up alarms and finding phones",
            words = listOf(
                "hemma", "home", "borta", "away", "vem", "who", "väck*", "wake*", "alarm*", "väckarklock*", "sov*", "sleep*", "asleep", "awake", "vaken",
                "uppe", "går nu", "åker nu", "drar nu", "sticker", "leaving", "kommer hem", "back", "tillbaka", "godnatt", "god natt", "goodnight",
                "good night", "morgon", "morning", "telefon*", "mobil*", "phone*", "iphone*", "ipad*", "hitta", "find", "ringa", "ring",
            ),
            tools = listOf("who_is_home", "set_presence", "alarms", "find_phone"),
            rules = listOf(
                "Who is home, away or asleep comes from who_is_home; when someone says they leave, come home, go to bed or wake up, tell Homey with set_presence.",
                "Wake-up alarms go through alarms; to find a phone, ring it with find_phone.",
            ),
        ),
        Skill(
            id = "routines", title = "Routines and instructions", about = "making and changing routines, standing instructions and topic rules",
            words = listOf(
                "rutin*", "routine*", "när jag säger", "when i say", "kom ihåg", "remember", "glöm*", "forget*", "alltid", "always", "aldrig", "never",
                "instruktion*", "instruction*", "regel", "regler*", "rule*", "lär dig", "learn*",
            ),
            tools = listOf("list_routines", "save_routine", "delete_routine", "remember_instruction", "forget_instruction", "run_routine", "teach", "forget_rule"),
            rules = listOf(
                "The owner has routines: when the note says a routine was started, the app has already done its steps; follow the routine's instructions, using your tools for anything they ask that isn't done yet, and answer from what it found.",
                "When the user asks you to make or change a routine (\"when I say X, do Y\") or to always do something, save it with your tools.",
                "An instruction that is only about one topic (music, timers, the vacuum…) goes to teach with that topic, so it is only read when the topic comes up; one for every answer goes to remember_instruction.",
            ),
        ),
        Skill(
            id = "memory", title = "Memory", about = "remembering and forgetting facts about people and the home",
            words = listOf("kom ihåg", "remember", "glöm*", "forget*", "minns", "memory", "minne*"),
            tools = listOf("remember", "forget_memory"),
            rules = listOf(
                "When they ask you to remember a fact (\"kom ihåg att…\"), call remember; standing instructions on how to answer go to remember_instruction, and rules for one topic to teach.",
            ),
        ),
        Skill(
            id = "logic", title = "Homey Logic", about = "Homey Logic variables and their values",
            words = listOf("variabel*", "variable*", "logic", "logik*"),
            tools = listOf("variables"),
            rules = listOf("Homey Logic variables are read with variables."),
        ),
        Skill(
            id = "battery", title = "Tablet battery", about = "the tablet's own battery and charging reminders",
            words = listOf("batteri*", "battery", "batteries", "ladd*", "charg*", "laddning*", "ström*", "power"),
            rules = listOf(BatteryNag.rule(batteryReminders)),
        ),
    )

    /**
     * Every topic on this tablet: the built-in ones and those made from what is in Homey
     * ([HomeyTopics], from [view]) with the owner's rules and the learned words added, then the
     * owner's own. An own topic with the id of one of the others adds to it instead.
     */
    fun graph(data: KnowHowData, batteryReminders: Boolean = true, view: HomeView? = null): List<Skill> {
        val own = data.own.associateBy { it.id }
        val shipped = builtIn(batteryReminders)
        val fromHomey = view?.let { v -> HomeyTopics.from(v, shipped.flatMap { it.classes }.toSet()) }.orEmpty()
        val built = (shipped + fromHomey).map { s ->
            val mine = own[s.id]
            s.copy(
                words = (s.words + mine?.words.orEmpty().map(::loose) + data.learned[s.id].orEmpty()).distinct(),
                rules = s.rules + mine?.rules.orEmpty() + data.extra[s.id].orEmpty(),
                tools = (s.tools + mine?.tools.orEmpty()).distinct(),
            )
        }
        val ids = built.map { it.id }.toSet()
        val added = data.own.filter { it.id !in ids }.map { s ->
            s.copy(
                own = true,
                words = (s.words.map(::loose) + titleWords(s.title) + data.learned[s.id].orEmpty()).distinct(),
                rules = s.rules + data.extra[s.id].orEmpty(),
            )
        }
        return built + added
    }

    /** A word the owner typed also matches its longer forms: "blommor" is blommorna too. */
    private fun loose(w: String): String = if (w.length >= 4 && ' ' !in w && !w.endsWith('*')) "$w*" else w

    /** An own topic's title says what it is about: "Plant watering" comes with "plant*" and "watering*". */
    private fun titleWords(title: String): List<String> =
        Routines.normalize(title).split(' ').filter { it.length >= 4 }.map { "$it*" }

    /**
     * The topics [question] needs, best first: by its words (rarer words count more), by a
     * device it names, and by what is going on ([active], like an open recipe), with the
     * topics they bring along. Topics in [already] are in the conversation and left out.
     */
    fun find(
        skills: List<Skill>,
        question: String,
        view: HomeView? = null,
        active: Set<String> = emptySet(),
        already: Set<String> = emptySet(),
        limit: Int = MAX_TOPICS,
    ): List<Found> {
        val text = " " + Routines.normalize(question) + " "
        val tokens = text.trim().split(' ').filter { it.isNotEmpty() }
        val open = skills.filter { !it.always }
        // How many topics share a word: "pausa" is music and TV, so it counts for less than "timer".
        val spread = HashMap<String, Int>()
        open.forEach { s -> s.words.map(::key).distinct().forEach { spread[it] = (spread[it] ?: 0) + 1 } }
        val score = LinkedHashMap<String, Double>()
        val why = HashMap<String, MutableList<String>>()
        fun add(id: String, points: Double, reason: String) {
            score[id] = (score[id] ?: 0.0) + points
            why.getOrPut(id) { mutableListOf() } += reason
        }
        for (s in open) {
            for (w in s.words) {
                val heard = heard(w, text, tokens) ?: continue
                add(s.id, ln(1.0 + open.size.toDouble() / (spread[key(w)] ?: 1)), heard)
            }
        }
        view?.let { v ->
            for (d in named(v, text, tokens)) {
                open.filter { d.deviceClass in it.classes }.forEach { add(it.id, 2.0, "device: ${d.name}") }
            }
        }
        active.forEach { id -> if (open.any { it.id == id }) add(id, 3.0, "on now") }
        val byId = open.associateBy { it.id }
        val best = score.entries.sortedByDescending { it.value }.filter { it.key !in already }.take(limit)
        val out = LinkedHashMap<String, Found>()
        best.forEach { (id, points) -> out[id] = Found(byId.getValue(id), points, why[id].orEmpty().distinct().joinToString(", ")) }
        // Brought along: a recipe's step times need the timer tool.
        best.flatMap { byId.getValue(it.key).related }.filter { it !in out && it !in already }.forEach { id ->
            byId[id]?.let { out[id] = Found(it, 0.0, "with ${out.values.first { f -> id in f.skill.related }.skill.title}") }
        }
        return out.values.toList()
    }

    private fun key(word: String) = word.lowercase().trimEnd('*')

    /** The word or phrase [w] as heard in [text], or null. */
    private fun heard(w: String, text: String, tokens: List<String>): String? {
        val k = Routines.normalize(w.trimEnd('*'))
        if (k.isEmpty()) return null
        return when {
            ' ' in k -> k.takeIf { text.contains(" $k ") || (w.endsWith('*') && text.contains(" $k")) }
            w.endsWith('*') -> tokens.firstOrNull { it.startsWith(k) }
            else -> tokens.firstOrNull { it == k }
        }
    }

    /**
     * Devices [text] names, for the topics their kind brings ("Roborock" brings the vacuum).
     * A device named like a room ("Vardagsrum" for the Apple TV) only counts when named with
     * more than the room, so "tänd i vardagsrummet" doesn't bring the TV.
     */
    internal fun named(view: HomeView, text: String, tokens: List<String>): List<AgentDevice> {
        val rooms = view.rooms.values.map { Routines.normalize(it) }.toSet()
        return view.devices.filter { d ->
            (listOf(d.name) + d.nicknames).any { n ->
                val name = Routines.normalize(n)
                name.isNotEmpty() && name !in rooms && name.split(' ').all { part ->
                    part.length >= 3 && tokens.any { it == part || (part.length >= 4 && it.startsWith(part)) }
                }
            }
        }
    }

    /**
     * The line in the fixed part that says which topics there are, so the AI can ask for one
     * the note left out. It only changes when the owner adds a topic.
     */
    fun index(skills: List<Skill>): String {
        val open = skills.filter { !it.always }
        if (open.isEmpty()) return ""
        return "More know-how comes in the note before a question when it needs it: the rules and tools for that topic. " +
            "If a question needs a topic the note and the conversation don't cover, first call $GUIDE with its name, then use what it gives. Topics: " +
            open.joinToString("; ") { s -> s.id + (s.about.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") } + ".\n"
    }

    /** The topics' rules for a question's note. */
    fun note(found: List<Skill>): String = if (found.isEmpty()) "" else
        "\nKnow-how for this question:\n" + found.joinToString("\n") { rulesOf(it) }

    fun rulesOf(s: Skill): String = "[${s.title}] " + s.rules.joinToString(" ").ifBlank { "Use its tools." }

    /**
     * The topic [name] says, by id or title, loosely ("Music", "musik", "timer"). A name of
     * several words that isn't a title ("plant watering") is a new topic, not one whose words it
     * happens to share.
     */
    fun topic(skills: List<Skill>, name: String): Skill? {
        val n = Routines.normalize(name)
        if (n.isEmpty()) return null
        return skills.firstOrNull { it.id == n || Routines.normalize(it.title) == n }
            ?: skills.firstOrNull { s -> Routines.normalize(s.title).split(' ').any { it.length >= 4 && (n.startsWith(it) || it.startsWith(n)) } }
            ?: skills.takeIf { ' ' !in n }?.firstOrNull { s -> s.words.any { w -> heard(w, " $n ", listOf(n)) != null } }
    }

    /**
     * Words of [question] to remember for [topic], after the AI had to ask for it: the words no
     * topic knew yet, so next time it comes with the question by itself. Short and common words,
     * and names of the home's devices and rooms, teach nothing.
     */
    fun newWords(skills: List<Skill>, question: String, view: HomeView?): List<String> {
        val known = skills.flatMap { it.words }
        val text = " " + Routines.normalize(question) + " "
        val tokens = text.trim().split(' ')
        val places = view?.let { v -> (v.rooms.values + v.devices.map { it.name }).flatMap { Routines.normalize(it).split(' ') }.toSet() }.orEmpty()
        return tokens.filter { t ->
            t.length >= 4 && t !in COMMON && t !in places && t.none { it.isDigit() } && known.none { w -> heard(w, " $t ", listOf(t)) != null }
        }.distinct().take(3)
    }

    /** Words too common to bring a topic. */
    private val COMMON = setOf(
        "och", "att", "det", "som", "inte", "lite", "kan", "ska", "vill", "jag", "du", "hur", "vad", "när", "var", "vilken", "vilka", "några", "något",
        "mycket", "också", "bara", "eller", "efter", "innan", "till", "från", "med", "utan", "över", "under", "snälla", "tack", "okej", "hej",
        "the", "and", "what", "when", "where", "which", "how", "please", "thanks", "with", "from", "about", "some", "this", "that", "there", "have",
        "does", "could", "would", "should", "nova", "tars", "make", "gör", "göra", "säg", "tell", "just", "then", "sen", "now", "nu",
        "sätt", "sätta", "slå", "tänd", "tända", "släck", "släcka", "starta", "stäng", "stänga", "turn", "switch", "start", "kolla", "check",
        "kan du", "skulle", "kunna", "behöver", "need", "want",
    )
}

/** The know-how the owner and the assistant added, on disk (knowhow.json), like [MemoryBook]. */
class KnowHowBook(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Mutex()
    private val file = File(dir, "knowhow.json")

    private val _data = MutableStateFlow(KnowHowData())
    val data: StateFlow<KnowHowData> = _data.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            _data.value = if (!file.exists()) KnowHowData() else
                runCatching { json.decodeFromString<KnowHowData>(file.readText()) }.getOrElse {
                    file.copyTo(File(dir, "knowhow.broken-${System.currentTimeMillis()}.json"), overwrite = true)
                    KnowHowData()
                }
        }
    }

    /** Saves on its own, so a question being put together needn't wait for the disk. */
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** That [ids] came with a question; counted in the background. */
    fun used(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        saver.launch {
            runCatching {
                change { d -> d.copy(used = d.used + ids.associateWith { (d.used[it] ?: 0) + 1 }, lastUsed = d.lastUsed + ids.associateWith { now }) }
            }
        }
    }

    /**
     * Notes the topics made from Homey that are there now; returns the ones never seen before,
     * or null the first time (everything is new then, so nothing counts as new). Saved only
     * when something changed.
     */
    suspend fun notice(ids: Collection<String>): Set<String>? {
        val d = _data.value
        val fresh = ids.filter { it !in d.seen }.toSet()
        if (fresh.isEmpty()) return emptySet()
        val first = d.seen.isEmpty()
        val now = clock()
        change { it.copy(seen = it.seen + fresh.associateWith { now }) }
        return if (first) null else fresh
    }

    /** Remembers [words] for topic [id], so they bring it next time. */
    suspend fun learn(id: String, words: List<String>) {
        if (words.isEmpty()) return
        change { d ->
            val list = (d.learned[id].orEmpty() + words).distinct().takeLast(KnowHow.MAX_LEARNED)
            d.copy(learned = d.learned + (id to list))
        }
    }

    suspend fun unlearn(id: String, word: String) = change { d ->
        d.copy(learned = d.learned + (id to d.learned[id].orEmpty().filterNot { it == word }))
    }

    /** Adds the owner's [rule] to topic [id]. */
    suspend fun addRule(id: String, rule: String) {
        val text = rule.trim().takeIf { it.isNotEmpty() } ?: return
        change { d -> d.copy(extra = d.extra + (id to (d.extra[id].orEmpty() + text).distinct())) }
    }

    /** Removes the owner's rules on [id] (any topic when null) that contain every word of [what]; how many went. */
    suspend fun removeRules(id: String?, what: String): Int {
        val words = Routines.normalize(what).split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return 0
        var n = 0
        change { d ->
            d.copy(extra = d.extra.mapValues { (topic, rules) ->
                if (id != null && topic != id) rules else rules.filterNot { r ->
                    val t = Routines.normalize(r)
                    words.all { it in t }.also { if (it) n++ }
                }
            })
        }
        return n
    }

    suspend fun removeRule(id: String, rule: String) = change { d -> d.copy(extra = d.extra + (id to d.extra[id].orEmpty().filterNot { it == rule })) }

    /** Adds or replaces the owner's own topic. */
    suspend fun saveTopic(skill: Skill) = change { d ->
        val s = skill.copy(own = true)
        val i = d.own.indexOfFirst { it.id == s.id }
        d.copy(own = if (i < 0) d.own + s else d.own.toMutableList().also { it[i] = s })
    }

    suspend fun deleteTopic(id: String) = change { d ->
        d.copy(own = d.own.filterNot { it.id == id }, extra = d.extra - id, learned = d.learned - id)
    }

    private suspend fun change(edit: (KnowHowData) -> KnowHowData) = lock.withLock {
        val next = edit(_data.value)
        _data.value = next
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val temp = File(dir, "knowhow.json.tmp")
            temp.writeText(json.encodeToString(KnowHowData.serializer(), next))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    companion object {
        /** An id for a new topic from its title: "Plant watering" is plant_watering. */
        fun idFor(title: String): String = Routines.normalize(title).replace(' ', '_').ifEmpty { "topic" }
    }
}

/** teach and forget_rule: the owner's rules on a topic, added and removed by voice. The [Agent] answers guide itself. */
class KnowHowTools(private val book: KnowHowBook, private val batteryReminders: () -> Boolean = { true }) {
    val names = setOf("teach", "forget_rule")

    fun skills(): List<Skill> = KnowHow.graph(book.data.value, batteryReminders())

    fun specs(): List<ToolSpec> = listOf(
        tool("teach", "Remember a rule for one topic, read whenever that topic comes up, e.g. topic music: in the morning play P1 in the kitchen.") {
            string("topic", "The topic's name; a new name makes a new topic")
            string("rule", "The rule, in the user's words")
            string("words", "For a new topic: words that bring it up, comma-separated")
            required("topic", "rule")
        },
        tool("forget_rule", "Forget a rule the user gave for a topic.") {
            string("rule", "Words from the rule to forget")
            string("topic", "The topic's name, if said")
            required("rule")
        },
    )

    suspend fun run(call: ToolCall): ToolResult? {
        fun str(k: String) = (call.arguments[k] as? JsonPrimitive)?.content.orEmpty().trim()
        val skills = skills()
        return when (call.name) {
            "teach" -> {
                val rule = str("rule").ifEmpty { return ToolResult("Say what the rule is.") }
                val name = str("topic")
                val found = KnowHow.topic(skills, name)
                if (found != null) {
                    book.addRule(found.id, rule)
                    ToolResult("Remembered for ${found.title}: $rule")
                } else {
                    val title = name.ifBlank { return ToolResult("Say which topic the rule is for.") }.replaceFirstChar { it.uppercase() }
                    val words = str("words").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    book.saveTopic(Skill(id = KnowHowBook.idFor(title), title = title, about = rule.take(60), words = words, rules = listOf(rule), own = true))
                    ToolResult("Made a new topic, $title, with that rule.")
                }
            }
            "forget_rule" -> {
                val id = str("topic").takeIf { it.isNotEmpty() }?.let { KnowHow.topic(skills, it)?.id }
                val n = book.removeRules(id, str("rule"))
                ToolResult(if (n == 0) "No rule like that." else "Forgot $n rule${if (n == 1) "" else "s"}.")
            }
            else -> null
        }
    }
}

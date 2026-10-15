package app.homeydash.agent

/**
 * Plain on, off and dim commands, understood without the AI: "tänd taklampan i
 * vardagsrummet", "släck i köket", "sätt köksbordet på 30 %", "turn off the hall light".
 * They are run straight away, which is faster and can't go wrong the way a small model
 * can (promising to do something and not doing it). Anything else goes to the AI.
 */
object QuickCommand {
    /** What to do: [device] or every light in [roomId], set to [on] or to [dim] (0..1). */
    data class Action(
        val device: AgentDevice?,
        val roomId: String?,
        val on: Boolean?,
        val dim: Double?,
        val swedish: Boolean,
        /** One of the owner's card groups ("Taklampor"), instead of a room. */
        val group: String? = null,
    )

    private val ON = Regex("""^(?:kan du |snälla |please |can you )?(tänd|tända|sätt på|sätta på|slå på|starta|start|turn on|switch on|put on)\s+(.+)$""")
    private val ON_AFTER = Regex("""^(?:sätt|sätta|slå|turn|switch)\s+(.+?)\s+(på|on)$""")
    private val OFF = Regex("""^(?:kan du |snälla |please |can you )?(släck|släcka|stäng av|stänga av|slå av|turn off|switch off|shut off)\s+(.+)$""")
    private val OFF_AFTER = Regex("""^(?:stäng|stänga|slå|turn|switch)\s+(.+?)\s+(av|off)$""")
    private val DIM = Regex("""^(?:dimma|dimra|sätt|sätta|ställ|set|dim)\s+(.+?)\s+(?:till|på|to|at)\s+(\d{1,3})\s*(?:%|procent|percent)?$""")
    private val ON_BARE = Regex("""^(?:kan du |snälla )?(?:sätt|sätta)\s+(.+)$""")
    private val MANY =Regex("""\b(alla|allt|lamporna|lampor|ljuset|lights|everything)\b""")
    private val ROOM_WORDS = Regex("""\b(alla lampor(?:na)?|lamporna|lampor|ljuset|ljus|allt|alla|all the lights|all lights|the lights|lights|everything|in|i)\b""")

    fun parse(text: String, view: HomeView): Action? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val swedish = Confirmation.looksSwedish(text)
        DIM.matchEntire(t)?.let { m ->
            val pct = m.groupValues[2].toInt().coerceIn(0, 100)
            return target(m.groupValues[1], view, swedish, on = null, dim = pct / 100.0)
        }
        (ON.matchEntire(t)?.groupValues?.get(2) ?: ON_AFTER.matchEntire(t)?.groupValues?.get(1))?.let {
            return target(it, view, swedish, on = true, dim = null)
        }
        (OFF.matchEntire(t)?.groupValues?.get(2) ?: OFF_AFTER.matchEntire(t)?.groupValues?.get(1))?.let {
            return target(it, view, swedish, on = false, dim = null)
        }
        // "sätt taklampan i vardagsrummet": speech often drops the "på", so a bare "sätt" on a
        // lamp means on. Only when it names a device; anything else goes to the AI.
        ON_BARE.matchEntire(t)?.groupValues?.get(1)?.let { w ->
            return target(w, view, swedish, on = true, dim = null)?.takeIf { it.device != null }
        }
        return null
    }

    private fun target(words: String, view: HomeView, swedish: Boolean, on: Boolean?, dim: Double?): Action? {
        val w = words.trim()
        if (w.isEmpty()) return null
        val cap = if (dim != null) "dim" else "onoff"
        // "lamporna", "the lights", "allt" or a bare "i köket" mean the whole room, not one lamp.
        val wholeRoom = MANY.containsMatchIn(w) || w.startsWith("i ") || w.startsWith("in ")
        // "tänd taklamporna": a group of cards the owner made comes before a single lamp.
        view.group(w.replace(ROOM_WORDS, " ").trim())?.let { g ->
            val members = view.devices.filter { it.group == g && it.secret == null && it.capability(cap)?.setable == true }
            if (members.isNotEmpty()) return Action(null, null, on, dim, swedish, group = g)
        }
        if (!wholeRoom) {
            view.device(w)?.let { d ->
                val c = d.capability(cap)
                return if (d.secret == null && c != null && c.setable) Action(d, null, on, dim, swedish) else null
            }
        }
        // "släck i köket", "tänd lamporna i vardagsrummet": every light in the room, but only
        // when what's left is clearly just a room, so "taklampan i vardagsrummet" never
        // turns on the whole room.
        val roomPart = w.replace(ROOM_WORDS, " ").replace(Regex("\\s+"), " ").trim()
        if (roomPart.isEmpty() || roomPart == w) return null
        val roomId = view.room(roomPart) ?: return null
        if (NameMatch.score(roomPart, view.rooms[roomId].orEmpty()) < 0.95) return null
        val lights = view.devices.filter {
            view.inRoom(it, roomId) && it.deviceClass == "light" && it.secret == null && !it.hiddenOnDashboard && it.capability(cap)?.setable == true
        }
        return if (lights.isEmpty()) null else Action(null, roomId, on, dim, swedish)
    }

    private val MUSIC = Regex("""^(?:kan du |snälla |please |can you )?(?:sätt på|sätta på|slå på|spela|starta|play|put on|start)\s+(?:lite |some |)(?:musik|music|låtar|songs)(?:\s+(?:i|in|på|on)\s+(.+))?$""")

    /**
     * "Sätt på musik i köket", "spela musik", "play some music in the kitchen": the player to
     * play something of its own choosing on. Null when it isn't such a request, or when it's
     * unclear which player is meant.
     */
    fun music(text: String, view: HomeView): AgentDevice? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val m = MUSIC.matchEntire(t) ?: return null
        val players = view.devices.filter { it.deviceClass == "speaker" && it.secret == null && !it.unreachable }
        val where = m.groupValues[1].trim().removePrefix("the ").trim()
        if (where.isEmpty()) {
            return view.settings.speakerId?.let { id -> players.firstOrNull { it.id == id } }
                ?: players.filter { !it.hiddenOnDashboard }.singleOrNull()
        }
        val roomId = view.room(where)?.takeIf { NameMatch.score(where, view.rooms[it].orEmpty()) >= 0.95 }
        if (roomId != null) {
            val inRoom = players.filter { view.inRoom(it, roomId) }
            return inRoom.firstOrNull { !it.hiddenOnDashboard } ?: inRoom.firstOrNull()
        }
        return view.device(where)?.takeIf { it in players }
    }

    /** What to say once [played] is playing on [player]. */
    fun playing(played: String, player: AgentDevice, swedish: Boolean): String =
        if (swedish) "Spelar $played på ${player.name}." else "Playing $played on ${player.name}."

    /** The tool call that does [a]. */
    fun call(a: Action): ToolCall {
        val args = buildMap {
            if (a.device != null) put("device", a.device.alias) else if (a.group != null) put("room", a.group) else {
                put("room", a.roomId!!)
                put("device_class", "light")
            }
            put("capability", if (a.dim != null) "dim" else "onoff")
            put("value", a.dim?.toString() ?: a.on.toString())
        }
        val json = kotlinx.serialization.json.JsonObject(args.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
        return ToolCall("quick", if (a.device != null) "set_device" else "set_room", json)
    }

    /** What to say when it's done. */
    fun done(a: Action, view: HomeView): String {
        val what = a.device?.name ?: a.group ?: view.rooms[a.roomId].orEmpty().let { if (a.swedish) "lamporna i $it" else "the lights in $it" }
        val many = a.device == null
        return when {
            a.dim != null -> if (a.swedish) "${cap(what)} är på ${(a.dim * 100).toInt()} procent." else "${cap(what)} is at ${(a.dim * 100).toInt()} percent."
            a.on == true -> if (a.swedish) "${cap(what)} är ${if (many) "tända" else "tänd"}." else "${cap(what)} ${if (many) "are" else "is"} on."
            else -> if (a.swedish) "${cap(what)} är ${if (many) "släckta" else "släckt"}." else "${cap(what)} ${if (many) "are" else "is"} off."
        }
    }

    /** What to say when Homey couldn't reach the device, or the device didn't change. */
    fun notChanged(a: Action, view: HomeView): String {
        val what = a.device?.name ?: a.group ?: view.rooms[a.roomId].orEmpty()
        return if (a.swedish) "${cap(what)} svarar inte i Homey, så den ändrades inte. Den kan vara strömlös eller utom räckhåll."
        else "${cap(what)} isn't responding in Homey, so it didn't change. It may be switched off at the wall or out of range."
    }

    /** How the tools begin a message about a change that didn't happen. */
    const val NOT_CHANGED_PREFIX = "Homey accepted it, but"
    const val UNREACHABLE_PREFIX = "Homey can't reach"

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
}

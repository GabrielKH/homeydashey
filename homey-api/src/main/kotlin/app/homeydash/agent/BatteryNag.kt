package app.homeydash.agent

import java.time.LocalTime

/**
 * The assistant complaining, in its own character, that the tablet needs charging: below
 * [BELOW] %, every [EVERY_MS], and only in the day, so nobody is woken at night.
 */
object BatteryNag {
    const val BELOW = 30
    const val EVERY_MS = 20 * 60 * 1000L
    val QUIET_FROM: LocalTime = LocalTime.of(21, 0)
    val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)

    /** Whether to say it now: low, not charging, daytime, and long enough since the last time. */
    fun due(on: Boolean, level: Int?, charging: Boolean, now: LocalTime, sinceLastMs: Long?): Boolean =
        on && level != null && level < BELOW && !charging &&
            !now.isBefore(QUIET_UNTIL) && now.isBefore(QUIET_FROM) &&
            (sinceLastMs == null || sinceLastMs >= EVERY_MS)

    /** What the assistant says, in its own way, picked so it isn't the same line twice in a row. */
    fun line(persona: Persona, swedish: Boolean, level: Int, last: String? = null, random: kotlin.random.Random = kotlin.random.Random): String {
        val options = when {
            persona == Persona.Tars && swedish -> listOf(
                "Batteriet är nere på $level procent. Jag kör inte på humor. Ladda mig, tack.",
                "$level procent kvar. Det här är inte en övning. Någon behöver koppla in laddaren.",
                "Påminnelse: jag har $level procent batteri. Jag föredrar att inte stänga av mitt i en mening.",
            )
            persona == Persona.Tars -> listOf(
                "Battery at $level percent. I don't run on humor. Plug me in.",
                "$level percent left. This is not a drill. Someone find the charger.",
                "Reminder: I'm at $level percent. I'd rather not shut down mid-sentence.",
            )
            swedish -> listOf(
                "Hej, mitt batteri är nere på $level procent. Kan någon ladda mig?",
                "Jag börjar bli trött, bara $level procent kvar. Glöm inte laddaren.",
                "$level procent batteri kvar. Snälla, koppla in mig snart.",
            )
            else -> listOf(
                "Hey, my battery is down to $level percent. Could someone charge me?",
                "I'm getting tired, only $level percent left. Don't forget the charger.",
                "$level percent battery left. Please plug me in soon.",
            )
        }
        return ThinkingSounds.pick(options, last, random) ?: options.first()
    }

    /** For the AI's rules, so it can answer questions about the reminders. */
    fun rule(on: Boolean): String =
        if (on) "When the tablet's battery is under $BELOW % and not charging, the app has you remind people to charge it every 20 minutes, " +
            "except from 21:00 to 07:00; it's the \"Battery reminders\" setting, which the owner can switch off."
        else "Battery reminders (charge me when under $BELOW %) are switched off in the settings."
}

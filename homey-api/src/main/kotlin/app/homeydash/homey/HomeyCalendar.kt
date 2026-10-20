package app.homeydash.homey

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One event the IcalCalendar Homey app holds. Times are epoch millis; an all-day event starts at local midnight. */
data class HomeyCalendarEvent(
    val uid: String,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val calendar: String,
)

/**
 * Reads the events of the IcalCalendar Homey app (`no.runely.calendar`), which keeps the
 * calendars the user linked in it (Google's secret iCal address, iCloud, Outlook, school).
 * The app has no Web API of its own, so this runs its Flow cards: "Get calendars metadata"
 * for each calendar's event numbers, then "Get calendar event" for each one.
 */
class HomeyCalendar(
    private val run: suspend (cardId: String, args: JsonObject) -> JsonElement,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun events(limit: Int = MAX_EVENTS): List<HomeyCalendarEvent> = coroutineScope {
        val calendars = parseMetadata(run("get_calendars_metadata", JsonObject(emptyMap())))
        val wanted = calendars.flatMap { (name, indexes) -> indexes.map { name to it } }.take(limit)
        // A few at a time, so a long calendar doesn't keep Homey busy.
        val gate = Semaphore(4)
        wanted.map { (name, index) ->
            async {
                gate.withPermit {
                    runCatching {
                        val args = buildJsonObject {
                            put("calendar", buildJsonObject { put("id", name); put("name", name) })
                            put("index", index)
                        }
                        parseEvent(run("get_calendar_event", args), name, zone())
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.begin }
    }

    companion object {
        const val APP_ID = "no.runely.calendar"
        private const val MAX_EVENTS = 120

        /** What a run card gave back: its tokens, straight or wrapped. */
        private fun tokens(result: JsonElement): JsonObject? {
            val obj = result as? JsonObject ?: return null
            return (obj["returnTokens"] as? JsonObject) ?: (obj["tokens"] as? JsonObject) ?: obj
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        /** Each calendar's name and event numbers, from "Get calendars metadata". */
        fun parseMetadata(result: JsonElement): List<Pair<String, List<Int>>> {
            val json = tokens(result)?.text("json") ?: return emptyList()
            val list = runCatching { HomeyJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
            return list.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val name = obj.text("calendarName") ?: return@mapNotNull null
                val indexes = (obj["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                name to indexes
            }
        }

        /** One event from "Get calendar event"'s tokens, or null when its start can't be read. */
        fun parseEvent(result: JsonElement, calendar: String, zone: ZoneId): HomeyCalendarEvent? {
            val t = tokens(result) ?: return null
            val allDay = (t["event_fullday_event"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true) } ?: false
            val start = parseDateTime(t.text("event_start") ?: return null) ?: return null
            val minutes = (t["event_duration"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
            val end = t.text("event_end")?.let(::parseDateTime)?.takeIf { !it.isBefore(start) }
                ?: start.plusMinutes(minutes ?: if (allDay) 24 * 60 else 60)
            val begin = if (allDay) start.toLocalDate().atStartOfDay() else start
            return HomeyCalendarEvent(
                uid = t.text("event_uid") ?: "$calendar:${t.text("event_name")}:$begin",
                title = t.text("event_name") ?: "(No title)",
                begin = begin.atZone(zone).toInstant().toEpochMilli(),
                end = end.atZone(zone).toInstant().toEpochMilli(),
                allDay = allDay,
                location = t.text("event_location"),
                calendar = calendar,
            )
        }

        private val NUMERIC_DATE = Regex("""(\d{1,4})([./-])(\d{1,2})\2(\d{1,4})""")
        private val WORD_DATE = Regex("""(?i)(?:(\d{1,2})\.?\s+([a-zåäöé]{3,})\.?,?\s+(\d{4}))|(?:([a-zåäöé]{3,})\.?\s+(\d{1,2}),?\s+(\d{4}))""")
        private val TIME = Regex("""(?i)(\d{1,2})[:.](\d{2})(?:\s*([ap])\.?m\.?)?""")
        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "maj" to 5, "mai" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "des" to 12,
        )

        /**
         * A date and time in the format the user set in IcalCalendar, which isn't sent along:
         * `yy-MM-dd HH:mm` (Swedish default), `dd.MM.yy` (Norwegian), `MM/dd/yy` (English),
         * or a written month like `20 oktober 2026` / `October 20, 2026`.
         */
        fun parseDateTime(text: String): LocalDateTime? {
            var rest = text
            val date: LocalDate = NUMERIC_DATE.find(text)?.let { m ->
                rest = text.removeRange(m.range)
                val (a, sep, b, c) = m.destructured
                val first = a.toInt(); val second = b.toInt(); val third = c.toInt()
                runCatching {
                    when {
                        a.length == 4 || sep == "-" -> LocalDate.of(year(first), second, third)
                        sep == "." -> LocalDate.of(year(third), second, first)
                        // Slashes: day first only when the first number can't be a month.
                        first > 12 -> LocalDate.of(year(third), second, first)
                        else -> LocalDate.of(year(third), first, second)
                    }
                }.getOrNull()
            } ?: WORD_DATE.find(text)?.let { m ->
                rest = text.removeRange(m.range)
                val g = m.groupValues
                val (day, month, year) = if (g[1].isNotEmpty()) Triple(g[1], g[2], g[3]) else Triple(g[5], g[4], g[6])
                val number = MONTHS[month.lowercase().take(3)] ?: return null
                runCatching { LocalDate.of(year.toInt(), number, day.toInt()) }.getOrNull()
            } ?: return null
            val time = TIME.find(rest)?.let { m ->
                var hour = m.groupValues[1].toInt()
                val pm = m.groupValues[3].lowercase()
                if (pm == "p" && hour < 12) hour += 12
                if (pm == "a" && hour == 12) hour = 0
                runCatching { LocalTime.of(hour, m.groupValues[2].toInt()) }.getOrNull()
            } ?: LocalTime.MIDNIGHT
            return date.atTime(time)
        }

        private fun year(y: Int) = if (y < 100) 2000 + y else y
    }
}

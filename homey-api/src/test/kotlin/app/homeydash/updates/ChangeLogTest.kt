package app.homeydash.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeLogTest {
    private val text = """
        {"build":324,"version":"0.2.324","apkBytes":47500000,"changes":[
          {"sha":"c","date":"2026-10-20T11:18:06+00:00","subject":"Timer island: kitchen timers at the top"},
          {"sha":"m","date":"2026-10-20T11:10:00+00:00","subject":"Merge remote-tracking branch 'x'"},
          {"sha":"b","date":"2026-10-19T09:00:00+00:00","subject":"Fix build: escape quotes"},
          {"sha":"a","date":"2026-10-18T09:00:00+00:00","subject":"\"Släck lamporna\" changes only the lights"}
        ]}
    """.trimIndent()

    @Test
    fun readsBuildAndDropsNoise() {
        val info = ChangeLog.parse(text)!!
        assertEquals(324, info.build)
        assertEquals(47500000L, info.apkBytes)
        val changes = ChangeLog.changes(info)
        assertEquals(listOf("c", "a"), changes.map { it.sha })
        assertEquals(Change("c", "2026-10-20", "Timer island", "Kitchen timers at the top"), changes[0])
        assertEquals("\"Släck lamporna\" changes only the lights", changes[1].title)
        assertEquals("", changes[1].detail)
    }

    @Test
    fun sinceKeepsOnlyNewCommits() {
        val newer = ChangeLog.changes(ChangeLog.parse(text))
        val current = newer.drop(1)
        assertEquals(listOf("c"), ChangeLog.since(newer, current).map { it.sha })
    }

    @Test
    fun longPrefixIsNotATitle() {
        val subject = "Every command goes through the AI and nothing else happens here: the quick path"
        assertEquals(subject to "", ChangeLog.split(subject))
    }

    @Test
    fun badTextIsNull() {
        assertNull(ChangeLog.parse("not json"))
        assertNull(ChangeLog.parse(""))
    }
}

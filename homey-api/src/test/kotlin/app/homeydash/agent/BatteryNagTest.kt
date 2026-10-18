package app.homeydash.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class BatteryNagTest {
    private val noon = LocalTime.of(12, 0)

    @Test fun `a low tablet complains every 20 minutes in the day only`() {
        assertTrue(BatteryNag.due(true, 29, false, noon, null))
        assertFalse("not below 30 %", BatteryNag.due(true, 30, false, noon, null))
        assertFalse("charging", BatteryNag.due(true, 10, true, noon, null))
        assertFalse("switched off", BatteryNag.due(false, 10, false, noon, null))
        assertFalse("after 21:00", BatteryNag.due(true, 10, false, LocalTime.of(21, 0), null))
        assertFalse("before 07:00", BatteryNag.due(true, 10, false, LocalTime.of(6, 59), null))
        assertFalse("too soon", BatteryNag.due(true, 10, false, noon, 19 * 60 * 1000L))
        assertTrue(BatteryNag.due(true, 10, false, noon, 20 * 60 * 1000L))
    }

    @Test fun `each character says it its own way, not the same line twice`() {
        val first = BatteryNag.line(Persona.Tars, false, 22)
        assertTrue(first.contains("22 percent"))
        assertNotEquals(first, BatteryNag.line(Persona.Tars, false, 22, last = first))
        assertTrue(BatteryNag.line(Persona.Nova, true, 22).contains("22 procent"))
    }
}

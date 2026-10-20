package app.homeydash.modules

import app.homeydash.homey.Device
import app.homeydash.homey.HomeyApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModulesTest {
    private fun device(id: String, cls: String, app: String) = Device(id = id, name = id, deviceClass = cls, driverUri = "homey:app:$app:driver")

    private fun status(states: List<ModuleState>, id: String) = states.first { it.module.id == id }.status

    @Test fun `features are on with their built-in source by default`() {
        val states = AppModules.states(emptyMap(), apps = emptyMap(), devices = emptyList())
        assertEquals(ModuleStatus.On, status(states, "calendar"))
        assertEquals("tablet", states.first { it.module.id == "calendar" }.source.id)
        assertEquals(ModuleStatus.Planned, status(states, "energy"))
    }

    @Test fun `a source whose Homey app is missing needs setup`() {
        val choices = mapOf("calendar" to ModuleChoice(on = true, source = "homey"))
        val missing = AppModules.states(choices, apps = emptyMap(), devices = emptyList())
        assertEquals(ModuleStatus.NeedsApp, status(missing, "calendar"))
        val installed = AppModules.states(choices, apps = mapOf("no.runely.calendar" to HomeyApp("no.runely.calendar", state = "running")), devices = emptyList())
        assertEquals(ModuleStatus.On, status(installed, "calendar"))
        assertEquals(listOf(true), installed.first { it.module.id == "calendar" }.apps.map { it.second })
    }

    @Test fun `a turned off feature is off whatever its source`() {
        val states = AppModules.states(mapOf("fridge" to ModuleChoice(on = false)), apps = null, devices = emptyList())
        assertEquals(ModuleStatus.Off, status(states, "fridge"))
        assertFalse(AppModules.isOn(mapOf("fridge" to ModuleChoice(on = false)), "fridge"))
        assertTrue(AppModules.isOn(emptyMap(), "fridge"))
    }

    @Test fun `device modules follow Homey's devices, from any app of their class`() {
        val devices = listOf(device("v1", "vacuumcleaner", "com.xiaomi"), device("p1", "other", "com.apple.findmy"))
        val states = AppModules.states(emptyMap(), apps = null, devices = devices)
        assertEquals(ModuleStatus.On, status(states, "vacuum"))
        assertEquals(listOf("v1"), states.first { it.module.id == "vacuum" }.devices.map { it.id })
        assertEquals(ModuleStatus.On, status(states, "phones"))
        assertEquals(ModuleStatus.NeedsApp, status(states, "tv"))
    }

    @Test fun `an installed app without devices says so`() {
        val states = AppModules.states(emptyMap(), apps = mapOf("com.appletv.native" to HomeyApp("com.appletv.native")), devices = emptyList())
        assertEquals(ModuleStatus.NoDevices, status(states, "tv"))
    }

    @Test fun `without the apps list the installed apps come from the devices`() {
        val installed = AppModules.installed(null, listOf(device("x", "light", "com.ikea.tradfri")))
        assertEquals(setOf("com.ikea.tradfri"), installed)
        val stopped = AppModules.installed(mapOf("a" to HomeyApp("a", enabled = false), "b" to HomeyApp("b", state = "running")), emptyList())
        assertEquals(setOf("b"), stopped)
    }

    @Test fun `choices survive the settings store`() {
        val choices = mapOf("calendar" to ModuleChoice(true, "homey"), "fridge" to ModuleChoice(false, null))
        assertEquals(choices, AppModules.decode(AppModules.encode(choices)))
    }
}

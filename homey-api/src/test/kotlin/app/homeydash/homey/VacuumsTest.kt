package app.homeydash.homey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VacuumsTest {
    /** Shaped like the Roborock Cloud app's robot vacuum (be.functor.roborock). */
    private val roborock = HomeyJson.decodeFromString(
        Device.serializer(),
        """
        {
          "id": "rr", "name": "Robbie", "class": "vacuumcleaner", "driverUri": "homey:app:be.functor.roborock:vacuum",
          "capabilities": ["measure_battery", "battery_charging_state", "is_cleaning", "clean_time", "clean_area", "clean_full",
            "pause_clean", "dock", "active_map", "position_x", "clean_room_pick", "locate", "spot_clean", "cancel_and_dock",
            "alarm_problem", "alarm_stuck", "alarm_bin_full", "suction_power", "scrub_intensity", "mop_dry", "empty_dustbin",
            "button.refresh_rooms", "mop_attached"],
          "capabilitiesObj": {
            "measure_battery": {"id": "measure_battery", "type": "number", "value": 87, "units": "%"},
            "battery_charging_state": {"id": "battery_charging_state", "type": "enum", "value": "charging",
               "values": [{"id": "charging"}, {"id": "discharging"}]},
            "is_cleaning": {"id": "is_cleaning", "type": "boolean", "value": false},
            "clean_time": {"id": "clean_time", "type": "number", "value": 0.8, "units": "h"},
            "clean_area": {"id": "clean_area", "type": "number", "value": 42, "units": "m²"},
            "clean_full": {"id": "clean_full", "type": "boolean", "value": false, "setable": true, "title": "Clean all rooms"},
            "pause_clean": {"id": "pause_clean", "type": "boolean", "value": false, "setable": true},
            "dock": {"id": "dock", "type": "boolean", "value": true, "setable": true},
            "active_map": {"id": "active_map", "type": "enum", "value": "0", "setable": true, "values": [{"id": "0", "title": "Floor 1"}]},
            "position_x": {"id": "position_x", "type": "number", "value": 25500},
            "clean_room_pick": {"id": "clean_room_pick", "type": "enum", "setable": true,
               "values": [{"id": "16", "title": "Kitchen"}, {"id": "17", "title": "Hall"}]},
            "locate": {"id": "locate", "type": "boolean", "value": false, "setable": true, "title": "Locate"},
            "spot_clean": {"id": "spot_clean", "type": "boolean", "value": false, "setable": true, "title": "Spot clean"},
            "cancel_and_dock": {"id": "cancel_and_dock", "type": "boolean", "value": false, "setable": true},
            "alarm_problem": {"id": "alarm_problem", "type": "boolean", "value": false},
            "alarm_stuck": {"id": "alarm_stuck", "type": "boolean", "value": false},
            "alarm_bin_full": {"id": "alarm_bin_full", "type": "boolean", "value": true, "title": "Dustbin full"},
            "suction_power": {"id": "suction_power", "type": "enum", "value": "102", "setable": true,
               "values": [{"id": "101", "title": "Quiet"}, {"id": "102", "title": "Balanced"}]},
            "scrub_intensity": {"id": "scrub_intensity", "type": "enum", "value": "200", "setable": true,
               "values": [{"id": "200", "title": "Off"}, {"id": "201", "title": "Mild"}]},
            "mop_dry": {"id": "mop_dry", "type": "boolean", "value": false, "setable": true},
            "empty_dustbin": {"id": "empty_dustbin", "type": "boolean", "value": false, "setable": true, "title": "Empty dustbin"},
            "button.refresh_rooms": {"id": "button.refresh_rooms", "type": "boolean", "setable": true, "getable": false, "title": "Refresh rooms"},
            "mop_attached": {"id": "mop_attached", "type": "boolean", "value": true, "title": "Mop attached"}
          },
          "ui": {"components": [{"id": "toggle", "capabilities": ["mop_dry"]}, {"id": "button", "capabilities": ["locate"]}]},
          "images": [{"id": "map", "type": "camera", "title": "Map", "imageObj": {"id": "img1", "url": "/api/image/img1", "lastUpdated": 5}}]
        }
        """,
    )

    private val cards = FlowCards.parseActions(
        HomeyJson.parseToJsonElement(
            """
            {
              "homey:device:rr:clean_all": {"id": "homey:device:rr:clean_all", "title": "Clean all rooms"},
              "homey:device:rr:pause_clean": {"id": "homey:device:rr:pause_clean", "title": "Pause cleaning"},
              "homey:device:rr:resume_clean": {"id": "homey:device:rr:resume_clean", "title": "Resume cleaning"},
              "homey:device:rr:stop_clean": {"id": "homey:device:rr:stop_clean", "title": "Stop cleaning"},
              "homey:device:rr:dock": {"id": "homey:device:rr:dock", "title": "Return to docking station"},
              "homey:device:rr:clean_spot": {"id": "homey:device:rr:clean_spot", "title": "Clean the current spot"},
              "homey:device:rr:repeat_last_clean": {"id": "homey:device:rr:repeat_last_clean", "title": "Repeat the last cleaning task"},
              "homey:device:rr:clean_room": {"id": "homey:device:rr:clean_room", "title": "Clean one or more rooms",
                 "args": [{"name": "device", "type": "device"}, {"name": "rooms", "type": "text", "title": "rooms"}, {"name": "repeat", "type": "number", "min": 1}]},
              "homey:device:rr:clean_room_2": {"id": "homey:device:rr:clean_room_2",
                 "args": [{"name": "room1", "type": "autocomplete"}, {"name": "room2", "type": "autocomplete"}, {"name": "repeat", "type": "number"}]},
              "homey:device:rr:load_map": {"id": "homey:device:rr:load_map", "args": [{"name": "value", "type": "autocomplete"}]},
              "homey:device:rr:start_scene": {"id": "homey:device:rr:start_scene", "title": "Start routine", "args": [{"name": "scene", "type": "autocomplete"}]},
              "homey:device:rr:set_suction_power": {"id": "homey:device:rr:set_suction_power", "args": [{"name": "value", "type": "dropdown"}]},
              "homey:device:rr:set_child_lock": {"id": "homey:device:rr:set_child_lock", "title": "Set the child lock", "args": [{"name": "state", "type": "checkbox"}]},
              "homey:device:rr:raw_command": {"id": "homey:device:rr:raw_command", "args": [{"name": "method", "type": "text"}, {"name": "params", "type": "text"}]},
              "homey:device:rr:reset_consumable": {"id": "homey:device:rr:reset_consumable",
                 "args": [{"name": "consumable", "type": "dropdown", "values": [
                   {"id": "main_brush_work_time", "label": "Main brush"}, {"id": "side_brush_work_time", "label": "Side brush"},
                   {"id": "filter_work_time", "label": "Filter"}, {"id": "sensor_dirty_time", "label": "Sensors"}]}]}
            }
            """,
        ),
    ).let { FlowCards.byDevice(it).getValue("rr") }

    @Test fun `roborock is a vacuum card`() {
        assertEquals(CardKind.Vacuum, DashboardLayout.cardKind(roborock))
    }

    @Test fun `roborock state and main buttons`() {
        val c = Vacuums.controls(roborock, cards)
        assertEquals(c.state, Vacuums.state(roborock))
        assertEquals(VacuumState.Charging, c.state)
        assertEquals(87.0, c.battery)
        assertTrue(c.charging)
        assertEquals(VacuumCommand.Set("clean_full", JsonPrimitive(true)), c.start)
        assertEquals(VacuumCommand.Set("pause_clean", JsonPrimitive(true)), c.pause)
        assertEquals("resume_clean", (c.resume as VacuumCommand.Card).card.cardId)
        assertEquals("stop_clean", (c.stop as VacuumCommand.Card).card.cardId)
        assertEquals(VacuumCommand.Set("cancel_and_dock", JsonPrimitive(true)), c.dock)
        assertEquals("/api/image/img1", c.map?.imageObj?.url)
        assertEquals(listOf("alarm_bin_full"), c.alarms.map { it.id })
    }

    @Test fun `roborock pickers toggles buttons and cards`() {
        val c = Vacuums.controls(roborock, cards)
        assertEquals(listOf("suction_power", "scrub_intensity"), c.pickers.map { it.id })
        assertEquals("active_map", c.mapPicker?.id)
        assertEquals(listOf("mop_dry"), c.toggles.map { it.id })
        val buttons = c.buttons.map { it.title }
        assertTrue(buttons.containsAll(listOf("Locate", "Spot clean", "Empty dustbin", "Refresh rooms", "Repeat the last cleaning task")))
        assertFalse("spot card duplicates the spot capability", "Clean the current spot" in buttons)
        assertFalse("Clean all rooms" in buttons)
        assertEquals(listOf("Start routine", "Set the child lock"), c.choiceCards.map { it.title })
        assertTrue(c.readings.any { it.id == "clean_area" })
        assertTrue(c.readings.any { it.id == "mop_attached" })
        assertTrue(c.readings.none { it.id == "position_x" || it.id == "is_cleaning" })
    }

    @Test fun `rooms by name through the text card`() {
        val c = Vacuums.controls(roborock, cards)
        val cleaning = c.roomCleaning as RoomCleaning.Text
        assertNull(cleaning.maxRooms)
        assertEquals(listOf("Kitchen", "Hall"), c.pickerRooms.map { it.name })
        val cmd = Vacuums.roomArgs(cleaning, c.pickerRooms) as VacuumCommand.Card
        assertEquals(JsonObject(mapOf("repeat" to JsonPrimitive(1), "rooms" to JsonPrimitive("Kitchen, Hall"))), cmd.args)
    }

    @Test fun `rooms through numbered cards and the picker`() {
        val numbered = cards.filter { it.cardId != "clean_room" }
        val c = Vacuums.controls(roborock, numbered)
        val cleaning = c.roomCleaning as RoomCleaning.Cards
        assertEquals(2, cleaning.maxRooms)
        val rooms = Vacuums.roomsFrom(FlowCards.parseAutocomplete(HomeyJson.parseToJsonElement("""[{"id":16,"name":"Kitchen"},{"id":17,"name":"Hall"}]""")))
        val cmd = Vacuums.roomArgs(cleaning, rooms) as VacuumCommand.Card
        assertEquals(setOf("room1", "room2", "repeat"), cmd.args.keys)
        assertNull(Vacuums.roomArgs(cleaning, rooms.take(1)))
        assertEquals("room1", Vacuums.roomListArg(cleaning, numbered)?.second?.name)

        val picker = Vacuums.controls(roborock, emptyList()).roomCleaning as RoomCleaning.Picker
        assertEquals(VacuumCommand.Set("clean_room_pick", JsonPrimitive("16")), Vacuums.roomArgs(picker, c.pickerRooms.take(1)))
    }

    @Test fun `parts from the reset card`() {
        val parts = Vacuums.controls(roborock, cards).parts
        assertEquals(listOf("filter", "main_brush", "side_brush", "sensor"), parts.map { it.key })
        val filter = parts.first()
        assertEquals("Filter", filter.name)
        assertEquals(150.0, filter.lifetimeHours, 0.0)
        assertNull(filter.leftFraction)
        assertEquals(JsonObject(mapOf("consumable" to JsonPrimitive("filter_work_time"))), (filter.reset as VacuumCommand.Card).args)
    }

    @Test fun `standard homey vacuum`() {
        val device = HomeyJson.decodeFromString(
            Device.serializer(),
            """
            {"id": "v", "name": "Vac", "class": "vacuumcleaner", "capabilities": ["onoff", "vacuumcleaner_state", "measure_battery", "filter_life"],
             "capabilitiesObj": {
               "onoff": {"id": "onoff", "type": "boolean", "value": true, "setable": true},
               "vacuumcleaner_state": {"id": "vacuumcleaner_state", "type": "enum", "value": "cleaning", "setable": true},
               "measure_battery": {"id": "measure_battery", "type": "number", "value": 40},
               "filter_life": {"id": "filter_life", "type": "number", "value": 12, "units": "%", "title": "Filter life"}
             }}
            """,
        )
        val c = Vacuums.controls(device, emptyList())
        assertEquals(VacuumState.Cleaning, c.state)
        assertEquals(c.state, Vacuums.state(device))
        assertEquals(VacuumCommand.Set("vacuumcleaner_state", JsonPrimitive("cleaning")), c.start)
        assertEquals(VacuumCommand.Set("vacuumcleaner_state", JsonPrimitive("docked")), c.dock)
        assertEquals(VacuumCommand.Set("vacuumcleaner_state", JsonPrimitive("stopped")), c.stop)
        assertNull(c.pause)
        val filter = c.parts.single()
        assertEquals("filter_life", filter.capability)
        assertEquals(0.12, filter.leftFraction!!, 1e-9)
        assertTrue(c.readings.none { it.id == "filter_life" })
    }

    @Test fun `status text from the app`() {
        val device = Device(
            "x", "X", deviceClass = "vacuumcleaner", capabilities = listOf("operational_state", "battery_charging_state"),
            capabilitiesObj = mapOf(
                "operational_state" to Capability("operational_state", type = "string", value = JsonPrimitive("Returning to dock")),
                "battery_charging_state" to Capability("battery_charging_state", type = "enum", value = JsonPrimitive("discharging")),
            ),
        )
        val c = Vacuums.controls(device, emptyList())
        assertEquals(VacuumState.Returning, c.state)
        assertEquals(c.state, Vacuums.state(device))
        assertEquals("Returning to dock", c.statusText)
        assertFalse(c.charging)
        assertNotNull(Vacuums.readable("button.reset_mesh_filter"))
    }

    @Test fun `an update to the same value keeps the device`() {
        val device = Device(
            "x", "X", capabilities = listOf("onoff"),
            capabilitiesObj = mapOf("onoff" to Capability("onoff", type = "boolean", value = JsonPrimitive(true))),
        )
        assertSame(device, device.withCapabilityValue("onoff", JsonPrimitive(true)))
        assertEquals(JsonPrimitive(false), device.withCapabilityValue("onoff", JsonPrimitive(false)).capability("onoff")?.value)
    }
}

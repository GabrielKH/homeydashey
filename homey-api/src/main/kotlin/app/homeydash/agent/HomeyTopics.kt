package app.homeydash.agent

/**
 * Know-how topics made from what is in Homey right now, rebuilt for every question: a topic
 * for each kind of device the house has (heating, blinds, appliances, sensors…), one for each
 * Homey app whose devices are of no particular kind, and one for energy when something
 * measures power. Each topic gets the words people use for that kind of thing in Swedish and
 * English, the names of its devices, how to use the capabilities they really have, and the
 * tools that reach the rest of what their app can do. Add a heater in Homey and "gör det
 * varmare i hallen" brings the heating topic on the next question, with nothing to set up.
 *
 * Kinds the built-in topics cover (speakers, TVs, the vacuum) and lights and sockets, which
 * every question's rules cover, get no topic of their own.
 */
object HomeyTopics {
    /** What a group of Homey device classes is called and how people speak of it. */
    private class Kind(
        val id: String,
        val title: String,
        val about: String,
        val classes: Set<String>,
        val words: List<String>,
        val rule: String,
    )

    private val KINDS = listOf(
        Kind(
            "homey_climate", "Heating and climate", "heating, thermostats, heat pumps and air conditioning",
            setOf("thermostat", "heater", "heatpump", "airconditioning"),
            listOf(
                "värme*", "värma", "element*", "termostat*", "grader", "graders", "varm*", "kall*", "kyla*", "temperatur*",
                "golvvärme*", "värmepump*", "luftvärmepump*", "aircondition*", "luftkonditionering*", "ac", "heat*", "warm*", "cold*", "cool*",
                "thermostat*", "degree*", "temperature*",
            ),
            "Heating and cooling go through set_device on the device's own capabilities; \"varmare\" or \"warmer\" is 1 degree up and \"kallare\" or \"colder\" 1 down from what it is set to now (get_device reads it), unless they say how much.",
        ),
        Kind(
            "homey_covers", "Blinds, curtains and doors", "blinds, curtains, awnings and garage doors",
            setOf("blinds", "curtain", "windowcoverings", "garagedoor", "sunshade", "awning"),
            listOf(
                "persienn*", "rullgardin*", "gardin*", "markis*", "jalusi*", "solskydd*", "garage*", "garageport*", "port*", "dra upp", "dra ner",
                "fäll*", "blind*", "curtain*", "shade*", "shutter*", "awning*", "garage", "roll up", "roll down",
            ),
            "Blinds and curtains go up, stop and down with windowcoverings_state (up, idle, down), or to a part open with windowcoverings_set (0 closed, 1 fully open, so \"halvvägs\" is 0.5).",
        ),
        Kind(
            "homey_air", "Fans and air", "fans, air purifiers, humidifiers and ventilation",
            setOf("fan", "airpurifier", "dehumidifier", "humidifier", "airtreatment", "ventilation"),
            listOf(
                "fläkt*", "ventilation*", "ventil*", "luftrenare*", "avfuktare*", "luftfukt*", "befukt*", "köksfläkt*", "fan", "fans",
                "purifier*", "humidifier*", "dehumidifier*", "ventilation", "airflow",
            ),
            "Fans and air devices switch with onoff; their speed or mode is a capability of their own (get_device lists it).",
        ),
        Kind(
            "homey_appliances", "Kitchen and household appliances", "kettle, coffee machine, oven, dishwasher, washing machine and dryer",
            setOf("kettle", "coffeemachine", "oven", "cooktop", "microwave", "dishwasher", "washer", "dryer", "washingmachine", "fridge", "freezer", "grill"),
            listOf(
                "vattenkokare*", "kaffe*", "kaffebryggare*", "kaffemaskin*", "espresso*", "ugn*", "ugnen", "spis*", "häll*", "mikro*", "micro*",
                "diskmaskin*", "disk*", "diska", "tvättmaskin*", "tvätt*", "tvätta", "torktumlare*", "tork*",
                "kettle*", "coffee*", "oven*", "stove*", "hob", "microwave*", "dishwasher*", "dishes", "washer*", "washing*", "laundry", "dryer*",
            ),
            "Ask an appliance how it is going with get_device (its program, time left or state); starting a program or more goes through device_actions and run_device_action, never by guessing a capability.",
        ),
        Kind(
            "homey_sensors", "Sensors", "temperature, humidity, motion, door and window sensors",
            setOf("sensor"),
            listOf(
                "sensor*", "givare*", "temperatur*", "fukt*", "luftfukt*", "rörelse*", "öppen", "öppet", "stängd", "stängt", "fönster*", "dörr*",
                "co2", "koldioxid*", "lux", "vatten", "läck*", "humidity", "motion", "window*", "door*", "leak*", "how warm", "hur varmt", "hur kallt",
            ),
            "Sensors only read: answer from the note or get_device, in plain words (\"21 grader och 40 procent fukt\"); alarm_contact true means open, alarm_motion, alarm_water and alarm_smoke true mean it is detecting it now.",
        ),
        Kind(
            "homey_car", "Car and charging", "the car and its charger",
            setOf("evcharger", "car"),
            listOf("laddbox*", "laddare*", "elbil*", "bil", "bilen", "bilens", "laddning*", "ladda", "charger*", "car", "the car", "ev", "charging"),
            "Charging starts and stops with the charger's own capability (get_device lists it); the car's battery and range are read with get_device.",
        ),
        Kind(
            "homey_security", "Locks, alarm and cameras", "locks, the house alarm, doorbells and cameras",
            setOf("lock", "homealarm", "doorbell", "camera", "smokealarm"),
            listOf(
                "lås*", "låst", "låsa", "olåst", "larm*", "larmet", "dörrklocka*", "ringklocka*", "kamera*", "rök*", "brand*",
                "lock*", "unlock*", "alarm", "doorbell*", "camera*", "smoke", "fire",
            ),
            "locked true means locked; unlocking or switching off the alarm needs a yes, and the tool asks for it. Never guess or say a code.",
        ),
    )

    /** Kinds whose devices can be anything: their topic is made per Homey app instead. */
    private val PER_APP = setOf("other", "button", "remote", "networkrouter", "solarpanel", "battery", "petfeeder", "bicycle", "scooter")

    /** Kinds every question's rules cover, or a built-in topic does. */
    private val COVERED = setOf("light", "socket", "speaker", "amplifier", "tv", "settopbox", "vacuumcleaner")

    private val ENERGY_WORDS = listOf(
        "el", "elen", "ström", "strömmen", "förbruk*", "watt*", "kwh", "kilowatt*", "effekt*", "energi*", "elpris*", "elräkning*", "solcell*",
        "drar", "power", "energy", "consumption", "electricity", "usage", "solar",
    )

    /** What a capability means and how to use it, for the topics of devices that have it. */
    private val CAPABILITY_RULES = linkedMapOf(
        "target_temperature" to "target_temperature is the temperature it heats or cools to, in degrees, within its range.",
        "thermostat_mode" to "thermostat_mode picks one of its listed modes (heat, cool, auto, off).",
        "windowcoverings_tilt_set" to "windowcoverings_tilt_set tilts the slats, 0..1.",
        "garagedoor_closed" to "garagedoor_closed false means the garage door is open; opening it needs a yes.",
        "measure_power" to "measure_power is how many watts it uses right now; meter_power is kWh used in total.",
        "measure_battery" to "measure_battery is the device's own battery in %; alarm_battery true means it is low.",
        "evcharger_charging" to "evcharger_charging true charges, false stops.",
        "homealarm_state" to "homealarm_state is armed, disarmed or partially_armed.",
    )

    /**
     * The topics [view]'s devices make, leaving out kinds that the [covered] Homey classes
     * (the built-in topics' classes) already have.
     */
    fun from(view: HomeView, covered: Set<String> = emptySet()): List<Skill> {
        val rooms = view.rooms.values.map { Routines.normalize(it) }.toSet()
        val skip = COVERED + covered
        val devices = view.devices.filter { it.deviceClass !in skip }
        val out = mutableListOf<Skill>()
        for (kind in KINDS) {
            val list = devices.filter { it.deviceClass in kind.classes }
            if (list.isNotEmpty()) out += topic(kind.id, kind.title, kind.about, kind.classes.toList(), kind.words, kind.rule, list, rooms)
        }
        val known = KINDS.flatMap { it.classes }.toSet()
        // Devices of no particular kind, one topic per app: "Tibber", "Easee", "Verisure".
        devices.filter { it.deviceClass in PER_APP || it.deviceClass !in known }.groupBy { it.kind ?: "homey" }.forEach { (app, list) ->
            val title = app.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val id = "app_" + Routines.normalize(app).replace(' ', '_')
            val appWords = Routines.normalize(app).split(' ').filter { it.length >= 3 }.map { "$it*" }
            out += topic(
                id, if (app == "homey") "Other Homey devices" else "$title (Homey app)", "the ${list.size} device${if (list.size == 1) "" else "s"} of $title",
                emptyList(), appWords,
                "Its devices are read with get_device and changed with set_device; what else the app can do (programs, modes, scenes) is in device_actions, run with run_device_action.",
                list, rooms,
            )
        }
        // Anything that measures power: "vad drar tvättmaskinen?", "hur mycket el använder vi?".
        val metered = view.devices.filter { d -> d.capabilities.any { it.id == "measure_power" || it.id == "meter_power" } }
        if (metered.isNotEmpty()) {
            out += Skill(
                id = "homey_energy", title = "Energy", about = "how much electricity devices use",
                words = ENERGY_WORDS,
                tools = listOf("get_device"),
                rules = listOf(
                    "measure_power is watts right now and meter_power kWh in total; read them with get_device, add them up for a room or the house, and say them in words (\"ungefär 1,2 kilowatt\").",
                    "Measuring power: " + metered.take(MAX_DEVICES).joinToString { it.name + (it.room?.let { r -> " ($r)" } ?: "") } + if (metered.size > MAX_DEVICES) " and ${metered.size - MAX_DEVICES} more." else ".",
                ),
                fromHomey = true,
            )
        }
        return out
    }

    private const val MAX_DEVICES = 12

    /**
     * One topic: the kind's words, the names of its devices (without the room names, which
     * belong to every topic), its rule, and what each capability its devices have means.
     */
    private fun topic(
        id: String, title: String, about: String, classes: List<String>, words: List<String>, rule: String,
        devices: List<AgentDevice>, rooms: Set<String>,
    ): Skill {
        val names = devices.flatMap { d -> (listOf(d.name) + d.nicknames).flatMap { Routines.normalize(it).split(' ') } }
            .filter { it.length >= 4 && it !in rooms && it.none(Char::isDigit) }.map { "$it*" }
        val caps = devices.flatMap { d -> d.capabilities.map { it.id } }.toSet()
        val meanings = CAPABILITY_RULES.filterKeys { it in caps }.values
        val listed = "Its devices: " + devices.take(MAX_DEVICES).joinToString { d ->
            d.name + (d.room?.let { " ($it)" } ?: "") + d.capabilities.filter { it.setable }.map { it.id }.takeIf { it.isNotEmpty() }?.joinToString(", ", ": ").orEmpty()
        } + if (devices.size > MAX_DEVICES) " and ${devices.size - MAX_DEVICES} more." else "."
        return Skill(
            id = id, title = title, about = about,
            words = (words + names).distinct(),
            classes = classes,
            tools = listOf("get_device", "device_actions", "run_device_action"),
            rules = listOf(rule, listed) + meanings,
            fromHomey = true,
        )
    }
}

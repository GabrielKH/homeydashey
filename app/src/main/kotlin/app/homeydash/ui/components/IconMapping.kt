package app.homeydash.ui.components

import app.homeydash.homey.Device
import app.homeydash.homey.Mood
import app.homeydash.homey.Zone

/** Picks a symbol for a device from its Homey class and capabilities. */
fun deviceIcon(device: Device): String {
    val caps = device.capabilities.toSet()
    return when (device.effectiveClass) {
        "light" -> if (device.deviceClass == "socket") Sym.floorLamp else Sym.lightbulb
        "socket" -> Sym.outlet
        "thermostat" -> Sym.thermostat
        "heater", "boiler" -> Sym.heat
        "heatpump" -> Sym.heatPump
        "airconditioning" -> Sym.acUnit
        "fan" -> Sym.modeFan
        "airpurifier", "airtreatment" -> Sym.air
        "speaker" -> Sym.speaker
        "tv", "amplifier", "settopbox", "mediaplayer" -> Sym.tv
        "lock" -> Sym.lock
        "windowcoverings", "blinds" -> Sym.blinds
        "sunshade", "awning" -> Sym.rollerShades
        "curtain" -> Sym.curtains
        "garagedoor" -> Sym.garageDoor
        "doorbell" -> Sym.doorbell
        "camera" -> Sym.videocam
        "vacuumcleaner" -> Sym.vacuum
        "button", "remote" -> Sym.smartButton
        "coffeemachine", "kettle" -> Sym.coffeeMaker
        "dishwasher" -> Sym.dishwasherGen
        "washer", "dryer", "washingmachine" -> Sym.localLaundryService
        "oven", "cooktop", "microwave" -> Sym.microwave
        "fridge", "freezer" -> Sym.kitchen
        "evcharger", "car" -> Sym.electricBolt
        "solarpanel" -> Sym.sunny
        "battery" -> Sym.batteryFull
        "homealarm" -> Sym.shieldLock
        "waterheater" -> Sym.waterHeater
        "networkrouter" -> Sym.router
        "petfeeder", "pet" -> Sym.pets
        "smokealarm" -> Sym.localFireDepartment
        else -> when {
            "alarm_smoke" in caps || "alarm_fire" in caps -> Sym.localFireDepartment
            "alarm_contact" in caps -> Sym.sensorDoor
            "alarm_motion" in caps -> Sym.motionSensorActive
            "alarm_water" in caps -> Sym.waterDrop
            "measure_co2" in caps -> Sym.co2
            "measure_temperature" in caps -> Sym.deviceThermostat
            "measure_humidity" in caps -> Sym.waterDrop
            "measure_power" in caps || "meter_power" in caps -> Sym.bolt
            "measure_luminance" in caps -> Sym.wbSunny
            "onoff" in caps -> Sym.power
            caps.any { it.startsWith("alarm_") || it.startsWith("measure_") } -> Sym.sensors
            else -> Sym.devicesOther
        }
    }
}

/** Homey zone icon ids (and common room names, English and Swedish) to symbols. */
fun zoneIcon(zone: Zone): String {
    val byIcon = when (zone.icon) {
        "home" -> Sym.home
        "kitchen" -> Sym.countertops
        "livingRoom", "living" -> Sym.weekend
        "bedroom", "bedroomSingle", "bedroomDouble" -> Sym.bed
        "bedroomKids", "kidsRoom", "playroom" -> Sym.toys
        "bathroom", "shower" -> Sym.bathtub
        "toilet" -> Sym.wc
        "garden", "yard" -> Sym.yard
        "garage" -> Sym.garage
        "office", "study" -> Sym.desk
        "hallway", "entrance", "frontDoor" -> Sym.doorFront
        "stairs" -> Sym.stairs
        "diningRoom", "dining" -> Sym.dining
        "laundryRoom" -> Sym.localLaundryService
        "terrace", "veranda" -> Sym.deck
        "balcony" -> Sym.balcony
        "gym" -> Sym.fitnessCenter
        "pool" -> Sym.pool
        "gameRoom" -> Sym.sportsEsports
        "wardrobe", "closet" -> Sym.checkroom
        "firstFloor", "secondFloor", "groundFloor", "floor", "basement", "attic", "upstairs", "downstairs" -> Sym.apartment
        else -> null
    }
    if (byIcon != null) return byIcon
    val name = zone.name.lowercase()
    return when {
        listOf("kitchen", "kök").any { it in name } -> Sym.countertops
        listOf("living", "vardag", "lounge", "tv").any { it in name } -> Sym.weekend
        listOf("bed", "sov").any { it in name } -> Sym.bed
        listOf("kid", "barn").any { it in name } -> Sym.toys
        listOf("bath", "bad", "dusch").any { it in name } -> Sym.bathtub
        listOf("toilet", "wc").any { it in name } -> Sym.wc
        listOf("garden", "trädgård", "yard", "outside", "ute").any { it in name } -> Sym.yard
        "garage" in name -> Sym.garage
        listOf("office", "kontor", "study").any { it in name } -> Sym.desk
        listOf("hall", "entr").any { it in name } -> Sym.doorFront
        listOf("dining", "matsal").any { it in name } -> Sym.dining
        listOf("laundry", "tvätt").any { it in name } -> Sym.localLaundryService
        listOf("floor", "våning", "plan", "basement", "källare", "attic", "vind").any { it in name } -> Sym.apartment
        listOf("terrace", "terrass", "altan", "deck").any { it in name } -> Sym.deck
        listOf("balcon", "balkong").any { it in name } -> Sym.balcony
        else -> Sym.meetingRoom
    }
}

/** Moods have no icon in Homey, so this guesses one from the name. */
fun moodIcon(mood: Mood): String {
    val name = mood.name.lowercase()
    return when {
        listOf("cook", "laga", "matlag", "baking", "bak").any { it in name } -> Sym.restaurant
        listOf("dinner", "middag", "dining", "lunch", "eat", "äta").any { it in name } -> Sym.dinnerDining
        listOf("movie", "film", "cinema", "bio", "tv").any { it in name } -> Sym.movie
        listOf("relax", "chill", "mys", "cosy", "cozy", "lugn", "calm").any { it in name } -> Sym.spa
        listOf("night", "natt", "sleep", "sov", "bed", "läggdags").any { it in name } -> Sym.bedtime
        listOf("morning", "morgon", "coffee", "kaffe", "breakfast", "frukost").any { it in name } -> Sym.localCafe
        listOf("evening", "kväll", "sunset").any { it in name } -> Sym.wbTwilight
        listOf("party", "fest").any { it in name } -> Sym.celebration
        listOf("away", "borta", "leave", "lämna").any { it in name } -> Sym.directionsWalk
        listOf("bright", "ljust", "full", "clean", "städ", "day", "dag").any { it in name } -> Sym.sunny
        listOf("off", "av", "släck").any { name == it || name.startsWith("$it ") || name.endsWith(" $it") } -> Sym.powerSettingsNew
        listOf("read", "läs", "work", "jobb", "focus").any { it in name } -> Sym.emojiObjects
        listOf("music", "musik").any { it in name } -> Sym.musicNote
        else -> Sym.autoAwesome
    }
}

package app.homeydash.homey

object Fixtures {
    val zones = """
        {
          "z-home": {"id": "z-home", "name": "Home", "parent": null, "icon": "home"},
          "z-kitchen": {"id": "z-kitchen", "name": "Kitchen", "parent": "z-home", "icon": "kitchen"},
          "z-living": {"id": "z-living", "name": "Living room", "parent": "z-home", "icon": "livingRoom"},
          "z-attic": {"id": "z-attic", "name": "Attic", "parent": "z-home"}
        }
    """.trimIndent()

    val devices = """
        {
          "d-lamp": {
            "id": "d-lamp", "name": "Kitchen lamp", "zone": "z-kitchen", "class": "light",
            "capabilities": ["onoff", "dim"],
            "capabilitiesObj": {
              "onoff": {"id": "onoff", "type": "boolean", "title": "Turned on", "value": true, "setable": true, "getable": true},
              "dim": {"id": "dim", "type": "number", "title": "Dim level", "value": 0.4, "min": 0, "max": 1, "step": 0.01, "units": "%", "setable": true}
            },
            "available": true, "ready": true, "somethingNew": {"nested": 1}
          },
          "d-plug": {
            "id": "d-plug", "name": "Coffee machine", "zone": "z-kitchen", "class": "socket", "virtualClass": null,
            "capabilities": ["onoff", "measure_power"],
            "capabilitiesObj": {
              "onoff": {"id": "onoff", "type": "boolean", "value": false, "setable": true},
              "measure_power": {"id": "measure_power", "type": "number", "value": 3.2, "units": "W", "decimals": 1}
            }
          },
          "d-temp": {
            "id": "d-temp", "name": "Climate", "zone": "z-living", "class": "sensor",
            "capabilities": ["measure_temperature", "measure_humidity"],
            "capabilitiesObj": {
              "measure_temperature": {"id": "measure_temperature", "type": "number", "value": 21.5, "units": "°C"},
              "measure_humidity": {"id": "measure_humidity", "type": "number", "value": null, "units": "%"}
            }
          },
          "d-lost": {"id": "d-lost", "name": "Lost device", "zone": "z-gone", "class": "other", "capabilities": []}
        }
    """.trimIndent()
}

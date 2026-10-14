package app.homeydash.homey

import kotlinx.serialization.json.JsonObject

/**
 * Flow-card-shaped actions that the app runs itself rather than through Homey, such as
 * playing a Sonos favourite on a speaker whose Homey app has no card for it. They sit next
 * to the device's Homey cards, so the music section and the assistant use them the same way.
 */
interface CardSource {
    /** Cards per Homey device id. Called after each full load; may take a moment (it can search the network). */
    suspend fun cards(snapshot: HomeySnapshot): Map<String, List<FlowCardAction>>

    fun owns(card: FlowCardAction): Boolean

    suspend fun autocomplete(card: FlowCardAction, arg: String, query: String): List<AutocompleteItem>

    suspend fun run(card: FlowCardAction, args: JsonObject)
}

package app.homeydash.agent

/**
 * Everyday home words in Swedish and English, both ways, so "the note about the flowers" finds
 * "Köp blommor till mormor" and "start the dinner mood" finds the mood called Middag. Small on
 * purpose: only words people use for notes, moods, food and chores; names are matched as they are.
 */
object Translate {
    private val PAIRS = listOf(
        "flower" to "blomma", "flowers" to "blommor", "dinner" to "middag", "movie" to "film", "movies" to "film",
        "cozy" to "mys", "cosy" to "mys", "cozy" to "mysig", "cozy" to "mysigt", "night" to "natt", "evening" to "kvall",
        "morning" to "morgon", "party" to "fest", "reading" to "lasa", "read" to "las", "breakfast" to "frukost",
        "milk" to "mjolk", "egg" to "agg", "eggs" to "agg", "chicken" to "kyckling", "cheese" to "ost", "butter" to "smor",
        "bread" to "brod", "pancakes" to "pannkakor", "pancake" to "pannkaka", "yoghurt" to "yoghurt", "yogurt" to "yoghurt",
        "car" to "bil", "dentist" to "tandlakare", "grandma" to "mormor", "grandma" to "farmor", "package" to "paket",
        "parcel" to "paket", "bulb" to "glodlampa", "light" to "lampa", "cleaning" to "stad", "clean" to "stad",
        "laundry" to "tvatt", "dishes" to "disk", "shopping" to "handla", "key" to "nyckel", "keys" to "nycklar",
        "sofa" to "soffa", "couch" to "soffa", "table" to "bord", "coffee table" to "soffbord", "floor" to "golv", "ceiling" to "tak",
        "bed" to "sang", "lamp" to "lampa", "lamp" to "lampan", "kitchen" to "kok", "hall" to "hall", "hallway" to "hall", "bedroom" to "sovrum",
        "living room" to "vardagsrum", "desk" to "skrivbord", "window" to "fonster", "night" to "natt", "spotlights" to "spotlights",
        "relax" to "lugn", "calm" to "lugn", "bright" to "ljus", "dark" to "mork", "kids" to "barn", "children" to "barn",
    )
    private val BOTH: Map<String, Set<String>> = (PAIRS + PAIRS.map { it.second to it.first })
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    /** English phrases Swedish writes as one word ("sofa table" is soffbord). */
    private val PHRASES = listOf(
        "sofa table" to "soffbord", "coffee table" to "soffbord", "living room" to "vardagsrum", "dining room" to "matsal",
        "dining table" to "matbord", "night light" to "nattlampa", "bedside lamp" to "sanglampa", "bed lamp" to "sanglampa",
        "floor lamp" to "golvlampa", "ceiling light" to "taklampa", "ceiling lamp" to "taklampa", "car service" to "bilservice",
    )

    /** [folded] with English phrases also written the Swedish way, so both are there to match. */
    fun phrases(folded: String): String =
        PHRASES.filter { (en, _) -> folded.contains(en) }.fold(folded) { acc, (en, sv) -> "$acc $sv" }.let { if (it == folded) folded else it }

    /** [word] (normalized) and what it is in the other language. */
    fun variants(word: String): Set<String> = setOf(word) + BOTH[word].orEmpty() +
        // Swedish endings: "blommorna", "middagen", "kycklingen" are still the word.
        BOTH.entries.filter { (k, _) -> k.length >= 4 && word.length > k.length && word.startsWith(k) && word.length - k.length <= 3 }.flatMap { it.value }
}

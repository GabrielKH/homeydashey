package app.homeydash.homey

/** A streaming app the remote knows by name, so it can go first and show its logo. */
data class KnownTvApp(val key: String, val bundleIds: List<String>, val names: List<String>)

/** Orders a TV box's apps: streaming services first, then everything else by name. */
object TvApps {

    /** In the order they show when nothing is configured. */
    val known = listOf(
        KnownTvApp("netflix", listOf("com.netflix.Netflix"), listOf("netflix")),
        KnownTvApp("appletv", listOf("com.apple.TVWatchList"), listOf("appletv", "appletvplus")),
        KnownTvApp("primevideo", listOf("com.amazon.aiv.AIVApp"), listOf("primevideo", "amazonprimevideo")),
        KnownTvApp("disneyplus", listOf("com.disney.disneyplus"), listOf("disneyplus", "disney")),
        KnownTvApp("max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.hbogo"), listOf("max", "hbomax", "hbo")),
        KnownTvApp("youtube", listOf("com.google.ios.youtube"), listOf("youtube")),
        KnownTvApp("svtplay", listOf("se.svt.svtplay"), listOf("svtplay")),
        KnownTvApp("tv4play", listOf("se.tv4.tv4play", "se.tv4.tv4playtab"), listOf("tv4play", "tv4")),
        KnownTvApp("viaplay", emptyList(), listOf("viaplay")),
        KnownTvApp("skyshowtime", emptyList(), listOf("skyshowtime")),
        KnownTvApp("paramountplus", emptyList(), listOf("paramountplus", "paramount")),
        KnownTvApp("discoveryplus", emptyList(), listOf("discoveryplus")),
        KnownTvApp("plex", listOf("com.plexapp.plex"), listOf("plex")),
        KnownTvApp("infuse", listOf("com.firecore.infuse"), listOf("infuse", "infuse7", "infuse8")),
        KnownTvApp("kodi", listOf("org.xbmc.kodi-tvos"), listOf("kodi")),
        KnownTvApp("twitch", listOf("tv.twitch"), listOf("twitch")),
        KnownTvApp("crunchyroll", emptyList(), listOf("crunchyroll")),
        KnownTvApp("mubi", emptyList(), listOf("mubi")),
        KnownTvApp("spotify", listOf("com.spotify.client"), listOf("spotify")),
        KnownTvApp("applemusic", listOf("com.apple.TVMusic"), listOf("applemusic")),
    )

    private fun normalize(name: String) = name.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    /** The known app an entry is, from its bundle id or its name. */
    fun identify(id: String?, name: String): KnownTvApp? {
        if (id != null) known.firstOrNull { app -> app.bundleIds.any { it.equals(id, ignoreCase = true) } }?.let { return it }
        val n = normalize(name)
        return known.firstOrNull { n in it.names }
    }

    /**
     * Which apps to show and in what order. [chosen] is the user's list of app ids;
     * without one, streaming apps come first in [known] order, then the rest by name.
     */
    fun <T> arrange(apps: List<T>, id: (T) -> String, name: (T) -> String, chosen: List<String>?): List<T> {
        if (chosen != null) {
            val byId = apps.associateBy(id)
            return chosen.mapNotNull { byId[it] }
        }
        val rank = { app: T -> identify(id(app), name(app))?.let { known.indexOf(it) } ?: Int.MAX_VALUE }
        return apps.sortedWith(compareBy<T>(rank).thenBy { name(it).lowercase() })
    }
}

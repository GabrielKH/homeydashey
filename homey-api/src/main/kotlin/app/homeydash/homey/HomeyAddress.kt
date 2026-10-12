package app.homeydash.homey

/** Turns what the user typed into Homey base URLs to try, best first. */
object HomeyAddress {

    private val HOMEYLOCAL = Regex("""^(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})\.homey\.homeylocal\.com$""")

    fun candidates(input: String): List<String> {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        val hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val host = trimmed.substringAfter("://").substringBefore('/').substringBefore(':')
        val local = HOMEYLOCAL.matchEntire(host)
        val primary = when {
            hasScheme -> trimmed
            local != null -> "https://$trimmed"
            else -> "http://$trimmed"
        }
        // Some routers block DNS answers that point at private IPs, which breaks
        // *.homeylocal.com, so fall back to the plain IP it encodes.
        val ipFallback = local?.groupValues?.drop(1)?.joinToString(".")?.let { "http://$it" }
        return listOfNotNull(primary, ipFallback).distinct()
    }
}

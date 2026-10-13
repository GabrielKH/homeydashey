package app.homeydash.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homeydash.R
import app.homeydash.homey.TvApps
import app.homeydash.ui.components.glass
import app.homeydash.ui.theme.GlassTheme

/** How a known streaming app looks: its colour and logo (Simple Icons), or its name when there is no logo. */
private data class AppBrand(val background: Color, val tint: Color = Color.White, @DrawableRes val logo: Int? = null, val wordmark: String? = null)

private val brands = mapOf(
    "netflix" to AppBrand(Color(0xFF141414), Color(0xFFE50914), R.drawable.logo_netflix),
    "appletv" to AppBrand(Color(0xFF1C1C1E), logo = R.drawable.logo_appletv),
    "primevideo" to AppBrand(Color(0xFF00A8E1), wordmark = "prime video"),
    "disneyplus" to AppBrand(Color(0xFF0B1B5C), wordmark = "Disney+"),
    "max" to AppBrand(Color(0xFF002BE7), logo = R.drawable.logo_hbomax),
    "youtube" to AppBrand(Color(0xFFFF0000), logo = R.drawable.logo_youtube),
    "svtplay" to AppBrand(Color(0xFF1A7F3C), wordmark = "SVT Play"),
    "tv4play" to AppBrand(Color(0xFFE5002B), wordmark = "TV4 Play"),
    "viaplay" to AppBrand(Color(0xFFFE365F), logo = R.drawable.logo_viaplay),
    "skyshowtime" to AppBrand(Color(0xFF1B0F3D), wordmark = "SkyShowtime"),
    "paramountplus" to AppBrand(Color(0xFF0064FF), logo = R.drawable.logo_paramountplus),
    "discoveryplus" to AppBrand(Color(0xFF0A3EF0), wordmark = "discovery+"),
    "plex" to AppBrand(Color(0xFF1F1F1F), Color(0xFFEBAF00), R.drawable.logo_plex),
    "infuse" to AppBrand(Color(0xFFFF8000), logo = R.drawable.logo_infuse),
    "kodi" to AppBrand(Color(0xFF17B2E7), logo = R.drawable.logo_kodi),
    "twitch" to AppBrand(Color(0xFF9146FF), logo = R.drawable.logo_twitch),
    "crunchyroll" to AppBrand(Color(0xFFFF5E00), logo = R.drawable.logo_crunchyroll),
    "mubi" to AppBrand(Color(0xFF000000), logo = R.drawable.logo_mubi),
    "spotify" to AppBrand(Color(0xFF121212), Color(0xFF1ED760), R.drawable.logo_spotify),
    "applemusic" to AppBrand(Color(0xFFFA243C), logo = R.drawable.logo_applemusic),
)

private val AppShape = RoundedCornerShape(16.dp)

/** An app's icon like on the TV: the brand's tile for known services, a glass tile with its initial otherwise. */
@Composable
fun AppIcon(id: String?, name: String, modifier: Modifier = Modifier, logoSize: Dp = 34.dp) {
    val brand = TvApps.identify(id, name)?.let { brands[it.key] }
    Box(
        modifier
            .aspectRatio(16f / 10f)
            .clip(AppShape)
            .then(if (brand != null) Modifier.background(brand.background) else Modifier.glass(AppShape)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            brand?.logo != null -> Icon(painterResource(brand.logo), contentDescription = name, tint = brand.tint, modifier = Modifier.size(logoSize))
            brand?.wordmark != null -> Text(
                brand.wordmark,
                color = brand.tint,
                fontWeight = FontWeight.Bold,
                fontSize = (logoSize.value * 0.5f).sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            else -> Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = GlassTheme.colors.fg,
            )
        }
    }
}

/** An app tile with its name underneath. */
@Composable
fun AppTile(id: String?, name: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppIcon(id, name, Modifier.fillMaxWidth())
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = GlassTheme.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

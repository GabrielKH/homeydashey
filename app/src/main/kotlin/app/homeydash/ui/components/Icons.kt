package app.homeydash.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.homeydash.R
import app.homeydash.ui.theme.GlassTheme

private val SymbolsOutlined = FontFamily(Font(R.font.symbols_outlined))
private val SymbolsFilled = FontFamily(Font(R.font.symbols_filled))

/**
 * Material Symbols Rounded, drawn from two small subset fonts (outlined and filled)
 * rather than vector drawables, so icons stay crisp at any size and match the mockup.
 * To add an icon, add its name to the subset request and regenerate [Sym].
 */
@Composable
fun SymIcon(
    icon: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 24.dp,
    tint: Color = GlassTheme.colors.fg,
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        BasicText(
            text = icon,
            style = TextStyle(
                color = tint,
                fontFamily = if (filled) SymbolsFilled else SymbolsOutlined,
                fontSize = fontSize,
                lineHeight = fontSize,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

/** Code points of the Material Symbols in res/font. */
@Suppress("unused")
object Sym {
    // Generated from the subset fonts in res/font; each code point exists in both.
    const val acUnit = "\ueb3b"
    const val add = "\ue145"
    const val air = "\uefd8"
    const val airwave = "\ue29c"
    const val apartment = "\uea40"
    const val arrowBack = "\ue5c4"
    const val autoAwesome = "\ue65f"
    const val balcony = "\ue58f"
    const val bathtub = "\uea41"
    const val batteryFull = "\ue1a4"
    const val bed = "\uefdf"
    const val bedtime = "\uef44"
    const val blinds = "\ue286"
    const val bolt = "\uea0b"
    const val celebration = "\uea65"
    const val chair = "\uefed"
    const val check = "\ue5ca"
    const val checkroom = "\uf19e"
    const val chevronRight = "\ue409"
    const val close = "\ue14c"
    const val co2 = "\ue7b0"
    const val coffeeMaker = "\ueff0"
    const val contrast = "\ueb37"
    const val cottage = "\ue587"
    const val countertops = "\uf1f7"
    const val curtains = "\uec1e"
    const val darkMode = "\ue51c"
    const val deck = "\uea42"
    const val desk = "\uf8f4"
    const val deviceThermostat = "\ue1ff"
    const val devicesOther = "\ue337"
    const val dining = "\ueff4"
    const val dinnerDining = "\uea57"
    const val directionsWalk = "\ue536"
    const val dishwasherGen = "\ue832"
    const val doorFront = "\ueffd"
    const val doorbell = "\uefff"
    const val electricBolt = "\uec1c"
    const val emergencyHome = "\ue82a"
    const val emojiObjects = "\uea24"
    const val energySavingsLeaf = "\uec1a"
    const val error = "\ue000"
    const val fitnessCenter = "\ueb43"
    const val flight = "\ue539"
    const val floorLamp = "\ue21e"
    const val garage = "\uf011"
    const val garageDoor = "\ue714"
    const val gridView = "\ue9b0"
    const val heat = "\uf537"
    const val heatPump = "\uec18"
    const val home = "\ue88a"
    const val house = "\uea44"
    const val keyboardArrowDown = "\ue313"
    const val keyboardArrowLeft = "\ue314"
    const val keyboardArrowRight = "\ue315"
    const val keyboardArrowUp = "\ue316"
    const val kitchen = "\ueb47"
    const val light = "\uf02a"
    const val lightMode = "\ue518"
    const val lightbulb = "\ue0f0"
    const val living = "\uf02b"
    const val localCafe = "\ue541"
    const val localFireDepartment = "\uef55"
    const val localLaundryService = "\ue54a"
    const val lock = "\ue897"
    const val lockOpen = "\ue898"
    const val logout = "\ue9ba"
    const val meetingRoom = "\ueb4f"
    const val menu = "\ue5d2"
    const val microwave = "\uf204"
    const val modeFan = "\uf168"
    const val mop = "\ue28d"
    const val motionSensorActive = "\ue792"
    const val movie = "\ue02c"
    const val musicNote = "\ue3a1"
    const val nature = "\ue406"
    const val nightsStay = "\uea46"
    const val notifications = "\ue7f4"
    const val outlet = "\uf1d4"
    const val pause = "\ue034"
    const val pets = "\ue91d"
    const val playArrow = "\ue037"
    const val playCircle = "\ue1c4"
    const val pool = "\ueb48"
    const val power = "\ue63c"
    const val powerSettingsNew = "\ue8ac"
    const val refresh = "\ue5d5"
    const val remove = "\ue15b"
    const val restaurant = "\ue56c"
    const val rollerShades = "\uec12"
    const val router = "\ue328"
    const val schedule = "\ue8b5"
    const val sensorDoor = "\uf1b5"
    const val sensorWindow = "\uf1b4"
    const val sensors = "\ue51e"
    const val settings = "\ue8b8"
    const val shield = "\ue9e0"
    const val shieldLock = "\uf686"
    const val shower = "\uf061"
    const val skipNext = "\ue044"
    const val skipPrevious = "\ue045"
    const val smartButton = "\uf1c1"
    const val spa = "\ueb4c"
    const val spaceDashboard = "\ue66b"
    const val speaker = "\ue32d"
    const val sportsEsports = "\uea28"
    const val stairs = "\uf1a9"
    const val sunny = "\ue81a"
    const val sync = "\ue627"
    const val thermometer = "\ue846"
    const val thermostat = "\uf076"
    const val toggleOn = "\ue9f6"
    const val toys = "\ue332"
    const val tv = "\ue333"
    const val vacuum = "\uefc5"
    const val videocam = "\ue04b"
    const val visibilityOff = "\ue8f5"
    const val volumeDown = "\ue04d"
    const val volumeOff = "\ue04f"
    const val volumeUp = "\ue050"
    const val warning = "\ue002"
    const val waterDrop = "\ue798"
    const val waterHeater = "\ue284"
    const val wbIridescent = "\ue436"
    const val wbSunny = "\ue430"
    const val wbTwilight = "\ue1c6"
    const val wc = "\ue63d"
    const val weekend = "\ue16b"
    const val wifiOff = "\ue648"
    const val window = "\uf088"
    const val work = "\ue8f9"
    const val yard = "\uf089"
}

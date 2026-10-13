package app.homeydash.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.homeydash.R
import app.homeydash.ui.theme.GlassTheme

/** An icon in its outlined (regular) and filled forms. */
@Immutable
class Ic(@DrawableRes val regular: Int, @DrawableRes val fill: Int)

/**
 * Phosphor Icons (phosphoricons.com, MIT), converted from the official SVGs into
 * vector drawables (res/drawable/ph_*.xml), so they sit exactly centred at any size.
 * Regular when off, Fill when on.
 */
@Composable
fun SymIcon(
    icon: Ic,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 24.dp,
    tint: Color = GlassTheme.colors.fg,
) {
    Icon(
        painter = painterResource(if (filled) icon.fill else icon.regular),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** The icons the dashboard uses; see res/drawable/ph_*.xml. */
object Sym {
    val acUnit = Ic(R.drawable.ph_snowflake, R.drawable.ph_snowflake_fill)
    val add = Ic(R.drawable.ph_plus, R.drawable.ph_plus_fill)
    val air = Ic(R.drawable.ph_wind, R.drawable.ph_wind_fill)
    val apartment = Ic(R.drawable.ph_buildings, R.drawable.ph_buildings_fill)
    val arrowBack = Ic(R.drawable.ph_arrow_left, R.drawable.ph_arrow_left_fill)
    val autoAwesome = Ic(R.drawable.ph_sparkle, R.drawable.ph_sparkle_fill)
    val balcony = Ic(R.drawable.ph_plant, R.drawable.ph_plant_fill)
    val bathtub = Ic(R.drawable.ph_bathtub, R.drawable.ph_bathtub_fill)
    val batteryFull = Ic(R.drawable.ph_battery_full, R.drawable.ph_battery_full_fill)
    val bed = Ic(R.drawable.ph_bed, R.drawable.ph_bed_fill)
    val bedtime = Ic(R.drawable.ph_moon_stars, R.drawable.ph_moon_stars_fill)
    val blinds = Ic(R.drawable.ph_rows, R.drawable.ph_rows_fill)
    val bolt = Ic(R.drawable.ph_lightning, R.drawable.ph_lightning_fill)
    val celebration = Ic(R.drawable.ph_confetti, R.drawable.ph_confetti_fill)
    val check = Ic(R.drawable.ph_check, R.drawable.ph_check_fill)
    val checkroom = Ic(R.drawable.ph_t_shirt, R.drawable.ph_t_shirt_fill)
    val chevronRight = Ic(R.drawable.ph_caret_right, R.drawable.ph_caret_right_fill)
    val close = Ic(R.drawable.ph_x, R.drawable.ph_x_fill)
    val co2 = Ic(R.drawable.ph_cloud, R.drawable.ph_cloud_fill)
    val coffeeMaker = Ic(R.drawable.ph_coffee, R.drawable.ph_coffee_fill)
    val contrast = Ic(R.drawable.ph_circle_half, R.drawable.ph_circle_half_fill)
    val countertops = Ic(R.drawable.ph_cooking_pot, R.drawable.ph_cooking_pot_fill)
    val curtains = Ic(R.drawable.ph_columns, R.drawable.ph_columns_fill)
    val darkMode = Ic(R.drawable.ph_moon, R.drawable.ph_moon_fill)
    val deck = Ic(R.drawable.ph_sun_horizon, R.drawable.ph_sun_horizon_fill)
    val desk = Ic(R.drawable.ph_desk, R.drawable.ph_desk_fill)
    val deviceThermostat = Ic(R.drawable.ph_thermometer_simple, R.drawable.ph_thermometer_simple_fill)
    val devicesOther = Ic(R.drawable.ph_devices, R.drawable.ph_devices_fill)
    val dining = Ic(R.drawable.ph_fork_knife, R.drawable.ph_fork_knife_fill)
    val dinnerDining = Ic(R.drawable.ph_fork_knife, R.drawable.ph_fork_knife_fill)
    val directionsWalk = Ic(R.drawable.ph_person_simple_walk, R.drawable.ph_person_simple_walk_fill)
    val dishwasherGen = Ic(R.drawable.ph_washing_machine, R.drawable.ph_washing_machine_fill)
    val doorFront = Ic(R.drawable.ph_door, R.drawable.ph_door_fill)
    val doorbell = Ic(R.drawable.ph_bell, R.drawable.ph_bell_fill)
    val electricBolt = Ic(R.drawable.ph_lightning, R.drawable.ph_lightning_fill)
    val emojiObjects = Ic(R.drawable.ph_lightbulb_filament, R.drawable.ph_lightbulb_filament_fill)
    val error = Ic(R.drawable.ph_warning_circle, R.drawable.ph_warning_circle_fill)
    val fitnessCenter = Ic(R.drawable.ph_barbell, R.drawable.ph_barbell_fill)
    val floorLamp = Ic(R.drawable.ph_lamp, R.drawable.ph_lamp_fill)
    val garage = Ic(R.drawable.ph_garage, R.drawable.ph_garage_fill)
    val garageDoor = Ic(R.drawable.ph_garage, R.drawable.ph_garage_fill)
    val heat = Ic(R.drawable.ph_fire, R.drawable.ph_fire_fill)
    val heatPump = Ic(R.drawable.ph_thermometer_hot, R.drawable.ph_thermometer_hot_fill)
    val home = Ic(R.drawable.ph_house, R.drawable.ph_house_fill)
    val keyboardArrowDown = Ic(R.drawable.ph_caret_down, R.drawable.ph_caret_down_fill)
    val keyboardArrowLeft = Ic(R.drawable.ph_caret_left, R.drawable.ph_caret_left_fill)
    val keyboardArrowRight = Ic(R.drawable.ph_caret_right, R.drawable.ph_caret_right_fill)
    val keyboardArrowUp = Ic(R.drawable.ph_caret_up, R.drawable.ph_caret_up_fill)
    val kitchen = Ic(R.drawable.ph_snowflake, R.drawable.ph_snowflake_fill)
    val lightMode = Ic(R.drawable.ph_sun, R.drawable.ph_sun_fill)
    val lightbulb = Ic(R.drawable.ph_lightbulb, R.drawable.ph_lightbulb_fill)
    val localCafe = Ic(R.drawable.ph_coffee, R.drawable.ph_coffee_fill)
    val localFireDepartment = Ic(R.drawable.ph_fire, R.drawable.ph_fire_fill)
    val localLaundryService = Ic(R.drawable.ph_washing_machine, R.drawable.ph_washing_machine_fill)
    val lock = Ic(R.drawable.ph_lock, R.drawable.ph_lock_fill)
    val lockOpen = Ic(R.drawable.ph_lock_open, R.drawable.ph_lock_open_fill)
    val logout = Ic(R.drawable.ph_sign_out, R.drawable.ph_sign_out_fill)
    val meetingRoom = Ic(R.drawable.ph_door, R.drawable.ph_door_fill)
    val microwave = Ic(R.drawable.ph_oven, R.drawable.ph_oven_fill)
    val modeFan = Ic(R.drawable.ph_fan, R.drawable.ph_fan_fill)
    val motionSensorActive = Ic(R.drawable.ph_person_simple_walk, R.drawable.ph_person_simple_walk_fill)
    val movie = Ic(R.drawable.ph_film_slate, R.drawable.ph_film_slate_fill)
    val musicNote = Ic(R.drawable.ph_music_notes, R.drawable.ph_music_notes_fill)
    val nightsStay = Ic(R.drawable.ph_moon, R.drawable.ph_moon_fill)
    val outlet = Ic(R.drawable.ph_plug, R.drawable.ph_plug_fill)
    val pause = Ic(R.drawable.ph_pause, R.drawable.ph_pause_fill)
    val pets = Ic(R.drawable.ph_paw_print, R.drawable.ph_paw_print_fill)
    val play = Ic(R.drawable.ph_play, R.drawable.ph_play_fill)
    val playArrow = Ic(R.drawable.ph_play, R.drawable.ph_play_fill)
    val playCircle = Ic(R.drawable.ph_play_circle, R.drawable.ph_play_circle_fill)
    val pool = Ic(R.drawable.ph_swimming_pool, R.drawable.ph_swimming_pool_fill)
    val power = Ic(R.drawable.ph_power, R.drawable.ph_power_fill)
    val powerSettingsNew = Ic(R.drawable.ph_power, R.drawable.ph_power_fill)
    val remove = Ic(R.drawable.ph_minus, R.drawable.ph_minus_fill)
    val restaurant = Ic(R.drawable.ph_cooking_pot, R.drawable.ph_cooking_pot_fill)
    val rollerShades = Ic(R.drawable.ph_rows, R.drawable.ph_rows_fill)
    val router = Ic(R.drawable.ph_wifi_high, R.drawable.ph_wifi_high_fill)
    val sensorDoor = Ic(R.drawable.ph_door_open, R.drawable.ph_door_open_fill)
    val sensors = Ic(R.drawable.ph_broadcast, R.drawable.ph_broadcast_fill)
    val settings = Ic(R.drawable.ph_gear_six, R.drawable.ph_gear_six_fill)
    val shieldLock = Ic(R.drawable.ph_shield_check, R.drawable.ph_shield_check_fill)
    val skipNext = Ic(R.drawable.ph_skip_forward, R.drawable.ph_skip_forward_fill)
    val skipPrevious = Ic(R.drawable.ph_skip_back, R.drawable.ph_skip_back_fill)
    val smartButton = Ic(R.drawable.ph_hand_tap, R.drawable.ph_hand_tap_fill)
    val spa = Ic(R.drawable.ph_flower_lotus, R.drawable.ph_flower_lotus_fill)
    val spaceDashboard = Ic(R.drawable.ph_squares_four, R.drawable.ph_squares_four_fill)
    val speaker = Ic(R.drawable.ph_speaker_hifi, R.drawable.ph_speaker_hifi_fill)
    val sportsEsports = Ic(R.drawable.ph_game_controller, R.drawable.ph_game_controller_fill)
    val stairs = Ic(R.drawable.ph_stairs, R.drawable.ph_stairs_fill)
    val sunny = Ic(R.drawable.ph_sun, R.drawable.ph_sun_fill)
    val thermostat = Ic(R.drawable.ph_thermometer, R.drawable.ph_thermometer_fill)
    val toys = Ic(R.drawable.ph_baby, R.drawable.ph_baby_fill)
    val tv = Ic(R.drawable.ph_television, R.drawable.ph_television_fill)
    val vacuum = Ic(R.drawable.ph_robot, R.drawable.ph_robot_fill)
    val videocam = Ic(R.drawable.ph_video_camera, R.drawable.ph_video_camera_fill)
    val visibilityOff = Ic(R.drawable.ph_eye_slash, R.drawable.ph_eye_slash_fill)
    val volumeDown = Ic(R.drawable.ph_speaker_low, R.drawable.ph_speaker_low_fill)
    val volumeOff = Ic(R.drawable.ph_speaker_x, R.drawable.ph_speaker_x_fill)
    val volumeUp = Ic(R.drawable.ph_speaker_high, R.drawable.ph_speaker_high_fill)
    val waterDrop = Ic(R.drawable.ph_drop, R.drawable.ph_drop_fill)
    val waterHeater = Ic(R.drawable.ph_drop_half_bottom, R.drawable.ph_drop_half_bottom_fill)
    val wbSunny = Ic(R.drawable.ph_sun, R.drawable.ph_sun_fill)
    val wbTwilight = Ic(R.drawable.ph_sun_horizon, R.drawable.ph_sun_horizon_fill)
    val wc = Ic(R.drawable.ph_toilet, R.drawable.ph_toilet_fill)
    val weekend = Ic(R.drawable.ph_couch, R.drawable.ph_couch_fill)
    val wifiOff = Ic(R.drawable.ph_wifi_slash, R.drawable.ph_wifi_slash_fill)
    val yard = Ic(R.drawable.ph_plant, R.drawable.ph_plant_fill)
    val book = Ic(R.drawable.ph_book_open, R.drawable.ph_book_open_fill)
    val image = Ic(R.drawable.ph_image, R.drawable.ph_image_fill)
    val palette = Ic(R.drawable.ph_palette, R.drawable.ph_palette_fill)
    val sliders = Ic(R.drawable.ph_sliders_horizontal, R.drawable.ph_sliders_horizontal_fill)
    val remote = Ic(R.drawable.ph_dots_nine, R.drawable.ph_dots_nine_fill)
    val vinyl = Ic(R.drawable.ph_vinyl_record, R.drawable.ph_vinyl_record_fill)
    val playlist = Ic(R.drawable.ph_playlist, R.drawable.ph_playlist_fill)
    val shuffle = Ic(R.drawable.ph_shuffle, R.drawable.ph_shuffle_fill)
    val repeat = Ic(R.drawable.ph_repeat, R.drawable.ph_repeat_fill)
    val backKey = Ic(R.drawable.ph_arrow_u_up_left, R.drawable.ph_arrow_u_up_left_fill)
    val apps = Ic(R.drawable.ph_squares_four, R.drawable.ph_squares_four_fill)
    val airplay = Ic(R.drawable.ph_airplay, R.drawable.ph_airplay_fill)
    val lampPendant = Ic(R.drawable.ph_lamp_pendant, R.drawable.ph_lamp_pendant_fill)
    val search = Ic(R.drawable.ph_magnifying_glass, R.drawable.ph_magnifying_glass_fill)
    val musicNotes = Ic(R.drawable.ph_music_notes, R.drawable.ph_music_notes_fill)
    val playPause = Ic(R.drawable.ph_play_pause, R.drawable.ph_play_pause_fill)
    val speakerHifi = Ic(R.drawable.ph_speaker_hifi, R.drawable.ph_speaker_hifi_fill)
    val brightness = Ic(R.drawable.ph_sun_dim, R.drawable.ph_sun_dim_fill)
    val thermometerSimple = Ic(R.drawable.ph_thermometer_simple, R.drawable.ph_thermometer_simple_fill)
}

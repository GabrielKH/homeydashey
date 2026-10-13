package app.homeydash

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.WindowManager
import app.homeydash.data.DisplaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The kitchen screen's power and kiosk behaviour.
 *
 * Dimming: after the chosen minutes without a touch the screen goes dark (brightness to
 * the minimum under a black overlay with the clock) instead of sleeping, so it can wake
 * at once: on a touch, or when the proximity sensor sees someone come close. The first
 * touch only wakes the screen; it doesn't press what is under the finger.
 *
 * Kiosk: pins the app (Android's screen pinning, or full lock task when the app is a
 * device owner) and lets it be chosen as the tablet's home app.
 */
class ScreenControl(private val activity: Activity, private val display: DisplaySettings, private val scope: CoroutineScope) {

    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximity: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    /** Whether this tablet has a proximity sensor at all. */
    val hasProximity: Boolean get() = proximity != null

    private val _dimmed = MutableStateFlow(false)
    val dimmed: StateFlow<Boolean> = _dimmed.asStateFlow()

    private var lastActivity = SystemClock.elapsedRealtime()
    private var listening = false

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = proximity ?: return
            // Most sensors report 0 for near and their maximum range for far.
            if (event.values.firstOrNull()?.let { it < sensor.maximumRange } == true) wake()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        scope.launch {
            while (true) {
                val minutes = display.dimAfter.value
                if (minutes > 0 && !_dimmed.value && SystemClock.elapsedRealtime() - lastActivity > minutes * 60_000L) dim()
                delay(5_000)
            }
        }
        scope.launch {
            combine(display.dimAfter, display.wakeOnProximity) { minutes, wake -> minutes > 0 && wake }.collect { listen(it) }
        }
        scope.launch {
            display.kioskSaved.distinctUntilChanged().collect { applyKiosk(it) }
        }
    }

    fun stop() {
        listen(false)
    }

    /** Called for every touch. Returns true when the touch only woke the screen. */
    fun onTouch(): Boolean {
        lastActivity = SystemClock.elapsedRealtime()
        if (_dimmed.value) {
            wake()
            return true
        }
        return false
    }

    fun wake() {
        lastActivity = SystemClock.elapsedRealtime()
        if (!_dimmed.value) return
        _dimmed.value = false
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun dim() {
        _dimmed.value = true
        setBrightness(0.01f)
    }

    private fun setBrightness(value: Float) {
        activity.runOnUiThread {
            val attributes = activity.window.attributes
            attributes.screenBrightness = value
            activity.window.attributes = attributes
        }
    }

    private fun listen(on: Boolean) {
        val sensor = proximity ?: return
        if (on == listening) return
        listening = on
        if (on) {
            sensors.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sensors.unregisterListener(proximityListener)
        }
    }

    /** Pins the app again when it comes back to the front in kiosk mode. */
    fun onResume() {
        if (display.kiosk.value) pin(true)
    }

    private fun applyKiosk(on: Boolean) {
        val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            val component = ComponentName(activity, "app.homeydash.HomeScreen")
            if (activity.packageManager.getComponentEnabledSetting(component) == wanted) return@runCatching
            activity.packageManager.setComponentEnabledSetting(
                ComponentName(activity, "app.homeydash.HomeScreen"),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        pin(on)
    }

    private fun pin(on: Boolean) {
        val manager = activity.getSystemService(ActivityManager::class.java) ?: return
        val pinned = manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        activity.runOnUiThread {
            runCatching {
                if (on && !pinned) activity.startLockTask()
                if (!on && pinned) activity.stopLockTask()
            }
        }
    }
}

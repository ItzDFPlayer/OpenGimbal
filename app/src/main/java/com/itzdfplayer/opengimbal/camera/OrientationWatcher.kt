package com.itzdfplayer.opengimbal.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Feeds the phone's own orientation into a [FlipWatch] and reports flips.
 *
 * The device sensors rather than the gimbal's angle packets: a flip is a rotation of
 * the phone in the world, which the accelerometer sees directly, and the gravity
 * sensor is available on every phone, needs no permission and no protocol decoding.
 *
 * A gravity sensor at the default rate costs essentially nothing and never holds the
 * CPU awake, so this is simply kept registered while the feature is switched on.
 * [SensorManager] delivers to the calling thread's looper, so construct and start it
 * from the main thread.
 */
class OrientationWatcher(
    context: Context,
    private val onFlip: () -> Unit,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val watch = FlipWatch()

    /** Gravity is the ideal sensor; the raw accelerometer is close enough as a fallback. */
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listening = false

    /** False when the device has no accelerometer, in which case flips cannot be seen. */
    val supported: Boolean get() = sensor != null

    /** How far the phone currently is from the orientation the reference was taken in. */
    val angleFromReference: Float get() = watch.angleFromReference

    /** Starts listening. Returns false when there is nothing to listen to. */
    fun start(): Boolean {
        val target = sensor ?: return false
        if (listening) return true
        listening = sensors?.registerListener(this, target, SensorManager.SENSOR_DELAY_NORMAL) == true
        return listening
    }

    fun stop() {
        if (!listening) return
        runCatching { sensors?.unregisterListener(this) }
        listening = false
    }

    /**
     * Forgets the reference orientation, so the next reading defines it. Called when a
     * recording starts, because "the right way up" is whatever it was at that moment.
     */
    fun rearm() = watch.reset()

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (watch.onGravity(values[0], values[1], values[2], SystemClock.elapsedRealtime())) {
            onFlip()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

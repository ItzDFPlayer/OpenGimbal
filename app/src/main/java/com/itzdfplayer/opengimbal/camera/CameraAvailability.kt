package com.itzdfplayer.opengimbal.camera

/**
 * Tracks which cameras the system reports as in use.
 *
 * `CameraManager.AvailabilityCallback` reports a camera as *unavailable* while a
 * client is holding it. This app never opens a camera itself, so "unavailable"
 * means another app — in practice, a camera app — is using it. That is the signal
 * behind the "only while a camera app is running" mapping option.
 *
 * Deliberately free of Android types so the rule can be unit tested.
 */
class CameraAvailability {

    /** Every camera id we have heard about, so we can say "2 of 4". */
    private val known = linkedSetOf<String>()

    /** Camera ids currently reported as unavailable = held by another app. */
    private val unavailable = linkedSetOf<String>()

    /**
     * True when at least one camera is held by another app.
     *
     * Note the caveat: a device that permanently withholds a camera from
     * third-party apps would keep this true. [unavailableIds] is surfaced in
     * Settings so that case is diagnosable rather than mysterious.
     */
    val inUse: Boolean get() = unavailable.isNotEmpty()

    val unavailableIds: List<String> get() = unavailable.toList()

    val knownCount: Int get() = known.size

    fun onAvailable(cameraId: String) {
        known.add(cameraId)
        unavailable.remove(cameraId)
    }

    fun onUnavailable(cameraId: String) {
        known.add(cameraId)
        unavailable.add(cameraId)
    }

    fun reset() {
        known.clear()
        unavailable.clear()
    }
}

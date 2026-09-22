package com.itzdfplayer.opengimbal.gimbal

/**
 * Device identification, mirrored from Gimbal Show's `assets/adapter_device_list.json`.
 *
 * The official app decides *which* gimbal is connected purely from the advertised
 * Bluetooth name, using a plain `startsWith` test against this table (see
 * `com.honji.base.utils.app.AdapterDeviceManager#getAdapterDevice`).
 *
 * The table is kept in the official app's list order for reference; [detect] itself
 * resolves the longest matching prefix, so the order does not affect the result.
 */
object DeviceTable {

    data class Entry(val namePrefix: String, val model: GimbalModel)

    val entries: List<Entry> = listOf(
        Entry("EC Gimbal AI", GimbalModel.GIMBAL_AI),
        Entry("LINBER AI", GimbalModel.GIMBAL_AI),
        Entry("TNW AI", GimbalModel.GIMBAL_AI),
        Entry("Gimbal AI", GimbalModel.GIMBAL_AI),
        Entry("YESIDO SF18-M01", GimbalModel.M01),
        Entry("SelfieShow-M01", GimbalModel.M01),
        Entry("HQ6-M01", GimbalModel.M01),
        Entry("Proove Axis-M01", GimbalModel.M01),
        Entry("HALIMOMO-M01", GimbalModel.M01),
        Entry("XO-SS17-M01", GimbalModel.M01),
        Entry("PEI-M01", GimbalModel.M01),
        Entry("MM-WDQ01-M01", GimbalModel.M01),
        Entry("hoco K24-M01", GimbalModel.M01),
        Entry("LENYES LPH105-M01", GimbalModel.M01),
        Entry("EP-T201-M01", GimbalModel.M01),
        Entry("OR-1681-M01", GimbalModel.M01),
        Entry("SEL-6830 M01", GimbalModel.M01),
        Entry("SLF-10-M01", GimbalModel.M01),
        Entry("M01", GimbalModel.M01),
        Entry("M07", GimbalModel.M07),
        Entry("TNW M0X", GimbalModel.M0X),
        Entry("BPS01B-M0X", GimbalModel.M0X),
        Entry("LENYES LPH113-M03", GimbalModel.M0X),
        Entry("LENYES LPH113-M0X", GimbalModel.M0X),
        Entry("G-01-M0X", GimbalModel.M0X),
        Entry("SelfieShow-M0X", GimbalModel.M0X),
        Entry("PEI-M0X", GimbalModel.M0X),
        Entry("XSS-GS02 3IN1 M0X", GimbalModel.M0X),
        Entry("Selfieshow-M0X", GimbalModel.M0X),
        Entry("Viplatina VF22-M0X", GimbalModel.M0X),
        Entry("OR-1682-M0X", GimbalModel.M0X),
        Entry("ZJ07 M0X", GimbalModel.M0X),
        Entry("AX05-M0X", GimbalModel.M0X),
        Entry("Igoma L10-M0X", GimbalModel.M0X),
        Entry("B0X", GimbalModel.M0X),
        Entry("M0X", GimbalModel.M0X),
        Entry("TOKQI PK01", GimbalModel.PK01),
        Entry("SelfieShow", GimbalModel.Q09),
        Entry("EZ-I13", GimbalModel.Q09),
        Entry("Spacetronik01", GimbalModel.Q09),
        Entry("CiYatt-AI", GimbalModel.Q09),
        Entry("Igoma L11-AI", GimbalModel.Q09),
        Entry("EP-T212 AI", GimbalModel.Q09),
        Entry("Q09", GimbalModel.Q09),
        Entry("L18", GimbalModel.Q18),
        Entry("SelfieShow-Q18", GimbalModel.Q18),
        Entry("ewtto ET-N0511-Q18", GimbalModel.Q18),
        Entry("EP-T202-Q18", GimbalModel.Q18),
        Entry("M40-Q18", GimbalModel.Q18),
        Entry("FG-AX04-Q18", GimbalModel.Q18),
        Entry("Q18", GimbalModel.Q18),
        Entry("Gimbal Pro", GimbalModel.Q18),
    )

    /**
     * Returns the matching table entry, or `null` when the name is unknown.
     *
     * The longest matching prefix wins. The official app instead returns the first
     * match in list order, which mis-detects e.g. "SelfieShow-Q18" as a Q09 because
     * the generic "SelfieShow" entry is listed earlier.
     */
    fun detect(bluetoothName: String?): Entry? {
        if (bluetoothName.isNullOrEmpty()) return null
        return entries
            .filter { bluetoothName.startsWith(it.namePrefix) }
            .maxByOrNull { it.namePrefix.length }
    }

    fun modelOf(bluetoothName: String?): GimbalModel =
        detect(bluetoothName)?.model ?: GimbalModel.NONE

    /** `true` when the name looks like a gimbal we know how to talk to. */
    fun isSupported(bluetoothName: String?): Boolean {
        val entry = detect(bluetoothName) ?: return false
        return entry.model.ble
    }
}

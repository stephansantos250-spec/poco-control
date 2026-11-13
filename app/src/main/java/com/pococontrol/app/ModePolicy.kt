package com.pococontrol.app

/** Pure policy only; actual changes still require Android permissions and confirmation. */
internal object ModePolicy {
    fun target(mode: String, saved: List<String>): List<String> = when (mode) {
        "wifi" -> listOf("1", "0", "1")
        "poco" -> listOf("0", "0", "0")
        "normal" -> saved
        else -> throw IllegalArgumentException("Unknown mode")
    }
    fun order(mode: String): List<Int> = when (mode) {
        "wifi" -> listOf(1)
        "poco" -> listOf(1, 2, 0)
        "normal" -> listOf(1, 2, 0)
        else -> throw IllegalArgumentException("Unknown mode")
    }
    // Fixed commands only. Check wireless after a settling interval; retry once
    // before disabling developer options. Stop if the setting cannot be confirmed.
    val fullDisableScript: String = """
        /system/bin/settings put global adb_enabled 0 || exit 11
        [ "${'$'}(/system/bin/settings get global adb_enabled)" = "0" ] || exit 11
        /system/bin/settings put global adb_wifi_enabled 0 || exit 12
        sleep 1
        if [ "${'$'}(/system/bin/settings get global adb_wifi_enabled)" != "0" ]; then
            /system/bin/settings put global adb_wifi_enabled 0 || exit 12
            sleep 1
            [ "${'$'}(/system/bin/settings get global adb_wifi_enabled)" = "0" ] || exit 12
        fi
        /system/bin/settings put global development_settings_enabled 0 || exit 13
        printf 'OK'
    """.trimIndent()
    fun wifiPreflight(mode: String, values: List<String>): Boolean =
        mode != "wifi" || (values.size == 3 && values[0] == "1" && values[2] == "1")
}

internal fun isPairingCode(value: String): Boolean = value.length == 6 && value.all { it in '0'..'9' }

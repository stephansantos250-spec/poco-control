package com.pococontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FullModeScriptTest {
    private data class Result(val code: Int, val out: String, val writes: List<String>)
    private fun run(mode: String): Result {
        val dir = Files.createTempDirectory("poco-mode-test").toFile()
        try {
            val mock = dir.resolve("settings")
            mock.writeText("""
                #!/bin/sh
                op="${'$'}1"; key="${'$'}3"
                if [ "${'$'}op" = put ]; then
                    echo "${'$'}key" >> "${'$'}TEST_DIR/writes"
                    if [ "${'$'}key" = adb_enabled ] && [ "${'$'}TEST_MODE" = usb_fail ]; then exit 1; fi
                    if [ "${'$'}key" = adb_wifi_enabled ]; then
                        n=0; [ ! -f "${'$'}TEST_DIR/count" ] || n=${'$'}(cat "${'$'}TEST_DIR/count")
                        n=${'$'}((n+1)); echo "${'$'}n" > "${'$'}TEST_DIR/count"
                        if [ "${'$'}TEST_MODE" = wifi_always_on ] || { [ "${'$'}TEST_MODE" = wifi_retry ] && [ "${'$'}n" = 1 ]; }; then
                            echo 1 > "${'$'}TEST_DIR/${'$'}key"; exit 0
                        fi
                    fi
                    echo "${'$'}4" > "${'$'}TEST_DIR/${'$'}key"; exit 0
                fi
                if [ "${'$'}op" = get ]; then
                    if [ -f "${'$'}TEST_DIR/${'$'}key" ]; then cat "${'$'}TEST_DIR/${'$'}key"; else echo 1; fi
                    exit 0
                fi
                exit 2
            """.trimIndent())
            assertTrue(mock.setExecutable(true))
            val script = ModePolicy.fullDisableScript.replace("/system/bin/settings", "'${mock.absolutePath}'").replace("sleep 1", ":")
            val pb = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true)
            pb.environment()["TEST_DIR"] = dir.absolutePath
            pb.environment()["TEST_MODE"] = mode
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            val log = dir.resolve("writes").takeIf { it.exists() }?.readLines() ?: emptyList()
            return Result(code, out, log)
        } finally { dir.deleteRecursively() }
    }
    @Test fun shutsDownInRequestedOrder() {
        val r = run("normal")
        assertEquals(0, r.code); assertEquals("OK", r.out)
        assertEquals(listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled"), r.writes)
    }
    @Test fun retriesWirelessBeforeDeveloperIfItReactivates() {
        val r = run("wifi_retry")
        assertEquals(0, r.code)
        assertEquals(listOf("adb_enabled", "adb_wifi_enabled", "adb_wifi_enabled", "development_settings_enabled"), r.writes)
    }
    @Test fun stopsBeforeDeveloperIfWirelessWontStayOff() {
        val r = run("wifi_always_on")
        assertEquals(12, r.code)
        assertEquals(listOf("adb_enabled", "adb_wifi_enabled", "adb_wifi_enabled"), r.writes)
    }
    @Test fun stopsIfUsbCannotBeDisabled() {
        val r = run("usb_fail")
        assertEquals(11, r.code); assertEquals(listOf("adb_enabled"), r.writes)
    }
}

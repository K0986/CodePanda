package com.codepanda.otg.feature.deviceinfo

import com.codepanda.otg.adb.service.AdbShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects a broad snapshot of the connected device by combining `getprop`,
 * `/proc` files and a few `dumpsys` sections into human-readable groups.
 */
class DeviceInfoRepository(private val shell: AdbShell) {

    suspend fun load(): DeviceInfo = withContext(Dispatchers.IO) {
        val props = parseGetProp(shell.exec("getprop"))
        val cpuInfo = shell.exec("cat /proc/cpuinfo")
        val memInfo = shell.exec("cat /proc/meminfo")
        val battery = shell.exec("dumpsys battery")
        val size = shell.exec("wm size")
        val density = shell.exec("wm density")
        val storage = shell.exec("df -h")

        val sections = buildList {
            add(overviewSection(props))
            add(displaySection(size, density, props))
            add(cpuSection(cpuInfo, props))
            add(memorySection(memInfo))
            add(batterySection(battery))
            add(storageSection(storage))
        }.filter { it.entries.isNotEmpty() }

        val all = props.entries
            .sortedBy { it.key }
            .map { InfoEntry(it.key, it.value) }

        DeviceInfo(sections, all)
    }

    private fun overviewSection(props: Map<String, String>) = InfoSection(
        "Overview",
        listOfNotNull(
            entry("Manufacturer", props["ro.product.manufacturer"]),
            entry("Brand", props["ro.product.brand"]),
            entry("Model", props["ro.product.model"]),
            entry("Device", props["ro.product.device"]),
            entry("Android version", props["ro.build.version.release"]),
            entry("SDK level", props["ro.build.version.sdk"]),
            entry("Security patch", props["ro.build.version.security_patch"]),
            entry("Build ID", props["ro.build.display.id"]),
            entry("Build fingerprint", props["ro.build.fingerprint"]),
            entry("Bootloader", props["ro.bootloader"]),
            entry("Serial", props["ro.serialno"]),
            entry("Primary ABI", props["ro.product.cpu.abi"]),
            entry("Supported ABIs", props["ro.product.cpu.abilist"]),
        ),
    )

    private fun displaySection(size: String, density: String, props: Map<String, String>) =
        InfoSection(
            "Display",
            listOfNotNull(
                entry("Resolution", firstMatch(size, Regex("""Physical size:\s*(\S+)"""))
                    ?: firstMatch(size, Regex("""Override size:\s*(\S+)"""))),
                entry("Density", firstMatch(density, Regex("""Physical density:\s*(\d+)"""))),
                entry("SF density", props["ro.sf.lcd_density"]),
            ),
        )

    private fun cpuSection(cpuInfo: String, props: Map<String, String>): InfoSection {
        val cores = cpuInfo.lineSequence().count { it.startsWith("processor") }
        val hardware = firstMatch(cpuInfo, Regex("""Hardware\s*:\s*(.+)"""))
        val features = firstMatch(cpuInfo, Regex("""Features\s*:\s*(.+)"""))
        return InfoSection(
            "CPU & SoC",
            listOfNotNull(
                entry("Cores", cores.takeIf { it > 0 }?.toString()),
                entry("Hardware", hardware),
                entry("Chipset", props["ro.board.platform"]),
                entry("Board", props["ro.product.board"]),
                entry("Features", features),
            ),
        )
    }

    private fun memorySection(memInfo: String): InfoSection {
        val total = firstMatch(memInfo, Regex("""MemTotal:\s*(\d+)\s*kB"""))?.toLongOrNull()
        val available = firstMatch(memInfo, Regex("""MemAvailable:\s*(\d+)\s*kB"""))?.toLongOrNull()
        return InfoSection(
            "Memory",
            listOfNotNull(
                entry("Total RAM", total?.let { humanKb(it) }),
                entry("Available RAM", available?.let { humanKb(it) }),
            ),
        )
    }

    private fun batterySection(battery: String) = InfoSection(
        "Battery",
        listOfNotNull(
            entry("Level", firstMatch(battery, Regex("""level:\s*(\d+)"""))?.let { "$it%" }),
            entry("Status", batteryStatus(firstMatch(battery, Regex("""status:\s*(\d+)""")))),
            entry("Health", batteryHealth(firstMatch(battery, Regex("""health:\s*(\d+)""")))),
            entry("Temperature", firstMatch(battery, Regex("""temperature:\s*(\d+)"""))?.let {
                "%.1f°C".format(it.toInt() / 10.0)
            }),
            entry("Voltage", firstMatch(battery, Regex("""voltage:\s*(\d+)"""))?.let { "$it mV" }),
            entry("Technology", firstMatch(battery, Regex("""technology:\s*(.+)"""))),
        ),
    )

    private fun storageSection(df: String): InfoSection {
        val entries = df.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val cols = line.trim().split(Regex("""\s+"""))
                if (cols.size >= 6 &&
                    (cols[5].startsWith("/data") || cols[5] == "/storage/emulated" || cols[5] == "/")
                ) {
                    InfoEntry(cols[5], "${cols[2]} used of ${cols[1]} (${cols[4]})")
                } else null
            }
            .distinctBy { it.label }
            .toList()
        return InfoSection("Storage", entries)
    }

    // ---- helpers -----------------------------------------------------------

    private fun entry(label: String, value: String?): InfoEntry? =
        value?.trim()?.takeIf { it.isNotBlank() }?.let { InfoEntry(label, it) }

    private fun parseGetProp(output: String): Map<String, String> {
        val regex = Regex("""\[(.+?)]:\s*\[(.*?)]""")
        val map = LinkedHashMap<String, String>()
        output.lineSequence().forEach { line ->
            regex.find(line)?.let { map[it.groupValues[1]] = it.groupValues[2] }
        }
        return map
    }

    private fun firstMatch(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)?.trim()

    private fun humanKb(kb: Long): String {
        val mb = kb / 1024.0
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    private fun batteryStatus(code: String?): String? = when (code) {
        "1" -> "Unknown"; "2" -> "Charging"; "3" -> "Discharging"
        "4" -> "Not charging"; "5" -> "Full"; else -> code
    }

    private fun batteryHealth(code: String?): String? = when (code) {
        "1" -> "Unknown"; "2" -> "Good"; "3" -> "Overheat"; "4" -> "Dead"
        "5" -> "Over voltage"; "6" -> "Failure"; "7" -> "Cold"; else -> code
    }
}

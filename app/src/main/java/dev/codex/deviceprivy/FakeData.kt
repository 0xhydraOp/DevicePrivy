package dev.codex.deviceprivy

import java.util.UUID
import kotlin.random.Random

object FakeData {
    private data class CarrierProfile(
        val name: String,
        val mccMnc: String,
        val countryIso: String,
        val phonePrefix: String,
        val locale: String,
        val timezone: String
    )

    private data class HardwareProfile(
        val width: String,
        val height: String,
        val density: String,
        val glVendor: String,
        val glRenderer: String,
        val batteryScale: String
    )

    private val carriers = listOf(
        CarrierProfile("Verizon", "311480", "us", "+1", "en-US", "America/New_York"),
        CarrierProfile("T-Mobile", "310260", "us", "+1", "en-US", "America/Los_Angeles"),
        CarrierProfile("AT&T", "310410", "us", "+1", "en-US", "America/Chicago"),
        CarrierProfile("Jio", "405840", "in", "+91", "en-IN", "Asia/Kolkata"),
        CarrierProfile("Airtel", "40410", "in", "+91", "en-IN", "Asia/Kolkata"),
        CarrierProfile("Vodafone", "23415", "gb", "+44", "en-GB", "Europe/London"),
        CarrierProfile("Orange", "20801", "fr", "+33", "fr-FR", "Europe/Paris"),
        CarrierProfile("Telstra", "50501", "au", "+61", "en-AU", "Australia/Sydney")
    )

    // ========== Randomized Hardware Pools ==========
    // Every call to hardwareFor() randomly picks from these pools, so even the
    // same device model selected twice will get different hardware values.

    private val resolutionPool = listOf(
        Triple("1440", "3120", "560"),
        Triple("1440", "3200", "520"),
        Triple("1440", "3168", "520"),
        Triple("1440", "2880", "560"),
        Triple("1344", "2992", "480"),
        Triple("1264", "2780", "460"),
        Triple("1220", "2712", "460"),
        Triple("1224", "2700", "440"),
        Triple("1280", "2800", "460"),
        Triple("1080", "2412", "420"),
        Triple("1080", "2400", "440"),
        Triple("1080", "2520", "450"),
        Triple("1080", "2340", "420"),
        Triple("1116", "2480", "440"),
        Triple("1600", "2560", "320"),
    )

    private val gpuPool = listOf(
        "Qualcomm" to "Adreno (TM) 750",
        "Qualcomm" to "Adreno (TM) 740",
        "Qualcomm" to "Adreno (TM) 730",
        "ARM" to "Mali-G715 Immortalis",
        "ARM" to "Mali-G720 MP12",
        "ARM" to "Mali-G78",
        "ARM" to "Mali-G77",
    )

    // Battery scale is still manufacturer-based (real hardware characteristic),
    // but everything else is randomized per call.
    private fun hardwareFor(device: DeviceEntry): HardwareProfile {
        val manu = device.manufacturer.lowercase()
        val scale = when {
            "samsung" in manu || "xiaomi" in manu || "oppo" in manu || "realme" in manu -> "1000"
            else -> "100"
        }
        val (width, height, density) = resolutionPool.random()
        val (glVendor, glRenderer) = gpuPool.random()
        return HardwareProfile(width, height, density, glVendor, glRenderer, scale)
    }

    fun generateValidIMEI(): String {
        val tac = listOf("35", "86", "44", "49", "01").random()
        val body = buildString {
            append(tac)
            repeat(12) { append((0..9).random()) }
        }
        val imei14 = if (body.startsWith("0")) "1" + body.substring(1) else body
        val checkDigit = calculateLuhnCheckDigit(imei14)
        return imei14 + checkDigit
    }

    private fun calculateLuhnCheckDigit(imei: String): Int {
        var sum = 0
        for (i in imei.indices.reversed()) {
            var n = imei[i] - '0'
            if ((imei.length - i) % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return (10 - (sum % 10)) % 10
    }

    fun randomMac(): String = buildString {
        val hex = "0123456789ABCDEF"
        repeat(6) {
            if (it > 0) append(":")
            if (it == 0) {
                append(hex.random())
                append("26AE".random())
            } else {
                append(hex.random())
                append(hex.random())
            }
        }
    }

    fun randomHardwareId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    fun randomAndroidId(): String = buildString {
        val hex = "0123456789abcdef"
        repeat(16) { append(hex.random()) }
    }
    fun randomSimSerial(): String = buildString { repeat(20) { append((0..9).random()) } }
    fun randomSimSubId(): String = (1..9999).random().toString()
    fun randomMobileNo(prefix: String = "+1"): String = buildString {
        append(prefix)
        repeat(10) { append((0..9).random()) }
    }
    fun randomMediaDrmId(): String = UUID.randomUUID().toString()
    fun randomSimOperator(): String = listOf("T-Mobile", "Verizon", "AT&T", "Vodafone", "Orange", "Jio", "Airtel", "O2").random()
    fun randomMccMnc(): String = listOf("310410", "311480", "23415", "20404", "50501", "40410", "405840").random()
    fun randomSsid(): String = listOf("Home_WiFi_", "TP-Link_", "ASUS_", "Linksys_", "NETGEAR_").random() + buildString {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        repeat(5) { append(chars.random()) }
    }
    fun randomIpAddress(): String = "192.168.${(0..255).random()}.${(1..254).random()}"

    fun randomLat(): String = String.format(java.util.Locale.US, "%.6f", Random.nextDouble(-90.0, 90.0))
    fun randomLon(): String = String.format(java.util.Locale.US, "%.6f", Random.nextDouble(-180.0, 180.0))
    fun randomResolution(): Pair<String, String> {
        val (w, h, _) = resolutionPool.random()
        return w to h
    }
    fun randomDensity(): String = resolutionPool.random().third

    fun randomAaid(): String = UUID.randomUUID().toString()
    fun randomBuildId(): String = buildString {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        repeat(6) { append(chars.random()) }
    }

    fun randomFingerprint(device: DeviceEntry, androidVersion: String, buildId: String): String {
        return "${device.manufacturer}/${device.product}/${device.codename}:$androidVersion/$buildId/${(1000000..9999999).random()}:user/release-keys"
    }

    fun boardFor(device: DeviceEntry): String {
        return device.codename.lowercase().replace(Regex("[^a-z0-9._-]"), "")
    }

    fun randomGsfId(): String = buildString {
        val hex = "0123456789abcdef"
        repeat(16) { append(hex.random()) }
    }
    fun randomMeid(): String = buildString { repeat(14) { append((0..9).random()) } }
    fun randomGlRenderer(): String = gpuPool.random().second
    fun randomGlVendor(): String = gpuPool.random().first
    fun randomBatteryLevel(): String = (1..100).random().toString()

    fun generateAll(): Map<String, String> {
        val device = DeviceProfiles.entries.random()
        val carrier = carriers.random()
        val hardware = hardwareFor(device)
        val androidVersion = "15"
        val buildId = randomBuildId()
        return mapOf(
            "imei" to generateValidIMEI(),
            "meid" to randomMeid(),
            "gsf_id" to randomGsfId(),
            "hardware_id" to randomHardwareId(),
            "mac_address" to randomMac(),
            "mac_bssid" to randomMac(),
            "mac_ssid" to randomSsid(),
            "bluetooth_mac" to randomMac(),
            "android_id" to randomAndroidId(),
            "sim_serial" to randomSimSerial(),
            "sim_sub_id" to randomSimSubId(),
            "mobile_no" to randomMobileNo(carrier.phonePrefix),
            "media_drm_id" to randomMediaDrmId(),
            "sim_operator" to carrier.name,
            "network_operator" to carrier.mccMnc,
            "country_iso" to carrier.countryIso,
            "locale" to carrier.locale,
            "timezone" to carrier.timezone,
            "ip_address" to randomIpAddress(),
            "manufacturer" to device.manufacturer,
            "model" to device.model,
            "brand" to device.manufacturer,
            "device" to device.codename,
            "product" to device.product,
            "board" to boardFor(device),
            "device_name" to device.model,
            "build_id" to buildId,
            "android_version" to androidVersion,
            "aaid" to randomAaid(),
            "fingerprint" to randomFingerprint(device, androidVersion, buildId),
            "latitude" to randomLat(),
            "longitude" to randomLon(),
            "screen_width" to hardware.width,
            "screen_height" to hardware.height,
            "screen_density" to hardware.density,
            "user_agent" to "Mozilla/5.0 (Linux; Android $androidVersion; ${device.model} Build/$buildId) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "gl_renderer" to hardware.glRenderer,
            "gl_vendor" to hardware.glVendor,
            "battery_level" to randomBatteryLevel(),
            "battery_scale" to hardware.batteryScale
        )
    }
}

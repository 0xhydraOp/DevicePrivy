package dev.codex.deviceprivy

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.location.Location
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val MODULE_PACKAGE = "dev.codex.deviceprivy"
    private val PREFS_NAME = "device_privy_prefs"
    private val PROVIDER_URI = Uri.parse("content://$MODULE_PACKAGE.provider")
    
    private var debugEnabled = false
    private val cachedValues = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var dataFetched = false
    private var fetchAttempts = 0
    private var prefs: XSharedPreferences? = null
    @Volatile private var appContext: Context? = null
    
    private var lastSysPropRefresh = 0L
    private var lastPropPollTime = 0L
    private val PROP_POLL_INTERVAL_MS = 2000L
    private var dataFromUserPrefs = false  // true when data came from XSharedPreferences (user's saved config)
    private var deferredAntiXposed = false // install anti-detection hooks after app init

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("DevicePrivy: initZygote started")
        initPrefs()
        fetchData()
        if (cachedValues.isEmpty()) {
            XposedBridge.log("DevicePrivy: initZygote - no prefs data, generating fake defaults")
            cachedValues.putAll(FakeData.generateAll())
        }
        if (!dataFetched) dataFetched = true
        if (dataFromUserPrefs) writeSharedFile()  // only main zygote with real prefs
        writeToSystemProperties()
        lastSysPropRefresh = System.currentTimeMillis()
        debugEnabled = cachedValues["setting_debug_log"] == "true"
        XposedBridge.log("DevicePrivy: initZygote complete, ${cachedValues.size} values cached")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                XposedHelpers.findAndHookMethod(
                    MainActivity::class.java.name, lpparam.classLoader, "isModuleActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (e: Throwable) {}
            return
        }

        if (lpparam.packageName == "android" || lpparam.packageName == "com.android.systemui") {
            return
        }

        XposedBridge.log("DevicePrivy: [${lpparam.packageName}] Loading module hooks")

        // Always re-fetch from shared prefs so the user's saved values take priority
        dataFetched = false
        fetchData()
        
        try { hookBuildFields(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookSystemProperties(lpparam.classLoader) } catch (_: Throwable) {}
        
        try {
            val instrumentationClass = XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(instrumentationClass, "callApplicationOnCreate",
                android.app.Application::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val app = param.args[0] as? android.app.Application
                        if (app != null) {
                            appContext = app.applicationContext ?: app
                            XposedBridge.log("DevicePrivy: [${lpparam.packageName}] Application context captured, refreshing data")
                            dataFetched = false
                            fetchData(appContext)
                            if (!dataFetched) dataFetched = true
                            try { hookBuildFields(lpparam.classLoader) } catch (_: Throwable) {}
                            // Install deferred anti-detection hooks AFTER app is fully initialized
                            if (deferredAntiXposed) {
                                deferredAntiXposed = false
                                try { hookAntiXposed(lpparam.classLoader) } catch (_: Throwable) {}
                                try { hookSensors(lpparam.classLoader) } catch (_: Throwable) {}
                            }
                        }
                    }
                })
        } catch (e: Throwable) {}

        try { hookTelephony(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookWifi(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookWifiDhcp(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookPhoneStateListener(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookNetworkInterface(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookSettings(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookSharedPreferences(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookBluetooth(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookMediaDrm(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookPackageManager(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookLocation(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookDisplay(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookUserAgent(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookAAID(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookGServices(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookContentResolverQueries(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookOpenGL(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookBattery(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookLocaleAndTimezone(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookJavaSystemProperties(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookLocationFused(lpparam.classLoader) } catch (_: Throwable) {}
        try { hookLocationLive(lpparam.classLoader) } catch (_: Throwable) {}
        deferredAntiXposed = true
    }

    private fun initPrefs() {
        synchronized(this) {
            prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            prefs?.makeWorldReadable()
        }
    }

    @SuppressLint("Range")
    private fun fetchData(contextHint: Context? = appContext) {
        fetchAttempts++
        
        synchronized(cachedValues) {
            // Try XSharedPreferences FIRST — this contains the user's saved config
            try {
                initPrefs()
                prefs?.let {
                    it.reload()
                    val all = it.all
                    if (all != null && all.isNotEmpty()) {
                        cachedValues.clear()
                        for ((key, value) in all) {
                            cachedValues[key] = value.toString()
                        }
                        dataFetched = true
                        dataFromUserPrefs = true
                        debugEnabled = cachedValues["setting_debug_log"] == "true"
                        XposedBridge.log("DevicePrivy: Data fetched via XSharedPreferences (count=${all.size})")
                        return
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("DevicePrivy: XSharedPreferences error: ${e.message}")
            }

            try {
                val context = contextHint ?: run {
                    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                    val activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                    if (activityThread != null) {
                        (XposedHelpers.callMethod(activityThread, "getApplication") as? Context)
                            ?: (XposedHelpers.callMethod(activityThread, "getSystemContext") as? Context)
                    } else null
                }
                if (context != null) {
                    val cr = context.contentResolver
                    cr.query(PROVIDER_URI, null, null, null, null)?.use { cursor ->
                        if (cursor.count > 0) {
                            cachedValues.clear()
                            while (cursor.moveToNext()) {
                                val key = cursor.getString(cursor.getColumnIndex("key")) ?: continue
                                val value = cursor.getString(cursor.getColumnIndex("value")) ?: ""
                                cachedValues[key] = value
                            }
                            dataFetched = true
                            debugEnabled = getValue("setting_debug_log") == "true"
                            XposedBridge.log("DevicePrivy: Data updated via ContentProvider (count=${cursor.count})")
                            return
                        }
                    }
                }
            } catch (e: Throwable) {
                if (contextHint != null) XposedBridge.log("DevicePrivy: ContentProvider update error: ${e.message}")
            }

            // Fallback: Shared file (written by main zygote, readable across processes)
            if (cachedValues.isEmpty()) {
                if (readSharedFile()) {
                    dataFetched = true
                    debugEnabled = cachedValues["setting_debug_log"] == "true"
                    return
                }
            }

            // Fallback: SystemProperties (may have stale data from initZygote)
            if (cachedValues.isEmpty()) {
                if (!readFromSystemProperties()) {
                    XposedBridge.log("DevicePrivy: All data sources failed, generating fake defaults")
                    cachedValues.putAll(FakeData.generateAll())
                }
            }
            dataFetched = true
            debugEnabled = cachedValues["setting_debug_log"] == "true"
        }
    }

    private fun getValue(key: String): String {
        if (!dataFetched) fetchData()
        val now = System.currentTimeMillis()
        // Only poll SystemProperties if data did NOT come from user's saved prefs
        if (dataFetched && !dataFromUserPrefs && now - lastPropPollTime > PROP_POLL_INTERVAL_MS) {
            lastPropPollTime = now
            try {
                val spClass = Class.forName("android.os.SystemProperties")
                val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
                val refreshed = getMethod.invoke(null, "deviceprivy.refreshed", "0") as? String ?: "0"
                val ts = refreshed.toLongOrNull() ?: 0
                if (ts > lastSysPropRefresh) {
                    readFromSystemProperties()
                    lastSysPropRefresh = ts
                }
            } catch (e: Throwable) {}
        }
        return cachedValues[key] ?: ""
    }

    private fun getLiteralOrValue(valueOrKey: String): String {
        return when (valueOrKey) {
            "REL", "user", "android-build", "release-keys", "us", "01" -> valueOrKey
            else -> getValue(valueOrKey)
        }
    }

    private fun log(msg: String) {
        if (debugEnabled) XposedBridge.log("DevicePrivy: $msg")
    }

    private fun hookMethodRet(className: String, classLoader: ClassLoader, methodName: String, retValKey: String, vararg argTypes: Any) {
        try {
            val args = arrayOf(*argTypes, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val value = getLiteralOrValue(retValKey)
                    if (value.isNotEmpty()) {
                        param.result = value
                        log("Hooked $className.$methodName -> $value")
                    }
                }
            })
            XposedHelpers.findAndHookMethod(className, classLoader, methodName, *args)
        } catch (e: Throwable) {}
    }

    private fun hookBuildFields(classLoader: ClassLoader) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            setStaticSafe(buildClass, "MANUFACTURER", "manufacturer")
            setStaticSafe(buildClass, "MODEL", "model")
            setStaticSafe(buildClass, "BRAND", "brand")
            setStaticSafe(buildClass, "DEVICE", "device")
            setStaticSafe(buildClass, "PRODUCT", "product")
            setStaticSafe(buildClass, "BOARD", "board")
            setStaticSafe(buildClass, "HARDWARE", "hardware_id")
            setStaticSafe(buildClass, "SERIAL", "hardware_id")
            setStaticSafe(buildClass, "FINGERPRINT", "fingerprint")
            setStaticSafe(buildClass, "ID", "build_id")
            setStaticSafe(buildClass, "DISPLAY", "build_id")
            setStaticSafe(buildClass, "TAGS", "release-keys")
            setStaticSafe(buildClass, "TYPE", "user")
            setStaticSafe(buildClass, "USER", "android-build")
            setStaticSafe(buildClass, "HOST", "android-build")
            setStaticSafe(buildClass, "BOOTLOADER", "build_id")
            
            val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", classLoader)
            setStaticSafe(versionClass, "RELEASE", "android_version")
            try {
                val sdkIntValue = XposedEntry.sdkVersionToInt(getValue("android_version"))
                setStaticSafe(versionClass, "SDK_INT", sdkIntValue)
            } catch (e: Throwable) {
                setStaticSafe(versionClass, "SDK_INT", 35)
            }
            setStaticSafe(versionClass, "CODENAME", "REL")
            setStaticSafe(versionClass, "INCREMENTAL", "build_id")
        } catch (e: Throwable) {}
    }

    private fun setStaticSafe(clazz: Class<*>, fieldName: String, valueKey: String) {
        try {
            val value = getLiteralOrValue(valueKey)
            if (value.isNotEmpty()) {
                XposedHelpers.setStaticObjectField(clazz, fieldName, value)
            }
        } catch (e: Throwable) {}
    }

    private fun setStaticSafe(clazz: Class<*>, fieldName: String, value: Int) {
        try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
            field.setInt(null, value)
        } catch (e: Throwable) {}
    }

    private fun hookAAID(classLoader: ClassLoader) {
        try {
            val aaidClass = XposedHelpers.findClass("com.google.android.gms.ads.identifier.AdvertisingIdClient", classLoader)
            XposedHelpers.findAndHookMethod(aaidClass, "getAdvertisingIdInfo", Context::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.result ?: return
                    val aaid = getValue("aaid")
                    if (aaid.isEmpty()) return
                    
                    try {
                        val methods = info.javaClass.declaredMethods
                        for (m in methods) {
                            if (m.name == "getId" || (m.returnType == String::class.java && m.parameterTypes.isEmpty())) {
                                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        p.result = aaid
                                    }
                                })
                            }
                        }
                    } catch (e: Throwable) {}
                }
            })
        } catch (e: Throwable) {}
    }

    private fun hookSystemProperties(classLoader: ClassLoader) {
        try {
            val spClass = XposedHelpers.findClass("android.os.SystemProperties", classLoader)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    val fake = when {
                        key.contains("ro.product.model") -> getValue("model")
                        key.contains("ro.product.manufacturer") -> getValue("manufacturer")
                        key.contains("ro.product.brand") -> getValue("brand")
                        key.contains("ro.product.device") -> getValue("device")
                        key.contains("ro.product.name") -> getValue("product")
                        key.contains("ro.product.board") -> getValue("board")
                        key.contains("ro.board.platform") -> getValue("board")
                        key.contains("ro.product.marketname") -> getValue("device_name")
                        key.contains("ro.product.system.marketname") -> getValue("device_name")
                        key.contains("ro.product.system_ext.marketname") -> getValue("device_name")
                        key.contains("ro.product.vendor.marketname") -> getValue("device_name")
                        key.contains("ro.product.odm.marketname") -> getValue("device_name")
                        key.contains("ro.config.marketing_name") -> getValue("device_name")
                        key.contains("persist.sys.device_name") -> getValue("device_name")
                        key.contains("ro.serialno") -> getValue("hardware_id")
                        key.contains("ro.boot.serialno") -> getValue("hardware_id")
                        key.contains("ro.build.id") -> getValue("build_id")
                        key.contains("ro.build.display.id") -> getValue("build_id")
                        key.contains("ro.build.fingerprint") -> getValue("fingerprint")
                        key.contains("ro.build.version.release") -> getValue("android_version")
                        key.contains("ro.build.version.sdk") -> {
                            XposedEntry.sdkVersionToInt(getValue("android_version")).toString()
                        }
                        key.contains("http.agent") -> getValue("user_agent")
                        key.contains("gsm.version.baseband") -> "M8996_1234.56.01R"
                        key.contains("ro.gsm.imei") -> getValue("imei")
                        key.contains("gsm.operator.iso-country") -> getValue("country_iso")
                        key.contains("persist.sys.locale") -> getValue("locale")
                        key.contains("persist.sys.timezone") -> getValue("timezone")
                        else -> null
                    }
                    if (fake != null && fake.isNotEmpty()) {
                        param.result = fake
                    }
                }
            }
            try { XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, hook) } catch (_: Throwable) {}
            XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, String::class.java, hook)
            try {
                val intHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key.contains("ro.build.version.sdk")) {
                            param.result = XposedEntry.sdkVersionToInt(getValue("android_version"))
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(spClass, "getInt", String::class.java, Int::class.javaPrimitiveType!!, intHook)
            } catch (e: Throwable) {}
        } catch (e: Throwable) {}
    }

    private fun hookTelephony(classLoader: ClassLoader) {
        val tm = "android.telephony.TelephonyManager"
        hookMethodRet(tm, classLoader, "getDeviceId", "imei")
        hookMethodRet(tm, classLoader, "getDeviceId", "imei", Int::class.javaPrimitiveType!!)
        hookMethodRet(tm, classLoader, "getImei", "imei")
        hookMethodRet(tm, classLoader, "getImei", "imei", Int::class.javaPrimitiveType!!)
        hookMethodRet(tm, classLoader, "getMeid", "meid")
        hookMethodRet(tm, classLoader, "getMeid", "meid", Int::class.javaPrimitiveType!!)
        hookMethodRet(tm, classLoader, "getSimSerialNumber", "sim_serial")
        hookMethodRet(tm, classLoader, "getSimOperator", "network_operator")
        hookMethodRet(tm, classLoader, "getSimOperatorName", "sim_operator")
        hookMethodRet(tm, classLoader, "getNetworkOperator", "network_operator")
        hookMethodRet(tm, classLoader, "getNetworkOperatorName", "sim_operator")
        hookMethodRet(tm, classLoader, "getNetworkCountryIso", "country_iso")
        hookMethodRet(tm, classLoader, "getSimCountryIso", "country_iso")
        hookMethodRet(tm, classLoader, "getLine1Number", "mobile_no")
        hookMethodRet(tm, classLoader, "getSubscriberId", "sim_serial")
        try {
            hookMethodRet(tm, classLoader, "getSubscriptionId", "sim_sub_id")
        } catch (e: Throwable) {}
    }

    // ========== v3.7.6: PhoneStateListener Hooks ==========
    // TelephonyManager.listen() registers a PhoneStateListener for live signal/cell
    // updates. We intercept the listener and hook its callbacks to return spoofed data.
    private fun hookPhoneStateListener(classLoader: ClassLoader) {
        try {
            val tm = "android.telephony.TelephonyManager"
            XposedHelpers.findAndHookMethod(tm, classLoader, "listen",
                android.telephony.PhoneStateListener::class.java, Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] as? android.telephony.PhoneStateListener ?: return
                        // Hook onSignalStrengthsChanged on this listener instance
                        try {
                            XposedHelpers.findAndHookMethod(listener.javaClass, "onSignalStrengthsChanged",
                                android.telephony.SignalStrength::class.java,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        p.result = null // block callback entirely — no signal leak
                                    }
                                })
                        } catch (e: Throwable) {}
                        // Hook onCellInfoChanged to return empty list
                        try {
                            XposedHelpers.findAndHookMethod(listener.javaClass, "onCellInfoChanged",
                                java.util.List::class.java,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        p.result = java.util.Collections.emptyList<Any>()
                                    }
                                })
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

    private fun hookWifi(classLoader: ClassLoader) {
        val wi = "android.net.wifi.WifiInfo"
        hookMethodRet(wi, classLoader, "getMacAddress", "mac_address")
        hookMethodRet(wi, classLoader, "getBSSID", "mac_bssid")
        hookMethodRet(wi, classLoader, "getSSID", "mac_ssid")
        
        try {
            XposedHelpers.findAndHookMethod(wi, classLoader, "getIpAddress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ip = getValue("ip_address")
                    if (ip.isNotEmpty()) {
                        val p = ip.split(".")
                        if (p.size == 4) {
                            try {
                                param.result = (p[3].toInt() shl 24) or (p[2].toInt() shl 16) or (p[1].toInt() shl 8) or p[0].toInt()
                            } catch (e: Throwable) {}
                        }
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    // ========== v3.7.6: WifiManager DHCP Info Hook ==========
    // getDhcpInfo() leaks real gateway, DNS, server IP, and netmask from the actual
    // network connection. We spoof it to match our fake IP address.
    private fun hookWifiDhcp(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", classLoader, "getDhcpInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.result ?: return
                        val ip = getValue("ip_address")
                        if (ip.isEmpty()) return
                        val parts = ip.split(".")
                        if (parts.size != 4) return
                        try {
                            val a = parts[0].toInt()
                            val b = parts[1].toInt()
                            val c = parts[2].toInt()
                            val d = parts[3].toInt()
                            val ipInt = (d shl 24) or (c shl 16) or (b shl 8) or a
                            val gwInt = (1 shl 24) or (c shl 16) or (b shl 8) or a
                            val dns = (8 shl 24) or (8 shl 16) or (8 shl 8) or 8
                            XposedHelpers.setIntField(info, "ipAddress", ipInt)
                            XposedHelpers.setIntField(info, "gateway", gwInt)
                            XposedHelpers.setIntField(info, "dns1", dns)
                            XposedHelpers.setIntField(info, "dns2", dns)
                            XposedHelpers.setIntField(info, "serverAddress", gwInt)
                            XposedHelpers.setIntField(info, "netmask", 0x00FFFFFF)
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

    private fun hookNetworkInterface(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("java.net.NetworkInterface", classLoader, "getHardwareAddress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mac = getValue("mac_address")
                    if (mac.isNotEmpty() && mac.contains(":")) {
                        try {
                            val bytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
                            if (bytes.size == 6) {
                                param.result = bytes
                            }
                        } catch (e: Throwable) {}
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    private fun hookSettings(classLoader: ClassLoader) {
        try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val requestedKey = param.args.getOrNull(1) as? String ?: return
                    val fake = when (requestedKey) {
                        "android_id" -> getValue("android_id")
                        "device_name", "bluetooth_name" -> getValue("device_name")
                        else -> null
                    }
                    if (!fake.isNullOrEmpty()) {
                        param.result = fake
                    }
                }
            }
            XposedHelpers.findAndHookMethod("android.provider.Settings.Secure", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod("android.provider.Settings.System", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod("android.provider.Settings.Global", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, hook)
            
            try {
                XposedHelpers.findAndHookMethod("android.provider.Settings.Secure", classLoader, "getStringForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!, hook)
            } catch (e: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod("android.provider.Settings.System", classLoader, "getStringForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!, hook)
            } catch (e: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod("android.provider.Settings.Global", classLoader, "getStringForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!, hook)
            } catch (e: Throwable) {}
            
        } catch (e: Throwable) {}
    }

    private fun hookSharedPreferences(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.SharedPreferencesImpl",
                classLoader,
                "getString",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(0) as? String ?: return
                        val fake = when (key) {
                            "market_name", "device_name", "bluetooth_name" -> getValue("device_name")
                            else -> null
                        }
                        if (!fake.isNullOrEmpty()) {
                            param.result = fake
                        }
                    }
                }
            )
        } catch (e: Throwable) {}
    }

    private fun hookBluetooth(classLoader: ClassLoader) {
        hookMethodRet("android.bluetooth.BluetoothAdapter", classLoader, "getAddress", "bluetooth_mac")
        hookMethodRet("android.bluetooth.BluetoothAdapter", classLoader, "getName", "device_name")
    }

    private fun hookMediaDrm(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.media.MediaDrm", classLoader, "getPropertyByteArray", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty() && "deviceUniqueId" == param.args[0]) {
                        val did = getValue("media_drm_id")
                        if (did.isNotEmpty()) {
                            try {
                                val uuid = java.util.UUID.fromString(did)
                                param.result = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
                            } catch (e: Exception) {}
                        }
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    private fun hookPackageManager(classLoader: ClassLoader) {
        if (getValue("setting_hide_self") != "true") return
        try {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    val newList = java.util.ArrayList<Any>()
                    for (item in list) {
                        if (item != null) {
                            try {
                                val pkgName = XposedHelpers.getObjectField(item, "packageName") as? String
                                if (pkgName != MODULE_PACKAGE) newList.add(item)
                            } catch (e: Throwable) {
                                newList.add(item)
                            }
                        }
                    }
                    param.result = newList
                }
            }
            XposedHelpers.findAndHookMethod(pmClass, "getInstalledApplications", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(pmClass, "getInstalledPackages", Int::class.javaPrimitiveType!!, hook)
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    XposedHelpers.findAndHookMethod(pmClass, "getInstalledApplications",
                        android.content.pm.PackageManager.ApplicationInfoFlags::class.java, hook)
                } catch (e: Throwable) {}
                try {
                    XposedHelpers.findAndHookMethod(pmClass, "getInstalledPackages",
                        android.content.pm.PackageManager.PackageInfoFlags::class.java, hook)
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }

    private fun hookLocation(classLoader: ClassLoader) {
        try {
            val lm = "android.location.LocationManager"
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val loc = param.result as? Location ?: return
                    val lat = getValue("latitude").toDoubleOrNull()
                    val lon = getValue("longitude").toDoubleOrNull()
                    if (lat != null && lon != null) {
                        loc.latitude = lat
                        loc.longitude = lon
                        loc.time = System.currentTimeMillis()
                        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    }
                }
            }
            XposedHelpers.findAndHookMethod(lm, classLoader, "getLastKnownLocation", String::class.java, hook)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(lm, classLoader, "getLastKnownLocation", String::class.java, 
                        XposedHelpers.findClass("android.location.LastLocationRequest", classLoader), hook)
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Fused Location Provider Hook ==========
    // Returns a pre-completed Task with spoofed Location instead of modifying
    // the real task (calling getResult() on an incomplete Task crashes).
    private fun hookLocationFused(classLoader: ClassLoader) {
        try {
            val fusedClass = XposedHelpers.findClass(
                "com.google.android.gms.location.FusedLocationProviderClient", classLoader)
            XposedHelpers.findAndHookMethod(fusedClass, "getLastLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        val loc = Location("gps").apply {
                            latitude = lat
                            longitude = lon
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        }
                        try {
                            val tasksClass = XposedHelpers.findClass(
                                "com.google.android.gms.tasks.Tasks", classLoader)
                            param.result = XposedHelpers.callStaticMethod(
                                tasksClass, "forResult", loc)
                        } catch (e: Throwable) {
                            log("Tasks.forResult failed: ${e.message}")
                        }
                    }
                })
        } catch (e: Throwable) {
            log("FusedLocationProviderClient not available (no GMS)")
        }
    }

    // ========== v3.7.5: Live Location Hook (constructor-level) ==========
    // Hooks the Location(String) constructor so EVERY Location object created
    // anywhere — by LocationManager, FusedProvider, or app code — gets spoofed
    // coordinates. Much more robust than hooking individual listeners.
    private fun hookLocationLive(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookConstructor(
                "android.location.Location", classLoader, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val loc = param.thisObject as? Location ?: return
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        loc.latitude = lat
                        loc.longitude = lon
                    }
                })
        } catch (e: Throwable) {
            log("Location constructor hook failed: ${e.message}")
        }

        // Also hook the copy-constructor Location(Location) for completeness
        try {
            XposedHelpers.findAndHookConstructor(
                "android.location.Location", classLoader,
                XposedHelpers.findClass("android.location.Location", classLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val loc = param.thisObject as? Location ?: return
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        loc.latitude = lat
                        loc.longitude = lon
                    }
                })
        } catch (e: Throwable) {}

        // Android 14+: LocationManager.getCurrentLocation()
        try {
            val lm = "android.location.LocationManager"
            XposedHelpers.findAndHookMethod(lm, classLoader, "getCurrentLocation",
                String::class.java, android.os.CancellationSignal::class.java,
                java.util.concurrent.Executor::class.java, java.util.function.Consumer::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val consumer = param.args[3] as? java.util.function.Consumer<Any> ?: return
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        param.args[3] = java.util.function.Consumer<Any> { locObj ->
                            if (locObj is Location) {
                                locObj.latitude = lat
                                locObj.longitude = lon
                                locObj.time = System.currentTimeMillis()
                                locObj.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            }
                            consumer.accept(locObj)
                        }
                    }
                })
        } catch (e: Throwable) {}
    }

    private fun hookDisplay(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dm = param.args[0] as DisplayMetrics
                val w = getValue("screen_width").toIntOrNull()
                val h = getValue("screen_height").toIntOrNull()
                val d = getValue("screen_density").toIntOrNull()
                if (w != null) dm.widthPixels = w
                if (h != null) dm.heightPixels = h
                if (d != null) {
                    dm.densityDpi = d
                    dm.density = d / 160f
                    dm.xdpi = d.toFloat()
                    dm.ydpi = d.toFloat()
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.view.Display", classLoader, "getMetrics", DisplayMetrics::class.java, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.view.Display", classLoader, "getRealMetrics", DisplayMetrics::class.java, hook)
        } catch (e: Throwable) {}
    }

    private fun hookUserAgent(classLoader: ClassLoader) {
        try {
            val ua = getValue("user_agent")
            if (ua.isEmpty()) return
            hookMethodRet("android.webkit.WebSettings", classLoader, "getDefaultUserAgent", "user_agent", Context::class.java)
            
            try {
                XposedHelpers.findAndHookMethod("android.webkit.WebView", classLoader, "loadUrl", String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val settings = XposedHelpers.callMethod(param.thisObject, "getSettings")
                            XposedHelpers.callMethod(settings, "setUserAgentString", ua)
                        } catch (e: Throwable) {}
                    }
                })
            } catch (e: Throwable) {}
        } catch (e: Throwable) {}
    }

    private fun hookGServices(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.google.android.gsf.Gservices", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == "android_id") {
                        val gsf = getValue("gsf_id")
                        if (gsf.isNotEmpty()) param.result = gsf
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    private fun hookContentResolverQueries(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args.firstOrNull() as? Uri ?: return
                if (uri.authority != "com.google.android.gsf.gservices") return

                val gsf = getValue("gsf_id")
                if (gsf.isEmpty()) return

                val selectionArgs = param.args.filterIsInstance<Array<String>>().firstOrNull()
                val queryArgs = param.args.filterIsInstance<android.os.Bundle>().firstOrNull()
                val bundleSelectionArgs = queryArgs?.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                val requestedKeys = selectionArgs ?: bundleSelectionArgs ?: return
                if (requestedKeys.none { it == "android_id" }) return

                param.result = MatrixCursor(arrayOf("key", "value")).apply {
                    addRow(arrayOf("android_id", gsf))
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.content.ContentResolver",
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                hook
            )
        } catch (e: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.content.ContentResolver",
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                android.os.CancellationSignal::class.java,
                hook
            )
        } catch (e: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.content.ContentResolver",
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                android.os.Bundle::class.java,
                android.os.CancellationSignal::class.java,
                hook
            )
        } catch (e: Throwable) {}
    }

    private fun hookOpenGL(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = param.args[0] as? Int ?: return
                if (type == 0x1F00) {
                    val vendor = getValue("gl_vendor")
                    if (vendor.isNotEmpty()) param.result = vendor
                } else if (type == 0x1F01) {
                    val renderer = getValue("gl_renderer")
                    if (renderer.isNotEmpty()) param.result = renderer
                }
            }
        }
        try { XposedHelpers.findAndHookMethod("android.opengl.GLES20", classLoader, "glGetString", Int::class.javaPrimitiveType!!, hook) } catch (e: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.opengl.GLES30", classLoader, "glGetString", Int::class.javaPrimitiveType!!, hook) } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Battery Scale Fix ==========
    private fun hookBattery(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val filter = param.args.getOrNull(1) as? android.content.IntentFilter ?: return
                if (filter.hasAction(android.content.Intent.ACTION_BATTERY_CHANGED)) {
                    val intent = param.result as? android.content.Intent ?: return
                    val level = getValue("battery_level").toIntOrNull() ?: 85
                    val scale = getValue("battery_scale").toIntOrNull() ?: 100
                    val scaledLevel = (level * scale / 100).coerceIn(1, scale)
                    intent.putExtra("level", scaledLevel)
                    intent.putExtra("scale", scale)
                    intent.putExtra("status", 2)
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java,
                Int::class.javaPrimitiveType!!, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java,
                String::class.java, android.os.Handler::class.java, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java,
                String::class.java, android.os.Handler::class.java, Int::class.javaPrimitiveType!!, hook)
        } catch (e: Throwable) {}
    }

    private fun hookLocaleAndTimezone(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("java.util.Locale", classLoader, "getDefault", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val localeTag = getValue("locale")
                    if (localeTag.isNotEmpty()) {
                        param.result = java.util.Locale.forLanguageTag(localeTag)
                    }
                }
            })
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("java.util.Locale", classLoader, "getDefault",
                java.util.Locale.Category::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val localeTag = getValue("locale")
                        if (localeTag.isNotEmpty()) {
                            param.result = java.util.Locale.forLanguageTag(localeTag)
                        }
                    }
                })
        } catch (e: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod("java.util.TimeZone", classLoader, "getDefault", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val zone = getValue("timezone")
                    if (zone.isNotEmpty()) {
                        param.result = java.util.TimeZone.getTimeZone(zone)
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    private fun hookJavaSystemProperties(classLoader: ClassLoader) {
        try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    val fake = when {
                        key.contains("http.agent") -> {
                            log("Hooked java.lang.System.getProperty(http.agent) -> user_agent")
                            getValue("user_agent")
                        }
                        key.contains("os.name") -> "Linux"
                        key.contains("os.version") -> {
                            val ver = getValue("android_version")
                            when {
                                ver.startsWith("15") -> "6.1.43-android-15"
                                ver.startsWith("14") -> "5.15.123-android-14"
                                ver.startsWith("13") -> "5.10.198-android-13"
                                else -> "6.1.43-android-15"
                            }
                        }
                        key.contains("os.arch") -> "aarch64"
                        key.contains("java.vm.version") -> "2.1.0"
                        key.contains("java.runtime.version") -> "1.8.0"
                        else -> null
                    }
                    if (fake != null && fake.isNotEmpty()) {
                        param.result = fake
                    }
                }
            }
            XposedHelpers.findAndHookMethod(java.lang.System::class.java, "getProperty",
                String::class.java, hook)
            XposedHelpers.findAndHookMethod(java.lang.System::class.java, "getProperty",
                String::class.java, String::class.java, hook)
        } catch (e: Throwable) {
            XposedBridge.log("DevicePrivy: java.lang.System.getProperty hook error: ${e.message}")
        }
    }

    // ========== v3.7.5: Anti-Xposed Detection Hooks ==========
    private fun hookAntiXposed(classLoader: ClassLoader) {
        // --- Hook 1: Class.forName() ---
        // Blocks app detection of Xposed/LSPosed without breaking LSPosed internals.
        val forNameHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val className = param.args[0] as? String ?: return
                    val lower = className.lowercase()
                    if (!lower.contains("xposed") &&
                        !lower.contains("edxposed") &&
                        !lower.contains("lsposed")) return
                    val trace = Thread.currentThread().stackTrace
                    for (frame in trace) {
                        val cn = frame.className
                        if (cn.startsWith("org.lsposed") ||
                            cn.startsWith("de.robv.android.xposed.XposedBridge") ||
                            cn.startsWith("de.robv.android.xposed.XposedHelpers")) {
                            return
                        }
                    }
                    throw ClassNotFoundException(className)
                } catch (e: ClassNotFoundException) {
                    throw e
                } catch (_: Throwable) {
                    // safety: let original call proceed
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Class::class.java, "forName", String::class.java, forNameHook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(Class::class.java, "forName", String::class.java,
                Boolean::class.javaPrimitiveType!!, ClassLoader::class.java, forNameHook)
        } catch (e: Throwable) {}

        // --- Hook 2: /proc filesystem filters ---
        // Filters /proc/self/maps (Xposed libraries), /proc/cpuinfo (CPU details),
        // and /proc/meminfo (RAM size) to prevent hardware fingerprinting.
        try {
            XposedHelpers.findAndHookMethod("java.io.FileInputStream", classLoader, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val fis = param.thisObject
                            val path = XposedHelpers.getObjectField(fis, "path") as? String ?: return
                            if (!path.contains("/proc/")) return
                            
                            val bytesRead = param.result as? Int ?: return
                            if (bytesRead <= 0) return
                            
                            val buf = param.args[0] as ByteArray
                            val content = String(buf, 0, bytesRead)
                            
                            val filtered = when {
                                path.contains("maps") -> {
                                    // Strip Xposed/LSPosed library lines
                                    content.lines()
                                        .filter { line ->
                                            !line.contains("xposed", ignoreCase = true) &&
                                            !line.contains("lsposed", ignoreCase = true) &&
                                            !line.contains("edxposed", ignoreCase = true)
                                        }
                                        .joinToString("\n")
                                }
                                path.contains("cpuinfo") -> {
                                    // Rewrite CPU info to match spoofed device
                                    val manufacturer = getValue("manufacturer")
                                    val cpuName = when {
                                        manufacturer.equals("Samsung", true) -> "Qualcomm Snapdragon 8 Gen 3"
                                        manufacturer.equals("Google", true) -> "Google Tensor G4"
                                        else -> "ARMv8 Processor rev 1 (v8l)"
                                    }
                                    content.lines().map { line ->
                                        when {
                                            line.startsWith("Hardware") -> "Hardware\t: $cpuName"
                                            line.startsWith("processor") -> line  // keep core count
                                            else -> line
                                        }
                                    }.joinToString("\n")
                                }
                                path.contains("meminfo") -> {
                                    // Scale memory values around 8GB for modern Android 15 devices
                                    content.lines().map { line ->
                                        when {
                                            line.startsWith("MemTotal") -> "MemTotal:        8164000 kB"
                                            line.startsWith("MemFree") -> "MemFree:          524000 kB"
                                            line.startsWith("MemAvailable") -> "MemAvailable:    2800000 kB"
                                            else -> line
                                        }
                                    }.joinToString("\n")
                                }
                                else -> return  // not a file we care about
                            }
                            
                            if (filtered.isEmpty() || filtered.isBlank()) return  // keep original
                            val filteredBytes = filtered.toByteArray()
                            val copyLen = minOf(filteredBytes.size, buf.size)
                            System.arraycopy(filteredBytes, 0, buf, 0, copyLen)
                            param.result = copyLen
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Sensor Spoofing ==========
    private fun hookSensors(classLoader: ClassLoader) {
        val sensorHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sensors = param.result as? List<*> ?: return
                if (sensors.isEmpty()) return
                
                val manufacturer = getValue("manufacturer")
                val vendor = when {
                    manufacturer.equals("Samsung", ignoreCase = true) -> "STMicroelectronics"
                    manufacturer.equals("Google", ignoreCase = true) -> "Bosch"
                    manufacturer.equals("Xiaomi", ignoreCase = true) || manufacturer.equals("OnePlus", ignoreCase = true) -> "Qualcomm"
                    manufacturer.equals("Nothing", ignoreCase = true) || manufacturer.equals("Motorola", ignoreCase = true) -> "Qualcomm"
                    manufacturer.equals("Asus", ignoreCase = true) -> "Qualcomm"
                    else -> "Qualcomm"
                }
                
                for (sensor in sensors) {
                    try {
                        XposedHelpers.setObjectField(sensor, "mVendor", vendor)
                        XposedHelpers.setObjectField(sensor, "mStringType", vendor)
                    } catch (e: Throwable) {}
                }
                param.result = sensors
            }
        }
        
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", classLoader,
                "getFullSensorList", sensorHook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", classLoader,
                "getSensorList", Int::class.javaPrimitiveType!!, sensorHook)
        } catch (e: Throwable) {}
        
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SensorManager", classLoader,
                "getDefaultSensor", Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sensor = param.result ?: return
                        val manufacturer = getValue("manufacturer")
                        val vendor = when {
                            manufacturer.equals("Samsung", ignoreCase = true) -> "STMicroelectronics"
                            manufacturer.equals("Google", ignoreCase = true) -> "Bosch"
                            else -> "Qualcomm"
                        }
                        try {
                            XposedHelpers.setObjectField(sensor, "mVendor", vendor)
                            XposedHelpers.setObjectField(sensor, "mStringType", vendor)
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

    // ========== SystemProperties Bridge ==========

    companion object {
        private val KNOWN_KEYS = setOf(
            "manufacturer", "model", "brand", "device", "product", "board",
            "device_name", "build_id", "android_version", "fingerprint", "hardware_id",
            "android_id", "gsf_id", "aaid", "media_drm_id",
            "imei", "meid", "sim_serial", "sim_sub_id", "mobile_no",
            "sim_operator", "network_operator", "country_iso",
            "mac_address", "mac_bssid", "mac_ssid", "bluetooth_mac", "ip_address",
            "latitude", "longitude", "locale", "timezone",
            "screen_width", "screen_height", "screen_density", "user_agent",
            "gl_renderer", "gl_vendor", "battery_level", "battery_scale",
            "setting_debug_log", "setting_hide_self"
        )

        fun sdkVersionToInt(sdkVersion: String): Int = when {
            sdkVersion.startsWith("16") -> 36
            sdkVersion.startsWith("15") -> 35
            sdkVersion.startsWith("14") -> 34
            sdkVersion.startsWith("13") -> 33
            sdkVersion.startsWith("12") -> 32
            sdkVersion.startsWith("11") -> 31
            sdkVersion.startsWith("10") -> 30
            sdkVersion.startsWith("9") -> 29
            sdkVersion.startsWith("8") -> 28
            else -> 35  // default to Android 15 if unknown
        }
    }

    // ========== Shared File IPC (Android 15 cross-process workaround) ==========
    
    private val SHARED_FILE = "/data/adb/deviceprivy_data.json"

    private fun writeSharedFile() {
        // Try writing to SystemProperties via setprop (su -c) as cross-process bridge.
        // Direct SystemProperties.set() is blocked on Android 15, but setprop via root works.
        try {
            val sb = StringBuilder()
            for ((key, value) in cachedValues) {
                if (value.isNotEmpty() && key in KNOWN_KEYS) {
                    sb.append("setprop deviceprivy.$key '$value';")
                }
            }
            sb.append("setprop deviceprivy.refreshed '${System.currentTimeMillis()}'")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", sb.toString()))
            process.waitFor()
            if (process.exitValue() == 0) {
                lastSysPropRefresh = System.currentTimeMillis()
                XposedBridge.log("DevicePrivy: SystemProperties written via setprop (${cachedValues.size} values)")
            }
        } catch (e: Throwable) {
            // Fallback: try JSON file (may still fail on strict SELinux)
            try {
                val json = org.json.JSONObject(cachedValues as Map<String, Any>).toString()
                java.io.File(SHARED_FILE).writeText(json)
                XposedBridge.log("DevicePrivy: Shared file written (fallback)")
            } catch (_: Throwable) {}
        }
    }

    private fun readSharedFile(): Boolean {
        try {
            val file = java.io.File(SHARED_FILE)
            if (!file.exists() || !file.canRead()) return false
            // Only use if written recently (within last 24h)
            if (System.currentTimeMillis() - file.lastModified() > 86400000L) return false
            val json = file.readText()
            if (json.isBlank()) return false
            val obj = org.json.JSONObject(json)
            cachedValues.clear()
            for (key in obj.keys()) {
                cachedValues[key] = obj.optString(key, "")
            }
            XposedBridge.log("DevicePrivy: Data fetched via shared file (count=${cachedValues.size})")
            return cachedValues.isNotEmpty()
        } catch (e: Throwable) {
            XposedBridge.log("DevicePrivy: Shared file read error: ${e.message}")
            return false
        }
    }

    private fun writeToSystemProperties() {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val setMethod = spClass.getDeclaredMethod("set", String::class.java, String::class.java)
            var count = 0
            for ((key, value) in cachedValues) {
                if (value.isNotEmpty() && key in KNOWN_KEYS) {
                    try {
                        setMethod.invoke(null, "deviceprivy.$key", value)
                        count++
                    } catch (e: Throwable) {}
                }
            }
            setMethod.invoke(null, "deviceprivy.refreshed", System.currentTimeMillis().toString())
            XposedBridge.log("DevicePrivy: SystemProperties written ($count values)")
        } catch (e: Throwable) {
            log("SystemProperties write error: ${e.message}")
        }
    }

    private fun readFromSystemProperties(): Boolean {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            
            val refreshed = getMethod.invoke(null, "deviceprivy.refreshed", "") as? String ?: ""
            if (refreshed.isEmpty()) return false

            var count = 0
            for (key in KNOWN_KEYS) {
                val value = getMethod.invoke(null, "deviceprivy.$key", "") as? String ?: ""
                if (value.isNotEmpty()) {
                    cachedValues[key] = value
                    count++
                }
            }
            if (count > 0) {
                dataFetched = true
                debugEnabled = cachedValues["setting_debug_log"] == "true"
                XposedBridge.log("DevicePrivy: Read $count values from SystemProperties")
                return true
            }
        } catch (e: Throwable) {
            XposedBridge.log("DevicePrivy: SystemProperties read error: ${e.message}")
        }
        return false
    }
}

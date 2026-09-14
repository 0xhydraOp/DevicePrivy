package dev.codex.deviceprivy

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookPackageManager(classLoader: ClassLoader) {
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

    // ========== v3.7.5: Anti-Xposed Detection Hooks ==========
internal fun XposedEntry.hookAntiXposed(classLoader: ClassLoader) {
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


package dev.codex.deviceprivy

import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookSettings(classLoader: ClassLoader) {
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

internal fun XposedEntry.hookSharedPreferences(classLoader: ClassLoader) {
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

internal fun XposedEntry.hookAAID(classLoader: ClassLoader) {
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

internal fun XposedEntry.hookGServices(classLoader: ClassLoader) {
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

internal fun XposedEntry.hookContentResolverQueries(classLoader: ClassLoader) {
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

internal fun XposedEntry.hookMediaDrm(classLoader: ClassLoader) {
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


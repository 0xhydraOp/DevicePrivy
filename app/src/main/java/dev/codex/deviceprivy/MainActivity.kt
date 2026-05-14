package dev.codex.deviceprivy

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val PREFS_NAME = "device_privy_prefs"

    private data class FieldGroup(val title: String, val keys: List<String>)

    private val groups = listOf(
        FieldGroup("Device Identity", listOf(
            "manufacturer", "model", "brand", "device", "product", "board", "device_name",
            "build_id", "android_version", "fingerprint", "hardware_id"
        )),
        FieldGroup("Android IDs", listOf("android_id", "gsf_id", "aaid", "media_drm_id")),
        FieldGroup("Telephony", listOf(
            "imei", "meid", "sim_serial", "sim_sub_id", "mobile_no",
            "sim_operator", "network_operator", "country_iso"
        )),
        FieldGroup("Network", listOf("mac_address", "mac_bssid", "mac_ssid", "bluetooth_mac", "ip_address")),
        FieldGroup("Location & Locale", listOf("latitude", "longitude", "locale", "timezone")),
        FieldGroup("Display & Hardware", listOf(
            "screen_width", "screen_height", "screen_density", "user_agent",
            "gl_renderer", "gl_vendor", "battery_level", "battery_scale"
        ))
    )

    private val fieldLabels = mapOf(
        "manufacturer" to "Manufacturer", "model" to "Model", "brand" to "Brand",
        "device" to "Codename", "product" to "Product", "board" to "Board",
        "device_name" to "Device Name", "build_id" to "Build ID",
        "android_version" to "Android Version", "fingerprint" to "Fingerprint",
        "hardware_id" to "Hardware ID", "android_id" to "Android ID",
        "gsf_id" to "GSF ID", "aaid" to "Advertising ID", "media_drm_id" to "Media DRM ID",
        "imei" to "IMEI", "meid" to "MEID", "sim_serial" to "SIM Serial",
        "sim_sub_id" to "SIM Sub ID", "mobile_no" to "Phone Number",
        "sim_operator" to "SIM Operator", "network_operator" to "Network Operator",
        "country_iso" to "Country ISO", "mac_address" to "WiFi MAC",
        "mac_bssid" to "WiFi BSSID", "mac_ssid" to "WiFi SSID",
        "bluetooth_mac" to "Bluetooth MAC", "ip_address" to "IP Address",
        "latitude" to "Latitude", "longitude" to "Longitude",
        "locale" to "Locale", "timezone" to "Timezone",
        "screen_width" to "Screen Width", "screen_height" to "Screen Height",
        "screen_density" to "Screen Density", "user_agent" to "User Agent",
        "gl_renderer" to "GPU Renderer", "gl_vendor" to "GPU Vendor",
        "battery_level" to "Battery Level", "battery_scale" to "Battery Scale"
    )

    private val fieldKeys = groups.flatMap { it.keys }
    private val values = linkedMapOf<String, String>()
    private val inputs = mutableMapOf<String, EditText>()
    private lateinit var debugLogging: CheckBox
    private lateinit var hideSelf: CheckBox
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var accordionContainer: LinearLayout
    private val expandedGroups = mutableSetOf(0) // first group open by default

    // ========== Status Detection ==========

    private fun isModuleActive(): Boolean {
        // Check SystemProperties for the refresh timestamp
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            val refreshed = getMethod.invoke(null, "deviceprivy.refreshed", "") as? String ?: ""
            if (refreshed.isNotEmpty()) return true
        } catch (_: Throwable) {}
        // Fallback: check SharedPreferences populated by module
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mfr = prefs.getString("manufacturer", "") ?: ""
            if (mfr.isNotEmpty()) return true
        } catch (_: Throwable) {}
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadValues()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F0F2F5"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(buildHeader())
        root.addView(spacer(12))
        root.addView(buildStatusBadge())
        root.addView(spacer(12))
        root.addView(buildActions())
        root.addView(spacer(12))
        root.addView(buildDeviceCard())
        root.addView(spacer(12))
        root.addView(buildSettings())
        root.addView(spacer(12))
        root.addView(buildAccordionEditor())
        root.addView(spacer(8))
        root.addView(buildFooter())
    }

    // ========== Header ==========

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.WHITE, 14)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }, LinearLayout.LayoutParams(dp(56), dp(56)))

            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            copy.addView(TextView(this@MainActivity).apply {
                text = "DevicePrivy"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A1D21"))
            })
            copy.addView(TextView(this@MainActivity).apply {
                text = "v${BuildConfig.VERSION_NAME}"
                textSize = 13f
                setTextColor(Color.parseColor("#6B7280"))
            })
            addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ========== Status Badge ==========

    private fun buildStatusBadge(): View {
        val active = isModuleActive()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(if (active) Color.parseColor("#ECFDF5") else Color.parseColor("#FEF2F2"), 12)

            addView(View(this@MainActivity).apply {
                background = rounded(if (active) Color.parseColor("#10B981") else Color.parseColor("#EF4444"), 999)
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })

            statusText = TextView(this@MainActivity).apply {
                text = if (active) "MODULE ACTIVE – Spoofing running" else "MODULE INACTIVE – Reboot needed"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.parseColor("#065F46") else Color.parseColor("#991B1B"))
            }
            addView(statusText)
        }
    }

    // ========== Actions ==========

    private fun buildActions(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Actions"))

            // Randomize — full width, prominent
            addView(actionButton("🎲  Randomize Profile", Color.parseColor("#2563EB")) {
                randomizeAll()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })

            // Save + Fast Reboot side by side
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            lateinit var saveBtn: Button
            saveBtn = actionButton("💾  Save", Color.parseColor("#059669")) {
                saveConfig(saveBtn)
                Toast.makeText(this@MainActivity, "Saved – restart target apps.", Toast.LENGTH_SHORT).show()
            }
            row.addView(saveBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })

            row.addView(actionButton("🔄  Soft Reboot", Color.parseColor("#DC2626")) {
                softReboot()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })

            addView(row)
        }
    }

    // ========== Device Preview Card ==========

    private fun buildDeviceCard(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Device Profile"))

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(10))
            }
            headerRow.addView(TextView(this@MainActivity).apply {
                text = "📱"
                textSize = 28f
                setPadding(0, 0, dp(12), 0)
            })
            val nameCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            summaryText = TextView(this@MainActivity).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#111827"))
                setLineSpacing(0f, 1.2f)
            }
            nameCol.addView(summaryText)
            headerRow.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(headerRow)

            // Divider
            addView(divider())

            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            details.addView(detailRow("IMEI", "imei"))
            details.addView(detailRow("Android ID", "android_id"))
            details.addView(detailRow("WiFi MAC", "mac_address"))
            details.addView(detailRow("Carrier", "sim_operator"))
            addView(details)

            refreshSummary()
        }
    }

    private fun detailRow(label: String, key: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#6B7280"))
            }, LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                tag = "detail_$key"
                text = displayValue(key, "\u2014")
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#374151"))
                maxLines = 1
                setHorizontallyScrolling(true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ========== Settings ==========

    private fun buildSettings(): View {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Module Options"))

            debugLogging = CheckBox(this@MainActivity).apply {
                text = "Debug logging"
                textSize = 14f
                setTextColor(Color.parseColor("#374151"))
                isChecked = prefs.getBoolean("setting_debug_log", false)
                setPadding(0, dp(4), 0, dp(4))
            }
            hideSelf = CheckBox(this@MainActivity).apply {
                text = "Hide module from scoped apps"
                textSize = 14f
                setTextColor(Color.parseColor("#374151"))
                isChecked = prefs.getBoolean("setting_hide_self", true)
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(debugLogging)
            addView(hideSelf)
        }
    }

    // ========== Accordion Editor ==========

    private fun buildAccordionEditor(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Field Editor"))
            addView(TextView(this@MainActivity).apply {
                text = "Tap a group to expand and edit its fields."
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 0, 0, dp(10))
            })

            accordionContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(accordionContainer)

            groups.forEachIndexed { index, group ->
                accordionContainer.addView(buildGroupSection(index, group))
                if (index < groups.size - 1) accordionContainer.addView(spacer(6))
            }
        }
    }

    private fun buildGroupSection(index: Int, group: FieldGroup): View {
        val isExpanded = index in expandedGroups
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F9FAFB"), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = if (isExpanded) "▾  ${group.title}" else "▸  ${group.title}"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#374151"))
            tag = "header_$index"
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(this).apply {
            text = "${group.keys.size}"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6B7280"))
            background = rounded(Color.parseColor("#E5E7EB"), 999)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        })

        header.setOnClickListener {
            if (index in expandedGroups) expandedGroups.remove(index)
            else expandedGroups.add(index)
            refreshAccordion()
        }
        card.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            tag = "content_$index"
            setPadding(0, dp(8), 0, 0)
        }

        if (isExpanded) {
            populateFields(content, group)
        }

        card.addView(content)
        return card
    }

    private fun populateFields(container: LinearLayout, group: FieldGroup) {
        group.keys.forEach { key ->
            val label = fieldLabels[key] ?: key.replace("_", " ").replaceFirstChar { it.uppercase() }
            container.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, dp(10), 0, dp(4))
            })
            container.addView(EditText(this).apply {
                setText(values[key].orEmpty())
                setSingleLine()
                textSize = 14f
                setTextColor(Color.parseColor("#111827"))
                hint = label
                background = rounded(Color.WHITE, 8)
                setPadding(dp(12), 0, dp(12), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        values[key] = s?.toString().orEmpty()
                        refreshSummary()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                inputs[key] = this
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        }
    }

    private fun refreshAccordion() {
        for (i in 0 until accordionContainer.childCount) {
            val child = accordionContainer.getChildAt(i)
            if (child is LinearLayout && child.tag == null && child.childCount >= 2) {
                // This is a group card — find header text and content by index
                val headerRow = child.getChildAt(0) as? LinearLayout ?: continue
                val headerText = headerRow.getChildAt(0) as? TextView ?: continue
                val tag = headerText.tag as? String ?: continue
                if (!tag.startsWith("header_")) continue
                val idx = tag.removePrefix("header_").toIntOrNull() ?: continue
                val content = child.getChildAt(1) as? LinearLayout ?: continue
                val group = groups[idx]
                val isExpanded = idx in expandedGroups

                headerText.text = if (isExpanded) "▾  ${group.title}" else "▸  ${group.title}"
                content.visibility = if (isExpanded) View.VISIBLE else View.GONE

                if (isExpanded && content.childCount == 0) {
                    populateFields(content, group)
                }
            }
        }
    }

    // ========== Footer ==========

    private fun buildFooter(): View {
        return TextView(this).apply {
            text = "Scope target apps in LSPosed to spoof them.\nAfter saving, restart the target app to apply."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
    }

    // ========== Data ==========

    private fun loadValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fieldKeys.forEach { values[it] = prefs.getString(it, "").orEmpty() }
    }

    private fun randomizeAll() {
        // Brief crossfade on the profile card for visual feedback
        val deviceCard = summaryText.parent?.parent as? ViewGroup
        deviceCard?.animate()?.alpha(0.3f)?.setDuration(120)?.withEndAction {
            values.putAll(FakeData.generateAll())
            for ((key, editText) in inputs) {
                editText.setText(values[key].orEmpty())
            }
            refreshSummary()
            deviceCard.animate().alpha(1f).setDuration(200).start()
        }?.start() ?: run {
            values.putAll(FakeData.generateAll())
            for ((key, editText) in inputs) {
                editText.setText(values[key].orEmpty())
            }
            refreshSummary()
        }
        Toast.makeText(this, "New profile generated.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSummary() {
        if (!::summaryText.isInitialized) return
        val mfr = displayValue("manufacturer", "Unknown")
        val model = displayValue("model", "Unknown")
        val android = displayValue("android_version", "0")
        summaryText.text = "$mfr $model\nAndroid $android"

        // Refresh detail views in the device card
        val root = summaryText.parent?.parent as? ViewGroup ?: return
        for (key in listOf("imei", "android_id", "mac_address", "sim_operator")) {
            val tv = root.findViewWithTag<TextView>("detail_$key") ?: continue
            tv.text = displayValue(key, "\u2014")
        }
    }

    private fun displayValue(key: String, fallback: String): String {
        return values[key]?.takeIf { it.isNotBlank() } ?: fallback
    }

    // ========== Save ==========

    private fun saveConfig(saveButton: Button? = null) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putBoolean("setting_debug_log", debugLogging.isChecked)
        editor.putBoolean("setting_hide_self", hideSelf.isChecked)
        fieldKeys.forEach { editor.putString(it, values[it].orEmpty().trim()) }

        val saved = editor.commit()
        if (saved) {
            logToLogcat("Config saved via commit()")
        } else {
            logToLogcat("Config save failed via commit()")
        }

        try {
            val dataDir = File(applicationInfo.dataDir)
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "${PREFS_NAME}.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                dataDir.setReadable(true, false)
                dataDir.setExecutable(true, false)
            }
        } catch (e: Exception) {
            logToLogcat("Permission fix failed: ${e.message}")
        }

        // Brief confirmation animation on the save button
        saveButton?.let { btn ->
            val originalText = btn.text.toString()
            btn.text = "✓ Saved!"
            btn.background = rounded(Color.parseColor("#10B981"), 10)
            btn.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150)
                .withEndAction {
                    btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
            btn.postDelayed({
                btn.text = originalText
                btn.background = rounded(Color.parseColor("#059669"), 10)
            }, 1200)
        }
    }

    // ========== Soft Reboot ==========

    private fun softReboot() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("killall -9 system_server\n".toByteArray())
            os.write("exit\n".toByteArray())
            os.flush()
            os.close()
            process.waitFor()
            Toast.makeText(this, "Soft reboot triggered — system will restart in ~15s.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Soft reboot failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ========== Helpers ==========

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 14)
            elevation = dp(1).toFloat()
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1F2937"))
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setAllCaps(false)
            background = rounded(color, 10)
            setOnClickListener { onClick() }
        }
    }

    private fun divider(): View {
        return View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#E5E7EB")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun logToLogcat(msg: String) {
        android.util.Log.d("DevicePrivy", msg)
    }
}

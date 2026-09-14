# Changelog

## v3.8.5-FIX (2026-09-15)
- IMSI fix: new `imsi` field (generator + UI + validation); `getSubscriberId` remapped off `sim_serial`
- Consistency: city-based lat/lon per carrier, per-country phone lengths, `CHROME_VERSION` constant
- UI: field locks for Randomize, profile history (last 10) with restore, export/import via clipboard JSON
- Hook category toggles: 8 switches (device/network/location/stealth…), missing key defaults to enabled
- Refactor: XposedEntry split into 7 hook modules + shared helpers; sensor vendor mapping unified
- Tests: FieldValidators extracted (pure Kotlin) + FieldValidatorsTest; FakeDataTest covers IMSI/phone/geo
- CI: GitHub Actions workflow (assembleDebug + unit tests)
- VersionCode: 57

## v3.8.0-FINAL (2026-05-15)
- Final code review: 0 errors, 0 warnings, 0 crashes on scoped app
- Cross-process data bridge: setprop via su from main zygote to SystemProperties
- Both zygotes now read XSharedPreferences (42 values) — no more FakeData fallback
- Anti-Xposed Class.forName hook: stack-trace whitelist prevents LSPosed self-blocking
- SystemProperties write error log gated behind debugEnabled
- Secondary zygote no longer triggers root prompt (writeSharedFile guarded)
- VersionCode: 52

## v3.7.7-POLISH (2026-05-15)
- UI overhaul: accordion editor replaces spinner, all groups visible
- Human-readable field labels (battery_scale to Battery Scale)
- Device profile preview card with detail rows
- Status detection fixed: checks SystemProperties + SharedPreferences
- Improved button layout: full-width Randomize, side-by-side Save/Soft Reboot
- Button animations: crossfade on randomize, checkmark pulse on save
- "Fast Reboot" renamed to "Soft Reboot" with toast confirmation
- debugEnabled defaults to false (no log spam)
- getValue() polling guarded: won't overwrite user prefs with stale SystemProperties
- sdkVersionToInt: added Android 16 (SDK 36)
- hookSystemProperties: separate try-catch for get(String) fallback
- VersionCode: 50

## v3.7.6-SHIELD (2026-05-15)
- WifiManager.getDhcpInfo() hook: gateway, DNS, netmask, DHCP server spoofed
- PhoneStateListener hooks: onSignalStrengthsChanged blocked, onCellInfoChanged returns empty
- /proc filters expanded: /proc/cpuinfo (CPU name rewritten), /proc/meminfo (memory ~8GB)
- VersionCode: 49

## v3.7.5-HYDRA (2026-05-14)
- Device profiles: 42 Android 15 devices, Samsung trimmed from ~25 to 6
- Randomized hardware pools: 15 resolution combos x 7 GPU pairs per call
- Location constructor hook: catches ALL Location objects (String + copy constructor)
- FusedLocationProviderClient: returns pre-completed Task (fixes getResult() crash)
- Anti-Xposed detection: Class.forName block, /proc/self/maps filter
- Sensor spoofing: SensorManager vendor rewrite per manufacturer
- Battery scale manufacturer-specific (100 vs 1000)
- Fast randomize: direct EditText update, no view recreation
- VersionCode: 48

## v3.7.4-CRASH-SAFE (2026-05-13)
- Starting point: Xposed/LSPosed module with 41+ spoofed fields
- Removed sun.misc.Unsafe (native SIGSEGV risk on Android 15)
- Removed hookAllMethods on System (JVM instability)
- Patched LSPosed modules_config.db at byte offset 16250
- VersionCode: 47

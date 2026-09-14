# DevicePrivy v3.8.5-FIX

Advanced Android privacy tool — LSPosed/Xposed module that spoofs **46+ device identifiers** at the system API level.

## How It Works

DevicePrivy installs as an Xposed module (via LSPosed + Zygisk). Once scoped to a target app, it intercepts every API call the app makes to read device hardware details — and replaces the real values with spoofed ones.

### Data Pipeline

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────┐
│ SharedPreferences│ ──▶ │  Main Zygote     │ ──▶ │ setprop     │
│ (user config)   │     │  (XSharedPrefs)  │     │ (SystemProp)│
└─────────────────┘     └──────────────────┘     └──────┬──────┘
                                                        │
┌─────────────────┐     ┌──────────────────┐            │
│ FakeData pool   │ ◀── │ Secondary Zygote │ ◀──────────┘
│ (42 profiles)   │     │  (reads SysProp) │
└─────────────────┘     └────────┬─────────┘
                                 │
                        ┌────────▼─────────┐
                        │  Target App      │
                        │  (all hooks      │
                        │   active)        │
                        └──────────────────┘
```

## Spoofed Fields (24 hook categories)

### Device Identity
Build.MANUFACTURER, MODEL, BRAND, DEVICE, PRODUCT, BOARD, HARDWARE, SERIAL, FINGERPRINT, ID, DISPLAY, TAGS, TYPE, USER, HOST, BOOTLOADER, VERSION.RELEASE, VERSION.SDK_INT

### Telephony / SIM
IMEI, MEID, IMSI, ICCID, phone number, SIM operator, network operator, MCC/MNC, country ISO, PhoneStateListener callbacks

### Network
WiFi MAC, BSSID, SSID, IP, DHCP info (gateway, DNS, netmask), Bluetooth MAC, NetworkInterface

### Location (3 paths)
LocationManager.getLastKnownLocation(), Location constructor hooks, FusedLocationProviderClient

### Display & GPU
Screen resolution, density, GL_VENDOR, GL_RENDERER

### Battery
Level (proportional to manufacturer scale), scale (100/1000)

### Android IDs
Android ID, GSF ID, AAID (Advertising ID), MediaDrm ID

### Anti-Detection
- Class.forName() blocks Xposed/LSPosed class lookups
- /proc/self/maps filters strip Xposed library lines
- /proc/cpuinfo rewritten per manufacturer
- /proc/meminfo faked to ~8GB
- SensorManager.getSensorList rewrites vendor field
- PackageManager self-hiding

### Profiles
42 Android 15 devices across 14 brands with randomized hardware pools (15 resolution × 7 GPU combos)

## Building

**Requirements**: JDK 17, Android SDK 35, Gradle 8.10.2

```powershell
cd DevicePrivacyLab
.\build-debug.ps1
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Installation

1. Install LSPosed (Zygisk) on rooted device
2. Install APK
3. Enable module in LSPosed, scope target apps
4. Open DevicePrivy UI, randomize profile, save
5. Soft reboot or restart target app

## License

MIT — but forking/modifying **requires approval** from the repository owner. See [LICENSE](LICENSE).

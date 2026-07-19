# Cupcake Comics

Modern Android comic reader fork of [Bubble2](https://github.com/edeso/bubble2) with Samba library access, a virtual Pull List, Kapowarr requests, and optional Guided Panel reading.

**Approved spec:** [.wayfinder/SPEC-v1.md](./.wayfinder/SPEC-v1.md)  
**Wayfinding map:** [.wayfinder/maps/cupcake-comics-wayfinding.md](./.wayfinder/maps/cupcake-comics-wayfinding.md)

## Status

Phase 1–3 bootstrap:

- [x] Wayfinder decisions locked and SPEC approved
- [x] Bubble2 cloned; `upstream` remote preserved
- [x] Rebranded `applicationId` to `com.cupcakecomics.app`
- [x] minSdk 30 / targetSdk 35 / compileSdk 36 / Kotlin plugin enabled
- [x] Clean versionName `0.1.0` (no Bubble2 movie-quote tags)
- [x] Android Studio + wireless ADB; debug install on Pixel 6
- [x] Room + EncryptedSharedPreferences connection profiles
- [x] SMB browse (smbj, metadata listing) via drawer → Connections
- [x] Library shows SMB shares (display name, comic count, total size)
- [x] SMB stage + open comic in reader
- [ ] Pull List / Kapowarr / Guided Panel

## Build

Requires Android Studio (JDK 17+) and Android SDK 35.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

Device setup: [docs/DEVICE_SETUP.md](./docs/DEVICE_SETUP.md)

## License

GPL-3.0 as Bubble2. Panel-detection code adapted from Chika will be MPL-2.0 file-level Covered Software with additional model notices (see SPEC).

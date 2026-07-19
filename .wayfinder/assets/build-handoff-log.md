---
title: Cupcake Comics build handoff log
created: 2026-07-15
---

# Build handoff log

Started after [SPEC-v1](../SPEC-v1.md) approval.

## Done this session

1. Cloned Bubble2; remote renamed to `upstream`
2. Rebranded to Cupcake Comics / `com.cupcakecomics.app`
3. minSdk 30, targetSdk 35, compileSdk 36, Kotlin plugin
4. Installed Android Studio (winget) + cmdline-tools + platforms 35/36
5. Wireless-paired Pixel 6 (`oriole`, Android 16)
6. `./gradlew :app:installDebug` → **Installed on 1 device**

## Next implementation sessions (from SPEC §9)

1. Room + encrypted connection profiles  
2. SMB browse + stage + open  
3. Pull List index / WorkManager / notifications  
4. Kapowarr client + Request UI  
5. Panel engine + Guided Panel + left/up swipe  
6. Full acceptance matrix  

Phone package: `com.cupcakecomics.app.debug`

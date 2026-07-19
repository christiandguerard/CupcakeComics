---
title: Define device, test, and release acceptance
labels: [wayfinder:task]
type: task
status: closed
assignee: agent
parent: cupcake-comics-wayfinding.md
blocked_by:
  - 006-approve-the-product-flow.md
created: 2026-07-15
resolved: 2026-07-15
---

# Define device, test, and release acceptance

## Question

What device, toolchain, and acceptance gates must be true before/during the build phase?

## Resolution

### Device (human-owned checklist)
Modern phone, Android 11+:
1. Enable Developer options (tap Build number 7×)
2. Enable Wireless debugging
3. Pair with pairing code when prompted from PC
4. Keep phone + PC on the same reachable LAN/VPN
5. Record model + Android version in build notes on first successful deploy

### PC toolchain
- Android Studio (Koala+/latest stable) with Android SDK 35, Platform-Tools (adb), JDK 17+ (Studio JBR OK)
- Git + ability to clone edeso/bubble2
- Optional: `sdkmanager` / cmdline-tools if installing without full Studio UI

### Interactive vs automated
- Agent can install Studio/SDK where winget/chocolatey allow and run gradle/adb
- Human must: Developer Options, approve wireless pair, grant app storage/network prompts on device

### Test matrix (v1 gate)
| Layer | Cases |
| --- | --- |
| Unit | Kapowarr JSON envelope mapping; Pull List identity + 90% math; panel ordering/planner |
| Integration | Mock Kapowarr HTTP for search/add/task; mock SMB list/stage |
| Instrumented | Left/up page swipe; Guided Panel slot advance; zoom-before-swipe |
| Manual device | Connect real SMB + Kapowarr; open CBZ/CBR from share; Pull List notify; airplane mode then reconnect |
| Performance | Stage + open ≥200MB comic; scroll without OOM |
| Failure | Bad API key, SMB down mid-read, empty root folders, CV rate limit message |

### Release packaging
- Debug APK `com.cupcakecomics.app.debug` for wireless iteration
- Release APK/AAB unsigned-or-local-signed when gate passes; corresponding source present (GPL)
- About screen shows license notices including MPL panel files + model

### Build-phase “done”
Wireless install succeeds; Reader page swipe works; guided optional path works offline; one SMB comic opens; Kapowarr search+add succeeds against user instance; Pull List shows a newly appeared monitored file and removes it at ≥90%.

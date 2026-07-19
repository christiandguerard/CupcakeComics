---
title: Cupcake Comics Wayfinding
labels: [wayfinder:map]
status: closed
created: 2026-07-15
resolved: 2026-07-15
---

# Cupcake Comics Wayfinding

## Destination

Produce an approved, implementation-ready specification for “Cupcake Comics,” a modern-Android fork of [Bubble2](https://github.com/edeso/bubble2), including validated architecture, UX behavior, acceptance criteria, and a phased build/test handoff. Implementation starts only after this specification is approved.

**Destination reached:** [SPEC-v1.md](../SPEC-v1.md) approved 2026-07-15. Build handoff begins.

## Notes

- Domain: Android comic reader (Java Bubble2 base + Kotlin additions), Kapowarr, SMB/Samba, on-device panel detection
- Skills: wayfinder; consult Bubble2 GPL-3.0 and Chika MPL-2.0 / third-party notices on every license-affecting decision
- Preferences locked before charting:
  - Android 11+ target, optimize for the user’s modern phone
  - Virtual in-app Pull List (no copies, moves, or server-side symlinks)
  - Leave Pull List at ≥90% pages read; include manual read/unread override
  - Kapowarr v1: base URL + API key → search → add/monitor volume → trigger downloads
  - Optional Guided Panel mode; otherwise left/up swipe advances whole pages
  - On-device/offline panel inference adapted from Chika (no Chika branding)
- Tracker: local-markdown under `.wayfinder/`
- Plan, don’t ship features inside this map — destination is the approved spec; build is a separate handoff (Notes override only for the final handoff todo after approval)

## Decisions so far

- [Choose the fork and modernization boundary](../tickets/001-choose-the-fork-and-modernization-boundary.md) — `com.cupcakecomics.app` + keep Java `com.nkanaev.comics`; Kotlin for new services; minSdk 30 / target 35; GPL source duties; merge from upstream soft-touch.
- [Define the Kapowarr compatibility contract](../tickets/002-define-the-kapowarr-compatibility-contract.md) — `/api` + `api_key`; search → rootfolder → POST volumes (`auto_search`) → optional tasks; EncryptedSharedPreferences; LAN HTTP ack; stable UI error codes.
- [Choose the SMB streaming and cache model](../tickets/003-choose-the-smb-streaming-and-cache-model.md) — smbj read-only; metadata browse; stage current comic only for parsers; never mutate share.
- [Define library identity, pull-list scanning, and progress semantics](../tickets/004-define-library-identity-pull-list-scanning-and-progress-semantics.md) — identity = share+relpath; baseline then deltas → Pull List; 30m Wi‑Fi poll; leave at ≥90% highest page; notify ON by default.
- [Adapt Chika panel guidance into Bubble2](../tickets/005-adapt-chika-panel-guidance-into-bubble2.md) — MPL panel pipeline + TFLite; GPL GuidedPanelController; optional guided slots; left/up page swipe when guided off.
- [Approve the product flow](../tickets/006-approve-the-product-flow.md) — Pull List home; Library; Request; Settings/Connections; approved prototype asset.
- [Define device, test, and release acceptance](../tickets/007-define-device-test-and-release-acceptance.md) — Studio/SDK 35 + wireless ADB checklist; unit/integration/instrumented/manual gates; debug APK for iteration.

## Not yet specified

- Exact brand/icon package artwork (name locked; assets TBD at build time)
- Fine-grained notification grouping / quiet hours
- Kapowarr webhooks replacing polling (API unlikely; keep polling)
- Offline queue for Kapowarr requests while LAN unreachable
- Thumbnail generation policy for huge remote archives
- Per-comic LTR/RTL override persistence field

## Out of scope

- iOS / desktop clients
- Full Kapowarr settings/admin parity
- Replacing Bubble2’s archive engine wholesale
- Server-side pull-list folders or symlink farms
- Copying Chika trademarks, wordmark, or brand UI kit
- Preserving Bubble2 Android 5–10 as a hard requirement

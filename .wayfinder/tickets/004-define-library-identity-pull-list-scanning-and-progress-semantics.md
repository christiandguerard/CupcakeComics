---
title: Define library identity, pull-list scanning, and progress semantics
labels: [wayfinder:grilling]
type: grilling
status: closed
assignee: agent
parent: cupcake-comics-wayfinding.md
blocked_by:
  - 003-choose-the-smb-streaming-and-cache-model.md
created: 2026-07-15
resolved: 2026-07-15
---

# Define library identity, pull-list scanning, and progress semantics

## Question

How does the virtual Pull List identify files, discover “new”, and decide when something has been read?

## Resolution

1. **Identity:** `shareId` + relative path (normalized). Renames/moves are a **new** identity until remapped manually if we add remap later; v1 does not auto-hash.
2. **Monitored folders:** User picks one or more SMB folders after share connect. Recursive scan of those trees only. Metadata-only (smbj list); no staging during scan.
3. **Newness:** Maintain a DB of known files under monitored folders. Each scan: if a comic file path was **not previously recorded** and now exists → insert into Pull List as unread/new. Notified (default ON). Files present when a folder is first enrolled are baseline-indexed **without** entering the Pull List (avoid dumping the whole library as “new”); only appearances **after** baseline enter the list. (User intent: “wasn’t there before, now exists.”)
4. **Polling:** WorkManager every **30 minutes** while on Wi‑Fi (and on app open / pull-to-refresh). Notifications ON by default with Settings toggle to disable.
5. **Read:** Leave Pull List when `highestPageIndexReached / pageCount ≥ 0.90`, or when user marks Read. Mark Unread re-adds. Progress stored locally by identity key.
6. **Delete/offline:** Missing on scan → mark offline/missing; keep progress; remove from Pull List only if user clears or marks read. Opening requires share reachability; progress never wiped by disconnect.
7. **Formats:** Same comic archive extensions Bubble2 recognizes.

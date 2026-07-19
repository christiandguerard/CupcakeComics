---
title: Choose the fork and modernization boundary
labels: [wayfinder:research]
type: research
status: closed
assignee: agent
parent: cupcake-comics-wayfinding.md
blocked_by: []
created: 2026-07-15
resolved: 2026-07-15
---

# Choose the fork and modernization boundary

## Question

What is the safe fork/modernization boundary for Cupcake Comics on top of Bubble2?

## Resolution

**Upstream:** [edeso/bubble2](https://github.com/edeso/bubble2) (GPL-3.0). Keep Java package `com.nkanaev.comics` for merge cleanliness; brand separately.

1. **Identity:** Display name **Cupcake Comics**; `applicationId` **`com.cupcakecomics.app`** (debug suffix `.debug`).
2. **Packages:** Do **not** mass-rename upstream Java. New Kotlin lives under `com.cupcakecomics.*`.
3. **Split:** Keep parsers, reader views, Scanner, Storage, ReaderFragment as Java. Add Kotlin for network, SMB, Room façades, WorkManager, panel detection glue.
4. **SDK:** `minSdk 30` (Android 11), `targetSdk`/`compileSdk` **35** (bump with Play requirements).
5. **GPL-3.0:** Ship corresponding source with binaries; preserve notices; attribute Bubble/Bubble2; keep copyleft for the combined work; document third-party deps.
6. **Upstream sync:** remotes `origin` (Cupcake) + `upstream` (edeso/bubble2); merge periodically; avoid reformatting/renaming core Java.

**Touchpoints:** `ParserFactory`, `ReaderFragment`/`ReaderActivity`, `Scanner`, `Storage`/`Comic`, `PageImageView`/`ComicViewPager`, `AboutFragment`.

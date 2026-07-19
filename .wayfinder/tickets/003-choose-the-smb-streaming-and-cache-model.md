---
title: Choose the SMB streaming and cache model
labels: [wayfinder:research]
type: research
status: closed
assignee: agent
parent: cupcake-comics-wayfinding.md
blocked_by:
  - 001-choose-the-fork-and-modernization-boundary.md
created: 2026-07-15
resolved: 2026-07-15
---

# Choose the SMB streaming and cache model

## Question

How should Cupcake Comics read comics from Samba without copying the library or breaking Bubble2’s archive parsers?

## Resolution

1. **Library:** hierynomus **smbj** ≥ 0.14.0 under `com.cupcakecomics.smb.*` (SMB2/3). jcifs-ng is fallback only; SAF SMB is not primary.
2. **Auth:** username/password + optional domain; guest mode; EncryptedSharedPreferences; Test Connection UX; document prefer read-only NAS account.
3. **Browse vs open:** Browse/list/pull-scan = metadata only. Open archive = stage **current comic only** to seekable local `File`, then existing `ParserFactory`. Image folders = on-demand page stage into a session directory. No whole-library mirror.
4. **Cache:** `…/smb-stage/{shareId}/{pathHash}/`; max 2 staged comics; ≤2 GiB or 20% free space; LRU + delete on reader exit; cancel cleans partials.
5. **Timeouts:** connect 10s, read 30s; reconnect on transport death; preserve local progress DB across disconnects; no share write path.
6. **Invariant:** read-only SMB client — never create/write/delete/rename/symlink on the share for pull lists or anything else.

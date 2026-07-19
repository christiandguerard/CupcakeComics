---
title: Define the Kapowarr compatibility contract
labels: [wayfinder:research]
type: research
status: closed
assignee: agent
parent: cupcake-comics-wayfinding.md
blocked_by: []
created: 2026-07-15
resolved: 2026-07-15
---

# Define the Kapowarr compatibility contract

## Question

What exact Kapowarr HTTP/API contract must Cupcake Comics implement for v1?

## Resolution

Kapowarr API is under `{base}/api` with **`api_key` query param**. Success envelope `{ "error": null, "result": T }`. Default LAN is HTTP `:5656`.

### v1 call sequence
1. Persist `base_url` + `api_key` → `POST /api/auth/check` (or `GET /api/system/about` for version).
2. `GET /api/rootfolder` — require ≥1 root folder.
3. `GET /api/volumes/search?query=` — honor `already_added`.
4. `POST /api/volumes` with `comicvine_id`, `root_folder_id`, `monitor`, `monitoring_scheme` (`all`|`missing`|`none`), `monitor_new_issues`, `auto_search: true`.
5. If needed: `POST /api/system/tasks` `{ "cmd": "auto_search", "volume_id": N }`.

### Credentials
EncryptedSharedPreferences (Keystore-backed MasterKey). Never log API keys.

### LAN/TLS
Allow cleartext only for private-network Kapowarr after one-time acknowledgment. Public HTTP hard-blocked/strongly warned. Self-signed HTTPS needs explicit trust.

### UI error taxonomy
`AUTH_INVALID`, `SERVER_UNREACHABLE`, `CLEARTEXT_BLOCKED`, `TLS_UNTRUSTED`, `CV_NOT_CONFIGURED`, `CV_RATE_LIMITED`, `ALREADY_ADDED`, `NO_ROOT_FOLDER`, `VALIDATION`, `NOT_FOUND`, `TASK_REJECTED`, `SERVER_ERROR`, `UNKNOWN`.

### Out of v1
Root-folder create, Socket.IO UI, settings mutation, mass editor, manual download-link picking, ComicVine key management inside Kapowarr.

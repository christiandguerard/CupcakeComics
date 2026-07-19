# Local Markdown Wayfinder Tracker

Cupcake Comics uses a local-markdown issue tracker because no remote tracker was configured.

## Labels

- `wayfinder:map` — the map issue
- `wayfinder:research` | `wayfinder:prototype` | `wayfinder:grilling` | `wayfinder:task`

## Layout

- [`maps/`](maps/) — map issues
- [`tickets/`](tickets/) — child decision tickets
- Status lives in each ticket’s YAML frontmatter: `status`, `assignee`, `blocked_by`, `type`

## Wayfinding operations

| Operation | How |
| --- | --- |
| Create map | Add `maps/<slug>.md` with `labels: [wayfinder:map]` |
| Create ticket | Add `tickets/<nnn>-<slug>.md` with `parent` pointing at the map |
| Claim | Set `assignee` before work |
| Block | List ticket filenames in `blocked_by` |
| Frontier | Open tickets whose every `blocked_by` entry is `status: closed` and `assignee` is empty |
| Resolve | Append a `## Resolution` section, set `status: closed`, add one gist line under the map’s Decisions so far |

Refer to maps and tickets **by title**, wrapping any path/id inside the name.

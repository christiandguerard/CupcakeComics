---
title: Adapt Chika panel guidance into Bubble2
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

# Adapt Chika panel guidance into Bubble2

## Question

Which pieces of Chika’s panel pipeline can be adapted into Bubble2’s reader, under which license notices, and how do gestures behave?

## Resolution

1. **Adapt:** MPL files for Panel model, ordering, planner, pipeline, YOLO decode, camera math + Android `MlPanelDetector` + TFLite asset. **Rewrite:** GuidedPanelController (GPL). **Exclude:** Compose UI, Chika brand kit, Chika library/archives.
2. **Notices:** GPL-3.0 host; keep MPL Exhibit A on covered files and dual-offer MPL+GPL; Apache-2.0 model + Manga109-s disclosure + TFLite NOTICE; no Chika trademarks.
3. **Runtime:** Detection only when Guided Panel ON; lazy + prefetch next page; cache key `(comicId, page, RTL, modelId, pipelineVersion)`; one Interpreter; geometry LRU; fallback to whole-page slots if model fails/empty.
4. **Gestures:** Guided ON → advance/reverse steps slots then pages; pinch/pan wins until edge (`canScroll*`). Guided OFF → left/up advances whole pages; existing Bubble2 zoom/pan unchanged.
5. **Direction:** Map Bubble2 `SETTINGS_READING_LEFT_TO_RIGHT` to pipeline `rightToLeft` + `PanelPlanner.Config` / `Config.MANGA`. Clear cache on direction change.

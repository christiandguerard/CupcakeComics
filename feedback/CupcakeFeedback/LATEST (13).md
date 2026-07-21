# Cupcake Comics feedback — 20260719_204851

> Paste this file (and the PNG if present) into Cursor when reporting a bug or asking for a change.

## Context

- **Time:** 2026-07-19 20:48:51 -0400
- **App:** com.cupcakecomics.app.debug 0.1.0-DEBUG (1)
- **Activity:** com.nkanaev.comics.activity.ReaderActivity
- **Title:** Cupcake Comics
- **Visible fragments:**
  - CupcakeReaderFragment args=[PARAM_HANDLER, PARAM_IDENTITY_KEY, PARAM_MODE]
- **Intent action:** (none)
- **Intent extras:**
  - `PARAM_HANDLER` = /data/user/0/com.cupcakecomics.app.debug/files/offline-comics/a1f53506870f839924cbf381/Absolute Batman (2024) Volume …
  - `PARAM_IDENTITY_KEY` = smb:1:Absolute Batman/Volume 01 (2024)/Absolute Batman (2024) Volume 01 Issue 022.cbz
  - `PARAM_MODE` = MODE_BROWSER
- **User note:** (see below)

## Notes

When I'm zooming in, I'm getting this white border around the actual comic page. That shouldn't be there

## Screenshot

![screenshot](feedback_20260719_204851.png)

_File: `feedback_20260719_204851.png`_

## Pull into project

```bat
adb pull /sdcard/Download/CupcakeFeedback/ .\feedback\
```


# Cupcake Comics feedback — 20260718_134609

> Paste this file (and the PNG if present) into Cursor when reporting a bug or asking for a change.

## Context

- **Time:** 2026-07-18 13:46:09 -0400
- **App:** com.cupcakecomics.app.debug 0.1.0-DEBUG (1)
- **Activity:** com.nkanaev.comics.activity.ReaderActivity
- **Title:** Cupcake Comics
- **Visible fragments:**
  - CupcakeReaderFragment args=[PARAM_HANDLER, PARAM_MODE]
- **Intent action:** (none)
- **Intent extras:**
  - `PARAM_HANDLER` = /data/user/0/com.cupcakecomics.app.debug/files/offline-comics/b2a13ce689807b12da379135/Absolute Batman (2024) Volume …
  - `PARAM_MODE` = MODE_BROWSER
- **User note:** (see below)

## Notes

I'd like a feature when I tap the screen that is a share button that let's me send the page or potentially a crop of the page. It should let me send that page/crop to any app that it normally would with the share feature.

## Screenshot

![screenshot](feedback_20260718_134609.png)

_File: `feedback_20260718_134609.png`_

## Pull into project

```bat
adb pull /sdcard/Download/CupcakeFeedback/ .\feedback\
```


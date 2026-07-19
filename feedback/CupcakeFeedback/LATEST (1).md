# Cupcake Comics feedback — 20260717_192213

> Paste this file (and the PNG if present) into Cursor when reporting a bug or asking for a change.

## Context

- **Time:** 2026-07-17 19:22:13 -0400
- **App:** com.cupcakecomics.app.debug 0.1.0-DEBUG (1)
- **Activity:** com.nkanaev.comics.activity.MainActivity
- **Title:** Comic Book Vault
- **Visible fragments:**
  - SmbBrowseFragment args=[shareId]
- **Back stack (1):**
  - 0
- **Intent action:** android.intent.action.MAIN
- **Selected / checked views:**
  - CheckedTextView · id=design_menu_item_text · text="Library" · checked
- **User note:** (see below)

## Notes

I should be able to monitor a main folder for the pull list. But not individual volumes. Also I should be able to hold and select and select a bunch of folders if I want them all to be selected

## Screenshot

![screenshot](feedback_20260717_192213.png)

_File: `feedback_20260717_192213.png`_

## Pull into project

```bat
adb pull /sdcard/Download/CupcakeFeedback/ .\feedback\
```


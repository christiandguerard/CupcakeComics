# Cupcake Comics feedback — 20260718_162323

> Paste this file (and the PNG if present) into Cursor when reporting a bug or asking for a change.

## Context

- **Time:** 2026-07-18 16:23:23 -0400
- **App:** com.cupcakecomics.app.debug 0.1.0-DEBUG (1)
- **Activity:** com.nkanaev.comics.activity.MainActivity
- **Title:** Library
- **Visible fragments:**
  - LibraryFragment
- **Intent action:** android.intent.action.MAIN
- **Selected / checked views:**
  - CheckedTextView · id=design_menu_item_text · text="Library" · checked
- **User note:** (see below)

## Notes

I'd love for downloaded comics, when they're over 4 comics, you should start layering them over eachother and put some tabs at the top of the comic that show you what's in that section. You should layer the comics so that like approximately 20% of the comic is still showing when it's got another comic layered on top of it. If I click on a section, the screen should darken and I should be able to see all the comics layed out in a grid fashion for me to pick and select which I'd like to read

## Pull into project

```bat
adb pull /sdcard/Download/CupcakeFeedback/ .\feedback\
```


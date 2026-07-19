# Cupcake Comics — feedback inbox

Enable **Settings → Debug → In-app feedback** on the phone. Tap the floating pencil button anywhere, add a note, and submit.

Each capture writes:

- `feedback_YYYYMMDD_HHMMSS.md` — context, note, screenshot link
- `feedback_YYYYMMDD_HHMMSS.png` — screenshot
- `LATEST.md` — most recent report (markdown is also copied to the clipboard)

On device these live under **Downloads/CupcakeFeedback/**. Pull them into this folder:

```bat
adb pull /sdcard/Download/CupcakeFeedback/ .\feedback\
```

Then open the `.md` file, review it, and paste it into Cursor.

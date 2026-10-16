# OpenReader

Android app that opens Microsoft Office documents — `.docx`, `.xlsx`, `.pptx` and the legacy `.doc`, `.xls`, `.ppt` — with no third-party libraries (no Apache POI).

- **Viewer**: each file is parsed from its OOXML package (own lightweight parser) into HTML and shown in a WebView (JavaScript disabled) with pinch-zoom.
  - Word: headings, bold/italic/underline, colors, lists, tables, images.
  - Excel: all sheets as tables with tab links, shared strings, dates, row/column caps.
  - PowerPoint: slides in order, positioned text/pictures/tables, groups, speaker notes.
- **Export to PDF**: toolbar action opens the system print dialog; choose *Save as PDF*.
- Opens files from the in-app picker or from other apps (`ACTION_VIEW`).
- Legacy formats (OLE2) are read by a built-in parser and shown as text/tables only: no fonts, colours or images. Old `.ppt` files that keep edit history may show earlier slide versions. Password-protected files are rejected.

## Build

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) runs tests, builds the debug APK and uploads it as an artifact.

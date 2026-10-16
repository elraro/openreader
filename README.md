# OpenReader

Android app that opens Microsoft Office documents — `.docx`, `.xlsx`, `.pptx` — without any Office dependency.

- **Viewer**: each file is parsed from its OOXML package (own lightweight parser) into HTML and shown in a WebView (JavaScript disabled) with pinch-zoom.
  - Word: headings, bold/italic/underline, colors, lists, tables, images.
  - Excel: all sheets as tables with tab links, shared strings, dates, row/column caps.
  - PowerPoint: slides in order, positioned text/pictures/tables, groups, speaker notes.
- **Export to PDF**: toolbar action opens the system print dialog; choose *Save as PDF*.
- Opens files from the in-app picker or from other apps (`ACTION_VIEW`).
- Legacy binary formats (`.doc`, `.xls`, `.ppt`) are not supported.

## Build

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) runs tests, builds the debug APK and uploads it as an artifact.

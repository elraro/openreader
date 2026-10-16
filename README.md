# OpenReader

Android app that opens Microsoft Office documents — `.docx`, `.xlsx`, `.pptx` and the legacy `.doc`, `.xls`, `.ppt` — with no third-party libraries (no Apache POI).

- **Viewer**: each file is parsed from its OOXML package (own lightweight parser) into HTML and shown in a WebView (JavaScript disabled) with pinch-zoom. Pages and slides keep their real size and break where the document does, to stay close to the original.
  - Word: styles (with inheritance), theme fonts, numbering, tables (borders, shading, merges, table styles), headers/footers, footnotes, images, text boxes, hyperlinks, page size and margins.
  - Excel: every sheet with cell styles (fonts, fills, borders, alignment), number/date/percent/currency formats, column widths, merged cells, hidden rows/columns.
  - PowerPoint: slide/layout/master inheritance, theme colours and fonts, placeholders, bullets, shapes, pictures with cropping, tables, groups, speaker notes.
  - Fonts: Calibri, Cambria, Arial, Times New Roman and Courier New are replaced by bundled metric-compatible fonts (Carlito, Caladea, Liberation; SIL OFL) so line breaks match.
- **Export to PDF**: toolbar action opens the system print dialog; choose *Save as PDF*.
- Known gaps: Word's own automatic pagination (only explicit/recorded page breaks are honoured), charts, SmartArt, EMF/WMF images, macros, tracked-change markup, formulas without a stored result.
- Opens files from the in-app picker or from other apps (`ACTION_VIEW`).
- Legacy formats (OLE2) are read by a built-in parser and shown as text/tables only: no fonts, colours or images. Old `.ppt` files that keep edit history may show earlier slide versions. Password-protected files are rejected.

## Build

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) runs tests, builds the debug APK and uploads it as an artifact.

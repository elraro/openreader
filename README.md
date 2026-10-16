# OpenReader

Android app that opens Microsoft Office documents — `.docx`, `.xlsx`, `.pptx` and the legacy `.doc`, `.xls`, `.ppt` — with no third-party libraries (no Apache POI).

- **Viewer**: each file is parsed from its OOXML package (own lightweight parser) into HTML and shown in a WebView (JavaScript disabled) with pinch-zoom. Pages and slides keep their real size and break where the document does, to stay close to the original.
  - Word: styles (with inheritance), theme fonts, numbering, tables (borders, shading, merges, table styles), headers/footers, footnotes, images, text boxes, hyperlinks, page size and margins.
  - Excel: every sheet with cell styles (fonts, fills, borders, alignment), number/date/percent/currency formats, column widths, merged cells, hidden rows/columns.
  - PowerPoint: slide/layout/master inheritance, theme colours and fonts, placeholders, bullets, shapes, pictures with cropping, tables, groups, speaker notes.
  - Fonts: Calibri, Cambria, Arial, Times New Roman and Courier New are replaced by bundled metric-compatible fonts (Carlito, Caladea, Liberation; SIL OFL) so line breaks match.
- **Export to PDF**: toolbar action opens the system print dialog; choose *Save as PDF*.
  - Charts (bar/column, line, area, pie/doughnut, scatter, radar, stock, plus waterfall, funnel, treemap, sunburst, box & whisker and histogram) in Word, Excel and PowerPoint, drawn as SVG from the chart data; SmartArt via its cached drawing; EMF/WMF images converted to SVG; tracked changes shown as insertions/deletions; spreadsheet formulas without a stored result are calculated (common functions).
- **Settings** (overflow menu): light/dark/system theme, dark mode for documents, language (follows the device language by default; English, Spanish, Catalan, French, German, Italian, Portuguese, Russian, Chinese, Japanese), link to the source repository and a donation link (paypal.me/elraro).
- Word documents are paginated on the device: pages end where Word recorded a break, and longer pages are split by measuring the rendered content (tables row by row). Page counts can differ slightly from Word's.
- Known gaps: Word-exact page counts (pages are laid out with the bundled metric-compatible fonts, not Word's own engine); chart kinds without a classic or chartex definition (region maps are drawn as a bar chart of their values); SmartArt without a stored drawing (shown as plain boxes); spreadsheet functions beyond the ~170 supported (shown as #NAME?); Mac PICT pictures that don't embed a JPEG/PNG; shape outlines beyond rectangles, ellipses, rounded rectangles, common polygons/stars and the heart in `.doc`/`.ppt`.
- Opens files from the in-app picker or from other apps (`ACTION_VIEW`).
- Legacy formats (OLE2) are read by a built-in parser. `.xls` keeps cell fonts, colours, fills, borders, alignment, widths and merges; `.doc` keeps styles, fonts, sizes, colours, alignment, spacing, bullets and numbering, headers/footers, footnotes, inline and floating pictures (JPEG/PNG/DIB/EMF/WMF), WordArt text, simple filled shapes, text boxes, tables and page setup; `.ppt` is drawn slide by slide with shape positions, fills, lines, common auto shapes, pictures, formatted text, speaker notes and master placeholders, following the newest edit so earlier versions of a slide don't appear. Old `.ppt` files that keep edit history may show earlier slide versions. Password-protected files are rejected.

## Build

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) runs tests, builds the debug APK and uploads it as an artifact.

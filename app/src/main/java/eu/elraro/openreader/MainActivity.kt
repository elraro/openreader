package eu.elraro.openreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var empty: LinearLayout
    private lateinit var spinner: ProgressBar
    private var docTitle = ""
    private var pageLoaded = false
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (!url.startsWith(Fonts.BASE_URL + "fonts/")) return null
                    return try {
                        val name = url.removePrefix(Fonts.BASE_URL + "fonts/").substringBefore('?')
                        if (!name.matches(Regex("[A-Za-z]+-[A-Za-z]+\\.ttf"))) return null
                        WebResourceResponse("font/ttf", null, assets.open("fonts/$name")).apply {
                            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                        }
                    } catch (e: java.io.IOException) {
                        null
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.host == "appassets.local") return false // in-page anchors (sheet tabs)
                    if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    }
                    return true // never navigate inside the viewer
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    pageLoaded = true
                    spinner.visibility = View.GONE
                    invalidateOptionsMenu()
                }
            }
            visibility = View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply {
                text = getString(R.string.empty_hint)
                gravity = Gravity.CENTER
                textSize = 16f
            })
            addView(Button(context).apply {
                text = getString(R.string.open_document)
                setOnClickListener { pickDocument() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        root.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        val uri = incomingUri(intent)
        if (uri != null) openUri(uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { openUri(it) }
    }

    private fun incomingUri(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        else -> null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_OPEN, 0, R.string.open_document).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_PDF, 1, R.string.export_pdf).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_PDF)?.isEnabled = pageLoaded
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_OPEN -> { pickDocument(); true }
        MENU_PDF -> { exportPdf(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun pickDocument() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME_DOCX, MIME_XLSX, MIME_PPTX, MIME_DOC, MIME_XLS, MIME_PPT))
        }
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { openUri(it) }
    }

    private fun openUri(uri: Uri) {
        pageLoaded = false
        invalidateOptionsMenu()
        spinner.visibility = View.VISIBLE
        val name = displayName(uri)
        worker.execute {
            val result = try {
                Result.success(convert(uri, name))
            } catch (e: Exception) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(UnsupportedFormat(getString(R.string.error_too_large)))
            } catch (e: StackOverflowError) {
                Result.failure(UnsupportedFormat(getString(R.string.error_too_large)))
            }
            runOnUiThread {
                result.onSuccess { html ->
                    docTitle = name
                    title = name
                    empty.visibility = View.GONE
                    web.visibility = View.VISIBLE
                    web.loadDataWithBaseURL(Fonts.BASE_URL, html, "text/html", "utf-8", null)
                }.onFailure { e ->
                    spinner.visibility = View.GONE
                    val msg = if (e is UnsupportedFormat) e.message else getString(R.string.error_read, e.message ?: e.javaClass.simpleName)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private class UnsupportedFormat(msg: String) : Exception(msg)

    private fun convert(uri: Uri, name: String): String {
        val tmp = File(cacheDir, "current.ooxml")
        contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("Cannot open file")
        val head = ByteArray(8)
        tmp.inputStream().use { it.read(head) }
        if (Cfb.hasMagic(head)) return convertLegacy(tmp, name)
        val pkg = try {
            OoxmlPackage(tmp)
        } catch (e: java.util.zip.ZipException) {
            throw UnsupportedFormat(getString(R.string.error_unsupported))
        }
        pkg.use {
            return when {
                it.has("word/document.xml") -> DocxConverter.toHtml(it, name)
                it.has("xl/workbook.xml") -> XlsxConverter.toHtml(it, name)
                it.has("ppt/presentation.xml") -> PptxConverter.toHtml(it, name)
                else -> throw UnsupportedFormat(getString(R.string.error_unsupported))
            }
        }
    }

    private fun convertLegacy(file: File, name: String): String {
        val cfb = Cfb.parse(file.readBytes())
        return when {
            cfb.hasStream("EncryptedPackage") -> throw UnsupportedFormat(getString(R.string.error_encrypted))
            cfb.hasStream("WordDocument") -> DocConverter.toHtml(cfb, name)
            cfb.hasStream("Workbook") || cfb.hasStream("Book") -> XlsConverter.toHtml(cfb, name)
            cfb.hasStream("PowerPoint Document") -> PptConverter.toHtml(cfb, name)
            else -> throw UnsupportedFormat(getString(R.string.error_unsupported))
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "document"
    }

    /** Opens the system print dialog, whose "Save as PDF" target writes the PDF. */
    private fun exportPdf() {
        if (!pageLoaded) return
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        val jobName = docTitle.substringBeforeLast('.').ifEmpty { "document" }
        pm.print(jobName, web.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_OPEN = 1
        private const val MENU_OPEN = 1
        private const val MENU_PDF = 2
        private const val MIME_DOC = "application/msword"
        private const val MIME_XLS = "application/vnd.ms-excel"
        private const val MIME_PPT = "application/vnd.ms-powerpoint"
        private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}

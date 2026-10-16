package eu.elraro.openreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Prefs.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Prefs.themeRes(this))
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(res: Int) = TextView(this).apply {
            setText(res); textSize = 14f; setPadding(0, pad, 0, pad / 4); setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        col.addView(label(R.string.theme))
        val themes = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).map { getString(it) }
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, themes)
            setSelection(Prefs.theme(context))
            onItemSelectedListener = onChange(Prefs.theme(context)) { Prefs.setTheme(context, it); applyChange() }
        })

        col.addView(CheckBox(this).apply {
            setText(R.string.dark_documents)
            isChecked = Prefs.darkDocuments(context)
            setOnCheckedChangeListener { _, v -> Prefs.setDarkDocuments(context, v) }
        })

        col.addView(label(R.string.language))
        val names = Prefs.LANGUAGES.map { if (it.first.isEmpty()) getString(R.string.language_system) else it.second }
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, names)
            val cur = Prefs.LANGUAGES.indexOfFirst { it.first == Prefs.language(context) }.coerceAtLeast(0)
            setSelection(cur)
            onItemSelectedListener = onChange(cur) { Prefs.setLanguage(context, Prefs.LANGUAGES[it].first); applyChange() }
        })

        col.addView(label(R.string.about))
        col.addView(TextView(this).apply {
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
            text = getString(R.string.app_name) + " " + v + "\n" + getString(R.string.about_text)
        })
        col.addView(link(R.string.source_code, Prefs.REPO_URL))
        col.addView(link(R.string.donate, Prefs.DONATE_URL))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun link(label: Int, url: String) = Button(this).apply {
        setText(label)
        setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
    }

    private fun onChange(initial: Int, f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        var last = initial
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (pos != last) { last = pos; f(pos) }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun applyChange() {
        setResult(RESULT_OK)
        recreate()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

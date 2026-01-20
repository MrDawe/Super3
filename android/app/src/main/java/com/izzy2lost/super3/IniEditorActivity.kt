package com.izzy2lost.super3

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

class IniEditorActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE_URI = "treeUri"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathText: TextView
    private lateinit var editor: EditText

    private var iniDoc: DocumentFile? = null
    private var suppressDirty = false
    private var dirty = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        setContentView(R.layout.activity_ini_editor)

        toolbar = findViewById(R.id.ini_toolbar)
        pathText = findViewById(R.id.ini_path)
        editor = findViewById(R.id.ini_editor)

        toolbar.inflateMenu(R.menu.menu_ini_editor)
        toolbar.setNavigationOnClickListener { maybeExit() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> {
                    saveIni()
                    true
                }
                else -> false
            }
        }
        tintMenuIcons()
        updateSaveEnabled()

        editor.addTextChangedListener {
            if (suppressDirty || busy) return@addTextChangedListener
            if (!dirty) {
                dirty = true
                toolbar.subtitle = "Unsaved changes"
                updateSaveEnabled()
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            maybeExit()
        }

        val treeUri = intent.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse)
        if (treeUri == null) {
            Toast.makeText(this, "Data folder not set", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pathText.text = "Editing: Config/Supermodel.ini\nData folder: $treeUri"
        loadIni(treeUri)
    }

    private fun maybeExit() {
        if (busy) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        if (!dirty) {
            finish()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Discard changes?")
            .setMessage("You have unsaved changes to Supermodel.ini.")
            .setPositiveButton("Save") { _, _ -> saveIni(finishAfter = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun loadIni(treeUri: Uri) {
        setBusy(true, "Loading...")
        thread(name = "Super3IniLoad") {
            val doc = IniDocumentStore.ensureIniDocument(this, treeUri)
            val text = doc?.let { IniDocumentStore.readIniText(this, it) }
            runOnUiThread {
                if (doc == null || text == null) {
                    setBusy(false, null)
                    Toast.makeText(this, "Failed to load Supermodel.ini", Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                iniDoc = doc
                suppressDirty = true
                editor.setText(text)
                editor.setSelection(0)
                suppressDirty = false
                dirty = false
                toolbar.subtitle = null
                updateSaveEnabled()
                setBusy(false, null)
            }
        }
    }

    private fun saveIni(finishAfter: Boolean = false) {
        val doc = iniDoc ?: return
        val text = editor.text?.toString() ?: ""
        setBusy(true, "Saving...")
        thread(name = "Super3IniSave") {
            val ok = IniDocumentStore.writeIniText(this, doc, text)
            runOnUiThread {
                setBusy(false, null)
                if (!ok) {
                    Toast.makeText(this, "Failed to save Supermodel.ini", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                dirty = false
                toolbar.subtitle = "Saved"
                updateSaveEnabled()
                if (finishAfter) {
                    finish()
                }
            }
        }
    }

    private fun setBusy(isBusy: Boolean, status: String?) {
        busy = isBusy
        editor.isEnabled = !isBusy
        toolbar.menu.findItem(R.id.action_save)?.isEnabled = !isBusy && dirty
        if (status != null) {
            toolbar.subtitle = status
        } else if (!isBusy) {
            toolbar.subtitle = null
        }
    }

    private fun updateSaveEnabled() {
        val saveItem = toolbar.menu.findItem(R.id.action_save)
        saveItem?.isEnabled = dirty && !busy
        tintMenuIcons()
    }

    private fun tintMenuIcons() {
        val enabledColor =
            MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnPrimary)
        val disabledColor =
            MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val saveItem = toolbar.menu.findItem(R.id.action_save)
        val tint = if (saveItem?.isEnabled == true) enabledColor else disabledColor
        saveItem?.icon?.setTint(tint)
    }

    // IniDocumentStore handles SAF reads/writes + file selection.
}

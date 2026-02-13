package com.izzy2lost.super3

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

class IniSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnPpcFrequency: MaterialButton
    private lateinit var btnMultithreaded: MaterialButton
    private lateinit var btnGpuMultithreaded: MaterialButton
    private lateinit var btnVsync: MaterialButton
    private lateinit var btnEmulateSound: MaterialButton
    private lateinit var btnSoundVolume: MaterialButton
    private lateinit var btnMusicVolume: MaterialButton
    private lateinit var btnNetworkEnabled: MaterialButton
    private lateinit var btnSimulateNet: MaterialButton
    private lateinit var btnAddressOut: MaterialButton
    private lateinit var btnPortIn: MaterialButton
    private lateinit var btnPortOut: MaterialButton

    private var iniDoc: DocumentFile? = null
    private var iniLines: MutableList<String> = mutableListOf()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        setContentView(R.layout.activity_ini_settings)

        toolbar = findViewById(R.id.ini_settings_toolbar)
        btnPpcFrequency = findViewById(R.id.btn_ppc_frequency)
        btnMultithreaded = findViewById(R.id.btn_multithreaded)
        btnGpuMultithreaded = findViewById(R.id.btn_gpu_multithreaded)
        btnVsync = findViewById(R.id.btn_vsync)
        btnEmulateSound = findViewById(R.id.btn_emulate_sound)
        btnSoundVolume = findViewById(R.id.btn_sound_volume)
        btnMusicVolume = findViewById(R.id.btn_music_volume)
        btnNetworkEnabled = findViewById(R.id.btn_network_enabled)
        btnSimulateNet = findViewById(R.id.btn_simulate_net)
        btnAddressOut = findViewById(R.id.btn_address_out)
        btnPortIn = findViewById(R.id.btn_port_in)
        btnPortOut = findViewById(R.id.btn_port_out)

        toolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this) { finish() }

        val treeUri = intent.getStringExtra(IniEditorActivity.EXTRA_TREE_URI)?.let(Uri::parse)
        if (treeUri == null) {
            Toast.makeText(this, "Data folder not set", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindActions()
        loadIni(treeUri)
    }

    private fun bindActions() {
        btnPpcFrequency.setOnClickListener {
            val current = readIniInt("PowerPCFrequency") ?: 50
            showNumberDialog(
                title = "PowerPC frequency",
                key = "PowerPCFrequency",
                current = current,
                min = 10,
                max = 200,
            )
        }

        btnMultithreaded.setOnClickListener {
            val enabled = btnMultithreaded.isChecked
            applyUpdate(
                updates = mapOf("MultiThreaded" to if (enabled) "1" else "0"),
                onApplied = { btnMultithreaded.isChecked = enabled },
                onFailed = { btnMultithreaded.isChecked = !enabled },
            )
        }

        btnGpuMultithreaded.setOnClickListener {
            val enabled = btnGpuMultithreaded.isChecked
            applyUpdate(
                updates = mapOf("GPUMultiThreaded" to if (enabled) "1" else "0"),
                onApplied = { btnGpuMultithreaded.isChecked = enabled },
                onFailed = { btnGpuMultithreaded.isChecked = !enabled },
            )
        }

        btnVsync.setOnClickListener {
            val enabled = btnVsync.isChecked
            applyUpdate(
                updates = mapOf("VSync" to if (enabled) "1" else "0"),
                onApplied = { btnVsync.isChecked = enabled },
                onFailed = { btnVsync.isChecked = !enabled },
            )
        }

        btnEmulateSound.setOnClickListener {
            val enabled = btnEmulateSound.isChecked
            applyUpdate(
                updates = mapOf("EmulateSound" to if (enabled) "1" else "0"),
                onApplied = { btnEmulateSound.isChecked = enabled },
                onFailed = { btnEmulateSound.isChecked = !enabled },
            )
        }

        btnSoundVolume.setOnClickListener {
            val current = readIniInt("SoundVolume") ?: 100
            showNumberDialog(
                title = "Sound volume",
                key = "SoundVolume",
                current = current,
                min = 0,
                max = 200,
            )
        }

        btnMusicVolume.setOnClickListener {
            val current = readIniInt("MusicVolume") ?: 150
            showNumberDialog(
                title = "Music volume",
                key = "MusicVolume",
                current = current,
                min = 0,
                max = 200,
            )
        }

        btnNetworkEnabled.setOnClickListener {
            val enabled = btnNetworkEnabled.isChecked
            applyUpdate(
                updates = mapOf("Network" to if (enabled) "1" else "0"),
                onApplied = { btnNetworkEnabled.isChecked = enabled },
                onFailed = { btnNetworkEnabled.isChecked = !enabled },
            )
        }

        btnSimulateNet.setOnClickListener {
            val enabled = btnSimulateNet.isChecked
            applyUpdate(
                updates = mapOf("SimulateNet" to if (enabled) "1" else "0"),
                onApplied = { btnSimulateNet.isChecked = enabled },
                onFailed = { btnSimulateNet.isChecked = !enabled },
            )
        }

        btnAddressOut.setOnClickListener {
            val current = readIniStringUnquoted("AddressOut").orEmpty().ifBlank { "127.0.0.1" }
            showTextDialog(
                title = "Target address (AddressOut)",
                key = "AddressOut",
                current = current,
            )
        }

        btnPortIn.setOnClickListener {
            val current = readIniInt("PortIn") ?: 1970
            showNumberDialog(
                title = "Listen port (PortIn)",
                key = "PortIn",
                current = current,
                min = 1,
                max = 65535,
            )
        }

        btnPortOut.setOnClickListener {
            val current = readIniInt("PortOut") ?: 1971
            showNumberDialog(
                title = "Send port (PortOut)",
                key = "PortOut",
                current = current,
                min = 1,
                max = 65535,
            )
        }
    }

    private fun loadIni(treeUri: Uri) {
        setBusy(true, "Loading...")
        thread(name = "Super3IniSettingsLoad") {
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
                iniLines = text.split("\n").toMutableList()
                applyUiFromIni()
                setBusy(false, null)
            }
        }
    }

    private fun applyUiFromIni() {
        val ppc = readIniInt("PowerPCFrequency") ?: 50
        btnPpcFrequency.text = "PowerPC frequency: $ppc"

        btnMultithreaded.isChecked = readIniBool("MultiThreaded") ?: true
        btnGpuMultithreaded.isChecked = readIniBool("GPUMultiThreaded") ?: false
        btnVsync.isChecked = readIniBool("VSync") ?: true
        btnEmulateSound.isChecked = readIniBool("EmulateSound") ?: true

        val sound = readIniInt("SoundVolume") ?: 100
        val music = readIniInt("MusicVolume") ?: 150
        btnSoundVolume.text = "Sound volume: $sound"
        btnMusicVolume.text = "Music volume: $music"

        val networkEnabled = readIniBool("Network") ?: false
        val simulateNet = readIniBool("SimulateNet") ?: true
        val portIn = readIniInt("PortIn")?.coerceIn(1, 65535) ?: 1970
        val portOut = readIniInt("PortOut")?.coerceIn(1, 65535) ?: 1971
        val addressOut = readIniStringUnquoted("AddressOut").orEmpty().ifBlank { "127.0.0.1" }

        btnNetworkEnabled.isChecked = networkEnabled
        btnSimulateNet.isChecked = simulateNet
        btnAddressOut.text = "Address out: $addressOut"
        btnPortIn.text = "Listen port (PortIn): $portIn"
        btnPortOut.text = "Send port (PortOut): $portOut"
    }

    private fun showNumberDialog(title: String, key: String, current: Int, min: Int, max: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_number_input, null)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.number_input_layout)
        val input = view.findViewById<TextInputEditText>(R.id.number_input)
        inputLayout.hint = "$title ($min-$max)"
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(current.toString())
        input.setSelection(input.text?.length ?: 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val value = raw.toIntOrNull()
                if (value == null) {
                    Toast.makeText(this, "Enter a number between $min and $max", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val clamped = value.coerceIn(min, max)
                if (clamped != value) {
                    Toast.makeText(this, "Clamped to $clamped", Toast.LENGTH_SHORT).show()
                }
                applyUpdate(
                    updates = mapOf(key to clamped.toString()),
                    onApplied = {
                    when (key) {
                        "PowerPCFrequency" -> btnPpcFrequency.text = "PowerPC frequency: $clamped"
                        "SoundVolume" -> btnSoundVolume.text = "Sound volume: $clamped"
                        "MusicVolume" -> btnMusicVolume.text = "Music volume: $clamped"
                        "PortIn" -> btnPortIn.text = "Listen port (PortIn): $clamped"
                        "PortOut" -> btnPortOut.text = "Send port (PortOut): $clamped"
                    }
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextDialog(title: String, key: String, current: String) {
        val view = layoutInflater.inflate(R.layout.dialog_number_input, null)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.number_input_layout)
        val input = view.findViewById<TextInputEditText>(R.id.number_input)
        inputLayout.hint = title
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setText(current)
        input.setSelection(input.text?.length ?: 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val value = raw.trim('"')
                if (value.isBlank()) {
                    Toast.makeText(this, "Address cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyUpdate(
                    updates = mapOf(key to "\"$value\""),
                    onApplied = {
                        if (key == "AddressOut") {
                            btnAddressOut.text = "Address out: $value"
                        }
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyUpdate(
        updates: Map<String, String>,
        onApplied: () -> Unit,
        onFailed: (() -> Unit)? = null,
    ) {
        if (busy) return
        val doc = iniDoc ?: return
        val updated = updateIniSection(iniLines, "global", updates)
        setBusy(true, "Saving...")
        thread(name = "Super3IniSettingsSave") {
            val ok = IniDocumentStore.writeIniText(this, doc, updated.joinToString("\n"))
            runOnUiThread {
                setBusy(false, null)
                if (!ok) {
                    Toast.makeText(this, "Failed to save Supermodel.ini", Toast.LENGTH_SHORT).show()
                    onFailed?.invoke()
                    return@runOnUiThread
                }
                iniLines = updated
                onApplied()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readIniInt(key: String): Int? {
        return readIniString(key)?.trim()?.toIntOrNull()
    }

    private fun readIniBool(key: String): Boolean? {
        val v = readIniString(key)?.trim()?.lowercase() ?: return null
        return when (v) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    private fun readIniString(key: String): String? {
        val range = findSectionRange("global") ?: (0 until iniLines.size)
        for (i in range) {
            val line = iniLines[i]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith(";")) continue
            val rx = Regex("^\\s*${Regex.escape(key)}\\s*=", RegexOption.IGNORE_CASE)
            if (rx.containsMatchIn(line)) {
                val idx = line.indexOf("=")
                return if (idx >= 0) line.substring(idx + 1).trim() else null
            }
        }
        return null
    }

    private fun readIniStringUnquoted(key: String): String? {
        return readIniString(key)?.trim()?.trim('"')
    }

    private fun updateIniSection(
        lines: List<String>,
        section: String,
        updates: Map<String, String>,
    ): MutableList<String> {
        val (start, end) = findSectionBounds(lines, section) ?: run {
            val out = ArrayList<String>(lines.size + updates.size + 2)
            out.addAll(lines)
            if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
            out.add("[ Global ]")
            for ((k, v) in updates) {
                out.add("$k = $v")
            }
            return out
        }

        val out = ArrayList<String>(lines.size + updates.size)
        out.addAll(lines.take(start + 1))

        val existing = HashSet<String>(updates.size)
        for (i in (start + 1) until end) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith(";") || trimmed.isBlank()) {
                out.add(line)
                continue
            }
            var replaced = false
            for ((k, v) in updates) {
                val rx = Regex("^\\s*${Regex.escape(k)}\\s*=", RegexOption.IGNORE_CASE)
                if (rx.containsMatchIn(line)) {
                    out.add("$k = $v")
                    existing.add(k.lowercase())
                    replaced = true
                    break
                }
            }
            if (!replaced) out.add(line)
        }

        for ((k, v) in updates) {
            if (existing.contains(k.lowercase())) continue
            out.add("$k = $v")
        }

        out.addAll(lines.drop(end))
        return out
    }

    private fun findSectionRange(section: String): IntRange? {
        val (start, end) = findSectionBounds(iniLines, section) ?: return null
        return (start + 1) until end
    }

    private fun findSectionBounds(lines: List<String>, section: String): Pair<Int, Int>? {
        val target = section.lowercase()
        var start = -1
        for (i in lines.indices) {
            val name = sectionName(lines[i]) ?: continue
            if (name == target) {
                start = i
                break
            }
        }
        if (start < 0) return null
        var end = lines.size
        for (i in (start + 1) until lines.size) {
            if (sectionName(lines[i]) != null) {
                end = i
                break
            }
        }
        return start to end
    }

    private fun sectionName(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        return trimmed.substring(1, trimmed.length - 1).trim().lowercase()
    }

    private fun setBusy(isBusy: Boolean, status: String?) {
        busy = isBusy
        toolbar.subtitle = status ?: if (busy) toolbar.subtitle else "Easy settings"
        val enabled = !isBusy
        btnPpcFrequency.isEnabled = enabled
        btnMultithreaded.isEnabled = enabled
        btnGpuMultithreaded.isEnabled = enabled
        btnVsync.isEnabled = enabled
        btnEmulateSound.isEnabled = enabled
        btnSoundVolume.isEnabled = enabled
        btnMusicVolume.isEnabled = enabled
        btnNetworkEnabled.isEnabled = enabled
        btnSimulateNet.isEnabled = enabled
        btnAddressOut.isEnabled = enabled
        btnPortIn.isEnabled = enabled
        btnPortOut.isEnabled = enabled
    }
}

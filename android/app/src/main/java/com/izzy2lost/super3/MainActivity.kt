package com.izzy2lost.super3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationView
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("super3_prefs", MODE_PRIVATE) }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchBar: SearchBar
    private lateinit var searchView: SearchView

    private lateinit var gamesFolderText: TextView
    private lateinit var userFolderText: TextView
    private lateinit var gamesList: RecyclerView
    private lateinit var searchResultsList: RecyclerView
    private lateinit var statusText: TextView

    private lateinit var btnResolution: MaterialButton
    private lateinit var btnResolutionMatchDevice: MaterialButton
    private lateinit var btnWidescreen: MaterialButton
    private lateinit var btnWideBackground: MaterialButton

    private lateinit var gamesAdapter: GamesAdapter

    private var gamesTreeUri: Uri? = null
    private var userTreeUri: Uri? = null

    private var games: List<GameDef> = emptyList()
    private var zipDocs: Map<String, DocumentFile> = emptyMap()

    @Volatile
    private var scanning = false

    private data class VideoSettings(
        val xResolution: Int,
        val yResolution: Int,
        val wideScreen: Boolean,
        val wideBackground: Boolean,
        val matchDevice: Boolean,
    )

    private data class ResolutionOption(val label: String, val x: Int, val y: Int)

    private val resolutionOptions =
        listOf(
            ResolutionOption("Native", 496, 384),
            ResolutionOption("2x", 992, 768),
            ResolutionOption("3x", 1488, 1152),
            ResolutionOption("4x", 1984, 1536),
            ResolutionOption("5x", 2480, 1920),
            ResolutionOption("6x", 2976, 2304),
            ResolutionOption("7x", 3472, 2688),
            ResolutionOption("8x", 3968, 3072),
        )

    private val pickGamesFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                gamesTreeUri = uri
                prefs.edit().putString("gamesTreeUri", uri.toString()).apply()
                refreshUi()
            }
        }

    private val pickUserFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                userTreeUri = uri
                prefs.edit().putString("userTreeUri", uri.toString()).apply()
                refreshUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)
        toolbar = findViewById(R.id.toolbar)
        searchBar = findViewById(R.id.search_bar)
        searchView = findViewById(R.id.search_view)
        gamesList = findViewById(R.id.games_list)
        searchResultsList = findViewById(R.id.search_results_list)

        // Get views from the navigation header
        val headerView = navigationView.getHeaderView(0)
        gamesFolderText = headerView.findViewById(R.id.games_folder_text)
        userFolderText = headerView.findViewById(R.id.user_folder_text)
        statusText = headerView.findViewById(R.id.status_text)

        val btnPickGamesFolder: MaterialButton = headerView.findViewById(R.id.btn_pick_games_folder)
        val btnPickUserFolder: MaterialButton = headerView.findViewById(R.id.btn_pick_user_folder)
        val btnRescan: MaterialButton = headerView.findViewById(R.id.btn_rescan)
        btnResolution = headerView.findViewById(R.id.btn_resolution)
        btnResolutionMatchDevice = headerView.findViewById(R.id.btn_resolution_match_device)
        btnWidescreen = headerView.findViewById(R.id.btn_widescreen)
        btnWideBackground = headerView.findViewById(R.id.btn_wide_background)

        gamesAdapter = GamesAdapter { item ->
            if (!item.launchable) {
                Toast.makeText(this, item.status, Toast.LENGTH_SHORT).show()
                return@GamesAdapter
            }
            launchGame(item.game)
        }
        gamesList.adapter = gamesAdapter
        searchResultsList.adapter = gamesAdapter

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnPickGamesFolder.setOnClickListener { pickGamesFolder.launch(null) }
        btnPickUserFolder.setOnClickListener { pickUserFolder.launch(null) }
        btnRescan.setOnClickListener { refreshUi() }

        bindVideoSettingsUi()

        runCatching { searchView.setupWithSearchBar(searchBar) }
        searchBar.setOnClickListener {
            searchView.show()
            searchView.requestFocusAndShowKeyboard()
        }
        searchView.setAutoShowKeyboard(true)

        val onSurface = MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant =
            MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        searchView.editText.setTextColor(onSurface)
        searchView.editText.setHintTextColor(onSurfaceVariant)

        searchView.addTransitionListener { _, _, newState ->
            val searchActive = newState != SearchView.TransitionState.HIDDEN
            gamesList.visibility = if (searchActive) View.GONE else View.VISIBLE
            if (newState == SearchView.TransitionState.SHOWING || newState == SearchView.TransitionState.SHOWN) {
                searchView.requestFocusAndShowKeyboard()
            }
        }
        searchView.editText.addTextChangedListener { editable ->
            gamesAdapter.setFilter(editable?.toString().orEmpty())
        }

        loadPrefs()
        games = GameXml.parseGamesXmlFromAssets(this)

        AssetInstaller.ensureInstalled(this, internalUserRoot())

        applyVideoSettingsToIni(internalUserRoot(), loadVideoSettings())

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    private fun loadPrefs() {
        gamesTreeUri = prefs.getString("gamesTreeUri", null)?.let(Uri::parse)
        userTreeUri = prefs.getString("userTreeUri", null)?.let(Uri::parse)
    }

    private fun internalUserRoot(): File {
        return File(getExternalFilesDir(null), "super3")
    }

    private fun supermodelIniFile(internalRoot: File = internalUserRoot()): File {
        return File(File(internalRoot, "Config"), "Supermodel.ini")
    }

    private fun loadVideoSettings(): VideoSettings {
        val hasPrefs = prefs.contains("video_xResolution") && prefs.contains("video_yResolution")
        if (hasPrefs) {
            val x = prefs.getInt("video_xResolution", 496)
            val y = prefs.getInt("video_yResolution", 384)
            val wideScreen = prefs.getBoolean("video_wideScreen", false)
            val wideBackground = prefs.getBoolean("video_wideBackground", false)
            val matchDevice = prefs.getBoolean("video_matchDevice", false)
            return VideoSettings(x, y, wideScreen, wideBackground, matchDevice)
        }

        val ini = supermodelIniFile()
        val x = readIniInt(ini, "XResolution") ?: 496
        val y = readIniInt(ini, "YResolution") ?: 384
        val wideScreen = readIniBool(ini, "WideScreen") ?: false
        val wideBackground = readIniBool(ini, "WideBackground") ?: false
        return VideoSettings(x, y, wideScreen, wideBackground, matchDevice = false)
    }

    private fun saveVideoSettings(settings: VideoSettings) {
        prefs.edit()
            .putInt("video_xResolution", settings.xResolution)
            .putInt("video_yResolution", settings.yResolution)
            .putBoolean("video_wideScreen", settings.wideScreen)
            .putBoolean("video_wideBackground", settings.wideBackground)
            .putBoolean("video_matchDevice", settings.matchDevice)
            .apply()
    }

    private fun bindVideoSettingsUi() {
        fun renderResolutionLabel(x: Int, y: Int): String {
            val match = resolutionOptions.firstOrNull { it.x == x && it.y == y }
            return if (match != null) {
                "Resolution: ${match.label} (${match.x}x${match.y})"
            } else {
                "Resolution: Custom (${x}x${y})"
            }
        }

        fun applyUi(settings: VideoSettings) {
            btnResolution.text =
                if (settings.matchDevice) {
                    "Resolution: Device (${settings.xResolution}x${settings.yResolution})"
                } else {
                    renderResolutionLabel(settings.xResolution, settings.yResolution)
                }
            btnResolutionMatchDevice.isChecked = settings.matchDevice
            btnWidescreen.isChecked = settings.wideScreen
            btnWideBackground.isChecked = settings.wideBackground
        }

        fun persistAndApply(settings: VideoSettings) {
            saveVideoSettings(settings)
            applyVideoSettingsToIni(internalUserRoot(), settings)
            val tree = userTreeUri
            if (tree != null) {
                thread(name = "Super3SyncSettings") {
                    UserDataSync.syncInternalIntoTree(this, internalUserRoot(), tree)
                }
            }
        }

        applyUi(loadVideoSettings())

        btnResolution.setOnClickListener {
            val cur = loadVideoSettings()
            val curIndex = resolutionOptions.indexOfFirst { it.x == cur.xResolution && it.y == cur.yResolution }
            val next = resolutionOptions[(curIndex + 1).coerceAtLeast(0) % resolutionOptions.size]
            val leavingMatchMode = cur.matchDevice
            val updated =
                cur.copy(
                    xResolution = next.x,
                    yResolution = next.y,
                    matchDevice = false,
                    wideScreen = if (leavingMatchMode) false else cur.wideScreen,
                    wideBackground = if (leavingMatchMode) false else cur.wideBackground,
                )
            applyUi(updated)
            persistAndApply(updated)
            Toast.makeText(this, "Resolution set to ${next.label} (${next.x}x${next.y})", Toast.LENGTH_SHORT).show()
        }

        btnResolutionMatchDevice.setOnClickListener {
            val dm = resources.displayMetrics
            val target = exactDeviceResolution(dm.widthPixels, dm.heightPixels)
            val cur = loadVideoSettings()
            val updated =
                cur.copy(
                    xResolution = target.x,
                    yResolution = target.y,
                    wideScreen = true,
                    wideBackground = true,
                    matchDevice = true,
                )
            applyUi(updated)
            persistAndApply(updated)
            Toast.makeText(this, "Resolution set to ${target.label} (${target.x}x${target.y})", Toast.LENGTH_SHORT).show()
        }

        btnWidescreen.setOnClickListener {
            val cur = loadVideoSettings()
            val updated = cur.copy(wideScreen = btnWidescreen.isChecked, matchDevice = false)
            persistAndApply(updated)
            applyUi(updated)
        }

        btnWideBackground.setOnClickListener {
            val cur = loadVideoSettings()
            val updated = cur.copy(wideBackground = btnWideBackground.isChecked, matchDevice = false)
            persistAndApply(updated)
            applyUi(updated)
        }
    }

    private fun applyVideoSettingsToIni(internalRoot: File, settings: VideoSettings) {
        val ini = supermodelIniFile(internalRoot)
        updateIniKeys(
            ini,
            mapOf(
                "XResolution" to settings.xResolution.toString(),
                "YResolution" to settings.yResolution.toString(),
                "WideScreen" to if (settings.wideScreen) "1" else "0",
                "WideBackground" to if (settings.wideBackground) "1" else "0",
            ),
        )
    }

    private fun exactDeviceResolution(widthPx: Int, heightPx: Int): ResolutionOption {
        val x = max(1, max(widthPx, heightPx))
        val y = max(1, min(widthPx, heightPx))
        return ResolutionOption("Device", x, y)
    }

    private fun readIniInt(file: File, key: String): Int? {
        return readIniString(file, key)?.trim()?.toIntOrNull()
    }

    private fun readIniBool(file: File, key: String): Boolean? {
        val v = readIniString(file, key)?.trim()?.lowercase() ?: return null
        return when (v) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    private fun readIniString(file: File, key: String): String? {
        if (!file.exists()) return null
        val lines = file.readLines()
        val keyRegex = Regex("^\\s*${Regex.escape(key)}\\s*=\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE)

        var inGlobal = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val name = trimmed.removePrefix("[").removeSuffix("]").trim()
                inGlobal = name.equals("global", ignoreCase = true)
                continue
            }
            if (!inGlobal) continue
            if (trimmed.startsWith(";")) continue
            val m = keyRegex.find(line) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    private fun updateIniKeys(file: File, updates: Map<String, String>) {
        val lines = if (file.exists()) file.readLines() else emptyList()
        val out = ArrayList<String>(lines.size + updates.size + 8)

        fun isSectionHeader(s: String): Boolean {
            val t = s.trim()
            return t.startsWith("[") && t.endsWith("]")
        }

        fun sectionName(s: String): String {
            return s.trim().removePrefix("[").removeSuffix("]").trim()
        }

        val globalStart = lines.indexOfFirst { isSectionHeader(it) && sectionName(it).equals("global", ignoreCase = true) }
        if (globalStart < 0) {
            out.addAll(lines)
            if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
            out.add("[ Global ]")
            for ((k, v) in updates) {
                out.add("$k = $v")
            }
            file.parentFile?.mkdirs()
            file.writeText(out.joinToString("\n"))
            return
        }

        val globalEnd =
            (globalStart + 1 + lines.drop(globalStart + 1).indexOfFirst { isSectionHeader(it) })
                .let { if (it <= globalStart) lines.size else it }

        out.addAll(lines.take(globalStart + 1))

        val existing = HashMap<String, Int>(updates.size)
        for (i in (globalStart + 1) until globalEnd) {
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
                    existing[k.lowercase()] = 1
                    replaced = true
                    break
                }
            }
            if (!replaced) out.add(line)
        }

        for ((k, v) in updates) {
            if (existing.containsKey(k.lowercase())) continue
            out.add("$k = $v")
        }

        out.addAll(lines.drop(globalEnd))
        file.parentFile?.mkdirs()
        file.writeText(out.joinToString("\n"))
    }

    private fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    private fun refreshUi() {
        gamesFolderText.text = "Games folder: ${gamesTreeUri?.toString() ?: "Not set"}"
        userFolderText.text = "Data folder: ${userTreeUri?.toString() ?: "Not set"}"
        val gamesUri = gamesTreeUri
        if (gamesUri == null) {
            zipDocs = emptyMap()
            gamesAdapter.submitList(buildItems(games, zipDocs))
            statusText.text = "Games folder not set. Tap \"Games folder\" to choose."
            scanning = false
            return
        }

        if (scanning) return
        scanning = true
        statusText.text = "Scanning…"

        thread(name = "Super3Scanner") {
            val zips = scanZipDocs(gamesUri)
            val items = buildItems(games, zips)
            runOnUiThread {
                zipDocs = zips
                gamesAdapter.submitList(items)
                statusText.text = "Found ${zipDocs.size} ZIP(s). Tap a game to launch."
                scanning = false
            }
        }
    }

    private fun scanZipDocs(treeUri: Uri?): Map<String, DocumentFile> {
        if (treeUri == null) return emptyMap()
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyMap()
        val map = HashMap<String, DocumentFile>(256)
        for (child in tree.listFiles()) {
            if (!child.isFile) continue
            val name = child.name ?: continue
            if (!name.endsWith(".zip", ignoreCase = true)) continue
            map[name.substring(0, name.length - 4)] = child
        }
        return map
    }

    private fun launchGame(game: GameDef) {
        val gamesUri = gamesTreeUri
        if (gamesUri == null) {
            Toast.makeText(this, "Pick a games folder first", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "Preparing ${game.displayName}…"

        thread(name = "Super3Prep") {
            val internalRoot = internalUserRoot()
            val userUri = userTreeUri

            if (userUri != null) {
                UserDataSync.syncFromTreeIntoInternal(this, userUri, internalRoot)
            }

            applyVideoSettingsToIni(internalRoot, loadVideoSettings())

            val cacheDir = File(internalRoot, "romcache")
            val required = resolveRequiredRomZips(game)
            val missing = required.filter { !zipDocs.containsKey(it) }
            if (missing.isNotEmpty()) {
                runOnUiThread {
                    statusText.text = "Missing required ZIP(s): ${missing.joinToString(", ")}"
                    Toast.makeText(
                        this,
                        "Missing required ZIP(s): ${missing.joinToString(", ")}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@thread
            }

            for (zipBase in required) {
                val doc = zipDocs[zipBase] ?: continue
                val ok = copyDocToCacheIfNeeded(doc, File(cacheDir, "$zipBase.zip"))
                if (!ok) {
                    runOnUiThread {
                        statusText.text = "Failed to copy $zipBase.zip"
                        Toast.makeText(this, "Failed to copy $zipBase.zip", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }
            }

            val romPath = File(cacheDir, "${game.name}.zip").absolutePath
            val gamesXmlPath = File(internalRoot, "Config/Games.xml").absolutePath
            val userDataRoot = internalRoot.absolutePath

            runOnUiThread {
                val intent = Intent(this, Super3Activity::class.java).apply {
                    putExtra("romZipPath", romPath)
                    putExtra("gameName", game.name)
                    putExtra("gamesXmlPath", gamesXmlPath)
                    putExtra("userDataRoot", userDataRoot)
                }
                startActivity(intent)
            }
        }
    }

    private fun resolveRequiredRomZips(game: GameDef): List<String> {
        val mapByName = games.associateBy { it.name }
        val required = ArrayList<String>(4)
        val visited = HashSet<String>(8)

        var current: GameDef? = game
        while (current != null) {
            if (!visited.add(current.name)) break
            required.add(current.name)
            val parentName = current.parent
            if (parentName.isNullOrBlank()) break
            current = mapByName[parentName]
        }

        return required
    }

    private fun copyDocToCacheIfNeeded(doc: DocumentFile, outFile: File): Boolean {
        outFile.parentFile?.mkdirs()
        val expectedSize = doc.length()
        if (outFile.exists() && expectedSize > 0 && outFile.length() == expectedSize) {
            return true
        }
        val input = contentResolver.openInputStream(doc.uri) ?: return false
        input.use { ins ->
            outFile.outputStream().use { outs ->
                ins.copyTo(outs)
            }
        }
        return true
    }
}

private data class GameItem(val game: GameDef, val launchable: Boolean, val status: String)

private fun buildItems(games: List<GameDef>, zipDocs: Map<String, DocumentFile>): List<GameItem> {
    val byName = games.associateBy { it.name }

    fun requiredZips(g: GameDef): List<String> {
        val required = ArrayList<String>(4)
        val visited = HashSet<String>(8)
        var cur: GameDef? = g
        while (cur != null) {
            if (!visited.add(cur.name)) break
            required.add(cur.name)
            val parent = cur.parent
            if (parent.isNullOrBlank()) break
            cur = byName[parent]
        }
        return required
    }

    val items = games.map { g ->
        val req = requiredZips(g)
        val missing = req.filter { !zipDocs.containsKey(it) }
        val launchable = missing.isEmpty() && zipDocs.containsKey(g.name)
        val status =
            if (missing.isEmpty()) {
                if (req.size == 1) "${g.name}.zip found" else "needs ${req.joinToString(" + ") { "${it}.zip" }}"
            } else {
                "missing ${missing.joinToString(" + ") { "${it}.zip" }}"
            }
        GameItem(game = g, launchable = launchable, status = status)
    }

    return items.sortedWith(compareByDescending<GameItem> { it.launchable }.thenBy { it.game.displayName })
}

private class GamesAdapter(
    private val onClick: (GameItem) -> Unit,
) : RecyclerView.Adapter<GamesAdapter.VH>() {
    private var allItems: List<GameItem> = emptyList()
    private var shownItems: List<GameItem> = emptyList()
    private var filter: String = ""

    fun submitList(items: List<GameItem>) {
        allItems = items
        applyFilter()
    }

    fun setFilter(query: String) {
        val normalized = query.trim()
        if (normalized == filter) return
        filter = normalized
        applyFilter()
    }

    private fun applyFilter() {
        val q = filter.trim()
        shownItems =
            if (q.isBlank()) {
                allItems
            } else {
                val needle = q.lowercase()
                allItems.filter {
                    it.game.displayName.lowercase().contains(needle) || it.game.name.lowercase().contains(needle)
                }
            }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = shownItems[position]
        holder.bind(item, onClick)
    }

    override fun getItemCount(): Int = shownItems.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.game_title)
        private val subtitle: TextView = itemView.findViewById(R.id.game_subtitle)

        fun bind(item: GameItem, onClick: (GameItem) -> Unit) {
            title.text = item.game.displayName
            subtitle.text = item.status
            itemView.isEnabled = item.launchable
            title.isEnabled = item.launchable
            subtitle.isEnabled = item.launchable
            itemView.alpha = if (item.launchable) 1.0f else 0.5f
            itemView.setOnClickListener { onClick(item) }
        }
    }
}

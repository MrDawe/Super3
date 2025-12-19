package com.izzy2lost.super3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.PixelCopy
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread
import org.libsdl.app.SDLActivity

/**
 * SDL-driven activity shell. SDLActivity handles loading SDL2 and the native
 * library specified by SDL_MAIN_LIBRARY (set to "super3" in the manifest).
 */
class Super3Activity : SDLActivity() {
    private val prefs by lazy { getSharedPreferences("super3_prefs", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayControlsEnabled: Boolean = true
    private var menuPaused = false
    private var saveDialogOpen = false
    private var exitDialogOpen = false
    private var userPaused = false
    private var saveStateSlot = 0
    private var capturingThumbnail = false
    private var gameName: String = ""
    private var userDataRoot: File? = null

    private data class SaveSlot(
        val slotIndex: Int,
        val title: String,
        val subtitle: String,
        val hasData: Boolean,
        val screenshotPath: String?,
    )

    override fun getLibraries(): Array<String> = arrayOf(
        "SDL2",
        "super3",
    )

    override fun getArguments(): Array<String> {
        val rom = intent.getStringExtra("romZipPath") ?: return emptyArray()
        val game = intent.getStringExtra("gameName") ?: ""
        val gamesXml = intent.getStringExtra("gamesXmlPath") ?: ""
        val userDataRoot = intent.getStringExtra("userDataRoot") ?: ""

        val args = ArrayList<String>(4)
        args.add(rom)
        if (game.isNotBlank()) args.add(game)
        if (gamesXml.isNotBlank()) args.add(gamesXml)
        if (userDataRoot.isNotBlank()) args.add(userDataRoot)
        return args.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlayControlsEnabled = prefs.getBoolean("overlay_controls_enabled", true)
        saveStateSlot = prefs.getInt("save_state_slot", 0).coerceIn(0, 9)
        gameName = intent.getStringExtra("gameName").orEmpty()
        val userDataRootPath = intent.getStringExtra("userDataRoot").orEmpty()
        userDataRoot =
            if (userDataRootPath.isNotBlank()) {
                File(userDataRootPath)
            } else {
                getExternalFilesDir(null)?.let { File(it, "super3") }
            }

        val root = SDLActivity.getContentView() as? RelativeLayout ?: return
        if (overlayView != null) return

        val overlay = LayoutInflater.from(this).inflate(R.layout.overlay_controls, root, false)
        overlayView = overlay
        root.addView(
            overlay,
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        if (!overlayControlsEnabled) {
            overlay.findViewById<View>(R.id.overlay_controls_root)?.visibility = View.GONE
            overlay.visibility = View.GONE
            return
        }

        overlay.findViewById<View>(R.id.overlay_save_state)?.setOnClickListener {
            showSaveStateDialog()
        }

        overlay.findViewById<View>(R.id.overlay_pause)?.setOnClickListener {
            userPaused = !userPaused
            updatePauseState()
        }

        val game = gameName
        val gamesXml = intent.getStringExtra("gamesXmlPath").orEmpty()
        val isRacing =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("vehicle", "harley"))

        val hasShift4 =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("shift4"))

        val hasShiftUpDown =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("shiftupdown"))

        val isGunGame =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("gun1", "gun2", "analog_gun1", "analog_gun2"))

        val shifterEnabled = getSharedPreferences("super3_prefs", MODE_PRIVATE)
            .getBoolean("overlay_shifter_enabled", false)
        val showShifter = shifterEnabled && (hasShift4 || hasShiftUpDown)

        overlay.findViewById<LinearLayout>(R.id.overlay_pedals)?.visibility =
            if (isRacing) View.VISIBLE else View.GONE

        overlay.findViewById<ImageButton>(R.id.overlay_wheel)?.visibility =
            if (isRacing) View.VISIBLE else View.GONE

        overlay.findViewById<ImageButton>(R.id.overlay_shifter)?.visibility =
            if (isRacing && showShifter) View.VISIBLE else View.GONE

        overlay.findViewById<MaterialButton>(R.id.overlay_reload)?.visibility =
            if (isGunGame) View.VISIBLE else View.GONE

        fun nativeTouch(action: Int, fingerId: Int, x: Float, y: Float, p: Float = 1.0f) {
            SDLActivity.onNativeTouch(0, fingerId, action, x, y, p)
        }

        fun bindMomentary(viewId: Int, fingerId: Int, x: Float, y: Float) {
            val v = overlay.findViewById<View>(viewId) ?: return
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                        nativeTouch(MotionEvent.ACTION_DOWN, fingerId, x, y)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1.0f
                        nativeTouch(MotionEvent.ACTION_UP, fingerId, x, y)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        nativeTouch(MotionEvent.ACTION_UP, fingerId, x, y)
                        true
                    }
                    else -> true
                }
            }
        }

        fun bindHeld(viewId: Int, fingerId: Int, x: Float, y: Float) {
            val v = overlay.findViewById<View>(viewId) ?: return
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                        nativeTouch(MotionEvent.ACTION_DOWN, fingerId, x, y)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1.0f
                        nativeTouch(MotionEvent.ACTION_UP, fingerId, x, y)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        nativeTouch(MotionEvent.ACTION_UP, fingerId, x, y)
                        true
                    }
                    else -> true
                }
            }
        }

        // Use synthetic touch IDs that won't collide with real pointer IDs.
        bindMomentary(R.id.overlay_coin, fingerId = 1101, x = 0.10f, y = 0.90f)
        bindMomentary(R.id.overlay_start, fingerId = 1102, x = 0.50f, y = 0.90f)
        bindMomentary(R.id.overlay_service, fingerId = 1105, x = 0.10f, y = 0.10f)
        bindMomentary(R.id.overlay_test, fingerId = 1106, x = 0.90f, y = 0.10f)
        if (isGunGame) {
            bindMomentary(R.id.overlay_reload, fingerId = 1109, x = 0.90f, y = 0.90f)
        }

        if (isRacing) {
            // Match the native pedal zone (right-middle), independent of UI placement.
            bindHeld(R.id.overlay_gas, fingerId = 1103, x = 0.85f, y = 0.35f)
            bindHeld(R.id.overlay_brake, fingerId = 1104, x = 0.85f, y = 0.80f)

            val wheel = overlay.findViewById<ImageButton>(R.id.overlay_wheel)
            wheel?.setOnTouchListener { v, ev ->
                val w = v.width.toFloat().coerceAtLeast(1f)
                val cx = w / 2f
                val dx = (ev.x - cx) / cx
                val steer = dx.coerceIn(-1f, 1f)

                v.rotation = steer * 75f

                val encodedX = ((steer + 1f) / 2f).coerceIn(0f, 1f)
                val encodedY = 0.5f

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                        nativeTouch(MotionEvent.ACTION_DOWN, 1107, encodedX, encodedY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        nativeTouch(MotionEvent.ACTION_MOVE, 1107, encodedX, encodedY)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1.0f
                        v.rotation = 0f
                        nativeTouch(MotionEvent.ACTION_UP, 1107, 0.5f, encodedY)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        v.rotation = 0f
                        nativeTouch(MotionEvent.ACTION_UP, 1107, 0.5f, encodedY)
                        true
                    }
                    else -> true
                }
            }

            val shifter = overlay.findViewById<ImageButton>(R.id.overlay_shifter)
            if (showShifter) {
                shifter?.setOnTouchListener { v, ev ->
                val w = v.width.toFloat().coerceAtLeast(1f)
                val h = v.height.toFloat().coerceAtLeast(1f)
                val cx = w / 2f
                val cy = h / 2f
                val dx = ((ev.x - cx) / cx).coerceIn(-1f, 1f)
                val dy = ((ev.y - cy) / cy).coerceIn(-1f, 1f)

                // Provide a subtle "knob move" feel without changing layout.
                v.translationX = dx * 10f
                v.translationY = dy * 10f

                val encodedX = ((dx + 1f) / 2f).coerceIn(0f, 1f)
                val encodedY = ((dy + 1f) / 2f).coerceIn(0f, 1f)

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                        nativeTouch(MotionEvent.ACTION_DOWN, 1108, encodedX, encodedY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hasShift4) {
                            nativeTouch(MotionEvent.ACTION_MOVE, 1108, encodedX, encodedY)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1.0f
                        v.translationX = 0f
                        v.translationY = 0f
                        nativeTouch(MotionEvent.ACTION_UP, 1108, 0.5f, 0.5f)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        v.translationX = 0f
                        v.translationY = 0f
                        nativeTouch(MotionEvent.ACTION_UP, 1108, 0.5f, 0.5f)
                        true
                    }
                    else -> true
                }
            }
            } else {
                shifter?.setOnTouchListener(null)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handleBackPress()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        handleBackPress()
    }

    override fun onDestroy() {
        overlayView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onStop() {
        super.onStop()
        val uriStr = getSharedPreferences("super3_prefs", MODE_PRIVATE).getString("userTreeUri", null) ?: return
        val treeUri = Uri.parse(uriStr)
        val internalRoot = File(getExternalFilesDir(null), "super3")
        thread(name = "Super3UserSync") {
            UserDataSync.syncInternalIntoTree(this, internalRoot, treeUri)
        }
    }

    private fun handleBackPress() {
        if (exitDialogOpen) return
        exitDialogOpen = true
        updatePauseState()
        MaterialAlertDialogBuilder(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog,
        )
            .setTitle(R.string.exit_game_title)
            .setMessage(R.string.exit_game_message)
            .setPositiveButton(R.string.exit_game_confirm) { _, _ ->
                finish()
            }
            .setNegativeButton(R.string.exit_game_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setOnDismissListener {
                exitDialogOpen = false
                updatePauseState()
            }
            .show()
    }

    private fun updatePauseState() {
        val shouldPause = userPaused || exitDialogOpen || saveDialogOpen || capturingThumbnail
        if (shouldPause == menuPaused) return
        menuPaused = shouldPause
        nativeSetMenuPaused(shouldPause)
    }

    private fun showSaveStateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_saves, null, false)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_save_slots)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val slots = buildSaveSlots()
        var dialog: AlertDialog? = null
        val adapter =
            SaveSlotAdapter(
                slots,
                onSave = { slot ->
                    saveStateToSlot(slot)
                    dialog?.dismiss()
                },
                onLoad = { slot ->
                    loadStateFromSlot(slot)
                    dialog?.dismiss()
                },
            )
        recyclerView.adapter = adapter

        saveDialogOpen = true
        updatePauseState()
        dialog =
            MaterialAlertDialogBuilder(
                this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog,
            )
                .setTitle(getString(R.string.save_state_dialog_title))
                .setView(view)
                .setNegativeButton(android.R.string.cancel) { d, _ ->
                    d.dismiss()
                }
                .setOnDismissListener {
                    saveDialogOpen = false
                    updatePauseState()
                }
                .create()
        dialog.show()
    }

    private fun buildSaveSlots(): List<SaveSlot> {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val baseName = resolveSaveStateBaseName()
        val slots = ArrayList<SaveSlot>(10)
        for (i in 0 until 10) {
            val title = getString(R.string.save_state_slot_format, i + 1)
            val file = saveStateFile(i, baseName)
            if (file != null && file.exists()) {
                val stamp = formatter.format(Date(file.lastModified()))
                val subtitle = getString(R.string.save_state_slot_saved_format, stamp)
                val screenshot = saveStateScreenshotFile(i, baseName)
                slots.add(
                    SaveSlot(
                        slotIndex = i,
                        title = title,
                        subtitle = subtitle,
                        hasData = true,
                        screenshotPath = screenshot?.takeIf { it.exists() }?.absolutePath,
                    ),
                )
            } else {
                slots.add(
                    SaveSlot(
                        slotIndex = i,
                        title = title,
                        subtitle = getString(R.string.save_state_slot_empty),
                        hasData = false,
                        screenshotPath = null,
                    ),
                )
            }
        }
        return slots
    }

    private fun resolveSaveStateBaseName(): String {
        val nativeName = runCatching { nativeGetLoadedGameName() }.getOrNull()
        if (!nativeName.isNullOrBlank()) return nativeName
        return gameName
    }

    private fun saveStateFile(slotIndex: Int, baseName: String = resolveSaveStateBaseName()): File? {
        val root = userDataRoot ?: return null
        if (baseName.isBlank()) return null
        val slot = slotIndex.coerceIn(0, 9)
        return File(File(root, "Saves"), "${baseName}.st$slot")
    }

    private fun saveStateScreenshotFile(slotIndex: Int, baseName: String = resolveSaveStateBaseName()): File? {
        val root = userDataRoot ?: return null
        if (baseName.isBlank()) return null
        val slot = slotIndex.coerceIn(0, 9)
        return File(File(root, "Saves"), "${baseName}.st$slot.png")
    }

    private fun saveStateToSlot(targetSlot: Int) {
        val clamped = targetSlot.coerceIn(0, 9)
        nativeRequestSaveState(clamped)
        captureSaveStateScreenshot(clamped)

        saveStateSlot = clamped
        prefs.edit().putInt("save_state_slot", saveStateSlot).apply()
    }

    private fun loadStateFromSlot(targetSlot: Int) {
        val clamped = targetSlot.coerceIn(0, 9)
        nativeRequestLoadState(clamped)

        saveStateSlot = clamped
        prefs.edit().putInt("save_state_slot", saveStateSlot).apply()
    }

    private fun captureSaveStateScreenshot(slotIndex: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val outFile = saveStateScreenshotFile(slotIndex) ?: return

        val surfaceView = findSdlSurfaceView()
        val w = surfaceView?.width ?: window.decorView.width
        val h = surfaceView?.height ?: window.decorView.height
        if (w <= 0 || h <= 0) {
            window.decorView.post { captureSaveStateScreenshot(slotIndex) }
            return
        }

        capturingThumbnail = true
        updatePauseState()

        // Give the UI a moment to dismiss the dialog and redraw the game frame.
        mainHandler.postDelayed({
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val requestTarget: Any? = surfaceView ?: window
            val callback = PixelCopy.OnPixelCopyFinishedListener { result ->
                try {
                    if (result == PixelCopy.SUCCESS) {
                        outFile.parentFile?.mkdirs()
                        val scaled = scaleDown(bitmap, targetWidth = 640)
                        FileOutputStream(outFile).use { fos ->
                            scaled.compress(Bitmap.CompressFormat.PNG, 90, fos)
                        }
                        if (scaled !== bitmap) scaled.recycle()
                    }
                } catch (_: Throwable) {
                } finally {
                    bitmap.recycle()
                    capturingThumbnail = false
                    updatePauseState()
                }
            }
            when (requestTarget) {
                is SurfaceView -> PixelCopy.request(requestTarget, bitmap, callback, mainHandler)
                else -> PixelCopy.request(window, bitmap, callback, mainHandler)
            }
        }, 250L)
    }

    private fun scaleDown(src: Bitmap, targetWidth: Int): Bitmap {
        if (targetWidth <= 0) return src
        if (src.width <= targetWidth) return src
        val targetHeight = (src.height.toFloat() * (targetWidth.toFloat() / src.width.toFloat())).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
    }

    private fun findSdlSurfaceView(): SurfaceView? {
        val root = SDLActivity.getContentView() as? ViewGroup ?: return null
        return findFirstSurfaceView(root)
    }

    private fun findFirstSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val found = findFirstSurfaceView(view.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    private external fun nativeSetMenuPaused(paused: Boolean): Boolean
    private external fun nativeRequestSaveState(slot: Int): Boolean
    private external fun nativeRequestLoadState(slot: Int): Boolean
    private external fun nativeGetLoadedGameName(): String?

    private class SaveSlotAdapter(
        private val slots: List<SaveSlot>,
        private val onSave: (Int) -> Unit,
        private val onLoad: (Int) -> Unit,
    ) : RecyclerView.Adapter<SaveSlotAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_save_slot, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(slots[position], onSave, onLoad)
        }

        override fun getItemCount(): Int = slots.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.tv_slot_title)
            private val subtitle: TextView = itemView.findViewById(R.id.tv_slot_timestamp)
            private val screenshot: android.widget.ImageView = itemView.findViewById(R.id.iv_slot_screenshot)
            private val saveButton: MaterialButton = itemView.findViewById(R.id.btn_slot_save)
            private val loadButton: MaterialButton = itemView.findViewById(R.id.btn_slot_load)

            fun bind(slot: SaveSlot, onSave: (Int) -> Unit, onLoad: (Int) -> Unit) {
                title.text = slot.title
                subtitle.text = slot.subtitle

                val screenshotPath = slot.screenshotPath
                if (!screenshotPath.isNullOrBlank()) {
                    val bmp = decodeSampledBitmap(screenshotPath, reqW = 240, reqH = 160)
                    if (bmp != null) {
                        screenshot.setImageBitmap(bmp)
                        screenshot.visibility = View.VISIBLE
                        screenshot.setOnClickListener {
                            showEnlargedScreenshot(itemView, screenshotPath, slot.title)
                        }
                    } else {
                        screenshot.setImageDrawable(null)
                        screenshot.visibility = View.GONE
                        screenshot.setOnClickListener(null)
                    }
                } else {
                    screenshot.setImageDrawable(null)
                    screenshot.visibility = View.GONE
                    screenshot.setOnClickListener(null)
                }

                saveButton.setOnClickListener { onSave(slot.slotIndex) }
                loadButton.isEnabled = slot.hasData
                loadButton.alpha = if (slot.hasData) 1.0f else 0.5f
                loadButton.setOnClickListener {
                    if (slot.hasData) {
                        onLoad(slot.slotIndex)
                    }
                }
            }

            private fun showEnlargedScreenshot(anchor: View, path: String, title: String) {
                val context = anchor.context
                val bmp = BitmapFactory.decodeFile(path) ?: return
                val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_screenshot_preview, null, false)
                dialogView.findViewById<TextView>(R.id.tv_screenshot_title)?.text = title
                dialogView.findViewById<android.widget.ImageView>(R.id.iv_enlarged_screenshot)?.setImageBitmap(bmp)
                MaterialAlertDialogBuilder(
                    context,
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog,
                )
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                    .setOnDismissListener { bmp.recycle() }
                    .show()
            }

            private fun decodeSampledBitmap(path: String, reqW: Int, reqH: Int): Bitmap? {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while ((bounds.outWidth / sample) > reqW * 2 || (bounds.outHeight / sample) > reqH * 2) {
                    sample *= 2
                }
                return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }
}

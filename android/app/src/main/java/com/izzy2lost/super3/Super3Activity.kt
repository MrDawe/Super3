package com.izzy2lost.super3

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import java.io.File
import kotlin.concurrent.thread
import org.libsdl.app.SDLActivity

/**
 * SDL-driven activity shell. SDLActivity handles loading SDL2 and the native
 * library specified by SDL_MAIN_LIBRARY (set to "super3" in the manifest).
 */
class Super3Activity : SDLActivity() {
    private var overlayView: View? = null

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

        val game = intent.getStringExtra("gameName").orEmpty()
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

        val showShifter = hasShift4 || hasShiftUpDown

        overlay.findViewById<LinearLayout>(R.id.overlay_pedals)?.visibility =
            if (isRacing) View.VISIBLE else View.GONE

        overlay.findViewById<ImageButton>(R.id.overlay_wheel)?.visibility =
            if (isRacing) View.VISIBLE else View.GONE

        overlay.findViewById<ImageButton>(R.id.overlay_shifter)?.visibility =
            if (isRacing && showShifter) View.VISIBLE else View.GONE

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
        }
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
}

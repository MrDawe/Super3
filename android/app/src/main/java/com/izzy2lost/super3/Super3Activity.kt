package com.izzy2lost.super3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.PixelCopy
import android.view.KeyEvent
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private companion object {
        private const val COMBO_WINDOW_MS = 120L
    }

    private val prefs by lazy { getSharedPreferences("super3_prefs", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayControlsEnabled: Boolean = true
    private var gyroSteeringEnabled: Boolean = false
    private var menuPaused = false
    private var quickMenuOpen = false
    private var saveDialogOpen = false
    private var exitDialogOpen = false
    private var userPaused = false
    private var saveStateSlot = 0
    private var capturingThumbnail = false
    private var gameName: String = ""
    private var userDataRoot: File? = null

    private var comboStartDown = false
    private var comboSelectDown = false
    private var comboTriggered = false
    private var comboStartDownTime = 0L
    private var comboSelectDownTime = 0L
    private var comboStartDispatched = false
    private var comboSelectDispatched = false
    private var pendingStartDownEvent: KeyEvent? = null
    private var pendingSelectDownEvent: KeyEvent? = null
    private var pendingStartUpEvent: KeyEvent? = null
    private var pendingSelectUpEvent: KeyEvent? = null
    private var startDispatchToken = 0
    private var selectDispatchToken = 0

    private var gyroSensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var gyroListener: SensorEventListener? = null
    private var gyroZeroRollRad: Float? = null
    private var gyroFilteredSteer: Float = 0f

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
        gyroSteeringEnabled = prefs.getBoolean("gyro_steering_enabled", false)
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

        val hasViewChange =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("viewchange"))

        val hasVr4 =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("vr4"))

        val isGunGame =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("gun1", "gun2", "analog_gun1", "analog_gun2"))

        val isSoccer =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("soccer"))

        val isTwinJoysticks =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("twin_joysticks"))

        val isFishing =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("fishing"))

        val isSki =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("ski"))

        val isMagTruck =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("magtruck"))

        val isFighting =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("fighting"))

        val isSpikeout =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("spikeout"))

        val needsAimStickAndButtons =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                (
                    game == "lamachin" ||
                        GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("analog_joystick"))
                )

        val showFightButtons = isFighting || isSpikeout || isSoccer || isTwinJoysticks || isFishing || isSki
        val showFightStick = showFightButtons || isMagTruck || needsAimStickAndButtons
        val showExtraButtons = showFightButtons || needsAimStickAndButtons

        val shifterEnabled = getSharedPreferences("super3_prefs", MODE_PRIVATE)
            .getBoolean("overlay_shifter_enabled", false)
        val showShifter = shifterEnabled && (hasShift4 || hasShiftUpDown)

        overlay.findViewById<LinearLayout>(R.id.overlay_pedals)?.visibility =
            if (isRacing || isMagTruck) View.VISIBLE else View.GONE

        overlay.findViewById<ImageButton>(R.id.overlay_wheel)?.visibility =
            if (isRacing) View.VISIBLE else View.GONE

        overlay.findViewById<ImageButton>(R.id.overlay_shifter)?.visibility =
            if (isRacing && showShifter) View.VISIBLE else View.GONE

        overlay.findViewById<View>(R.id.overlay_view)?.visibility =
            if (isRacing && (hasViewChange || hasVr4)) View.VISIBLE else View.GONE

        overlay.findViewById<MaterialButton>(R.id.overlay_reload)?.visibility =
            if (isGunGame) View.VISIBLE else View.GONE

        overlay.findViewById<View>(R.id.overlay_fight_stick)?.visibility =
            if (showFightStick) View.VISIBLE else View.GONE

        overlay.findViewById<View>(R.id.overlay_fight_buttons)?.visibility =
            if (showExtraButtons) View.VISIBLE else View.GONE

        overlay.findViewById<View>(R.id.overlay_vo_row)?.visibility =
            if (isTwinJoysticks) View.VISIBLE else View.GONE

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

        fun bindDynamicMomentary(viewId: Int, fingerIdProvider: () -> Int, x: Float, y: Float) {
            val v = overlay.findViewById<View>(viewId) ?: return
            var activeFingerId: Int? = null
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                        val id = fingerIdProvider()
                        activeFingerId = id
                        nativeTouch(MotionEvent.ACTION_DOWN, id, x, y)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1.0f
                        val id = activeFingerId ?: fingerIdProvider()
                        nativeTouch(MotionEvent.ACTION_UP, id, x, y)
                        activeFingerId = null
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        val id = activeFingerId ?: fingerIdProvider()
                        nativeTouch(MotionEvent.ACTION_UP, id, x, y)
                        activeFingerId = null
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

        if (showExtraButtons) {
            val baseId =
                if (isSpikeout) {
                    1115
                } else if (isFishing) {
                    1120
                } else if (isSki) {
                    1140
                } else {
                    1110
                }

            bindHeld(R.id.overlay_fight_punch, fingerId = baseId + 0, x = 0.90f, y = 0.55f)
            bindHeld(R.id.overlay_fight_kick, fingerId = baseId + 1, x = 0.82f, y = 0.65f)
            bindHeld(R.id.overlay_fight_guard, fingerId = baseId + 2, x = 0.90f, y = 0.45f)
            bindHeld(R.id.overlay_fight_escape, fingerId = baseId + 3, x = 0.82f, y = 0.35f)
        }

        if (isTwinJoysticks) {
            bindHeld(R.id.overlay_vo_jump, fingerId = 1150, x = 0.85f, y = 0.60f)
            bindHeld(R.id.overlay_vo_boost, fingerId = 1151, x = 0.92f, y = 0.60f)
        }

        if (showFightStick) {
            val stick = overlay.findViewById<FrameLayout>(R.id.overlay_fight_stick)
            val knob = overlay.findViewById<View>(R.id.overlay_fight_stick_knob)
            stick?.setOnTouchListener { v, ev ->
                val w = v.width.toFloat().coerceAtLeast(1f)
                val h = v.height.toFloat().coerceAtLeast(1f)
                val cx = w / 2f
                val cy = h / 2f
                val dx = ((ev.x - cx) / cx).coerceIn(-1f, 1f)
                val dy = ((ev.y - cy) / cy).coerceIn(-1f, 1f)

                val radiusPx = (w.coerceAtMost(h) * 0.32f).coerceAtLeast(1f)
                knob?.translationX = dx * radiusPx
                knob?.translationY = dy * radiusPx

                val encodedX = ((dx + 1f) / 2f).coerceIn(0f, 1f)
                val encodedY = ((dy + 1f) / 2f).coerceIn(0f, 1f)

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.90f
                        nativeTouch(MotionEvent.ACTION_DOWN, 1114, encodedX, encodedY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        nativeTouch(MotionEvent.ACTION_MOVE, 1114, encodedX, encodedY)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1.0f
                        knob?.translationX = 0f
                        knob?.translationY = 0f
                        nativeTouch(MotionEvent.ACTION_UP, 1114, 0.5f, 0.5f)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        knob?.translationX = 0f
                        knob?.translationY = 0f
                        nativeTouch(MotionEvent.ACTION_UP, 1114, 0.5f, 0.5f)
                        true
                    }
                    else -> true
                }
            }
        } else {
            overlay.findViewById<View>(R.id.overlay_fight_stick)?.setOnTouchListener(null)
        }

        applyOverlayControlsEnabled(overlayControlsEnabled, persist = false)

        if (isRacing || isMagTruck) {
            val gasId = if (isMagTruck) 1130 else 1103
            val brakeId = if (isMagTruck) 1131 else 1104

            // Match the native pedal zone (right-middle), independent of UI placement.
            bindHeld(R.id.overlay_gas, fingerId = gasId, x = 0.85f, y = 0.35f)
            bindHeld(R.id.overlay_brake, fingerId = brakeId, x = 0.85f, y = 0.80f)

            if (hasViewChange || hasVr4) {
                var vrIndex = 0
                bindDynamicMomentary(
                    R.id.overlay_view,
                    fingerIdProvider = {
                        if (hasVr4) {
                            val id = 1153 + (vrIndex % 4)
                            vrIndex = (vrIndex + 1) % 4
                            id
                        } else {
                            1152
                        }
                    },
                    x = 0.90f,
                    y = 0.50f,
                )
            } else {
                overlay.findViewById<View>(R.id.overlay_view)?.setOnTouchListener(null)
            }

            val wheel = overlay.findViewById<ImageButton>(R.id.overlay_wheel)
            if (isRacing) {
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
            } else {
                wheel?.setOnTouchListener(null)
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

    private fun angleDiffRad(a: Float, b: Float): Float {
        var d = a - b
        val twoPi = (Math.PI * 2.0).toFloat()
        val pi = Math.PI.toFloat()
        while (d > pi) d -= twoPi
        while (d < -pi) d += twoPi
        return d
    }

    private fun startGyroSteering() {
        if (!gyroSteeringEnabled) return
        val game = gameName
        val gamesXml = intent.getStringExtra("gamesXmlPath").orEmpty()
        val isRacing =
            game.isNotBlank() &&
                gamesXml.isNotBlank() &&
                GameInputsIndex.hasAnyInputType(gamesXml, game, setOf("vehicle", "harley"))
        if (!isRacing) return

        val sensitivity = prefs.getFloat("gyro_steering_sensitivity", 1.0f).coerceIn(0.25f, 2.0f)

        if (gyroSensorManager == null) {
            gyroSensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        }
        val mgr = gyroSensorManager ?: return

        val sensor =
            mgr.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: mgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: return
        gyroSensor = sensor

        gyroZeroRollRad = null
        gyroFilteredSteer = 0f

        val listener =
            object : SensorEventListener {
                private val rotationMatrix = FloatArray(9)
                private val adjusted = FloatArray(9)
                private val orientation = FloatArray(3)
                private val maxRollRad = Math.toRadians(28.0).toFloat()

                override fun onSensorChanged(event: SensorEvent) {
                    if (!gyroSteeringEnabled) return
                    if (menuPaused || userPaused) return

                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    val rotation = display?.rotation ?: Surface.ROTATION_0
                    when (rotation) {
                        Surface.ROTATION_0 -> rotationMatrix.copyInto(adjusted)
                        Surface.ROTATION_90 ->
                            SensorManager.remapCoordinateSystem(
                                rotationMatrix,
                                SensorManager.AXIS_Y,
                                SensorManager.AXIS_MINUS_X,
                                adjusted,
                            )
                        Surface.ROTATION_180 ->
                            SensorManager.remapCoordinateSystem(
                                rotationMatrix,
                                SensorManager.AXIS_MINUS_X,
                                SensorManager.AXIS_MINUS_Y,
                                adjusted,
                            )
                        Surface.ROTATION_270 ->
                            SensorManager.remapCoordinateSystem(
                                rotationMatrix,
                                SensorManager.AXIS_MINUS_Y,
                                SensorManager.AXIS_X,
                                adjusted,
                            )
                    }

                    SensorManager.getOrientation(adjusted, orientation)
                    val roll = orientation[2]

                    val zero = gyroZeroRollRad
                    if (zero == null) {
                        gyroZeroRollRad = roll
                        nativeSetGyroSteer(0f)
                        return
                    }

                    val delta = angleDiffRad(roll, zero)
                    val raw = ((delta / maxRollRad) * sensitivity).coerceIn(-1f, 1f)
                    gyroFilteredSteer = (gyroFilteredSteer * 0.85f) + (raw * 0.15f)
                    nativeSetGyroSteer(gyroFilteredSteer)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

        gyroListener = listener
        mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyroSteering() {
        val mgr = gyroSensorManager
        val listener = gyroListener
        if (mgr != null && listener != null) {
            mgr.unregisterListener(listener)
        }
        gyroListener = null
        gyroSensor = null
        gyroZeroRollRad = null
        gyroFilteredSteer = 0f
        nativeSetGyroSteer(0f)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (quickMenuOpen || saveDialogOpen || exitDialogOpen) {
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_UP) {
                handleBackPress()
            }
            return true
        }
        if (handleStartSelectCombo(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        handleBackPress()
    }

    override fun onDestroy() {
        stopGyroSteering()
        overlayView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        gyroSteeringEnabled = prefs.getBoolean("gyro_steering_enabled", false)
        startGyroSteering()
    }

    override fun onPause() {
        stopGyroSteering()
        super.onPause()
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
            UserDataSync.syncInternalIntoTree(this, internalRoot, treeUri, UserDataSync.DIRS_GAME_SYNC)
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
        val shouldPause = userPaused || exitDialogOpen || saveDialogOpen || quickMenuOpen || capturingThumbnail
        if (shouldPause == menuPaused) return
        menuPaused = shouldPause
        nativeSetMenuPaused(shouldPause)
    }

    private fun showQuickOptionsMenu() {
        if (quickMenuOpen || exitDialogOpen || saveDialogOpen || capturingThumbnail) return
        quickMenuOpen = true
        updatePauseState()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_quick_menu, null)
        
        val btnPauseResume = dialogView.findViewById<MaterialButton>(R.id.btn_pause_resume)
        val btnSaveStates = dialogView.findViewById<MaterialButton>(R.id.btn_save_states)
        val btnTouchControls = dialogView.findViewById<MaterialButton>(R.id.btn_touch_controls)
        val btnGyroSteering = dialogView.findViewById<MaterialButton>(R.id.btn_gyro_steering)
        val btnExitGame = dialogView.findViewById<MaterialButton>(R.id.btn_exit_game)

        // Set initial text states
        btnPauseResume.text = getString(if (userPaused) R.string.quick_menu_resume else R.string.quick_menu_pause)
        btnTouchControls.text = getString(if (overlayControlsEnabled) R.string.quick_menu_hide_touch_controls else R.string.quick_menu_show_touch_controls)
        btnGyroSteering.text = getString(if (gyroSteeringEnabled) R.string.quick_menu_disable_gyro else R.string.quick_menu_enable_gyro)

        val dialog = MaterialAlertDialogBuilder(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog,
        )
            .setTitle(R.string.quick_menu_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                quickMenuOpen = false
                updatePauseState()
            }
            .create()

        btnPauseResume.setOnClickListener {
            userPaused = !userPaused
            updatePauseState()
            dialog.dismiss()
        }

        btnSaveStates.setOnClickListener {
            dialog.dismiss()
            mainHandler.post { showSaveStateDialog() }
        }

        btnTouchControls.setOnClickListener {
            applyOverlayControlsEnabled(!overlayControlsEnabled, persist = true)
            dialog.dismiss()
        }

        btnGyroSteering.setOnClickListener {
            applyGyroSteeringEnabled(!gyroSteeringEnabled, persist = true)
            dialog.dismiss()
        }

        btnExitGame.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private fun applyOverlayControlsEnabled(enabled: Boolean, persist: Boolean) {
        overlayControlsEnabled = enabled
        if (persist) {
            prefs.edit().putBoolean("overlay_controls_enabled", enabled).apply()
        }
        val overlay = overlayView
        overlay?.findViewById<View>(R.id.overlay_controls_root)?.visibility = if (enabled) View.VISIBLE else View.GONE
        overlay?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun applyGyroSteeringEnabled(enabled: Boolean, persist: Boolean) {
        gyroSteeringEnabled = enabled
        if (persist) {
            prefs.edit().putBoolean("gyro_steering_enabled", enabled).apply()
        }
        if (enabled) {
            startGyroSteering()
        } else {
            stopGyroSteering()
        }
    }

    private fun handleStartSelectCombo(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return false
        }
        val isStart = event.keyCode == KeyEvent.KEYCODE_BUTTON_START
        val isSelect = event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        if (!isStart && !isSelect) return false

        if (quickMenuOpen || saveDialogOpen || exitDialogOpen || capturingThumbnail) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (isStart) comboStartDown = true else comboSelectDown = true
                }
                KeyEvent.ACTION_UP -> {
                    if (isStart) comboStartDown = false else comboSelectDown = false
                    if (!comboStartDown && !comboSelectDown) comboTriggered = false
                }
            }
            cancelPendingComboDispatches()
            return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    if (comboTriggered) return true
                    if ((isStart && comboStartDispatched) || (isSelect && comboSelectDispatched)) {
                        deliverToSdl(event)
                    }
                    return true
                }

                if (isStart) {
                    comboStartDown = true
                    comboStartDownTime = event.eventTime
                    if (!comboStartDispatched && pendingStartDownEvent == null) {
                        pendingStartDownEvent = KeyEvent(event)
                        startDispatchToken += 1
                        val token = startDispatchToken
                        mainHandler.postDelayed({ flushPendingStart(token) }, COMBO_WINDOW_MS)
                    }
                } else {
                    comboSelectDown = true
                    comboSelectDownTime = event.eventTime
                    if (!comboSelectDispatched && pendingSelectDownEvent == null) {
                        pendingSelectDownEvent = KeyEvent(event)
                        selectDispatchToken += 1
                        val token = selectDispatchToken
                        mainHandler.postDelayed({ flushPendingSelect(token) }, COMBO_WINDOW_MS)
                    }
                }

                if (!comboTriggered && comboStartDown && comboSelectDown) {
                    val delta = kotlin.math.abs(comboStartDownTime - comboSelectDownTime)
                    if (delta <= COMBO_WINDOW_MS) {
                        comboTriggered = true
                        cancelPendingComboDispatches()
                        showQuickOptionsMenu()
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (isStart) {
                    comboStartDown = false
                    comboStartDownTime = 0L
                } else {
                    comboSelectDown = false
                    comboSelectDownTime = 0L
                }

                if (comboTriggered) {
                    if (!comboStartDown && !comboSelectDown) {
                        comboTriggered = false
                    }
                    return true
                }

                if (isStart) {
                    val down = pendingStartDownEvent
                    if (!comboStartDispatched && down != null) {
                        pendingStartDownEvent = null
                        deliverToSdl(down)
                        deliverToSdl(KeyEvent(event))
                        comboStartDispatched = false
                        pendingStartUpEvent = null
                        return true
                    }
                    if (comboStartDispatched) {
                        deliverToSdl(event)
                        comboStartDispatched = false
                        return true
                    }
                    pendingStartUpEvent = KeyEvent(event)
                    return true
                } else {
                    val down = pendingSelectDownEvent
                    if (!comboSelectDispatched && down != null) {
                        pendingSelectDownEvent = null
                        deliverToSdl(down)
                        deliverToSdl(KeyEvent(event))
                        comboSelectDispatched = false
                        pendingSelectUpEvent = null
                        return true
                    }
                    if (comboSelectDispatched) {
                        deliverToSdl(event)
                        comboSelectDispatched = false
                        return true
                    }
                    pendingSelectUpEvent = KeyEvent(event)
                    return true
                }
            }
            else -> return true
        }
    }

    private fun cancelPendingComboDispatches() {
        startDispatchToken += 1
        selectDispatchToken += 1
        pendingStartDownEvent = null
        pendingSelectDownEvent = null
        pendingStartUpEvent = null
        pendingSelectUpEvent = null
        comboStartDispatched = false
        comboSelectDispatched = false
    }

    private fun flushPendingStart(token: Int) {
        if (token != startDispatchToken) return
        if (comboTriggered || quickMenuOpen || saveDialogOpen || exitDialogOpen || capturingThumbnail) {
            pendingStartDownEvent = null
            pendingStartUpEvent = null
            comboStartDispatched = false
            return
        }
        val down = pendingStartDownEvent ?: return
        pendingStartDownEvent = null
        deliverToSdl(down)
        comboStartDispatched = true
        pendingStartUpEvent?.let { up ->
            pendingStartUpEvent = null
            deliverToSdl(up)
            comboStartDispatched = false
        }
    }

    private fun flushPendingSelect(token: Int) {
        if (token != selectDispatchToken) return
        if (comboTriggered || quickMenuOpen || saveDialogOpen || exitDialogOpen || capturingThumbnail) {
            pendingSelectDownEvent = null
            pendingSelectUpEvent = null
            comboSelectDispatched = false
            return
        }
        val down = pendingSelectDownEvent ?: return
        pendingSelectDownEvent = null
        deliverToSdl(down)
        comboSelectDispatched = true
        pendingSelectUpEvent?.let { up ->
            pendingSelectUpEvent = null
            deliverToSdl(up)
            comboSelectDispatched = false
        }
    }

    private fun deliverToSdl(event: KeyEvent) {
        super.dispatchKeyEvent(event)
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
                            scaled.compress(Bitmap.CompressFormat.PNG, 100, fos)
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
    private external fun nativeSetGyroSteer(steer: Float): Boolean

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

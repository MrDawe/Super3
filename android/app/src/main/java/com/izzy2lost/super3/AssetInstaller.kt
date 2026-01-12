package com.izzy2lost.super3

import android.content.Context
import java.io.File

object AssetInstaller {
    fun ensureInstalled(context: Context, internalUserRoot: File) {
        internalUserRoot.mkdirs()

        // Copy the standard Supermodel folder layout from APK assets into the internal user root.
        // We never overwrite existing files so user edits persist.
        val topLevel = listOf("Assets", "Config", "GraphicsAnalysis", "NVRAM", "Saves")
        for (dir in topLevel) {
            copyAssetTree(context, dir, File(internalUserRoot, dir))
        }

        migrateSupermodelIni(File(File(internalUserRoot, "Config"), "Supermodel.ini"))
    }

    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath) ?: return

        // If list() returns empty, it's a file.
        if (children.isEmpty()) {
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return
        }

        if (!dest.exists()) dest.mkdirs()
        for (child in children) {
            copyAssetTree(context, "$assetPath/$child", File(dest, child))
        }
    }

    private fun migrateSupermodelIni(ini: File) {
        if (!ini.exists()) return

        val lines = runCatching { ini.readLines() }.getOrNull() ?: return
        var changed = false
        val replacements =
            mapOf(
                "InputBrake = KEY_S,JOY1_ZAXIS_POS" to "InputBrake = KEY_X,JOY1_ZAXIS_POS",
                "InputGearShiftUp = KEY_Y,JOY1_BUTTON6" to "InputGearShiftUp = KEY_I,JOY1_BUTTON6",
                "InputGearShiftDown = KEY_H,JOY1_BUTTON5" to "InputGearShiftDown = KEY_K,JOY1_BUTTON5",
                "InputGearShift1 = KEY_Q,JOY1_BUTTON3" to "InputGearShift1 = KEY_7,JOY1_BUTTON3",
                "InputGearShift2 = KEY_W,JOY1_BUTTON1" to "InputGearShift2 = KEY_8,JOY1_BUTTON1",
                "InputGearShift3 = KEY_E,JOY1_BUTTON4" to "InputGearShift3 = KEY_9,JOY1_BUTTON4",
                "InputGearShift4 = KEY_R,JOY1_BUTTON2" to "InputGearShift4 = KEY_0,JOY1_BUTTON2",
                "InputGearShiftN = KEY_T" to "InputGearShiftN = KEY_6",
                "InputAutoTrigger = 0" to "InputAutoTrigger = 1",
                "InputAutoTrigger2 = 0" to "InputAutoTrigger2 = 1",
                // Star Wars Trilogy: correct mouse inversion + allow using the pedal touch zone as Trigger/Event.
                "InputAnalogJoyX = JOY_XAXIS,MOUSE_XAXIS" to "InputAnalogJoyX = JOY_XAXIS,MOUSE_XAXIS_INV",
                "InputAnalogJoyY = JOY_YAXIS,MOUSE_YAXIS" to "InputAnalogJoyY = JOY_YAXIS,MOUSE_YAXIS_INV",
                "InputAnalogJoyTrigger = KEY_A,JOY_BUTTON1,MOUSE_LEFT_BUTTON" to "InputAnalogJoyTrigger = KEY_A,KEY_W,JOY1_BUTTON1,MOUSE_LEFT_BUTTON",
                "InputAnalogJoyEvent = KEY_S,JOY_BUTTON2,MOUSE_RIGHT_BUTTON" to "InputAnalogJoyEvent = KEY_S,KEY_X,JOY1_BUTTON2,MOUSE_RIGHT_BUTTON",
                // Gun games: include right stick + trigger axes in default mappings.
                "InputGunX = MOUSE_XAXIS,JOY1_XAXIS" to "InputGunX = MOUSE_XAXIS,JOY1_RXAXIS,JOY1_XAXIS",
                "InputGunY = MOUSE_YAXIS,JOY1_YAXIS" to "InputGunY = MOUSE_YAXIS,JOY1_RYAXIS,JOY1_YAXIS",
                "InputTrigger = KEY_A,JOY1_BUTTON1,MOUSE_LEFT_BUTTON" to "InputTrigger = KEY_A,JOY1_RZAXIS_POS,JOY1_BUTTON1,MOUSE_LEFT_BUTTON",
                "InputOffscreen = KEY_S,JOY1_BUTTON2,MOUSE_RIGHT_BUTTON" to "InputOffscreen = KEY_S,JOY1_ZAXIS_POS,JOY1_BUTTON2,MOUSE_RIGHT_BUTTON",
                // Analog-gun games: include right stick + trigger axes, and set P2 defaults if still unmapped.
                "InputAnalogGunX = MOUSE_XAXIS,JOY1_XAXIS" to "InputAnalogGunX = MOUSE_XAXIS,JOY1_RXAXIS,JOY1_XAXIS",
                "InputAnalogGunY = MOUSE_YAXIS,JOY1_YAXIS" to "InputAnalogGunY = MOUSE_YAXIS,JOY1_RYAXIS,JOY1_YAXIS",
                "InputAnalogTriggerLeft = KEY_A,JOY1_BUTTON1,MOUSE_LEFT_BUTTON" to "InputAnalogTriggerLeft = KEY_A,JOY1_RZAXIS_POS,JOY1_BUTTON1,MOUSE_LEFT_BUTTON",
                "InputAnalogTriggerRight = KEY_S,JOY1_BUTTON2,MOUSE_RIGHT_BUTTON" to "InputAnalogTriggerRight = KEY_S,JOY1_ZAXIS_POS,JOY1_BUTTON2,MOUSE_RIGHT_BUTTON",
                "InputAnalogGunX2 = NONE" to "InputAnalogGunX2 = JOY2_RXAXIS,JOY2_XAXIS",
                "InputAnalogGunY2 = NONE" to "InputAnalogGunY2 = JOY2_RYAXIS,JOY2_YAXIS",
                "InputAnalogTriggerLeft2 = NONE" to "InputAnalogTriggerLeft2 = JOY2_RZAXIS_POS,JOY2_BUTTON1",
                "InputAnalogTriggerRight2 = NONE" to "InputAnalogTriggerRight2 = JOY2_ZAXIS_POS,JOY2_BUTTON2",
            )
        val updated =
            lines.map { line ->
                val trimmed = line.trim()
                val repl = replacements[trimmed]
                if (repl != null) {
                    changed = true
                    repl
                } else {
                    line
                }
            }

        if (!changed) return
        runCatching { ini.writeText(updated.joinToString(System.lineSeparator())) }
    }
}

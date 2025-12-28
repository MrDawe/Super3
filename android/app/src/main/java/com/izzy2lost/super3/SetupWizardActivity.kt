package com.izzy2lost.super3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SetupWizardActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("super3_prefs", MODE_PRIVATE) }

    private lateinit var pageGames: View
    private lateinit var pageData: View
    private lateinit var gamesPathText: TextView
    private lateinit var dataPathText: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var indicatorGames: View
    private lateinit var indicatorData: View

    private var gamesTreeUri: Uri? = null
    private var userTreeUri: Uri? = null
    private var currentStep = 0

    private val pickGamesFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                gamesTreeUri = uri
                prefs.edit().putString("gamesTreeUri", uri.toString()).apply()
                updateGamesSelection()
                updateButtons()
            }
        }

    private val pickUserFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                userTreeUri = uri
                prefs.edit().putString("userTreeUri", uri.toString()).apply()
                updateDataSelection()
                updateButtons()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()

        gamesTreeUri = prefs.getString("gamesTreeUri", null)?.let(Uri::parse)
        userTreeUri = prefs.getString("userTreeUri", null)?.let(Uri::parse)

        if (prefs.getBoolean("setup_complete", false) || (gamesTreeUri != null && userTreeUri != null)) {
            prefs.edit().putBoolean("setup_complete", true).apply()
            goToMain()
            return
        }

        setContentView(R.layout.activity_setup_wizard)

        val setupRoot: View = findViewById(R.id.setup_root)
        val setupCard: View = findViewById(R.id.setup_card)
        setupRoot.post {
            val target = (setupRoot.height * 0.92f).toInt()
            if (target > 0) {
                setupCard.minimumHeight = target
            }
        }

        pageGames = findViewById(R.id.page_games)
        pageData = findViewById(R.id.page_data)
        gamesPathText = findViewById(R.id.games_path_text)
        dataPathText = findViewById(R.id.data_path_text)
        btnBack = findViewById(R.id.btn_wizard_back)
        btnNext = findViewById(R.id.btn_wizard_next)
        indicatorGames = findViewById(R.id.step_indicator_games)
        indicatorData = findViewById(R.id.step_indicator_data)

        val btnPickGames: MaterialButton = findViewById(R.id.btn_pick_games_folder)
        val btnPickData: MaterialButton = findViewById(R.id.btn_pick_data_folder)

        btnPickGames.setOnClickListener { pickGamesFolder.launch(null) }
        btnPickData.setOnClickListener { pickUserFolder.launch(null) }

        btnBack.setOnClickListener { showStep(0) }
        btnNext.setOnClickListener {
            if (currentStep == 0) {
                showStep(1)
            } else {
                finishSetup()
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (currentStep == 1) {
                showStep(0)
            } else {
                finish()
            }
        }

        updateGamesSelection()
        updateDataSelection()

        val initialStep = if (gamesTreeUri == null) 0 else 1
        showStep(initialStep)
    }

    private fun showStep(step: Int) {
        currentStep = step.coerceIn(0, 1)
        pageGames.visibility = if (currentStep == 0) View.VISIBLE else View.GONE
        pageData.visibility = if (currentStep == 1) View.VISIBLE else View.GONE
        indicatorGames.setBackgroundResource(
            if (currentStep == 0) R.drawable.setup_wizard_indicator_active else R.drawable.setup_wizard_indicator_inactive
        )
        indicatorData.setBackgroundResource(
            if (currentStep == 1) R.drawable.setup_wizard_indicator_active else R.drawable.setup_wizard_indicator_inactive
        )
        updateButtons()
    }

    private fun updateButtons() {
        val onGames = currentStep == 0
        btnBack.visibility = if (onGames) View.INVISIBLE else View.VISIBLE
        btnNext.text = getString(if (onGames) R.string.setup_next else R.string.setup_finish)
        btnNext.isEnabled = if (onGames) gamesTreeUri != null else true
    }

    private fun updateGamesSelection() {
        val value = gamesTreeUri?.toString() ?: getString(R.string.setup_not_set)
        gamesPathText.text = getString(R.string.setup_games_folder_value, value)
    }

    private fun updateDataSelection() {
        val value = userTreeUri?.toString() ?: getString(R.string.setup_not_set)
        dataPathText.text = getString(R.string.setup_data_folder_value, value)
    }

    private fun finishSetup() {
        prefs.edit().putBoolean("setup_complete", true).apply()
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }
}

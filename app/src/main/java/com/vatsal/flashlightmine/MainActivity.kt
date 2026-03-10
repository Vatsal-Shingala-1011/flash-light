package com.vatsal.flashlightmine

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
import android.widget.TextView
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetView
import com.google.android.material.snackbar.Snackbar
import com.vatsal.flashlightmine.databinding.ActivityMainBinding
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private var isFlashlightOn = false
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraId: String
    private lateinit var binding: ActivityMainBinding

    // Pull rope animation variables
    private var initialY = 0f
    private var isDragging = false
    private val pullThreshold = 100f

    // Mode management
    private enum class FlashMode { NORMAL, SOS, STROBE, SCREEN }
    private var currentMode = FlashMode.NORMAL

    // SOS pattern: dot=200ms, dash=600ms, gap=200ms, letter_gap=600ms, word_gap=1400ms
    private val sosHandler = Handler(Looper.getMainLooper())
    private var isSosRunning = false

    // Strobe
    private val strobeHandler = Handler(Looper.getMainLooper())
    private var isStrobeRunning = false
    private var strobeIntervalMs = 200L // default 5 Hz

    // Timer
    private var autoOffTimer: CountDownTimer? = null
    private var selectedTimerMinutes = 0
    private val timerHandler = Handler(Looper.getMainLooper())

    // Battery
    private val batteryHandler = Handler(Looper.getMainLooper())

    // Shake debounce
    private var lastShakeTime = 0L
    private val shakeDebounceMs = 1000L

    // Rate dialog
    private var usageCount = 0

    // Sensor
    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null

    companion object {
        private const val PREFS_NAME = "FlashLightPrefs"
        private const val KEY_FIRST_LAUNCH = "isFirstLaunch"
        private const val KEY_USAGE_COUNT = "usageCount"
        private const val KEY_RATED = "hasRated"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize CameraManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList[0]

        // Load usage count
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        usageCount = prefs.getInt(KEY_USAGE_COUNT, 0)

        // Setup all UI
        setupBottomButton()
        setupPullRopeAnimation()
        setupShakeListener()
        setupModeSelector()
        setupTimerButtons()
        setupStrobeSpeedControl()
        setupScreenLightMode()
        setupBatteryMonitor()
        showTutorialIfFirstLaunch()

        // Track usage for rate dialog
        incrementUsageAndMaybeRate()
    }

    // ─── BOTTOM BUTTON ────────────────────────────────────────────
    private fun setupBottomButton() {
        binding.offButton.setOnClickListener {
            vibrateClick()
            when (currentMode) {
                FlashMode.NORMAL -> toggleFlashlight()
                FlashMode.SOS -> toggleSos()
                FlashMode.STROBE -> toggleStrobe()
                FlashMode.SCREEN -> toggleScreenLight()
            }
        }
        binding.shakeText.setOnClickListener {
            Snackbar.make(it, getString(R.string.shake_enabled), Snackbar.LENGTH_SHORT).show()
        }
    }

    // ─── MODE SELECTOR ────────────────────────────────────────────
    private fun setupModeSelector() {
        val modeButtons = listOf(
            binding.modeNormal to FlashMode.NORMAL,
            binding.modeStrobe to FlashMode.STROBE,
            binding.modeScreen to FlashMode.SCREEN
        )

        modeButtons.forEach { (button, mode) ->
            button.setOnClickListener {
                vibrateClick()
                switchMode(mode)
            }
        }

        // Standalone SOS button
        binding.sosButton.setOnClickListener {
            vibrateClick()
            if (currentMode == FlashMode.SOS) {
                // Already in SOS — stop and go back to Normal
                switchMode(FlashMode.NORMAL)
            } else {
                switchMode(FlashMode.SOS)
            }
        }
    }

    private fun switchMode(newMode: FlashMode) {
        // Stop any running effect from previous mode
        stopAllEffects()

        currentMode = newMode

        // Update mode selector button styles (Normal, Strobe, Screen)
        val selectorButtons = listOf(binding.modeNormal, binding.modeStrobe, binding.modeScreen)
        selectorButtons.forEach { btn ->
            btn.setTextColor(0xFFAAAAAA.toInt())
            btn.setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        // Highlight active button in the mode selector (SOS is separate)
        when (newMode) {
            FlashMode.NORMAL -> binding.modeNormal
            FlashMode.STROBE -> binding.modeStrobe
            FlashMode.SCREEN -> binding.modeScreen
            FlashMode.SOS -> null // handled separately
        }?.let { btn ->
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // Update SOS button appearance
        binding.sosButton.isSelected = (newMode == FlashMode.SOS)

        // Update mode label
        binding.modeLabel.text = when (newMode) {
            FlashMode.NORMAL -> getString(R.string.mode_normal)
            FlashMode.SOS -> getString(R.string.mode_sos)
            FlashMode.STROBE -> getString(R.string.mode_strobe)
            FlashMode.SCREEN -> getString(R.string.mode_screen)
        }

        // Show/hide strobe speed slider
        binding.strobeSpeedContainer.visibility =
            if (newMode == FlashMode.STROBE) View.VISIBLE else View.GONE

        // Show message
        val msg = when (newMode) {
            FlashMode.NORMAL -> null
            FlashMode.SOS -> getString(R.string.sos_activated)
            FlashMode.STROBE -> getString(R.string.strobe_activated)
            FlashMode.SCREEN -> getString(R.string.screen_light_info)
        }
        msg?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() }

        // Auto-start effects for SOS, Strobe, Screen
        when (newMode) {
            FlashMode.SOS -> {
                isSosRunning = true
                playSosPattern()
            }
            FlashMode.STROBE -> {
                isStrobeRunning = true
                runStrobe()
            }
            FlashMode.SCREEN -> {
                toggleScreenLight()
            }
            FlashMode.NORMAL -> { /* user toggles manually */ }
        }
    }

    private fun stopAllEffects() {
        // Stop SOS
        if (isSosRunning) {
            isSosRunning = false
            sosHandler.removeCallbacksAndMessages(null)
        }
        // Stop Strobe
        if (isStrobeRunning) {
            isStrobeRunning = false
            strobeHandler.removeCallbacksAndMessages(null)
        }
        // Turn off screen light
        binding.screenLightOverlay.visibility = View.GONE
        binding.screenLightClose.visibility = View.GONE

        // Turn off flash if on
        if (isFlashlightOn) {
            try {
                cameraManager.setTorchMode(cameraId, false)
                isFlashlightOn = false
                updateFlashlightUI()
            } catch (_: CameraAccessException) {}
        }
    }

    // ─── NORMAL FLASHLIGHT TOGGLE ─────────────────────────────────
    private fun toggleFlashlight() {
        try {
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
            updateFlashlightUI()
            playLightBulbAnimation()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateFlashlightUI() {
        binding.main.setBackgroundColor(
            if (isFlashlightOn) 0xFF332D2B.toInt() else 0xFF1E1E1E.toInt()
        )
        binding.lightBulb.setImageResource(
            if (isFlashlightOn) R.drawable.bulb_on else R.drawable.bulb_off
        )
        binding.status.text = if (isFlashlightOn) getString(R.string.status_on) else getString(R.string.status_off)
        setEllipsesTint(isFlashlightOn)
    }

    // ─── SOS MODE ─────────────────────────────────────────────────
    private fun toggleSos() {
        if (isSosRunning) {
            isSosRunning = false
            sosHandler.removeCallbacksAndMessages(null)
            turnOffFlash()
            return
        }
        isSosRunning = true
        playSosPattern()
    }

    private fun playSosPattern() {
        // SOS: ...---...
        // dot=150ms, dash=450ms, intra_gap=100ms, letter_gap=300ms, word_gap=700ms
        val dot = 150L
        val dash = 450L
        val gap = 100L
        val letterGap = 300L
        val wordGap = 700L

        val pattern = mutableListOf<Pair<Boolean, Long>>() // (flashOn, duration)
        // S: dot dot dot
        repeat(3) { pattern.add(true to dot); pattern.add(false to gap) }
        pattern.removeLastOrNull() // remove trailing gap
        pattern.add(false to letterGap)
        // O: dash dash dash
        repeat(3) { pattern.add(true to dash); pattern.add(false to gap) }
        pattern.removeLastOrNull()
        pattern.add(false to letterGap)
        // S: dot dot dot
        repeat(3) { pattern.add(true to dot); pattern.add(false to gap) }
        pattern.removeLastOrNull()
        pattern.add(false to wordGap)

        fun runPattern(index: Int) {
            if (!isSosRunning) return
            val idx = index % pattern.size
            val (on, duration) = pattern[idx]
            try {
                cameraManager.setTorchMode(cameraId, on)
                isFlashlightOn = on
                runOnUiThread { updateFlashlightUI() }
            } catch (_: CameraAccessException) {}
            sosHandler.postDelayed({ runPattern(idx + 1) }, duration)
        }
        runPattern(0)
    }

    // ─── STROBE MODE ──────────────────────────────────────────────
    private fun toggleStrobe() {
        if (isStrobeRunning) {
            isStrobeRunning = false
            strobeHandler.removeCallbacksAndMessages(null)
            turnOffFlash()
            return
        }
        isStrobeRunning = true
        runStrobe()
    }

    private fun runStrobe() {
        if (!isStrobeRunning) return
        try {
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
            runOnUiThread { updateFlashlightUI() }
        } catch (_: CameraAccessException) {}
        strobeHandler.postDelayed({ runStrobe() }, strobeIntervalMs)
    }

    private fun setupStrobeSpeedControl() {
        binding.speedSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // progress 1-20, map to 500ms - 50ms (slower to faster)
                strobeIntervalMs = (550 - progress * 25).toLong().coerceIn(50, 500)
                binding.speedLabel.text = "${getString(R.string.strobe_speed)} (${1000 / strobeIntervalMs} Hz)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ─── SCREEN LIGHT MODE ────────────────────────────────────────
    private fun setupScreenLightMode() {
        binding.screenLightOverlay.setOnClickListener {
            binding.screenLightOverlay.visibility = View.GONE
            binding.screenLightClose.visibility = View.GONE
            // Restore brightness
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }

    private fun toggleScreenLight() {
        if (binding.screenLightOverlay.visibility == View.VISIBLE) {
            binding.screenLightOverlay.visibility = View.GONE
            binding.screenLightClose.visibility = View.GONE
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } else {
            binding.screenLightOverlay.visibility = View.VISIBLE
            binding.screenLightClose.visibility = View.VISIBLE
            // Set max brightness
            val lp = window.attributes
            lp.screenBrightness = 1.0f
            window.attributes = lp
        }
    }

    // ─── AUTO-OFF TIMER ───────────────────────────────────────────
    private fun setupTimerButtons() {
        val timerButtons = listOf(
            binding.timerOff to 0,
            binding.timer1min to 1,
            binding.timer5min to 5,
            binding.timer15min to 15
        )

        timerButtons.forEach { (button, minutes) ->
            button.setOnClickListener {
                vibrateClick()
                selectTimer(minutes)
            }
        }

        // Custom timer button
        binding.timerCustom.setOnClickListener {
            vibrateClick()
            showCustomTimerDialog()
        }
    }

    private fun showCustomTimerDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.custom_timer_hint)
            setPadding(48, 32, 48, 32)
            textSize = 18f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(input)
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.custom_timer_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = input.text.toString().toIntOrNull()
                if (minutes != null && minutes in 1..6969) {
                    selectTimer(minutes)
                } else {
                    Snackbar.make(binding.root, "Enter 1–120 minutes", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectTimer(minutes: Int) {
        selectedTimerMinutes = minutes
        autoOffTimer?.cancel()

        // Update button styles
        val allTimerBtns = listOf(binding.timerOff, binding.timer1min, binding.timer5min, binding.timer15min, binding.timerCustom)
        allTimerBtns.forEach {
            it.setTextColor(0xFFAAAAAA.toInt())
            it.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        val activeBtn = when (minutes) {
            0 -> binding.timerOff
            1 -> binding.timer1min
            5 -> binding.timer5min
            15 -> binding.timer15min
            else -> binding.timerCustom // custom value
        }
        activeBtn.setTextColor(0xFFFFFFFF.toInt())
        activeBtn.setTypeface(null, android.graphics.Typeface.BOLD)

        // Update custom button text when a custom value is selected
        if (minutes !in listOf(0, 1, 5, 15)) {
            binding.timerCustom.text = getString(R.string.custom_timer_set, minutes)
        } else {
            binding.timerCustom.text = getString(R.string.timer_custom)
        }

        if (minutes == 0) {
            binding.timerDisplay.visibility = View.GONE
            Snackbar.make(binding.root, getString(R.string.timer_cancelled), Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.timerDisplay.visibility = View.VISIBLE
        val totalMs = minutes * 60 * 1000L

        autoOffTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = millisUntilFinished / 60000
                val secs = (millisUntilFinished % 60000) / 1000
                binding.timerDisplay.text = getString(R.string.timer_remaining, String.format("%d:%02d", mins, secs))
            }

            override fun onFinish() {
                binding.timerDisplay.visibility = View.GONE
                stopAllEffects()
                selectedTimerMinutes = 0
                // Reset timer button
                selectTimer(0)
            }
        }.start()

        val label = when (minutes) {
            1 -> getString(R.string.timer_1min)
            5 -> getString(R.string.timer_5min)
            15 -> getString(R.string.timer_15min)
            else -> getString(R.string.custom_timer_set, minutes)
        }
        Snackbar.make(binding.root, getString(R.string.timer_set, label), Snackbar.LENGTH_SHORT).show()
    }

    // ─── BATTERY MONITOR ──────────────────────────────────────────
    private fun setupBatteryMonitor() {
        updateBatteryLevel()
        val runnable = object : Runnable {
            override fun run() {
                updateBatteryLevel()
                batteryHandler.postDelayed(this, 30000) // Update every 30 seconds
            }
        }
        batteryHandler.postDelayed(runnable, 30000)
    }

    private fun updateBatteryLevel() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val pct = (level * 100 / scale)
            binding.batteryText.text = "$pct%"
            binding.batteryProgress.progress = pct
        }
    }

    // ─── PULL ROPE ANIMATION ──────────────────────────────────────
    private fun setupPullRopeAnimation() {
        binding.line5.pivotY = 0f

        binding.line5.post {
            val lineHeight = binding.line5.height.toFloat()
            val pullViews = listOf(binding.line5, binding.lightBulb)

            pullViews.forEach { view ->
                view.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialY = event.rawY
                            isDragging = true
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isDragging) {
                                val deltaY = event.rawY - initialY
                                if (deltaY > 0) {
                                    val stretchFactor = 1f + (deltaY / lineHeight) * 0.5f
                                    binding.line5.scaleY = stretchFactor
                                    val bottomOffset = lineHeight * (stretchFactor - 1f)
                                    binding.lightBulb.translationY = bottomOffset
                                    binding.ellipse1.translationY = bottomOffset
                                    binding.ellipse2.translationY = bottomOffset
                                    binding.ellipse3.translationY = bottomOffset
                                    binding.ellipse4.translationY = bottomOffset
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isDragging) {
                                val deltaY = event.rawY - initialY
                                isDragging = false
                                if (deltaY > pullThreshold) {
                                    vibrateClick()
                                    when (currentMode) {
                                        FlashMode.NORMAL -> toggleFlashlight()
                                        FlashMode.SOS -> toggleSos()
                                        FlashMode.STROBE -> toggleStrobe()
                                        FlashMode.SCREEN -> toggleScreenLight()
                                    }
                                    playLightBulbAnimation()
                                }
                                snapBackWithSpring()
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    private fun snapBackWithSpring() {
        val springLineScale = SpringAnimation(binding.line5, SpringAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        val springBulb = SpringAnimation(binding.lightBulb, SpringAnimation.TRANSLATION_Y, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        val ellipseViews = listOf(binding.ellipse1, binding.ellipse2, binding.ellipse3, binding.ellipse4)
        val ellipseSprings = ellipseViews.map { view ->
            SpringAnimation(view, SpringAnimation.TRANSLATION_Y, 0f).apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
        }
        springLineScale.start()
        springBulb.start()
        ellipseSprings.forEach { it.start() }
    }

    private fun playLightBulbAnimation() {
        val scaleX = ObjectAnimator.ofFloat(binding.lightBulb, View.SCALE_X, 1f, 1.2f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.lightBulb, View.SCALE_Y, 1f, 1.2f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 300
            interpolator = OvershootInterpolator()
            start()
        }
    }

    // ─── SHAKE LISTENER ───────────────────────────────────────────
    private fun setupShakeListener() {
        sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)

        shakeListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                if (event == null) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val gForce = sqrt(x * x + y * y + z * z) / android.hardware.SensorManager.GRAVITY_EARTH
                if (gForce > 2.5) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeTime > shakeDebounceMs) {
                        lastShakeTime = now
                        runOnUiThread {
                            when (currentMode) {
                                FlashMode.NORMAL -> toggleFlashlight()
                                FlashMode.SOS -> toggleSos()
                                FlashMode.STROBE -> toggleStrobe()
                                FlashMode.SCREEN -> toggleScreenLight()
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(
            shakeListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI
        )
    }

    // ─── TUTORIAL ─────────────────────────────────────────────────
    private fun showTutorialIfFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)

        if (isFirstLaunch) {
            binding.lightBulb.post { showLightBulbTutorial() }
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
    }

    private fun showLightBulbTutorial() {
        TapTargetView.showFor(this,
            TapTarget.forView(binding.lightBulb,
                "Pull the Light Bulb",
                "Drag the bulb down and release to toggle. Use modes below for SOS, Strobe, or Screen Light!")
                .outerCircleColor(R.color.tutorial_outer_circle)
                .outerCircleAlpha(0.96f)
                .targetCircleColor(android.R.color.white)
                .titleTextSize(24)
                .titleTextColor(android.R.color.white)
                .descriptionTextSize(16)
                .descriptionTextColor(android.R.color.white)
                .textColor(android.R.color.white)
                .dimColor(android.R.color.black)
                .drawShadow(true)
                .cancelable(true)
                .tintTarget(false)
                .transparentTarget(true)
                .targetRadius(80),
            object : TapTargetView.Listener() {
                override fun onTargetClick(view: TapTargetView) {
                    super.onTargetClick(view)
                }
            }
        )
    }

    // ─── RATE DIALOG ──────────────────────────────────────────────
    private fun incrementUsageAndMaybeRate() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasRated = prefs.getBoolean(KEY_RATED, false)
        if (hasRated) return

        usageCount++
        prefs.edit().putInt(KEY_USAGE_COUNT, usageCount).apply()

        // Show after 3rd, then every 10th use
        if (usageCount == 3 || (usageCount > 3 && usageCount % 10 == 0)) {
            showRateDialog()
        }
    }

    private fun showRateDialog() {
        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.rate_title))
            .setMessage(getString(R.string.rate_message))
            .setPositiveButton(getString(R.string.rate_now)) { _, _ ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RATED, true).apply()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                }
            }
            .setNeutralButton(getString(R.string.rate_later), null)
            .setNegativeButton(getString(R.string.rate_never)) { _, _ ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RATED, true).apply()
            }
            .setCancelable(true)
            .show()
    }

    // ─── HELPERS ──────────────────────────────────────────────────
    private fun turnOffFlash() {
        try {
            cameraManager.setTorchMode(cameraId, false)
            isFlashlightOn = false
            runOnUiThread { updateFlashlightUI() }
        } catch (_: CameraAccessException) {}
    }

    private fun setEllipsesTint(isOn: Boolean) {
        val tintColor = if (isOn) 0xFFFFA500.toInt() else 0xFF1E1E1E.toInt()
        binding.ellipse1.background.setTint(tintColor)
        binding.ellipse2.background.setTint(tintColor)
        binding.ellipse3.background.setTint(tintColor)
        binding.ellipse4.background.setTint(tintColor)
    }

    private fun vibrateClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(30)
                }
            }
        } catch (_: Exception) {}
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────
    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(shakeListener)
    }

    override fun onResume() {
        super.onResume()
        val accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(
            shakeListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI
        )
        updateBatteryLevel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllEffects()
        autoOffTimer?.cancel()
        batteryHandler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(shakeListener)
    }
}

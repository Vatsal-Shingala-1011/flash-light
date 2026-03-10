package com.vatsal.flashlightmine

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.widget.RemoteViews

class FlashlightWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.vatsal.flashlightmine.ACTION_TOGGLE_FLASHLIGHT"
        private const val PREFS_NAME = "FlashlightWidgetPrefs"
        private const val KEY_TORCH_ON = "isTorchOn"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            toggleFlashlight(context)
            // Update all widget instances
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, FlashlightWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, appWidgetManager, id)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Register torch callback to keep widget in sync
        registerTorchCallback(context)
    }

    private fun registerTorchCallback(context: Context) {
        try {
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(KEY_TORCH_ON, enabled).apply()
                    // Update widgets
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val ids = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, FlashlightWidgetProvider::class.java)
                    )
                    for (id in ids) {
                        updateWidget(context, appWidgetManager, id)
                    }
                }
            }, null)
        } catch (_: Exception) {}
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isOn = prefs.getBoolean(KEY_TORCH_ON, false)
            val newState = !isOn
            cameraManager.setTorchMode(cameraId, newState)
            prefs.edit().putBoolean(KEY_TORCH_ON, newState).apply()
        } catch (_: CameraAccessException) {}
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isOn = prefs.getBoolean(KEY_TORCH_ON, false)

        val views = RemoteViews(context.packageName, R.layout.widget_flashlight)

        // Set click intent
        val toggleIntent = Intent(context, FlashlightWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // Update visuals
        views.setImageViewResource(
            R.id.widget_icon,
            if (isOn) R.drawable.bulb_on else R.drawable.bulb_off
        )
        views.setTextViewText(R.id.widget_label, if (isOn) "ON" else "OFF")
        views.setInt(
            R.id.widget_root, "setBackgroundResource",
            if (isOn) R.drawable.bg_widget_on else R.drawable.bg_widget_off
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}

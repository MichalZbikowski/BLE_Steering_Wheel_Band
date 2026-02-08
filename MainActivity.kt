package com.example.band_testy

import BleButtonReceiver
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var bleReceiver: BleButtonReceiver
    private lateinit var audioManager: AudioManager

    private lateinit var connectionStatus: TextView
    private lateinit var lastAction: TextView
    private lateinit var actionHistory: TextView
    private lateinit var statusIcon: ImageView

    private val history = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.values.all { granted -> granted }) startBle()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        connectionStatus = findViewById(R.id.connectionStatus)
        lastAction = findViewById(R.id.lastAction)
        actionHistory = findViewById(R.id.actionHistory)
        statusIcon = findViewById(R.id.statusIcon)

        bleReceiver = BleButtonReceiver(this)

        bleReceiver.setActionCallback { action ->
            runOnUiThread { onAction(action) }
        }

        bleReceiver.setConnectionCallback { connected ->
            runOnUiThread {
                updateStatus(connected)
            }
        }

        bleReceiver.setReconnectCallback { attempt, delay ->
            runOnUiThread {
                connectionStatus.text = "Reconnecting (attempt $attempt, ${delay}ms)"
                statusIcon.setImageResource(android.R.drawable.presence_away)
            }
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) permissionLauncher.launch(perms) else startBle()
    }

    private fun startBle() {
        connectionStatus.text = "Scanning..."
        statusIcon.setImageResource(android.R.drawable.presence_away)
        bleReceiver.startScanning()
    }

    private fun onAction(action: String) {
        val mediaAction = when (action) {
            "short1" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Volume Up"
            }
            "short2" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Volume Down"
            }
            "short3" -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "Play/Pause"
            }
            "short4" -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Previous Track"
            }
            "short5" -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "Next Track"
            }
            else -> action
        }

        lastAction.text = mediaAction
        history.add(0, "${timeFormat.format(Date())} - $mediaAction")
        if (history.size > 20) history.removeAt(history.lastIndex)
        actionHistory.text = history.joinToString("\n")
    }

    private fun updateStatus(connected: Boolean) {
        if (connected) {
            connectionStatus.text = "Connected"
            statusIcon.setImageResource(android.R.drawable.presence_online)
        } else {
            connectionStatus.text = "Disconnected"
            statusIcon.setImageResource(android.R.drawable.presence_offline)
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val keyDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val keyUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(keyDown)
        audioManager.dispatchMediaKeyEvent(keyUp)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleReceiver.disconnect()
    }
}

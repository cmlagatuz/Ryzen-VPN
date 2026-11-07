package com.personal.privatevpn

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.AtomicFile
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File
import java.util.concurrent.Executors

private val countries = listOf("Türkiye", "Brazil", "Ukraine")
private val slots = listOf("tr", "br", "ua")

class VpnApp : Application() {
    val backend by lazy { GoBackend(this) }
    val worker = Executors.newSingleThreadExecutor()
    val tunnel = object : Tunnel {
        override fun getName() = "privatevpn"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }
    fun profile(slot: Int) = AtomicFile(File(noBackupFilesDir, "${slots[slot]}.conf"))
}

class MainActivity : Activity() {
    private val app get() = application as VpnApp
    private lateinit var location: Spinner
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var importProfile: Button
    private var busy = false
    private var connected = false
    private var pendingSlot = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(padding + insets.systemWindowInsetLeft,
                    padding + insets.systemWindowInsetTop,
                    padding + insets.systemWindowInsetRight,
                    padding + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(layout)
        layout.addView(TextView(this).apply { text = "Ryzen VPN"; textSize = 28f })
        location = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, countries)
            setSelection(savedInstanceState?.getInt("slot")
                ?: getPreferences(MODE_PRIVATE).getInt("slot", 0))
        }
        pendingSlot = savedInstanceState?.getInt("pending") ?: 0
        layout.addView(location)
        status = TextView(this)
        layout.addView(status)
        toggle = Button(this).apply {
            setOnClickListener {
                if (connected) runOperation("Disconnecting…", "Could not disconnect.") {
                    app.backend.setState(app.tunnel, Tunnel.State.DOWN, null)
                } else {
                    pendingSlot = location.selectedItemPosition
                    if (!app.profile(pendingSlot).baseFile.exists()) {
                        notifyUser("Import a WireGuard profile for this location first.")
                    } else {
                        val consent = VpnService.prepare(this@MainActivity)
                        if (consent == null) connect(pendingSlot)
                        else startActivityForResult(consent, 1)
                    }
                }
            }
        }
        layout.addView(toggle)
        importProfile = Button(this).apply {
            text = "Import profile for selected location"
            setOnClickListener {
                pendingSlot = location.selectedItemPosition
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, 2)
            }
        }
        layout.addView(importProfile)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        status.removeCallbacks(poll)
        status.postDelayed(poll, 1000)
    }

    override fun onPause() {
        status.removeCallbacks(poll)
        super.onPause()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!busy) refresh()
            status.postDelayed(this, 1000)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("slot", location.selectedItemPosition)
        outState.putInt("pending", pendingSlot)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Used with the platform Activity to keep dependencies small")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            1 -> connect(pendingSlot)
            2 -> data?.data?.let { saveProfile(pendingSlot, it) }
        }
    }

    private fun connect(slot: Int) {
        getPreferences(MODE_PRIVATE).edit().putInt("slot", slot).apply()
        runOperation("Connecting…", "Connection failed. Check the profile and Android VPN permission.") {
            val config = app.profile(slot).openRead().use { Config.parse(it) }
            app.backend.setState(app.tunnel, Tunnel.State.UP, config)
        }
    }

    private fun saveProfile(slot: Int, uri: Uri) {
        runOperation("Importing…", "Import failed. Use a single-peer WireGuard profile from the setup guide.") {
            val bytes = requireNotNull(contentResolver.openInputStream(uri)).use {
                it.readNBytesCompat(16385)
            }
            require(bytes.size <= 16384)
            val config = Config.parse(bytes.inputStream())
            val peer = config.peers.single()
            require(peer.endpoint.isPresent)
            require(peer.allowedIps.map { it.toString() }.toSet() == setOf("0.0.0.0/0", "0:0:0:0:0:0:0:0/0") ||
                peer.allowedIps.map { it.toString() }.toSet() == setOf("0.0.0.0/0", "::/0"))
            require(config.`interface`.includedApplications.isEmpty() && config.`interface`.excludedApplications.isEmpty())
            val file = app.profile(slot)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(buffer, count, limit - count)
            if (read < 0) break
            count += read
        }
        return buffer.copyOf(count)
    }

    private fun runOperation(label: String, errorMessage: String, operation: () -> Unit) {
        busy = true
        render(label)
        app.worker.execute {
            val error = runCatching(operation).exceptionOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                refresh()
                if (error != null) notifyUser(errorMessage)
            }
        }
    }

    private fun refresh() {
        if (busy) return
        connected = app.backend.getState(app.tunnel) == Tunnel.State.UP
        if (connected) location.setSelection(getPreferences(MODE_PRIVATE).getInt("slot", 0))
        render(if (connected) "Connected · Android tunnel active" else "Disconnected")
    }

    private fun render(label: String) {
        status.text = label
        toggle.text = if (connected) "Disconnect" else "Connect"
        toggle.isEnabled = !busy
        location.isEnabled = !busy && !connected
        importProfile.visibility = if (connected) View.GONE else View.VISIBLE
        importProfile.isEnabled = !busy
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

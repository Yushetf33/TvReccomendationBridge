package com.tunombre.tvbridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Lista los dispositivos vinculados al email verificado en este TV y
 * permite eliminarlos, sin esperar los 30 días de REBIND_COOLDOWN_DAYS
 * del backend. Solo tiene sentido si este dispositivo ya verificó una
 * suscripción antes: /api/devices exige ese mismo deviceId como prueba de
 * propiedad para devolver la lista.
 */
class DeviceManagerActivity : Activity() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var container: LinearLayout
    private lateinit var email: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manager)

        statusView = findViewById(R.id.devices_status)
        container = findViewById(R.id.devices_container)

        val savedEmail = LicenseManager.getSavedEmail(this)
        if (savedEmail == null) {
            finish()
            return
        }
        email = savedEmail

        loadDevices()
    }

    private fun loadDevices() {
        statusView.text = getString(R.string.devices_loading)
        container.removeAllViews()
        backgroundExecutor.execute {
            val result = DeviceApi.listDevices(this, email)
            runOnUiThread { showResult(result) }
        }
    }

    private fun showResult(result: DevicesResult) {
        when (result) {
            is DevicesResult.Loaded -> {
                if (result.devices.isEmpty()) {
                    statusView.text = getString(R.string.devices_empty)
                } else {
                    statusView.text = ""
                    result.devices.forEach { addDeviceRow(it) }
                }
            }
            is DevicesResult.Error -> {
                statusView.text = getString(R.string.devices_error)
            }
        }
    }

    private fun addDeviceRow(device: BoundDevice) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val name = device.deviceName ?: getString(R.string.devices_unknown_name)
        val date = if (device.boundAt > 0) {
            DateFormat.getDateInstance().format(Date(device.boundAt))
        } else {
            "?"
        }

        val label = TextView(this).apply {
            text = if (device.isThisDevice) {
                getString(R.string.devices_row_this_device, name, date)
            } else {
                getString(R.string.devices_row, name, date)
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeButton = Button(this).apply {
            text = getString(R.string.devices_remove_button)
            setOnClickListener { confirmRemove(device, name) }
        }

        row.addView(label)
        row.addView(removeButton)
        container.addView(row)
    }

    private fun confirmRemove(device: BoundDevice, displayName: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.devices_confirm_remove, displayName))
            .setPositiveButton(R.string.devices_remove_button) { _, _ -> removeDevice(device) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeDevice(device: BoundDevice) {
        backgroundExecutor.execute {
            val ok = DeviceApi.removeDevice(this, email, device.deviceId)
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, R.string.devices_error, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (device.isThisDevice) {
                    LicenseManager.clear(this)
                    Toast.makeText(this, R.string.devices_removed_self, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.devices_removed, Toast.LENGTH_SHORT).show()
                    loadDevices()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }
}

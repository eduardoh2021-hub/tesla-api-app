package com.misrutas.ev.trip

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.misrutas.ev.data.Prefs

/** Arranca/para el registro cuando el móvil se conecta o desconecta del Bluetooth del coche. */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val target = prefs.btAddress ?: return
        if (!prefs.autoMode || !prefs.isConfigured) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!device.address.equals(target, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> TripService.send(ctx, TripService.ACTION_BT_CONNECTED)
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                if (TripService.running.value) TripService.send(ctx, TripService.ACTION_BT_GONE)
        }
    }
}

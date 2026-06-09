package com.shipradar.comms.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.LifecycleService

/**
 * Foreground service shell for the communications module (T1.1, third wave).
 *
 * SCAFFOLD ONLY — the wiring worker fills these TODOs:
 *  - acquire [WifiManager.MulticastLock] (wired LAN still needs it on many Android builds),
 *  - open multicast sockets for HALO (236.6.7.x) + 61162-450 groups, run the comms-core parsers,
 *  - drive [com.shipradar.comms.halo.handshake] (01B1/01B2 + A1C1 watchdog) and
 *    [com.shipradar.comms.sync] reconnection,
 *  - expose [com.shipradar.contract.RadarDataBus] / [com.shipradar.contract.RadarController] to the UI.
 *
 * It compiles and starts as a foreground service so the Android base is verifiable now.
 */
class RadarCommsService : LifecycleService() {

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireMulticastLock()
        // TODO(T1.1 wiring): start sockets + comms-core pipeline here.
    }

    private fun acquireMulticastLock() {
        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("shipradar-multicast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val channelId = "radar_comms"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Radar communications", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification =
            Notification.Builder(this, channelId)
                .setContentTitle("Radar communications")
                .setContentText("Receiving radar & sensor data")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder icon
                .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        multicastLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

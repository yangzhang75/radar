package com.shipradar.comms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.shipradar.constants.DataInterfaceProfile
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarDataBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the radar link for the app's lifetime. It constructs the real
 * [AndroidMulticastTransport] + a [RadarCommsEngine] and exposes the engine's [RadarDataBus] /
 * [RadarController] to the UI via a local [Binder]. Being a foreground service (type
 * `connectedDevice`) keeps the multicast sockets and watchdog alive when the app is backgrounded —
 * losing the A1C1 watchdog would send the radar to standby.
 *
 * The Android shell is intentionally thin: all protocol/orchestration logic lives in
 * [RadarCommsEngine] / [CommsRouter] and is unit-tested off-device.
 */
class RadarCommsService : Service() {

    // 高实时性:数据接口摄取跑在专用提升优先级线程池上,与 UI/默认池隔离(见 RealtimeIngest)。
    private val scope = CoroutineScope(SupervisorJob() + RealtimeIngest.dispatcher())
    private var engine: RadarCommsEngine? = null
    private val binder = LocalBinder()

    /** Binding surface for the UI process — exposes only the frozen contract, never sockets. */
    inner class LocalBinder : Binder() {
        val dataBus: RadarDataBus? get() = engine
        val controller: RadarController? get() = engine
        /** 完整引擎(含双量程 echoSpokesB + 链路诊断 dataLinkSnapshot,非契约附加能力)。 */
        val radarEngine: RadarCommsEngine? get() = engine
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (engine == null) {
            // 数据接口 profile:模拟(模拟端口,供主机侧模拟器)/ 实际(法定 236.6.7.x 端口)。
            val profile = if (intent?.getStringExtra(EXTRA_PROFILE) == DataInterfaceProfile.SIMULATION.name) {
                DataInterfaceProfile.SIMULATION
            } else {
                DataInterfaceProfile.ACTUAL
            }
            val config = when (profile) {
                DataInterfaceProfile.SIMULATION -> CommsConfig.simulation()
                DataInterfaceProfile.ACTUAL -> CommsConfig.actual(manualRadarIp = intent?.getStringExtra(EXTRA_MANUAL_IP))
            }
            engine = RadarCommsEngine(AndroidMulticastTransport(this), config, scope).also { it.start() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Radar link")
            .setContentText("Maintaining radar communications")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Radar communications",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps the radar link and watchdog alive in the background." }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "radar_comms"
        private const val NOTIF_ID = 1001

        /** Optional intent extra: manual radar IP for the handshake fallback (蒲公英 VPN multicast loss). */
        const val EXTRA_MANUAL_IP = "com.shipradar.comms.MANUAL_IP"

        /** Intent extra: 数据接口 profile 名(SIMULATION / ACTUAL)。缺省按 ACTUAL。 */
        const val EXTRA_PROFILE = "com.shipradar.comms.PROFILE"

        /** Start the service in the foreground from anywhere with an app [Context]. */
        fun start(
            context: Context,
            profile: DataInterfaceProfile = DataInterfaceProfile.ACTUAL,
            manualRadarIp: String? = null,
        ) {
            val intent = Intent(context, RadarCommsService::class.java).apply {
                putExtra(EXTRA_PROFILE, profile.name)
                manualRadarIp?.let { putExtra(EXTRA_MANUAL_IP, it) }
            }
            context.startForegroundService(intent)
        }
    }
}

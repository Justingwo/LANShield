package org.distrinet.lanshield.vpnservice

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.distrinet.lanshield.ALLOW_DNS
import org.distrinet.lanshield.ALLOW_MULTICAST
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.HIDE_DNS_NOTIFICATIONS
import org.distrinet.lanshield.HIDE_MULTICAST_NOTIFICATIONS
import org.distrinet.lanshield.LocalNetworkPermission
import org.distrinet.lanshield.MainActivity
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.SERVICE_NOTIFICATION_CHANNEL_ID
import org.distrinet.lanshield.SYSTEM_APPS_POLICY_KEY
import org.distrinet.lanshield.crashreport.crashReporter
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.VPN_ALWAYS_ON_STATUS
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LANShieldSession
import org.distrinet.lanshield.database.model.LanAccessPolicy
import tech.httptoolkit.android.vpn.socket.IProtectSocket
import tech.httptoolkit.android.vpn.socket.SocketProtector
import javax.inject.Inject

const val TUN_IP4_ADDRESS = "10.215.173.1"
const val TUN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1"

@AndroidEntryPoint
class VPNService : VpnService(), IProtectSocket {
    private var vpnRunnable: VPNRunnable? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    private var installedInterfaceRoutes: Set<RoutePrefix> = emptySet()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routeRefresh = Runnable { refreshInterfaceRoutes() }

    private lateinit var accessPolicies: LiveData<List<LanAccessPolicy>>
    private lateinit var defaultForwardPolicyLive: LiveData<Policy>
    private lateinit var systemAppsForwardPolicyLive: LiveData<Policy>
    private lateinit var allowMulticastLive: LiveData<Boolean>
    private lateinit var allowDnsLive: LiveData<Boolean>
    private lateinit var hideMulticastNotificationsLive: LiveData<Boolean>
    private lateinit var hideDnsNotificationsLive: LiveData<Boolean>

    private var isVPNRunning = false

    private var lanShieldSession: LANShieldSession? = null

    @Inject
    lateinit var vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>

    @Inject
    lateinit var vpnAlwaysOnStatus: MutableLiveData<VPN_ALWAYS_ON_STATUS>

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    @Inject
    lateinit var lanAccessPolicyDao: LanAccessPolicyDao

    @Inject
    lateinit var vpnNotificationManager: LANShieldNotificationManager

    @Inject
    lateinit var lanShieldSessionDao: LANShieldSessionDao

    companion object {
        private const val REFUSAL_NOTIFICATION_ID = 2
        private const val ROUTE_REFRESH_DEBOUNCE_MS = 1500L
        const val STOP_VPN_SERVICE = "STOP_VPN_SERVICE"
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!this::defaultForwardPolicyLive.isInitialized) {
            defaultForwardPolicyLive = dataStore.data.map {
                Policy.valueOf(
                    it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()
                )
            }.distinctUntilChanged().asLiveData()
            systemAppsForwardPolicyLive = dataStore.data.map {
                Policy.valueOf(
                    it[SYSTEM_APPS_POLICY_KEY] ?: Policy.DEFAULT.toString()
                )
            }.distinctUntilChanged().asLiveData()

            allowMulticastLive = dataStore.data.map {
                it[ALLOW_MULTICAST] ?: false
            }.distinctUntilChanged().asLiveData()

            allowDnsLive = dataStore.data.map {
                it[ALLOW_DNS] ?: false
            }.distinctUntilChanged().asLiveData()

            hideMulticastNotificationsLive = dataStore.data.map {
                it[HIDE_MULTICAST_NOTIFICATIONS] ?: false
            }.distinctUntilChanged().asLiveData()

            hideDnsNotificationsLive = dataStore.data.map {
                it[HIDE_DNS_NOTIFICATIONS] ?: false
            }.distinctUntilChanged().asLiveData()

            accessPolicies = lanAccessPolicyDao.getAllLive()
        }

        updateAlwaysOnStatus()

        if (intent?.action == STOP_VPN_SERVICE) {
            if (isVPNRunning()) {
                stopVPNThread()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        } else if (!isVPNRunning()) {
            val notifications = LANShieldNotificationManager(this)
            notifications.createNotificationChannels()
            if (!LocalNetworkPermission.isGranted(this)) {
                return refuseStart(
                    notifications.buildServiceErrorNotification(
                        getString(R.string.local_network_permission_missing_title),
                        getString(R.string.local_network_permission_missing_text)
                    )
                )
            }
            if (prepare(this) != null) {
                return refuseStart(
                    notifications.buildServiceErrorNotification(
                        getString(R.string.lanshield_start_failed_title),
                        getString(R.string.vpn_consent_missing_text)
                    )
                )
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(1, createNotification(), FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                } else {
                    startForeground(1, createNotification())
                }
            } catch (e: SecurityException) {
                // Foreground eligibility lost between the checks above and here.
                Log.w(TAG, "Not permitted to start as a foreground service", e)
                return refuseStart(
                    notifications.buildServiceErrorNotification(
                        getString(R.string.lanshield_start_failed_title),
                        getString(R.string.lanshield_start_failed_text)
                    )
                )
            }
            startVPNThread()
        }

        // Return the appropriate service restart behavior
        return START_STICKY
    }

    private fun refuseStart(notification: Notification): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(REFUSAL_NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(REFUSAL_NOTIFICATION_ID, notification)
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVPNThread()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Permission was revoked (e.g. another VPN took over); don't let START_STICKY restart us.
        stopSelf()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        setVPNRunning(false)
        stopLanShieldSession()
        super.onDestroy()
    }

    private fun stopLanShieldSession() {
        if (lanShieldSession != null) {
            lanShieldSession!!.timeEnd = System.currentTimeMillis()
            val session = lanShieldSession!!
            CoroutineScope(Dispatchers.IO).launch {
                lanShieldSessionDao.update(session)
            }
            lanShieldSession = null
        }
    }

    private fun createNotification(): Notification {
        // Create an intent for stopping the VPN service
        val stopIntent = Intent(this, VPNService::class.java).apply {
            action = STOP_VPN_SERVICE
        }
        val stopPendingIntent: PendingIntent =
            PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val openAppIntent = Intent(this, MainActivity::class.java)
        openAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification using NotificationCompat.Builder
        return NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentText(getString(R.string.app_name) + " enabled")
            .setSmallIcon(R.mipmap.logo_foreground)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.mipmap.logo_foreground,
                "Stop LANShield",
                stopPendingIntent
            ).build()
    }

    private fun stopVPNThread(keepSession: Boolean = false) {
        unregisterNetworkCallback()
        if (!keepSession) stopLanShieldSession()

        vpnRunnable?.let {
            accessPolicies.removeObserver(it.accessPoliesObserver)
            defaultForwardPolicyLive.removeObserver(it.defaultPolicyObserver)
            systemAppsForwardPolicyLive.removeObserver(it.systemAppsPolicyObserver)
            allowMulticastLive.removeObserver(it.allowMulticastObserver)
            allowDnsLive.removeObserver(it.allowDnsObserver)
            hideMulticastNotificationsLive.removeObserver(it.hideMulticastNotificationsObserver)
            hideDnsNotificationsLive.removeObserver(it.hideDnsNotificationsObserver)
            it.stop()
        }
        vpnRunnable = null

//        vpnThread?.join() //TODO -> shouldn't be required
        vpnThread = null

        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        }
        vpnInterface = null

        setVPNRunning(false)
    }

    private fun isVPNRunning(): Boolean {
        return isVPNRunning
    }

    private fun setVPNRunning(isRunning: Boolean) {
        vpnServiceStatus.value = if (isRunning) {
            VPN_SERVICE_STATUS.ENABLED
        } else {
            VPN_SERVICE_STATUS.DISABLED
        }
        isVPNRunning = isRunning
    }

    private fun addIpv4Routes(builder: Builder) {
        // RFC1918 Private Internets
        builder.addRoute("10.0.0.0", 8)
            .addRoute("172.16.0.0", 12)
            .addRoute("192.168.0.0", 16)
            // RFC5735 Special Use addresses that are not globally reachable
            .addRoute("0.0.0.0", 8)
            .addRoute("169.254.0.0", 16)
            .addRoute("192.0.0.0", 24)
            .addRoute("192.0.2.0", 24)
            .addRoute("192.88.99.0", 24)
            .addRoute("198.18.0.0", 15)
            .addRoute("198.51.100.0", 24)
            .addRoute("203.0.113.0", 24)
            // IPv4 Multicast and Limited Broadcast
            .addRoute("224.0.0.0", 4)
            .addRoute("255.255.255.255", 32)
            // Remaining non-globally reachable addresses from the list at
            // https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml
            .addRoute("100.64.0.0", 10)
            .addRoute("240.0.0.0", 4)
    }

    private fun addIpv6Routes(builder: Builder) {
        // PART 1, based on https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml
        // The IETF Protocol Assignments range is not globally reachable, but subnets inside
        // it are reachable. We only intercept the non-gobally reachable subnets.
        builder.addRoute("100::", 64) // Discard-Only Address Block
            .addRoute("2001:2::", 32) // Benchmarking
            .addRoute("2001:db8::", 32) // Documentation
            .addRoute("5f00::", 16) // Segment Routing SIDs
            .addRoute("fc00::", 7) // Unique-Local
            .addRoute("fe80::", 10) // Link-Local Unicast
            // PART 2, deprecated ranges that are not on the IANA overview but might
            // still be used in practice
            .addRoute("fec0::", 10) // Site-local addresses
            // PART 3, based on https://www.iana.org/assignments/ipv6-multicast-addresses/ipv6-multicast-addresses.xhtml
            // Intercept all multicast destination. Ideally we would also exclude the global-scope
            // multicast addresses here already, but that requires a higher API level, so instead
            // we have to filter that address while processing packets.
            .addRoute("ff00::", 8)
        // TODO: Filter global-scope multicast later on in the processing of packets.
        // .excludeRoute("ff0e::", 16)
    }

    private fun startVPNThread(keepSession: Boolean = false) {
        updateAlwaysOnStatus()

        val interfaceRoutes = currentInterfaceRoutePrefixes()
        val builder = Builder()
        builder.setSession(getString(R.string.app_name) + " LAN Firewall")
            .addAddress(TUN_IP4_ADDRESS, 32)
            .addAddress(TUN_IP6_ADDRESS, 128)
        addIpv4Routes(builder)
        addIpv6Routes(builder)
        for (route in interfaceRoutes) {
            Log.d(TAG, "Also monitoring $route")
            builder.addRoute(route.address, route.prefixLength)
        }
        builder.addDisallowedApplication(packageName)
            .setBlocking(true)
            .setMtu(MAX_PACKET_LEN)
            .setMetered(false)

        val vpnInterface = try {
            builder.establish()
        } catch (e: IllegalStateException) {
            // Builder parameters rejected by the platform.
            Log.w(TAG, "Could not establish VPN interface", e)
            crashReporter.recordException(e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to establish VPN interface", e)
            null
        }
        if (vpnInterface == null) {
            if (keepSession && isVPNRunning()) {
                Log.w(TAG, "Keeping current VPN interface after failed re-establish")
                return
            }
            stopVPNThread()
            stopForeground(STOP_FOREGROUND_REMOVE)
            setVPNRunning(false)
            vpnNotificationManager.postServiceErrorNotification(
                getString(R.string.lanshield_start_failed_title),
                getString(R.string.lanshield_start_failed_text)
            )
            return
        }

        // The new interface has replaced the old one; now tear down the old packet loop.
        stopVPNThread(keepSession)
        this.vpnInterface = vpnInterface
        installedInterfaceRoutes = interfaceRoutes
        SocketProtector.getInstance().setProtector(this)

        vpnRunnable = VPNRunnable(vpnInterface, vpnNotificationManager, this)
        accessPolicies.observeForever(vpnRunnable!!.accessPoliesObserver)
        defaultForwardPolicyLive.observeForever(vpnRunnable!!.defaultPolicyObserver)
        systemAppsForwardPolicyLive.observeForever(vpnRunnable!!.systemAppsPolicyObserver)
        allowMulticastLive.observeForever(vpnRunnable!!.allowMulticastObserver)
        allowDnsLive.observeForever(vpnRunnable!!.allowDnsObserver)
        hideMulticastNotificationsLive.observeForever(vpnRunnable!!.hideMulticastNotificationsObserver)
        hideDnsNotificationsLive.observeForever(vpnRunnable!!.hideDnsNotificationsObserver)

        vpnThread = Thread(vpnRunnable, "VPN thread")

        if (!keepSession || lanShieldSession == null) {
            stopLanShieldSession()
            val session = LANShieldSession.createLANShieldSession()
            lanShieldSession = session
            CoroutineScope(Dispatchers.IO).launch {
                lanShieldSessionDao.insert(session)
            }
        }
        vpnThread!!.start()
        setVPNRunning(true)
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRouteRefresh()
            override fun onLost(network: Network) = scheduleRouteRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                scheduleRouteRefresh()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        mainHandler.removeCallbacks(routeRefresh)
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        networkCallback = null
    }

    private fun scheduleRouteRefresh() {
        mainHandler.removeCallbacks(routeRefresh)
        mainHandler.postDelayed(routeRefresh, ROUTE_REFRESH_DEBOUNCE_MS)
    }

    private fun refreshInterfaceRoutes() {
        if (!isVPNRunning()) return
        val current = currentInterfaceRoutePrefixes()
        if (current == installedInterfaceRoutes) return
        Log.i(TAG, "Interface routes changed $installedInterfaceRoutes -> $current; re-establishing VPN")
        startVPNThread(keepSession = true)
    }

    private fun updateAlwaysOnStatus() {
        vpnAlwaysOnStatus.postValue(if (isAlwaysOn) VPN_ALWAYS_ON_STATUS.ENABLED else VPN_ALWAYS_ON_STATUS.DISABLED)
    }
}

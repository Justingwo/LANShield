package org.distrinet.lanshield

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.vpnservice.VPNService
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Covers the sticky-restart / boot path: the service is started directly (no activity, no
 * request dialog) while ACCESS_LOCAL_NETWORK is revoked. It must not bring the tunnel up and must
 * tell the user why. Skipped below API 37 where the permission does not exist.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionGateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val entryPoint =
        EntryPointAccessors.fromApplication(context, VpnStatusEntryPoint::class.java)
    private val status: MutableLiveData<VPN_SERVICE_STATUS> = entryPoint.vpnServiceStatus()

    @Before
    fun setUp() {
        assumeTrue("Local network permission only exists from API 37", LocalNetworkPermission.isRequired())
        // The orchestrator clears permissions per test; make the preconditions explicit anyway.
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("pm revoke ${context.packageName} ${LocalNetworkPermission.PERMISSION}")
    }

    @After
    fun tearDown() {
        try {
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
        } catch (_: Exception) {
        }
    }

    @Test
    fun serviceRefusesToStart_andPostsNotification_whenLocalNetworkRevoked() {
        // Same entry point the boot worker uses: the service must stop itself before the
        // foreground deadline or the system kills the process with a ForegroundServiceDidNotStartInTime.
        context.startForegroundService(Intent(context, VPNService::class.java))

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            assertNotEquals(
                "VPN started despite ACCESS_LOCAL_NETWORK being revoked",
                VPN_SERVICE_STATUS.ENABLED,
                status.value
            )
            Thread.sleep(250)
        }

        val expectedTitle = context.getString(R.string.local_network_permission_missing_title)
        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = nm.activeNotifications.any {
            it.notification.extras.getCharSequence("android.title")?.toString() == expectedTitle
        }
        assertTrue("Expected a '$expectedTitle' notification to be posted", posted)
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}

package org.distrinet.lanshield.vpnservice

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.R
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.VpnStatusEntryPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Starts the service directly (as the boot worker and sticky restarts do) while VPN consent is
 * denied. establish() then returns null or throws SecurityException depending on the platform;
 * either way the service must not crash, must stay disabled, and must tell the user.
 */
@RunWith(AndroidJUnit4::class)
class VpnConsentMissingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val entryPoint =
        EntryPointAccessors.fromApplication(context, VpnStatusEntryPoint::class.java)
    private val status: MutableLiveData<VPN_SERVICE_STATUS> = entryPoint.vpnServiceStatus()

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} ACTIVATE_VPN deny")
    }

    @After
    fun tearDown() {
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
        try {
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
        } catch (_: Exception) {
        }
    }

    @Test
    fun serviceStaysDisabled_andNotifies_whenVpnConsentIsMissing() {
        context.startService(Intent(context, VPNService::class.java))

        val expectedTitle = context.getString(R.string.lanshield_start_failed_title)
        val nm = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 10_000
        var posted = false
        while (System.currentTimeMillis() < deadline && !posted) {
            assertEquals("VPN came up without consent", VPN_SERVICE_STATUS.DISABLED, status.value)
            posted = nm.activeNotifications.any {
                it.notification.extras.getCharSequence("android.title")?.toString() == expectedTitle
            }
            Thread.sleep(250)
        }
        assertTrue("Expected a '$expectedTitle' notification", posted)
        assertEquals(VPN_SERVICE_STATUS.DISABLED, status.value)
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}

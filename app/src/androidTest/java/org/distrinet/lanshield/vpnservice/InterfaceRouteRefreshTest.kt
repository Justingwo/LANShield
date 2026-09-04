package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.VpnStatusEntryPoint
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that the tunnel is re-established when a global prefix appears on or disappears from
 * a network interface. Adding an address needs root, which instrumentation does not have, so this
 * test cooperates with a host-side driver (see scripts/route-refresh-test.sh): after the VPN is
 * up, the driver adds 2001:db8:1::5/64 to wlan0, and later removes it again. Without the driver
 * the test is skipped, not failed.
 */
@RunWith(AndroidJUnit4::class)
class InterfaceRouteRefreshTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val entryPoint =
        EntryPointAccessors.fromApplication(context, VpnStatusEntryPoint::class.java)
    private val status: MutableLiveData<VPN_SERVICE_STATUS> = entryPoint.vpnServiceStatus()
    private val enabledCount = AtomicInteger(0)
    private val observer = Observer<VPN_SERVICE_STATUS> {
        if (it == VPN_SERVICE_STATUS.ENABLED) enabledCount.incrementAndGet()
    }

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        // The appop pre-consents, but the package still has to be prepared (the activity normally
        // does this); with consent in place prepare() returns null and no dialog is needed.
        assumeTrue("VPN consent could not be pre-granted", VpnService.prepare(context) == null)
        runOnMain { status.observeForever(observer) }
    }

    @After
    fun tearDown() {
        runOnMain { status.removeObserver(observer) }
        try {
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
        } catch (_: Exception) {
        }
    }

    @Test
    fun tunnelReestablishes_whenGlobalPrefixAppearsAndDisappears() {
        context.startService(Intent(context, VPNService::class.java))
        assertTrue("VPN did not come up", waitFor(15_000) { enabledCount.get() >= 1 })
        // Re-registering the network callback replays current link state; that must not restart.
        Thread.sleep(4_000)
        assertTrue("VPN restarted without any network change", enabledCount.get() == 1)

        assumeTrue(
            "Host driver did not add the test prefix; skipping",
            waitFor(60_000) { hasTestPrefixAddress() }
        )
        assertTrue(
            "VPN was not re-established after the prefix appeared",
            waitFor(20_000) { enabledCount.get() >= 2 }
        )
        assertTrue("VPN not running after re-establish", status.value == VPN_SERVICE_STATUS.ENABLED)

        assumeTrue(
            "Host driver did not remove the test prefix; skipping second half",
            waitFor(60_000) { !hasTestPrefixAddress() }
        )
        assertTrue(
            "VPN was not re-established after the prefix disappeared",
            waitFor(20_000) { enabledCount.get() >= 3 }
        )
        assertTrue("VPN not running after second re-establish", status.value == VPN_SERVICE_STATUS.ENABLED)
    }

    private fun hasTestPrefixAddress(): Boolean {
        val prefix = InetAddress.getByName("2001:db8:1::")
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        for (nif in interfaces) {
            for (address in nif.interfaceAddresses) {
                if (address.networkPrefixLength.toInt() == 64 &&
                    networkAddress(address.address, 64) == prefix
                ) return true
            }
        }
        return false
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}

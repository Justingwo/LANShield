package org.distrinet.lanshield.vpnservice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class InterfaceRoutesTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun `network address masks host bits and keeps the address family`() {
        val v4 = networkAddress(ip("203.0.113.77"), 24)
        assertThat(v4).isInstanceOf(Inet4Address::class.java)
        assertThat(v4).isEqualTo(ip("203.0.113.0"))

        val v4odd = networkAddress(ip("203.0.113.77"), 27)
        assertThat(v4odd).isEqualTo(ip("203.0.113.64"))

        val v6 = networkAddress(ip("2001:db8:1:2:abcd:ef01:2345:6789"), 64)
        assertThat(v6).isInstanceOf(Inet6Address::class.java)
        assertThat(v6).isEqualTo(ip("2001:db8:1:2::"))
    }

    @Test
    fun `global addresses become routes for their prefix`() {
        val routes = interfaceRoutePrefixes(
            listOf(ip("203.0.113.77") to 24, ip("2001:db8:1:2:abcd::1") to 64)
        )
        assertThat(routes).containsExactly(
            RoutePrefix(ip("203.0.113.0"), 24),
            RoutePrefix(ip("2001:db8:1:2::"), 64),
        )
    }

    @Test
    fun `addresses already covered by the static routes are ignored`() {
        val routes = interfaceRoutePrefixes(
            listOf(
                ip("10.215.173.1") to 32,                    // tun v4, site-local
                ip("192.168.1.20") to 24,                    // site-local
                ip("169.254.10.1") to 16,                    // link-local v4
                ip("fd00:2:fd00:1:fd00:1:fd00:1") to 128,    // tun v6, unique-local
                ip("fe80::1") to 64,                         // link-local v6
                ip("fec0::1") to 64,                         // site-local v6
                ip("127.0.0.1") to 8,                        // loopback
                ip("::1") to 128,
                ip("0.0.0.0") to 0,                          // any
                ip("::") to 0,
                ip("224.0.0.1") to 4,                        // multicast
                ip("ff02::1") to 8,
            )
        )
        assertThat(routes).isEmpty()
    }

    @Test
    fun `two addresses in the same prefix yield one route`() {
        val routes = interfaceRoutePrefixes(
            listOf(ip("2001:db8:1:2::10") to 64, ip("2001:db8:1:2:1234:5678:9abc:def0") to 64)
        )
        assertThat(routes).containsExactly(RoutePrefix(ip("2001:db8:1:2::"), 64))
    }

    @Test
    fun `a zero-length prefix never becomes a default route`() {
        val routes = interfaceRoutePrefixes(listOf(ip("203.0.113.77") to 0, ip("2001:db8::1") to 0))
        assertThat(routes).isEmpty()
    }

    @Test
    fun `route prefix equality is by address bytes`() {
        assertThat(RoutePrefix(ip("2001:db8::"), 32)).isEqualTo(RoutePrefix(ip("2001:0db8:0::0"), 32))
        assertThat(RoutePrefix(ip("2001:db8::"), 32)).isNotEqualTo(RoutePrefix(ip("2001:db8::"), 48))
    }
}

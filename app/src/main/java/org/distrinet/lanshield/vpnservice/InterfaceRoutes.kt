package org.distrinet.lanshield.vpnservice

import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

private const val TAG = "InterfaceRoutes"

/** A route as passed to [android.net.VpnService.Builder.addRoute]. Equality is by address bytes. */
data class RoutePrefix(val address: InetAddress, val prefixLength: Int) {
    override fun toString(): String = "${address.hostAddress}/$prefixLength"
}

/** Zeroes the host bits of [address], keeping its address family. */
fun networkAddress(address: InetAddress, prefixLength: Int): InetAddress {
    val bytes = address.address
    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8
    for (i in bytes.indices) {
        bytes[i] = when {
            i < fullBytes -> bytes[i]
            i == fullBytes && remainingBits != 0 ->
                (bytes[i].toInt() and (0xFF shl (8 - remainingBits))).toByte()
            else -> 0
        }
    }
    return InetAddress.getByAddress(bytes)
}

/**
 * Routes needed to capture traffic to the on-link prefixes of the given interface addresses that
 * the static private/link-local/multicast routes in [VPNService] do not already cover. In practice
 * these are global (public) prefixes, which matter for IPv6 where most LANs use them.
 */
fun interfaceRoutePrefixes(addresses: Iterable<Pair<InetAddress, Int>>): Set<RoutePrefix> =
    addresses
        .filterNot { (address, prefixLength) ->
            // A zero-length prefix (netmask 0.0.0.0, seen on some point-to-point links) would
            // become a default route through the tun and pull all internet traffic into LANShield.
            prefixLength <= 0 ||
                address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                isUniqueLocal(address)
        }
        .map { (address, prefixLength) -> RoutePrefix(networkAddress(address, prefixLength), prefixLength) }
        .toSet()

/** fc00::/7: covered by the static IPv6 routes, and where the tun's own IPv6 address lives. */
private fun isUniqueLocal(address: InetAddress): Boolean =
    address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC

/** [interfaceRoutePrefixes] for the addresses currently configured on this device's interfaces. */
fun currentInterfaceRoutePrefixes(): Set<RoutePrefix> {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces() ?: return emptySet()
    } catch (e: SocketException) {
        Log.w(TAG, "Could not enumerate network interfaces", e)
        return emptySet()
    }
    val addresses = mutableListOf<Pair<InetAddress, Int>>()
    for (networkInterface in interfaces) {
        try {
            if (networkInterface.isLoopback) continue
            for (address in networkInterface.interfaceAddresses) {
                addresses += address.address to address.networkPrefixLength.toInt()
            }
        } catch (e: SocketException) {
            Log.w(TAG, "Skipping interface ${networkInterface.name}: ${e.message}")
        }
    }
    return interfaceRoutePrefixes(addresses)
}

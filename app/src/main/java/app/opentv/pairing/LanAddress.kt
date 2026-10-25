/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.NetworkInterface

/**
 * The address a phone on the same network can reach this device at.
 *
 * "The first private IPv4 the OS lists" is right on a TV box, which has one interface. It is wrong
 * on a car head unit, where the same enumeration returns five (a Desay SV unit has `eth0`
 * 192.168.1.3, `eth1` 192.168.8.2, `eth0_101`, `eth0_104` and the real `wlan0`): the others are
 * links to the modem and other boards, so a QR code pointing at one of them can never be reached
 * from a phone. Every server that hands out a URL asks here, so they cannot disagree.
 *
 * The interface list still comes from [NetworkInterface] rather than from the Wi-Fi API, because TV
 * boxes are frequently wired and the Wi-Fi-shaped API returns nothing useful for them.
 * [ConnectivityManager] is only asked which interfaces are LAN-like, never for the address itself.
 */
object LanAddress {

    data class Candidate(val interfaceName: String, val address: String)

    fun find(context: Context): String? {
        val candidates = mutableListOf<Candidate>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host.contains(':')) continue // IPv4 only
                    if (PairingHttp.isPrivateAddress(host)) candidates += Candidate(nic.name, host)
                }
            }
        }.onFailure { Log.w(TAG, "Could not enumerate interfaces", it) }

        val lan = mutableSetOf<String>()
        val avoid = mutableSetOf<String>()
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION") // no synchronous replacement; a snapshot is all that is needed
            for (network in manager.allNetworks) {
                val name = manager.getLinkProperties(network)?.interfaceName ?: continue
                val caps = manager.getNetworkCapabilities(network) ?: continue
                when {
                    // Judged by transport, not by name: that same car labels its modem link "eth0_101".
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> avoid += name
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> lan += name
                }
            }
        }.onFailure { Log.w(TAG, "Could not query networks", it) }

        return pick(candidates, lan, avoid)
    }

    /**
     * Chooses among [candidates], best first:
     * 1. an interface the OS reports as Wi-Fi or ethernet ([lan]);
     * 2. `wlan*`;
     * 3. plain `eth<N>`;
     * 4. anything else not obviously internal;
     * 5. interfaces known to be cellular or VPN ([avoid]) and internal ones (usb tethering, p2p,
     *    tunnels, dummy).
     *
     * A 169.254 self-assigned address sorts after all of those, since it means DHCP failed and
     * nothing will be able to reach us. Ties keep the order the OS listed them in.
     */
    fun pick(
        candidates: List<Candidate>,
        lan: Set<String> = emptySet(),
        avoid: Set<String> = emptySet(),
    ): String? = candidates
        .sortedBy { tier(it, lan, avoid) }
        .firstOrNull()
        ?.address

    private fun tier(c: Candidate, lan: Set<String>, avoid: Set<String>): Int {
        val name = c.interfaceName.lowercase()
        val base = when {
            c.interfaceName in avoid || INTERNAL_PREFIXES.any { name.startsWith(it) } -> 4
            c.interfaceName in lan -> 0
            name.startsWith("wlan") -> 1
            ETHERNET.matches(name) -> 2
            else -> 3
        }
        return if (c.address.startsWith("169.254")) base + 10 else base
    }

    private val ETHERNET = Regex("eth\\d+")
    private val INTERNAL_PREFIXES = listOf("rndis", "usb", "dummy", "p2p", "ap", "tun", "ppp", "rmnet", "v4-")
    private const val TAG = "LanAddress"
}

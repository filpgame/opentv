/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.pairing.LanAddress
import app.opentv.pairing.LanAddress.Candidate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which of a device's private addresses goes into the QR code. The car case is the real list
 * from a Desay SV head unit: only `wlan0` is on the network the phone is on.
 */
class LanAddressTest {

    private val car = listOf(
        Candidate("eth0", "192.168.1.3"),
        Candidate("eth1", "192.168.8.2"),
        Candidate("eth0_101", "172.16.101.4"),
        Candidate("eth0_104", "172.16.104.4"),
        Candidate("wlan0", "192.168.0.149"),
    )

    @Test
    fun `a car head unit with five private interfaces picks the wifi one by name alone`() {
        assertThat(LanAddress.pick(car)).isEqualTo("192.168.0.149")
    }

    @Test
    fun `the interfaces the OS reports as wifi or ethernet win over names`() {
        // Same unit, but suppose the OS says eth0 is the LAN link: that is what the OS knows, so believe it.
        assertThat(LanAddress.pick(car, lan = setOf("eth0"), avoid = setOf("eth0_101"))).isEqualTo("192.168.1.3")
    }

    @Test
    fun `an interface labelled eth but reported as cellular is never preferred`() {
        val cellularFirst = listOf(Candidate("eth0_101", "172.16.101.4"), Candidate("wlan0", "192.168.0.149"))

        assertThat(LanAddress.pick(cellularFirst, lan = setOf("wlan0"), avoid = setOf("eth0_101")))
            .isEqualTo("192.168.0.149")
    }

    @Test
    fun `a wired TV box with a single interface still resolves to it`() {
        assertThat(LanAddress.pick(listOf(Candidate("eth0", "192.168.1.20")), lan = setOf("eth0")))
            .isEqualTo("192.168.1.20")
    }

    @Test
    fun `a self-assigned address loses to any real one`() {
        val candidates = listOf(Candidate("wlan0", "169.254.7.7"), Candidate("rndis0", "192.168.42.129"))

        assertThat(LanAddress.pick(candidates)).isEqualTo("192.168.42.129")
    }

    @Test
    fun `tethering and tunnels come last but are still better than nothing`() {
        val onlyInternal = listOf(Candidate("tun0", "10.8.0.2"), Candidate("rndis0", "192.168.42.129"))

        assertThat(LanAddress.pick(onlyInternal)).isEqualTo("10.8.0.2")
        assertThat(LanAddress.pick(onlyInternal + Candidate("wlan0", "192.168.0.10"))).isEqualTo("192.168.0.10")
    }

    @Test
    fun `no candidates means no address`() {
        assertThat(LanAddress.pick(emptyList())).isNull()
    }
}

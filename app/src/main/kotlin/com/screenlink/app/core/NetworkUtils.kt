package com.screenlink.app.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** 找一个能用的局域网 IPv4，给主控端照着填 */
    fun localIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        val addresses = mutableListOf<String>()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val usable = runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
            if (!usable) continue
            val inetAddresses = networkInterface.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val address = inetAddresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { addresses.add(it) }
                }
            }
        }
        return addresses.firstOrNull { it.startsWith("192.") || it.startsWith("10.") }
            ?: addresses.firstOrNull { it.startsWith("172.") }
            ?: addresses.firstOrNull()
    }
}

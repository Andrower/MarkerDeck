package com.andrower.markerdeck

import java.net.Inet4Address
import java.net.NetworkInterface

object MarkerDeckHostNetworkAddress {
    fun findLanIpv4(): String {
        val candidates = mutableListOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                    continue
                }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        candidates += address.hostAddress.orEmpty()
                    }
                }
            }
        }
        return candidates.firstOrNull { address ->
            address.startsWith("10.") || address.startsWith("192.168.") ||
                address.startsWith("172.") || address.startsWith("169.254.")
        } ?: candidates.firstOrNull().orEmpty().ifEmpty { "127.0.0.1" }
    }
}

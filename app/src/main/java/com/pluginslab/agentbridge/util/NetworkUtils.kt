package com.pluginslab.agentbridge.util

import java.net.NetworkInterface

object NetworkUtils {
    fun getLanIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.map { it.hostAddress }
                ?.firstOrNull { it?.startsWith("192.168.") == true || it?.startsWith("10.") == true || it?.startsWith("172.") == true }
        } catch (e: Exception) {
            null
        }
    }
}

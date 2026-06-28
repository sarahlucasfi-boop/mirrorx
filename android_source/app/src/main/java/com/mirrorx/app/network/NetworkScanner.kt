package com.mirrorx.app.network

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP broadcast scanner for auto-discovering MirrorX PC servers on the local network.
 *
 * Protocol:
 *   1. APK sends UDP broadcast to port 9999 with payload "MIRRORX?"
 *   2. PC server (if running) responds with "MIRRORX!IP=<addr>:PORT=<port>"
 *   3. APK parses the response and auto-fills the IP field
 *
 * The scan runs for up to [timeoutMs] milliseconds before giving up.
 */
object NetworkScanner {

    data class ScanResult(
        val ip: String,
        val port: Int = 8080,
        val hostname: String = ""
    )

    /**
     * Scan the local network for MirrorX servers via UDP broadcast.
     *
     * @param port UDP port to broadcast on (default 9999 — the discovery port, NOT the WebSocket port)
     * @param timeoutMs How long to wait for responses
     * @param scope CoroutineScope to run the scan in
     * @return The first server found, or null if none responded in time
     */
    suspend fun scanForServer(
        port: Int = 9999,
        timeoutMs: Long = 4000L
    ): ScanResult? = withContext(Dispatchers.IO) {
        val DISCOVERY_MESSAGE = "MIRRORX?"
        val results = mutableListOf<ScanResult>()

        try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = timeoutMs.toInt()
            socket.bind(null)  // bind to any available port

            try {
                // Send broadcast to 255.255.255.255 on port 9999
                val sendData = DISCOVERY_MESSAGE.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddress, port)
                socket.send(sendPacket)

                // Also try subnet-directed broadcasts for common ranges
                val subnetBroadcasts = listOf(
                    "192.168.1.255",
                    "192.168.0.255",
                    "192.168.100.255",
                    "10.0.2.255"
                )
                for (subnet in subnetBroadcasts) {
                    try {
                        val subnetAddr = InetAddress.getByName(subnet)
                        val subnetPacket = DatagramPacket(sendData, sendData.size, subnetAddr, port)
                        socket.send(subnetPacket)
                    } catch (_: Exception) { /* subnet might not be reachable */ }
                }

                // Listen for responses
                val receiveBuffer = ByteArray(256)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length).trim()

                        if (response.startsWith("MIRRORX!")) {
                            val result = parseResponse(response)
                            if (result != null) {
                                results.add(result)
                                break  // Return first valid response
                            }
                        } else if (response.startsWith("{")) {
                            // v1.8.9: server sends JSON {"ip":"...", "ports":"...", "name":"..."}
                            val result = parseJsonResponse(response)
                            if (result != null) {
                                results.add(result)
                                break
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break  // Timeout — no more responses
                    }
                }
            } finally {
                socket.close()
            }
        } catch (_: Exception) { /* scan failed silently */ }

        results.firstOrNull()
    }

    /**
     * Parse a JSON server discovery response.
     * Format: {"ip":"192.168.1.100", "ports":"8080,9900,7777", "name":"DESKTOP-ABC"}
     */
    private fun parseJsonResponse(response: String): ScanResult? {
        return try {
            val json = org.json.JSONObject(response)
            val ip = json.optString("ip", "")
            val portsStr = json.optString("ports", "8080")
            val port = portsStr.split(",").firstOrNull()?.toIntOrNull() ?: 8080
            if (ip.isNotEmpty() && ip.contains('.')) {
                ScanResult(ip = ip, port = port, hostname = json.optString("name", ""))
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Parse a MIRRORX! response string.
     * Expected format: "MIRRORX!IP=192.168.1.100:PORT=8080" or just "MIRRORX!192.168.1.100"
     */
    private fun parseResponse(response: String): ScanResult? {
        return try {
            val payload = response.removePrefix("MIRRORX!").trim()

            // Try structured format: IP=x.x.x.x:PORT=nnnn or IP=x.x.x.x PORT=nnnn
            val ipMatch = Regex("""IP=([\d.]+)""").find(payload)
            val portMatch = Regex("""PORT=(\d+)""").find(payload)
            val hostMatch = Regex("""HOST=(\S+)""").find(payload)

            if (ipMatch != null) {
                ScanResult(
                    ip = ipMatch.groupValues[1],
                    port = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8080,
                    hostname = hostMatch?.groupValues?.get(1) ?: ""
                )
            } else if (payload.contains('.') && !payload.contains('=')) {
                // Simple format: just an IP address (possibly with :port)
                val parts = payload.split(":")
                ScanResult(
                    ip = parts[0].trim(),
                    port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 8080
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

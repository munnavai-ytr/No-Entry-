package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class ShieldVpnService : VpnService() {

    companion object {
        private const val TAG = "ShieldVpnService"
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"

        const val CLEAN_BROWSING_DNS_PRIMARY = "185.228.168.168"
        const val CLEAN_BROWSING_DNS_SECONDARY = "185.228.169.168"
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX_LENGTH = 32
        const val DNS_PORT = 53
        const val MTU = 1500

        private const val NOTIFICATION_CHANNEL_ID = "shield_vpn_protection"
        private const val NOTIFICATION_ID = 202601

        private val _isVpnRunning = MutableStateFlow(false)
        val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ShieldVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShieldVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            ACTION_START -> {
                startForegroundNotification()
                startVpn()
            }
        }

        // START_STICKY ensures Android automatically restarts the VPN if killed by the OS
        return START_STICKY
    }

    private fun startVpn() {
        if (_isVpnRunning.value) {
            Log.d(TAG, "Shield VPN is already running.")
            return
        }

        try {
            val builder = Builder()
                .setSession("CleanBrowsing Family Shield")
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                // Configure CleanBrowsing Family DNS servers to filter adult content system-wide
                .addDnsServer(CLEAN_BROWSING_DNS_PRIMARY)
                .addDnsServer(CLEAN_BROWSING_DNS_SECONDARY)
                // Route all DNS traffic strictly to the CleanBrowsing servers
                .addRoute(CLEAN_BROWSING_DNS_PRIMARY, 32)
                .addRoute(CLEAN_BROWSING_DNS_SECONDARY, 32)
                .setBlocking(false)

            // Configure pending intent for notification tap
            val configureIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setConfigureIntent(pendingIntent)

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                _isVpnRunning.value = true
                Log.i(TAG, "Shield VPN interface established with CleanBrowsing DNS ($CLEAN_BROWSING_DNS_PRIMARY, $CLEAN_BROWSING_DNS_SECONDARY)")
                startDnsForwarder(vpnInterface!!)
            } else {
                Log.e(TAG, "Failed to establish VPN interface. Permission may not have been granted.")
                _isVpnRunning.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Shield VPN: ${e.message}", e)
            _isVpnRunning.value = false
        }
    }

    /**
     * Real DNS forwarder:
     * Reads IP/UDP packets from the TUN interface, forwards DNS queries over a protected
     * DatagramSocket directly to CleanBrowsing DNS, and writes the filtered DNS responses back to TUN.
     */
    private fun startDnsForwarder(pfd: ParcelFileDescriptor) {
        vpnJob?.cancel()
        vpnJob = serviceScope.launch {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)
            val packetBuffer = ByteArray(MTU)

            val dnsSocket = DatagramSocket()
            protect(dnsSocket)
            dnsSocket.soTimeout = 2500

            val primaryDns = InetAddress.getByName(CLEAN_BROWSING_DNS_PRIMARY)
            val secondaryDns = InetAddress.getByName(CLEAN_BROWSING_DNS_SECONDARY)

            // Mapping to track query source ports and transaction IDs
            val pendingQueries = ConcurrentHashMap<Int, ClientEndpoint>()

            // Receiver coroutine for responses from CleanBrowsing
            val responseJob = launch {
                val recvBuffer = ByteArray(MTU)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                while (isActive) {
                    try {
                        dnsSocket.receive(recvPacket)
                        if (recvPacket.length > 2) {
                            val txId = ((recvBuffer[0].toInt() and 0xFF) shl 8) or (recvBuffer[1].toInt() and 0xFF)
                            val endpoint = pendingQueries.remove(txId)
                            if (endpoint != null) {
                                val responseIpPacket = buildIpUdpPacket(
                                    sourceIp = recvPacket.address.address,
                                    destIp = endpoint.clientIp,
                                    sourcePort = recvPacket.port,
                                    destPort = endpoint.clientPort,
                                    payload = recvBuffer,
                                    payloadLength = recvPacket.length
                                )
                                synchronized(outputStream) {
                                    outputStream.write(responseIpPacket)
                                    outputStream.flush()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Socket timeout or loop iteration
                    }
                }
            }

            // Reader loop from TUN interface
            try {
                while (isActive) {
                    val bytesRead = try {
                        inputStream.read(packetBuffer)
                    } catch (e: Exception) {
                        -1
                    }

                    if (bytesRead > 28) { // IPv4 (min 20) + UDP (8)
                        val version = (packetBuffer[0].toInt() ushr 4) and 0x0F
                        val protocol = packetBuffer[9].toInt() and 0xFF

                        if (version == 4 && protocol == 17) { // IPv4 & UDP
                            val ihl = (packetBuffer[0].toInt() and 0x0F) * 4
                            val srcPort = ((packetBuffer[ihl].toInt() and 0xFF) shl 8) or (packetBuffer[ihl + 1].toInt() and 0xFF)
                            val dstPort = ((packetBuffer[ihl + 2].toInt() and 0xFF) shl 8) or (packetBuffer[ihl + 3].toInt() and 0xFF)

                            if (dstPort == DNS_PORT) {
                                val udpLength = ((packetBuffer[ihl + 4].toInt() and 0xFF) shl 8) or (packetBuffer[ihl + 5].toInt() and 0xFF)
                                val dnsPayloadOffset = ihl + 8
                                val dnsPayloadLength = udpLength - 8

                                if (dnsPayloadLength > 2 && dnsPayloadOffset + dnsPayloadLength <= bytesRead) {
                                    val txId = ((packetBuffer[dnsPayloadOffset].toInt() and 0xFF) shl 8) or
                                            (packetBuffer[dnsPayloadOffset + 1].toInt() and 0xFF)

                                    val clientIp = ByteArray(4) { packetBuffer[12 + it] }
                                    pendingQueries[txId] = ClientEndpoint(clientIp, srcPort)

                                    // Target CleanBrowsing DNS
                                    val targetDns = if ((txId and 1) == 0) primaryDns else secondaryDns
                                    val sendPacket = DatagramPacket(
                                        packetBuffer,
                                        dnsPayloadOffset,
                                        dnsPayloadLength,
                                        targetDns,
                                        DNS_PORT
                                    )
                                    dnsSocket.send(sendPacket)
                                }
                            }
                        }
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "TUN read terminated: ${e.message}")
            } finally {
                responseJob.cancel()
                dnsSocket.close()
            }
        }
    }

    private data class ClientEndpoint(val clientIp: ByteArray, val clientPort: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ClientEndpoint
            if (!clientIp.contentEquals(other.clientIp)) return false
            if (clientPort != other.clientPort) return false
            return true
        }

        override fun hashCode(): Int {
            var result = clientIp.contentHashCode()
            result = 31 * result + clientPort
            return result
        }
    }

    private fun buildIpUdpPacket(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray,
        payloadLength: Int
    ): ByteArray {
        val totalLength = 20 + 8 + payloadLength
        val buffer = ByteBuffer.allocate(totalLength)

        // IPv4 Header
        buffer.put(0x45.toByte()) // Version 4, IHL 5
        buffer.put(0x00.toByte()) // DSCP / ECN
        buffer.putShort(totalLength.toShort()) // Total Length
        buffer.putShort(0.toShort()) // Identification
        buffer.putShort(0x4000.toShort()) // Don't fragment flag
        buffer.put(64.toByte()) // TTL
        buffer.put(17.toByte()) // Protocol: UDP
        buffer.putShort(0.toShort()) // Checksum placeholder
        buffer.put(sourceIp)
        buffer.put(destIp)

        // Calculate IP checksum
        val ipChecksum = computeChecksum(buffer.array(), 0, 20)
        buffer.putShort(10, ipChecksum.toShort())

        // UDP Header
        buffer.position(20)
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putShort((8 + payloadLength).toShort())
        buffer.putShort(0.toShort()) // Optional UDP checksum

        // Payload
        buffer.put(payload, 0, payloadLength)

        return buffer.array()
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val high = (data[i].toInt() and 0xFF) shl 8
            val low = if (i + 1 < offset + length) data[i + 1].toInt() and 0xFF else 0
            sum += (high or low)
            i += 2
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun stopVpn() {
        vpnJob?.cancel()
        vpnJob = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        _isVpnRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Shield VPN stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Shield VPN Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors adult content blocking via CleanBrowsing DNS"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("No Entry: CleanBrowsing Shield Active")
            .setContentText("DNS traffic filtered via CleanBrowsing (185.228.168.168)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}

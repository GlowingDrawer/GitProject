package com.example.cgmdemo

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BluetoothSerialManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    // ⭐ 推荐写法：S 及以上用 BluetoothManager，老版本用 getDefaultAdapter()
    private val adapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter
    } else {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()
    }

    private var socket: BluetoothSocket? = null
    private var job: Job? = null
    private val buffer = StringBuilder()

    fun getPairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun connect(
        device: BluetoothDevice,
        onConnected: () -> Unit,
        onError: (Throwable) -> Unit,
        onJson: (JSONObject) -> Unit
    ) {
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            try {
                val uuid = device.uuids?.firstOrNull()?.uuid
                    ?: UUID.fromString("00001101-0000-1000-8000-00805f9b34fb") // SPP
                val s = device.createRfcommSocketToServiceRecord(uuid)
                adapter?.cancelDiscovery()
                s.connect()
                socket = s

                withContext(Dispatchers.Main) {
                    onConnected()
                }

                val input = s.inputStream
                val buf = ByteArray(1024)
                while (isActive) {
                    val n = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        break
                    }
                    if (n <= 0) break
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    handleIncoming(text, onJson)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }
    }

    private fun handleIncoming(chunk: String, onJson: (JSONObject) -> Unit) {
        buffer.append(chunk)
        while (true) {
            val start = buffer.indexOf('{')
            if (start == -1) {
                buffer.setLength(0)
                return
            }
            val end = buffer.indexOf('}', startIndex = start)
            if (end == -1) {
                // 不完整，保留从 { 开始的部分
                if (start > 0) {
                    buffer.delete(0, start)
                }
                return
            }
            val jsonStr = buffer.substring(start, end + 1)
            buffer.delete(0, end + 1)
            try {
                val obj = JSONObject(jsonStr)
                val nowStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date())
                obj.put("receive_time", nowStr)
                scope.launch(Dispatchers.Main) {
                    onJson(obj)
                }
            } catch (e: JSONException) {
                // 丢弃这段，继续尝试
            }
        }
    }

    fun send(text: String) {
        val out = socket?.outputStream ?: return
        scope.launch(Dispatchers.IO) {
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}

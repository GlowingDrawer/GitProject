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

    // Android 12+ 推荐用 BluetoothManager，旧版用 getDefaultAdapter()
    private val adapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter
    } else {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()
    }

    private var socket: BluetoothSocket? = null
    private var job: Job? = null

    // 用于拼接 JSON 文本
    private val jsonBuffer = StringBuilder()

    fun getPairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    /**
     * @param onRawBytes 每次 read() 收到的数据（原始字节），用于界面显示文本/Hex
     * @param onJson 每解析出一个完整 JSON，就回调一次
     */
    fun connect(
        device: BluetoothDevice,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onError: (Throwable) -> Unit,
        onRawBytes: (ByteArray, Int) -> Unit,
        onJson: (JSONObject) -> Unit
    ) {
        disconnect()
        jsonBuffer.setLength(0)

        job = scope.launch(Dispatchers.IO) {
            var error: Throwable? = null
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
                        error = e
                        break
                    }
                    if (n <= 0) break

                    // 1) 原始字节回调（给接收区显示）
                    val copy = buf.copyOf(n)
                    withContext(Dispatchers.Main) {
                        onRawBytes(copy, n)
                    }

                    // 2) 文本拼接成 JSON 缓冲
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    handleIncomingText(text, onJson)
                }
            } catch (e: Throwable) {
                error = e
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null

                withContext(Dispatchers.Main) {
                    if (error != null) {
                        onError(error!!)
                    }
                    onDisconnected()
                }
            }
        }
    }

    /**
     * 把串口文本流拼接成一个个 JSON: {...}，每解析出一个就回调
     */
    private fun handleIncomingText(chunk: String, onJson: (JSONObject) -> Unit) {
        jsonBuffer.append(chunk)
        while (true) {
            val start = jsonBuffer.indexOf("{")
            if (start == -1) {
                // 没有 '{'，清空缓冲
                jsonBuffer.setLength(0)
                return
            }
            val end = jsonBuffer.indexOf("}", startIndex = start)
            if (end == -1) {
                // 只找到起始 '{'，还没到 '}'，保留从 '{' 开始的部分
                if (start > 0) {
                    jsonBuffer.delete(0, start)
                }
                return
            }
            // 截取完整 JSON
            val jsonStr = jsonBuffer.substring(start, end + 1)
            jsonBuffer.delete(0, end + 1)
            try {
                val obj = JSONObject(jsonStr)
                // 增加接收时间字段
                val nowStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date())
                obj.put("receive_time", nowStr)
                scope.launch(Dispatchers.Main) {
                    onJson(obj)
                }
            } catch (e: JSONException) {
                // 解析失败，丢弃这一段，继续找下一段
            }
        }
    }

    fun send(text: String) {
        sendBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun sendBytes(data: ByteArray) {
        val out = socket?.outputStream ?: return
        scope.launch(Dispatchers.IO) {
            try {
                out.write(data)
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

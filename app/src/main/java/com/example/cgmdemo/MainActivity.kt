package com.example.cgmdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.cgmdemo.databinding.ActivityMainBinding
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.util.ArrayDeque

class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {

    data class SaveConfig(
        var autoSave: Boolean = false,
        var saveIntervalMs: Int = 1000,
        var savePath: String = "./serial_data"
    )

    private var saveConfig = SaveConfig()


    private lateinit var binding: ActivityMainBinding
    private lateinit var serialManager: BluetoothSerialManager
    private lateinit var pageManager: PageManager        // ← 新增
    private val sensorAdapter = SensorAdapter()

    // 数据缓存（最多保留 2000 条）
    private val records = mutableListOf<SensorRecord>()
    private val maxRecords = 2000

    // 简单滑动平均滤波（窗口 = 5）
    private val windowSize = 5
    private val uricBuf = ArrayDeque<Double>()
    private val ascorbicBuf = ArrayDeque<Double>()
    private val glucoseBuf = ArrayDeque<Double>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // MainActivity 本身就是一个 CoroutineScope
        serialManager = BluetoothSerialManager(this, this)

        setupUi()
        updateDeviceList()
    }

    private fun setupUi() {
        // ====== ① 初始化 PageManager ======
        pageManager = PageManager(
            flipper = binding.viewFlipper,
            tabSerial = binding.btnTabSerial,
            tabMonitor = binding.btnTabMonitor,
            tabSettings = binding.btnTabSettings
        )

        // 顶部 Tab 点击事件，交给 PageManager 来切页
        binding.btnTabSerial.setOnClickListener {
            pageManager.showSerial()
        }
        binding.btnTabMonitor.setOnClickListener {
            pageManager.showMonitor()
        }
        binding.btnTabSettings.setOnClickListener {
            pageManager.showSettings()
        }

        // 默认打开监测页（可根据需要改）
        pageManager.showMonitor()

        binding.txtReceive.movementMethod = ScrollingMovementMethod.getInstance()
        binding.recyclerData.layoutManager = LinearLayoutManager(this)
        binding.recyclerData.adapter = sensorAdapter
        initChart()

        // 刷新 / 连接 / 发送 / 命令按钮这些逻辑保持不变
        binding.btnRefreshDevices.setOnClickListener { updateDeviceList() }
        binding.btnConnect.setOnClickListener {
            if (!serialManager.isConnected()) {
                if (ensureBtPermission()) {
                    connectSelectedDevice()
                }
            } else {
                serialManager.disconnect()
                setDisconnectedUi()
            }
        }
        binding.btnSend.setOnClickListener {
            val text = binding.editSend.text.toString()
            if (text.isNotBlank()) {
                serialManager.send(text)
            }
        }
        binding.btnStart.setOnClickListener { sendCommand("START") }
        binding.btnPause.setOnClickListener { sendCommand("PAUSE") }
        binding.btnResume.setOnClickListener { sendCommand("RESUME") }
        binding.btnForcePause.setOnClickListener { sendCommand("ForcePause") }

        // 设置页简单逻辑（可选）
        setupSettingsPage()
    }

    private fun showPage(index: Int) {
        binding.viewFlipper.displayedChild = index
    }

    private fun setupSettingsPage() {
        // 初始化 UI 显示当前配置
        binding.checkAutoSave.isChecked = saveConfig.autoSave
        binding.editSaveInterval.setText(saveConfig.saveIntervalMs.toString())
        binding.editSavePath.setText(saveConfig.savePath)

        binding.btnSaveSettings.setOnClickListener {
            // 从 UI 读取配置
            val intervalText = binding.editSaveInterval.text.toString()
            val interval = intervalText.toIntOrNull() ?: 1000
            saveConfig = saveConfig.copy(
                autoSave = binding.checkAutoSave.isChecked,
                saveIntervalMs = interval,
                savePath = binding.editSavePath.text.toString()
            )
            Toast.makeText(this, "已保存设置：${interval}ms, 路径=${saveConfig.savePath}", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetSettings.setOnClickListener {
            // 恢复默认值
            saveConfig = SaveConfig()
            binding.checkAutoSave.isChecked = saveConfig.autoSave
            binding.editSaveInterval.setText(saveConfig.saveIntervalMs.toString())
            binding.editSavePath.setText(saveConfig.savePath)
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        }
    }




    private fun sendCommand(cmd: String) {
        binding.editSend.setText(cmd)
        serialManager.send("$cmd\n")
    }

    private fun initChart() {
        val chart = binding.chartGlucose
        chart.setNoDataText(getString(R.string.chart_no_data))
        val desc = Description()
        desc.text = getString(R.string.chart_description)
        chart.description = desc
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
    }

    private fun updateChart() {
        val chart = binding.chartGlucose
        if (records.isEmpty()) {
            chart.data = null
            chart.invalidate()
            return
        }
        val entries = records.map { r ->
            Entry(r.seconds.toFloat(), r.glucose.toFloat())
        }
        val dataSet = LineDataSet(entries, getString(R.string.chart_label_glucose))
        dataSet.setDrawCircles(false)
        dataSet.lineWidth = 1.5f
        dataSet.valueTextSize = 0f

        val lineData = LineData(dataSet)
        chart.data = lineData
        chart.invalidate()
    }

    private fun ensureBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val neededPerms = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                neededPerms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                neededPerms.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (neededPerms.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    neededPerms.toTypedArray(),
                    100
                )
                Toast.makeText(
                    this,
                    getString(R.string.grant_bt_permission),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
        }
        return true
    }


    private fun updateDeviceList() {
        if (!ensureBtPermission()) return
        val devices = serialManager.getPairedDevices()
        val names = if (devices.isEmpty()) {
            listOf(getString(R.string.no_paired_bluetooth))
        } else {
            devices.map {
                getString(
                    R.string.device_name_address,
                    it.name,
                    it.address
                )
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevices.adapter = adapter
    }

    private fun connectSelectedDevice() {
        val devices = serialManager.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.no_paired_device),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val index = binding.spinnerDevices.selectedItemPosition
        if (index !in devices.indices) {
            Toast.makeText(
                this,
                getString(R.string.select_valid_device),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val device = devices[index]

        serialManager.connect(
            device,
            onConnected = {
                setConnectedUi(device.name ?: device.address)
            },
            onError = {
                val msg = it.message ?: ""
                Toast.makeText(
                    this,
                    getString(R.string.connect_failed, msg),
                    Toast.LENGTH_SHORT
                ).show()
                setDisconnectedUi()
            },
            onJson = { json ->
                handleJson(json)
            }
        )
    }

    private fun setConnectedUi(name: String) {
        binding.txtStatus.text = getString(R.string.status_connected, name)
        binding.txtStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        )
        binding.btnConnect.text = getString(android.R.string.cancel) // 也可以用你自己的 string
    }

    private fun setDisconnectedUi() {
        binding.txtStatus.text = getString(R.string.status_disconnected)
        binding.txtStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        binding.btnConnect.text = getString(android.R.string.ok) // 或者自定义“连接”
    }

    private fun handleJson(json: JSONObject) {
        // 1. 原始 JSON 显示在接收区日志里（这里是调试日志，不用本地化）
        val text = json.toString()
        val tv = binding.txtReceive
        tv.append("$text\n")
        val layout = tv.layout
        if (layout != null) {
            val scrollAmount = layout.getLineTop(tv.lineCount) - tv.height
            if (scrollAmount > 0) {
                tv.scrollTo(0, scrollAmount)
            } else {
                tv.scrollTo(0, 0)
            }
        }

        // 2. 转成物理量
        val recordRaw = SensorMath.fromJson(json)

        // 3. 滑动平均滤波
        val recordFiltered = applySimpleMovingAverage(recordRaw)

        // 4. 加入缓存，限制最大条数
        records.add(recordFiltered)
        if (records.size > maxRecords) {
            records.removeAt(0)
        }

        // 5. 更新列表 & 图表
        sensorAdapter.submitList(records.toList())
        updateChart()
    }

    private fun applySimpleMovingAverage(r: SensorRecord): SensorRecord {

        fun filter(buf: ArrayDeque<Double>, value: Double): Double {
            buf.addLast(value)
            // 控制缓存长度 = 2 * windowSize
            while (buf.size > windowSize * 2) {
                buf.removeFirst()
            }
            if (buf.size < windowSize) return value

            // 手动取最后 windowSize 个值求平均
            val list = buf.toList()
            val size = list.size
            val fromIndex = (size - windowSize).coerceAtLeast(0)
            val window = list.subList(fromIndex, size)
            return window.average()
        }

        val uricF = filter(uricBuf, r.uric)
        val ascorbicF = filter(ascorbicBuf, r.ascorbic)
        val glucoseF = filter(glucoseBuf, r.glucose)

        return r.copy(
            uric = uricF,
            ascorbic = ascorbicF,
            glucose = glucoseF
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serialManager.disconnect()
        cancel() // 结束协程（CoroutineScope by MainScope()）
    }
}

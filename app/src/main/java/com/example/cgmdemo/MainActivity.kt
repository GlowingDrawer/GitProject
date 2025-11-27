package com.example.cgmdemo

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.cgmdemo.databinding.ActivityMainBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {

    // ---------- 枚举 & 配置 ----------

    // 滤波类型
    private enum class FilterType { NONE, MOVING_AVG, MEDIAN, KALMAN }

    // 当前图表类型
    private enum class ChartType { TIME_GLUCOSE, VOLT_URIC, VOLT_ASCORBIC, VOLT_GLUCOSE }

    private data class KalmanParams(
        var q: Double = 0.01,
        var r: Double = 0.1
    )

    private data class FilterConfig(
        var type: FilterType = FilterType.MOVING_AVG,
        var windowSize: Int = 5,
        var kalman: KalmanParams = KalmanParams()
    )

    // 简单一维卡尔曼滤波器
    private class KalmanFilter(
        private var q: Double,
        private var r: Double
    ) {
        private var x: Double? = null
        private var p: Double = 0.1

        fun update(z: Double): Double {
            if (x == null) {
                x = z
                return z
            }
            val xPred = x!!
            val pPred = p + q
            val k = pPred / (pPred + r)
            val xNew = xPred + k * (z - xPred)
            val pNew = (1 - k) * pPred
            x = xNew
            p = pNew
            return xNew
        }

        fun reset() {
            x = null
            p = 0.1
        }

        fun updateParams(newQ: Double, newR: Double) {
            q = newQ
            r = newR
            reset()
        }
    }

    // ---------- 成员变量 ----------

    private lateinit var binding: ActivityMainBinding
    private lateinit var serialManager: BluetoothSerialManager
    private val sensorAdapter = SensorAdapter()

    // 记录列表（用于表格/导出等）
    private val records = mutableListOf<SensorRecord>()
    private val maxRecords = 2000

    // 图表缓存（四种图共用一块缓存，仿照 Python DataMonitorPage）
    private val maxTimeWindowSec = 300.0
    private val maxVoltPoints = 600
    private val maxCvPoints = 600

    // 时间-葡萄糖 (seconds, glucose)
    private val glucoseTimeData = mutableListOf<Pair<Double, Double>>()

    // 电压-尿酸 / 电压-抗坏血酸 / 电压-葡萄糖
    private val voltUricData = mutableListOf<Pair<Double, Double>>()
    private val voltAscorbicData = mutableListOf<Pair<Double, Double>>()
    private val voltGlucoseData = mutableListOf<Pair<Double, Double>>()

    // 待处理记录队列（后台线程转到 UI）
    private val pendingRecords = mutableListOf<SensorRecord>()
    private var uiUpdateJob: Job? = null

    // 当前图表类型
    private var currentChartType = ChartType.TIME_GLUCOSE

    // 滤波配置
    private val filterConfig = FilterConfig()
    // 滑动窗口 / 中值滤波缓存
    private val filterBuffers = mutableMapOf(
        "uric" to ArrayDeque<Double>(),
        "ascorbic" to ArrayDeque<Double>(),
        "glucose" to ArrayDeque<Double>()
    )
    // 卡尔曼滤波器
    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // 串口显示/发送选项
    private var receiveAsHex = false
    private var showTimestamp = true
    private var autoScroll = true
    private var sendAsHex = false
    private var autoReconnect = false

    private var lastConnectedDevice: BluetoothDevice? = null
    private var userInitiatedDisconnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serialManager = BluetoothSerialManager(this, this)

        setupUi()
        updateDeviceList()
        startUiUpdateLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        serialManager.disconnect()
        uiUpdateJob?.cancel()
        cancel() // CoroutineScope by MainScope()
    }

    // ---------- UI 初始化 ----------

    private fun setupUi() {
        // 接收区 TextView 可滚动
        binding.txtReceive.movementMethod = ScrollingMovementMethod.getInstance()

        // RecyclerView
        binding.recyclerData.layoutManager = LinearLayoutManager(this)
        binding.recyclerData.adapter = sensorAdapter

        // 图表初始化
        initChart(binding.chartGlucose)

        // 串口显示/发送选项
        binding.checkHexReceive.setOnCheckedChangeListener { _, isChecked ->
            receiveAsHex = isChecked
        }
        binding.checkTimestamp.setOnCheckedChangeListener { _, isChecked ->
            showTimestamp = isChecked
        }
        binding.checkAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            autoScroll = isChecked
        }
        binding.checkHexSend.setOnCheckedChangeListener { _, isChecked ->
            sendAsHex = isChecked
        }
        binding.checkAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            autoReconnect = isChecked
        }

        binding.btnClearReceive.setOnClickListener {
            binding.txtReceive.text = ""
        }

        // 蓝牙：刷新设备列表
        binding.btnRefreshDevices.setOnClickListener {
            updateDeviceList()
        }

        // 连接 / 断开
        binding.btnConnect.setOnClickListener {
            if (!serialManager.isConnected()) {
                if (ensureBtPermission()) {
                    connectSelectedDevice()
                }
            } else {
                userInitiatedDisconnect = true
                serialManager.disconnect()
                setDisconnectedUi()
            }
        }

        // 普通发送
        binding.btnSend.setOnClickListener {
            val text = binding.editSend.text.toString()
            if (text.isNotBlank()) {
                if (sendAsHex) {
                    val bytes = parseHexString(text)
                    if (bytes == null) {
                        Toast.makeText(this, "Hex格式错误，请输入如：01 0A FF", Toast.LENGTH_SHORT).show()
                    } else {
                        serialManager.sendBytes(bytes)
                    }
                } else {
                    serialManager.send("$text\n")
                }
            }
        }

        // 快捷命令（总是按文本发送）
        binding.btnStart.setOnClickListener { sendQuickCommand("START") }
        binding.btnPause.setOnClickListener { sendQuickCommand("PAUSE") }
        binding.btnResume.setOnClickListener { sendQuickCommand("RESUME") }
        binding.btnForcePause.setOnClickListener { sendQuickCommand("ForcePause") }

        // 滤波 UI
        initFilterUi()

        // 图表切换
        initChartSwitchUi()
    }

    private fun sendQuickCommand(cmd: String) {
        binding.editSend.setText(cmd)
        serialManager.send("$cmd\n")
    }

    private fun initChart(chart: LineChart) {
        chart.setNoDataText("等待数据...")
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        val desc = Description()
        desc.text = "时间-葡萄糖（滤波后）"
        chart.description = desc
        chart.axisRight.isEnabled = false
    }

    // ---------- 图表切换按钮 ----------

    private fun initChartSwitchUi() {
        binding.btnTimeGlucoseChart.setOnClickListener {
            currentChartType = ChartType.TIME_GLUCOSE
            updateChart()
        }
        binding.btnVoltUricChart.setOnClickListener {
            currentChartType = ChartType.VOLT_URIC
            updateChart()
        }
        binding.btnVoltAscorbicChart.setOnClickListener {
            currentChartType = ChartType.VOLT_ASCORBIC
            updateChart()
        }
        binding.btnVoltGlucoseChart.setOnClickListener {
            currentChartType = ChartType.VOLT_GLUCOSE
            updateChart()
        }
        binding.btnClearChart.setOnClickListener {
            glucoseTimeData.clear()
            voltUricData.clear()
            voltAscorbicData.clear()
            voltGlucoseData.clear()
            updateChart()
        }
    }

    // ---------- 滤波 UI ----------

    private fun initFilterUi() {
        // 下拉选项名字直接用中文，避免额外 string 资源依赖
        val filterTypeNames = listOf("无滤波", "滑动平均", "中值滤波", "卡尔曼滤波")
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            filterTypeNames
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFilterType.adapter = spinnerAdapter

        // 默认选“滑动平均”
        binding.spinnerFilterType.setSelection(filterTypeNames.indexOf("滑动平均"))

        binding.spinnerFilterType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val name = filterTypeNames[position]
                    val newType = when (name) {
                        "无滤波" -> FilterType.NONE
                        "滑动平均" -> FilterType.MOVING_AVG
                        "中值滤波" -> FilterType.MEDIAN
                        "卡尔曼滤波" -> FilterType.KALMAN
                        else -> FilterType.NONE
                    }
                    if (newType != filterConfig.type) {
                        filterConfig.type = newType
                        onFilterTypeChanged()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // 窗口大小
        binding.editWindowSize.setText(filterConfig.windowSize.toString())
        binding.editWindowSize.doOnTextChanged { text, _, _, _ ->
            val v = text?.toString()?.toIntOrNull()
            if (v != null && v >= 3) {
                // 强制奇数
                val odd = if (v % 2 == 0) v + 1 else v
                filterConfig.windowSize = odd
                if (binding.editWindowSize.text.toString() != odd.toString()) {
                    binding.editWindowSize.setText(odd.toString())
                    binding.editWindowSize.setSelection(binding.editWindowSize.text.length)
                }
                updateFilterStatusLabel()
            }
        }

        // 卡尔曼 Q / R
        binding.editKalmanQ.setText(filterConfig.kalman.q.toString())
        binding.editKalmanR.setText(filterConfig.kalman.r.toString())

        binding.editKalmanQ.doOnTextChanged { text, _, _, _ ->
            val q = text?.toString()?.toDoubleOrNull()
            if (q != null && q > 0) {
                filterConfig.kalman.q = q
                kalmanFilters.values.forEach { it.updateParams(q, filterConfig.kalman.r) }
                updateFilterStatusLabel()
            }
        }

        binding.editKalmanR.doOnTextChanged { text, _, _, _ ->
            val r = text?.toString()?.toDoubleOrNull()
            if (r != null && r > 0) {
                filterConfig.kalman.r = r
                kalmanFilters.values.forEach { it.updateParams(filterConfig.kalman.q, r) }
                updateFilterStatusLabel()
            }
        }

        onFilterTypeChanged()
    }

    private fun onFilterTypeChanged() {
        val isKalman = (filterConfig.type == FilterType.KALMAN)
        binding.editWindowSize.isEnabled = !isKalman
        binding.layoutKalmanParams.visibility =
            if (isKalman) android.view.View.VISIBLE else android.view.View.GONE

        if (!isKalman) {
            kalmanFilters.values.forEach { it.reset() }
        }

        updateFilterStatusLabel()
    }

    private fun updateFilterStatusLabel() {
        val typeName = when (filterConfig.type) {
            FilterType.NONE -> "无滤波"
            FilterType.MOVING_AVG -> "滑动平均"
            FilterType.MEDIAN -> "中值滤波"
            FilterType.KALMAN -> "卡尔曼滤波"
        }
        val text = if (filterConfig.type == FilterType.KALMAN) {
            "当前滤波：$typeName (Q=${filterConfig.kalman.q}, R=${filterConfig.kalman.r})"
        } else {
            "当前滤波：$typeName (窗口=${filterConfig.windowSize})"
        }
        binding.txtFilterStatus.text = text
    }

    // 对单个指标应用当前滤波算法
    private fun applyFilter(indicatorName: String, value: Double): Double {
        return when (filterConfig.type) {
            FilterType.NONE -> value
            FilterType.KALMAN -> {
                val kf = kalmanFilters.getOrPut(indicatorName) {
                    KalmanFilter(filterConfig.kalman.q, filterConfig.kalman.r)
                }
                kf.update(value)
            }
            FilterType.MOVING_AVG, FilterType.MEDIAN -> {
                val buf = filterBuffers.getOrPut(indicatorName) { ArrayDeque() }
                buf.addLast(value)
                val maxBufSize = filterConfig.windowSize * 2
                while (buf.size > maxBufSize) {
                    buf.removeFirst()
                }
                val list = buf.toList()
                if (list.isEmpty()) return value
                val window = minOf(filterConfig.windowSize, list.size)
                val fromIndex = list.size - window
                val sub = list.subList(fromIndex, list.size)
                if (filterConfig.type == FilterType.MOVING_AVG) {
                    sub.average()
                } else {
                    val sorted = sub.sorted()
                    sorted[sorted.size / 2]
                }
            }
        }
    }

    private fun applyFilters(r: SensorRecord): SensorRecord {
        val uricF = applyFilter("uric", r.uric)
        val ascorbicF = applyFilter("ascorbic", r.ascorbic)
        val glucoseF = applyFilter("glucose", r.glucose)
        // 电压暂不滤波
        return r.copy(
            uric = uricF,
            ascorbic = ascorbicF,
            glucose = glucoseF
        )
    }

    // ---------- UI 定时刷新逻辑 ----------

    private fun startUiUpdateLoop() {
        uiUpdateJob?.cancel()
        uiUpdateJob = launch {
            while (true) {
                delay(50)
                flushPendingRecords()
            }
        }
    }

    private fun flushPendingRecords() {
        val batch: List<SensorRecord>
        synchronized(pendingRecords) {
            if (pendingRecords.isEmpty()) return
            val maxPerTick = 50
            val count = minOf(maxPerTick, pendingRecords.size)
            batch = pendingRecords.subList(0, count).toList()
            pendingRecords.subList(0, count).clear()
        }

        // 更新记录 + 图表缓存
        for (rec in batch) {
            records.add(rec)
            if (records.size > maxRecords) {
                records.removeAt(0)
            }
            updateChartCaches(rec)
        }

        // 更新表格
        sensorAdapter.submitList(records.toList())

        // 刷新当前图表
        updateChart()
    }

    // 将单条记录加入图表缓存
    private fun updateChartCaches(rec: SensorRecord) {
        val t = rec.seconds
        val uric = rec.uric
        val ascorbic = rec.ascorbic
        val glucose = rec.glucose
        val volt = rec.voltage

        glucoseTimeData.add(t to glucose)
        voltUricData.add(volt to uric)
        voltAscorbicData.add(volt to ascorbic)
        voltGlucoseData.add(volt to glucose)

        // 时间窗口：只保留最近 maxTimeWindowSec 秒
        val cutoff = t - maxTimeWindowSec
        while (glucoseTimeData.isNotEmpty() && glucoseTimeData.first().first < cutoff) {
            glucoseTimeData.removeAt(0)
        }

        // 电压相关：只保留最近 maxVoltPoints 个点
        fun <T> trim(list: MutableList<T>) {
            while (list.size > maxVoltPoints) {
                list.removeAt(0)
            }
        }
        trim(voltUricData)
        trim(voltAscorbicData)
        trim(voltGlucoseData)
    }

    // ---------- 蓝牙权限 & 连接 ----------

    private fun ensureBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
                Toast.makeText(this, "请先授予蓝牙相关权限", Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    private fun updateDeviceList() {
        if (!ensureBtPermission()) return
        val devices = serialManager.getPairedDevices()
        val names = if (devices.isEmpty()) {
            listOf("（无已配对蓝牙设备）")
        } else {
            devices.map { "${it.name} (${it.address})" }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevices.adapter = adapter
    }

    private fun connectSelectedDevice() {
        val devices = serialManager.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "没有已配对的蓝牙设备", Toast.LENGTH_SHORT).show()
            return
        }
        val index = binding.spinnerDevices.selectedItemPosition
        if (index !in devices.indices) {
            Toast.makeText(this, "请选择有效设备", Toast.LENGTH_SHORT).show()
            return
        }
        connectToDevice(devices[index])
    }

    private fun connectToDevice(device: BluetoothDevice) {
        userInitiatedDisconnect = false
        lastConnectedDevice = device

        serialManager.connect(
            device,
            onConnected = {
                setConnectedUi(device.name ?: device.address)
            },
            onDisconnected = {
                setDisconnectedUi()
                if (!userInitiatedDisconnect && autoReconnect) {
                    scheduleReconnect()
                }
            },
            onError = {
                val msg = it.message ?: ""
                Toast.makeText(this, "连接失败：$msg", Toast.LENGTH_SHORT).show()
                if (!userInitiatedDisconnect && autoReconnect) {
                    scheduleReconnect()
                }
            },
            onRawBytes = { data, len ->
                handleRawBytes(data, len)
            },
            onJson = { json ->
                handleJson(json)
            }
        )
    }

    private fun scheduleReconnect() {
        val device = lastConnectedDevice ?: return
        launch {
            delay(3000)
            if (!serialManager.isConnected()) {
                connectToDevice(device)
            }
        }
    }

    private fun setConnectedUi(name: String) {
        binding.txtStatus.text = "状态：已连接 ($name)"
        binding.txtStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        )
        binding.btnConnect.text = "断开"
    }

    private fun setDisconnectedUi() {
        binding.txtStatus.text = "状态：未连接"
        binding.txtStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        binding.btnConnect.text = "连接"
    }

    // ---------- 串口原始数据显示 ----------

    private fun handleRawBytes(data: ByteArray, length: Int) {
        val slice = data.copyOf(length)
        val baseText = if (receiveAsHex) {
            slice.joinToString(" ") { b -> String.format("%02X", b) }
        } else {
            slice.toString(Charsets.UTF_8)
        }
        val finalText = if (showTimestamp) {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            "[$ts] $baseText"
        } else {
            baseText
        }
        appendReceiveText(finalText)
    }

    private fun appendReceiveText(text: String) {
        val tv = binding.txtReceive
        tv.append(text)
        tv.append("\n")
        if (autoScroll) {
            val layout = tv.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(tv.lineCount) - tv.height
                if (scrollAmount > 0) {
                    tv.scrollTo(0, scrollAmount)
                } else {
                    tv.scrollTo(0, 0)
                }
            }
        }
    }

    // ---------- JSON → 记录 → pending 队列 ----------

    private fun handleJson(json: JSONObject) {
        val recordRaw = SensorMath.fromJson(json)
        val recordFiltered = applyFilters(recordRaw)
        synchronized(pendingRecords) {
            pendingRecords.add(recordFiltered)
        }
    }

    // ---------- 工具函数：解析 HEX 字符串 ----------

    private fun parseHexString(hex: String): ByteArray? {
        val clean = hex.replace("\\s".toRegex(), "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                val start = i * 2
                val byteStr = clean.substring(start, start + 2)
                byteStr.toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    // ---------- 图表绘制总入口 ----------

    private fun updateChart() {
        when (currentChartType) {
            ChartType.TIME_GLUCOSE -> drawTimeGlucoseChart()
            ChartType.VOLT_URIC -> drawVoltUricChart()
            ChartType.VOLT_ASCORBIC -> drawVoltAscorbicChart()
            ChartType.VOLT_GLUCOSE -> drawVoltGlucoseChart()
        }
    }

    // 时间-葡萄糖
    private fun drawTimeGlucoseChart() {
        val chart = binding.chartGlucose
        val points = glucoseTimeData

        if (points.size < 2) {
            chart.data = null
            val desc = Description()
            desc.text = "时间-葡萄糖"
            chart.description = desc
            chart.invalidate()
            return
        }

        val entries = points.map { (t, g) -> Entry(t.toFloat(), g.toFloat()) }
        val ds = LineDataSet(entries, "葡萄糖(mA)").apply {
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawValues(false)
            valueTextSize = 0f
        }
        chart.data = LineData(ds)

        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val xMin = xs.minOrNull() ?: 0.0
        val xMax = xs.maxOrNull() ?: 0.0
        val yMin = ys.minOrNull() ?: 0.0
        val yMax = ys.maxOrNull() ?: 0.0
        val yMargin = max(1.0, (yMax - yMin) * 0.1)

        chart.xAxis.axisMinimum = xMin.toFloat()
        chart.xAxis.axisMaximum = xMax.toFloat()
        chart.axisLeft.axisMinimum = (yMin - yMargin).toFloat()
        chart.axisLeft.axisMaximum = (yMax + yMargin).toFloat()
        chart.axisRight.isEnabled = false

        val desc = Description()
        desc.text = "时间-葡萄糖"
        chart.description = desc
        chart.invalidate()
    }

    // 电压-尿酸
    private fun drawVoltUricChart() {
        val chart = binding.chartGlucose
        val points = voltUricData

        if (points.size < 2) {
            chart.data = null
            val desc = Description()
            desc.text = "电压-尿酸"
            chart.description = desc
            chart.invalidate()
            return
        }

        val entries = points.map { (v, u) -> Entry(v.toFloat(), u.toFloat()) }
        val ds = LineDataSet(entries, "尿酸(mA)").apply {
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawValues(false)
            valueTextSize = 0f
        }
        chart.data = LineData(ds)

        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val xMin = xs.minOrNull() ?: 0.0
        val xMax = xs.maxOrNull() ?: 0.0
        val yMin = ys.minOrNull() ?: 0.0
        val yMax = ys.maxOrNull() ?: 0.0
        val yMargin = max(1.0, (yMax - yMin) * 0.1)

        chart.xAxis.axisMinimum = (xMin - 0.1).toFloat()
        chart.xAxis.axisMaximum = (xMax + 0.1).toFloat()
        chart.axisLeft.axisMinimum = (yMin - yMargin).toFloat()
        chart.axisLeft.axisMaximum = (yMax + yMargin).toFloat()
        chart.axisRight.isEnabled = false

        val desc = Description()
        desc.text = "电压-尿酸"
        chart.description = desc
        chart.invalidate()
    }

    // 电压-抗坏血酸
    private fun drawVoltAscorbicChart() {
        val chart = binding.chartGlucose
        val points = voltAscorbicData

        if (points.size < 2) {
            chart.data = null
            val desc = Description()
            desc.text = "电压-抗坏血酸"
            chart.description = desc
            chart.invalidate()
            return
        }

        val entries = points.map { (v, a) -> Entry(v.toFloat(), a.toFloat()) }
        val ds = LineDataSet(entries, "抗坏血酸(mA)").apply {
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawValues(false)
            valueTextSize = 0f
        }
        chart.data = LineData(ds)

        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val xMin = xs.minOrNull() ?: 0.0
        val xMax = xs.maxOrNull() ?: 0.0
        val yMin = ys.minOrNull() ?: 0.0
        val yMax = ys.maxOrNull() ?: 0.0
        val yMargin = max(1.0, (yMax - yMin) * 0.1)

        chart.xAxis.axisMinimum = (xMin - 0.1).toFloat()
        chart.xAxis.axisMaximum = (xMax + 0.1).toFloat()
        chart.axisLeft.axisMinimum = (yMin - yMargin).toFloat()
        chart.axisLeft.axisMaximum = (yMax + yMargin).toFloat()
        chart.axisRight.isEnabled = false

        val desc = Description()
        desc.text = "电压-抗坏血酸"
        chart.description = desc
        chart.invalidate()
    }

    // 电压-葡萄糖（CV，多圈）
    private fun drawVoltGlucoseChart() {
        val chart = binding.chartGlucose
        if (voltGlucoseData.size < 2) {
            chart.data = null
            val desc = Description()
            desc.text = "电压-葡萄糖(CV)"
            chart.description = desc
            chart.invalidate()
            return
        }

        // 只取最近 maxCvPoints
        val pointsAll = voltGlucoseData
        val points = if (pointsAll.size > maxCvPoints) {
            pointsAll.subList(pointsAll.size - maxCvPoints, pointsAll.size)
        } else {
            pointsAll
        }

        val cycles = buildCvCycles(points, dvThreshold = 0.002, minPoints = 10)

        if (cycles.isEmpty()) {
            // 分不出圈：画一条线
            val entries = points.map { (v, g) -> Entry(v.toFloat(), g.toFloat()) }
            val ds = LineDataSet(entries, "葡萄糖(mA)").apply {
                setDrawCircles(false)
                lineWidth = 1.5f
                setDrawValues(false)
                valueTextSize = 0f
            }
            chart.data = LineData(ds)

            val volts = points.map { it.first }
            val glucs = points.map { it.second }
            val maxVolt = volts.maxOrNull() ?: 0.0
            val minVolt = volts.minOrNull() ?: 0.0
            val maxGlucose = glucs.maxOrNull() ?: 0.0
            val minGlucose = glucs.minOrNull() ?: 0.0
            val margin = max(1.0, (maxGlucose - minGlucose) * 0.1)

            chart.xAxis.axisMinimum = (minVolt - 0.1).toFloat()
            chart.xAxis.axisMaximum = (maxVolt + 0.1).toFloat()
            chart.axisLeft.axisMinimum = (minGlucose - margin).toFloat()
            chart.axisLeft.axisMaximum = (maxGlucose + margin).toFloat()
            chart.axisRight.isEnabled = false

            val desc = Description()
            desc.text = "电压-葡萄糖(CV)"
            chart.description = desc
            chart.invalidate()
            return
        }

        // 多圈：每圈一条线，颜色轮换
        val colors = intArrayOf(
            Color.RED,
            Color.BLUE,
            Color.GREEN,
            Color.rgb(255, 165, 0),
            Color.MAGENTA,
            Color.CYAN
        )

        val dataSets = mutableListOf<ILineDataSet>()
        val allVolt = mutableListOf<Double>()
        val allGlucose = mutableListOf<Double>()

        cycles.forEachIndexed { index, cycle ->
            if (cycle.size < 2) return@forEachIndexed
            val entries = cycle.map { (v, g) -> Entry(v.toFloat(), g.toFloat()) }
            val ds = LineDataSet(entries, "第 ${index + 1} 圈").apply {
                setDrawCircles(false)
                lineWidth = 1.5f
                setDrawValues(false)
                valueTextSize = 0f
                color = colors[index % colors.size]
            }
            dataSets.add(ds)
            allVolt.addAll(cycle.map { it.first })
            allGlucose.addAll(cycle.map { it.second })
        }

        if (dataSets.isEmpty()) {
            chart.data = null
            chart.invalidate()
            return
        }

        chart.data = LineData(dataSets)

        val maxVolt = allVolt.maxOrNull() ?: 0.0
        val minVolt = allVolt.minOrNull() ?: 0.0
        val maxGlucose = allGlucose.maxOrNull() ?: 0.0
        val minGlucose = allGlucose.minOrNull() ?: 0.0
        val margin = max(1.0, (maxGlucose - minGlucose) * 0.1)

        chart.xAxis.axisMinimum = (minVolt - 0.1).toFloat()
        chart.xAxis.axisMaximum = (maxVolt + 0.1).toFloat()
        chart.axisLeft.axisMinimum = (minGlucose - margin).toFloat()
        chart.axisLeft.axisMaximum = (maxGlucose + margin).toFloat()
        chart.axisRight.isEnabled = false

        val desc = Description()
        desc.text = "电压-葡萄糖(CV)"
        chart.description = desc
        chart.invalidate()
    }

    // CV 分圈算法：按电压方向变化拆圈（和 Python 思路一致）
    private fun buildCvCycles(
        data: List<Pair<Double, Double>>,
        dvThreshold: Double,
        minPoints: Int
    ): List<List<Pair<Double, Double>>> {
        if (data.size < 2) return emptyList()

        val cycles = mutableListOf<List<Pair<Double, Double>>>()
        var currentCycle = mutableListOf<Pair<Double, Double>>()

        var (prevV, prevG) = data[0]
        currentCycle.add(prevV to prevG)
        var currentDir: Int? = null // +1 上扫, -1 下扫

        for (i in 1 until data.size) {
            val (v, g) = data[i]
            val dv = v - prevV

            // 电压变化太小 → 抖动，继续加入当前圈
            if (abs(dv) < dvThreshold) {
                currentCycle.add(v to g)
                prevV = v
                prevG = g
                continue
            }

            val stepDir = if (dv > 0) 1 else -1

            if (currentDir == null) {
                currentDir = stepDir
                currentCycle.add(v to g)
            } else {
                if (stepDir == currentDir) {
                    currentCycle.add(v to g)
                } else {
                    // 方向发生反转：如果点足够，截断成一圈
                    if (currentCycle.size >= minPoints) {
                        cycles.add(currentCycle)
                    }
                    // 新圈从转折点开始，保证连续
                    currentCycle = mutableListOf(prevV to prevG, v to g)
                    currentDir = stepDir
                }
            }

            prevV = v
            prevG = g
        }

        if (currentCycle.size >= minPoints) {
            cycles.add(currentCycle)
        }

        return cycles
    }
}

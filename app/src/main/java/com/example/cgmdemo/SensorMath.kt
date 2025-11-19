package com.example.cgmdemo

import org.json.JSONObject

object SensorMath {

    private const val ADC_VALUE_PER_VOLT = 1240.9091
    private const val REF_VOLT = 1.5
    private const val TIME_GAIN = 1000.0

    // 增益（和 Python 里一致）
    private const val URIC_GAIN = 20400.0 / 1_000_000.0     // uA
    private const val ASCORBIC_GAIN = 4700.0 / 1_000_000.0  // uA
    private const val GLUCOSE_GAIN = 200.0 / 1000.0         // mA

    private fun adcToCurrent(adcValue: Double, gain: Double): Double {
        val voltage = (adcValue - REF_VOLT * ADC_VALUE_PER_VOLT) / ADC_VALUE_PER_VOLT
        return voltage / gain
    }

    fun fromJson(obj: JSONObject): SensorRecord {
        val secondsRaw = obj.optDouble("Seconds", 0.0)
        val uricAdc = obj.optDouble("Uric", 0.0)
        val ascorbicAdc = obj.optDouble("Ascorbic", 0.0)
        val glucoseAdc = obj.optDouble("Glucose", 0.0)
        val voltAdc = obj.optDouble("Volt", 0.0)
        val receiveTime = obj.optString("receive_time", "")

        val seconds = secondsRaw / TIME_GAIN
        val uric = adcToCurrent(uricAdc, URIC_GAIN)
        val ascorbic = adcToCurrent(ascorbicAdc, ASCORBIC_GAIN)
        val glucose = adcToCurrent(glucoseAdc, GLUCOSE_GAIN)
        val voltage = REF_VOLT - voltAdc / ADC_VALUE_PER_VOLT

        return SensorRecord(
            seconds = seconds,
            uric = uric,
            ascorbic = ascorbic,
            glucose = glucose,
            voltage = voltage,
            receiveTime = receiveTime
        )
    }
}

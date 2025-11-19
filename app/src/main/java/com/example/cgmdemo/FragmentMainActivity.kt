package com.example.cgmdemo

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class FragmentMainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_SERIAL = "SerialFragment"
        private const val TAG_MONITOR = "MonitorFragment"
        private const val TAG_SETTINGS = "SettingsFragment"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_main)

        val btnSerial = findViewById<Button>(R.id.btnTabSerialFrag)
        val btnMonitor = findViewById<Button>(R.id.btnTabMonitorFrag)
        val btnSettings = findViewById<Button>(R.id.btnTabSettingsFrag)

        btnSerial.setOnClickListener { showFragment(TAG_SERIAL) }
        btnMonitor.setOnClickListener { showFragment(TAG_MONITOR) }
        btnSettings.setOnClickListener { showFragment(TAG_SETTINGS) }

        // 默认显示 Monitor 页
        if (savedInstanceState == null) {
            showFragment(TAG_MONITOR)
        }
    }

    private fun showFragment(tag: String) {
        val fragment = supportFragmentManager.findFragmentByTag(tag) ?: when (tag) {
            TAG_SERIAL -> SerialFragment()
            TAG_MONITOR -> MonitorFragment()
            TAG_SETTINGS -> SettingsFragment()
            else -> MonitorFragment()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }
}

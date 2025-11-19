package com.example.cgmdemo

import android.view.View
import android.widget.ViewFlipper

/**
 * 类似 Qt 里的 PageManager / StackedWidget 封装类
 * 统一管理当前页面索引 + 切页逻辑
 */
class PageManager(
    private val flipper: ViewFlipper,
    private val tabSerial: View,
    private val tabMonitor: View,
    private val tabSettings: View
) {

    companion object {
        const val PAGE_SERIAL = 0
        const val PAGE_MONITOR = 1
        const val PAGE_SETTINGS = 2
    }

    var currentPage: Int = PAGE_MONITOR
        private set

    init {
        // 初始高亮监测页
        setPageInternal(PAGE_MONITOR)
    }

    fun showSerial() {
        setPageInternal(PAGE_SERIAL)
    }

    fun showMonitor() {
        setPageInternal(PAGE_MONITOR)
    }

    fun showSettings() {
        setPageInternal(PAGE_SETTINGS)
    }

    private fun setPageInternal(page: Int) {
        if (page == currentPage) return

        flipper.displayedChild = page
        currentPage = page

        // 简单的“选中状态”标记，方便你以后做 selector
        tabSerial.isSelected = (page == PAGE_SERIAL)
        tabMonitor.isSelected = (page == PAGE_MONITOR)
        tabSettings.isSelected = (page == PAGE_SETTINGS)
    }
}

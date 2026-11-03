package com.netwatch.app

import android.app.Application
import com.netwatch.app.data.Prefs
import com.netwatch.app.monitor.Monitor

class NetWatchApp : Application() {
    lateinit var monitor: Monitor
        private set

    override fun onCreate() {
        super.onCreate()
        monitor = Monitor(this, Prefs(this))
    }
}

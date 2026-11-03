package com.networkowl.app

import android.app.Application
import com.networkowl.app.data.Prefs
import com.networkowl.app.monitor.Monitor

class NetworkOwlApp : Application() {
    lateinit var monitor: Monitor
        private set

    override fun onCreate() {
        super.onCreate()
        monitor = Monitor(this, Prefs(this))
    }
}

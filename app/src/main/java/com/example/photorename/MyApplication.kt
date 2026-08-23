package com.example.photorename

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val crashFile = File(filesDir, "last_crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText(sw.toString())
            } catch (_: Exception) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }
}

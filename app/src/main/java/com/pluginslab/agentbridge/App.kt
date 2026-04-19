package com.pluginslab.agentbridge

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "Download")
                dir.mkdirs()
                val file = File(dir, "agentbridge-crash.txt")
                PrintWriter(file.outputStream()).use { pw ->
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    pw.println("=== AgentBridge crash @ $ts ===")
                    pw.println("Thread: ${thread.name}")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
                Log.e("AgentBridge", "Wrote crash to ${file.absolutePath}", throwable)
            } catch (e: Throwable) {
                Log.e("AgentBridge", "Crash handler itself failed", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

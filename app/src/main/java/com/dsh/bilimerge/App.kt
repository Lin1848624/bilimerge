package com.dsh.bilimerge

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.dsh.bilimerge.core.merge.MergeManager
import com.dsh.bilimerge.data.Prefs

class App : Application() {

    lateinit var prefs: Prefs
        private set

    lateinit var mergeManager: MergeManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        mergeManager = MergeManager(this)

        // 把 ffmpeg 日志压到 error 级：既拿得到失败原因，又免去每条日志跨 JNI 分配字符串的开销
        runCatching { FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR) }
        // 会话历史只留最近几条，长批量合并时不会线性吃内存
        runCatching { FFmpegKitConfig.setSessionHistorySize(3) }
    }

    override fun onTerminate() {
        runCatching { mergeManager.shutdown() }
        super.onTerminate()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

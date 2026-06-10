package com.shipradar.comms.service

import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 高实时性数据接口调度器 —— 数据服务承接进入数据时使用。
 *
 * 雷达回波/状态/目标是连续高速流(IMAGE 通道尤甚)。若摄取跑在 [kotlinx.coroutines.Dispatchers.Default]
 * 或 UI 线程上,会被其它工作或合成阻塞,造成丢帧/时延抖动。这里用**专用线程池 + 提升线程优先级**,
 * 让 socket 读取与解析以低时延、确定性的节奏运行,与 UI/默认池隔离 —— 满足"高实时性"。
 *
 * 配合总线侧 DROP_OLDEST(过期回波可丢)与前台服务(OS 不降频),共同保证实时接收。
 */
object RealtimeIngest {

    /** 创建一个提升优先级的专用调度器(默认 3 线程,够并行 ECHO/STATUS/TARGET + 61162 收集器)。 */
    fun dispatcher(threads: Int = 3): CoroutineDispatcher {
        val counter = AtomicInteger(1)
        val executor = Executors.newFixedThreadPool(threads) { runnable ->
            Thread {
                // URGENT_DISPLAY(-8)高于默认/后台优先级,确保实时摄取不被普通工作饿死。
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                runnable.run()
            }.apply {
                name = "radar-rt-ingest-${counter.getAndIncrement()}"
                isDaemon = true
            }
        }
        return executor.asCoroutineDispatcher()
    }
}

package com.shipradar.app.theme

import android.util.Log
import kotlin.math.roundToInt

/**
 * 亮度(brilliance)→ **硬件 I/O 口 PWM 背光**。
 *
 * 软件把 0..1 的亮度映射成 PWM 占空比,写到硬件 PWM(船载显控机型的背光控制脚)。无此硬件(如模拟器)
 * 则仅记日志。船载机型把 [SysfsBacklightPwm.pwmPath] 指向实际 Linux PWM sysfs 节点即可。
 */
interface BacklightPwm {
    /** 设置背光亮度 [duty01](0..1)→ 写硬件 PWM 占空比。 */
    fun setBrilliance(duty01: Float)
}

/** PWM 满刻度(8-bit;按硬件可改 1023 等)。 */
const val PWM_FULL_SCALE = 255

/** 亮度(0..1)→ PWM 占空比整数(留最低背光下限 [minDuty],避免全黑看不见)。 */
fun brillianceToPwmDuty(brilliance: Float, minDuty: Int = 8, fullScale: Int = PWM_FULL_SCALE): Int {
    val b = brilliance.coerceIn(0f, 1f)
    return (minDuty + b * (fullScale - minDuty)).roundToInt()
}

/**
 * 默认实现:写 Linux **PWM sysfs**(`/sys/class/pwm/.../duty_cycle`,纳秒)。无权限/无硬件 → 记日志。
 * 真机:确保 PWM 已 export/enable,并把 [pwmPath]/[periodNs] 配成实际背光通道。
 */
object SysfsBacklightPwm : BacklightPwm {
    @Volatile var pwmPath: String = "/sys/class/pwm/pwmchip0/pwm0/duty_cycle"
    @Volatile var periodNs: Long = 1_000_000 // 1 kHz

    override fun setBrilliance(duty01: Float) {
        val duty = brillianceToPwmDuty(duty01)
        val ns = (duty01.coerceIn(0f, 1f) * periodNs).toLong()
        val ok = runCatching { java.io.File(pwmPath).writeText(ns.toString()) }.getOrDefault(false).let {
            runCatching { java.io.File(pwmPath).exists() }.getOrDefault(false)
        }
        if (!ok) {
            Log.i("Backlight", "PWM 占空比=$duty/$PWM_FULL_SCALE (周期 ${periodNs}ns) —— 无硬件 PWM(模拟器),仅记录;真机写 $pwmPath")
        }
    }
}

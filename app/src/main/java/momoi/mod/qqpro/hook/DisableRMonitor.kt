package momoi.mod.qqpro.hook

import momoi.anno.mixin.Mixin
import com.tencent.rmonitor.manager.RMonitorLauncher

/**
 * 禁用 QQ 自带 RMonitor 监控：MSF 进程启动时 `LifecycleCallback.<clinit>` 加载
 * `OperationLog` 失败（5-dex 合并后类依赖错位）抛 NoClassDefFoundError，直接崩掉 MSF
 * 进程并弹系统“出错报告”。本 mod 自带 Watchdog（崩溃/卡死捕获），QQ 的上报组件不需要。
 * 仅把启动入口 `e()` 置空并标记已初始化（`d = true`），其余逻辑不再执行。
 */
@Mixin
class DisableRMonitor : RMonitorLauncher() {
    override fun e() {
        d = true
    }
}

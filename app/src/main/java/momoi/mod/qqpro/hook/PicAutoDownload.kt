package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.config.IKernelUIConfigProcessor
import com.tencent.qqnt.kernel.nativeinterface.UIConfig
import com.tencent.qqnt.kernel.processor.KernelUIConfigProcessor
import momoi.mod.qqpro.util.Utils

/**
 * 内核图片自动下载/预加载开关覆盖。
 *
 * 背景（“收到的图片全是白框 / 点开超时”根因）：
 * 内核按 UIConfig 键向 Java 侧要配置字符串，其中
 *  - KXGAUTODOWNLOADPIC 走原版 DefaultConfigProcessor —— 恒返回 ""；
 *  - KAUTODOWNLOADPICCFG / KAIOPICCFG 走 PicPreDownloadConfigProcessor —— 读 Freesia 远端配置
 *    （102272/102274），本机 Freesia 从不同步 → 也是 ""。
 * 空配置让内核按“关闭”处理（GetAutoPreloadSize ... switch:0、IsCanAutoDownload false），
 * 于是图片既不自动下载、显式触发也被拒（30s 超时）。
 *
 * 这里把 KernelUIConfigProcessor.c 里这两个处理器的实例换成我们自己的实现，
 * 让相关键返回开启配置。lookup 按 class simpleName 取实例，所以键名就是
 * "DefaultConfigProcessor" / "PicPreDownloadConfigProcessor"。
 */
object PicAutoDownload {

    fun install() {
        runCatching {
            val processor = object : IKernelUIConfigProcessor {
                override fun a(cfg: UIConfig): String? = when (cfg) {
                    UIConfig.KXGAUTODOWNLOADPIC ->
                        """{"xg_auto_download":true,"XG_Auto_Download":true}"""
                    UIConfig.KAUTODOWNLOADPICCFG, UIConfig.KAIOPICCFG,
                    UIConfig.KGIFCFG, UIConfig.KDATALINEAUTODOWNLOADCFG ->
                        """{"switch":1,"peak_time_control":0,"peak_type":"small","default":"small","real_size":200}"""
                    else -> null
                }
            }
            val instances = KernelUIConfigProcessor.c
            instances["DefaultConfigProcessor"] = processor
            instances["PicPreDownloadConfigProcessor"] = processor
            Utils.log("PicAutoDownload: override installed (${instances.size} processors)")
        }.onFailure { Utils.log("PicAutoDownload: install failed: $it") }
    }
}

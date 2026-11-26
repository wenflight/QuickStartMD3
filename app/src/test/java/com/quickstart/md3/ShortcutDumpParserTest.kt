package com.quickstart.md3

import com.quickstart.md3.shizuku.ShizukuManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutDumpParserTest {

    @Test
    fun parsesWeChatAlipayAndUnionPayPersistableBundlesAccurately() {
        val dump = """
            ShortcutInfo {id=launch_type_scan_qrcode, flags=0x85 [DynIc-rStr]
              packageName=com.tencent.mm
              activity=ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}
              shortLabel=扫一扫, resId=0[null]
              longLabel=扫一扫, resId=0[null]
              intents=[Intent { act=com.tencent.mm.ui.ShortCutDispatchAction }/PersistableBundle[{LauncherUI.Shortcut.LaunchType=launch_type_scan_qrcode}]]
            }
            ShortcutInfo {id=1001, flags=0x89 [DynIc-fStr]
              packageName=com.eg.android.AlipayGphone
              activity=ComponentInfo{com.eg.android.AlipayGphone/com.eg.android.AlipayGphone.AlipayLogin}
              shortLabel=扫一扫, resId=0[null]
              longLabel=扫一扫, resId=0[null]
              intents=[Intent { act=android.intent.action.VIEW cmp=com.eg.android.AlipayGphone/com.alipay.android.phone.wallet.shortcuts.bridge.ShortcutsLauncherActivity }/PersistableBundle[{KEY_APP_ID=10000007, KEY_SCHEME=alipays://platformapi/startapp?appId=10000007&sourceId=scan3dtouch}]]
            }
            ShortcutInfo {id=id0, flags=0x85 [DynIc-rStr]
              packageName=com.unionpay
              activity=ComponentInfo{com.unionpay/com.unionpay.activity.UPActivityMain}
              shortLabel=扫一扫, resId=0[null]
              longLabel=扫一扫, resId=0[null]
              intents=[Intent { act=android.intent.action.VIEW flg=0x4000000 cmp=com.unionpay/.activity.UPActivity3DTouch }/PersistableBundle[{shortcur_extra={"chName":"扫一扫","dest":"/upwallet/scan","shortcutId":"scanCode","type":"native"}}]]
            }
        """.trimIndent()

        val parsed = ShizukuManager.parseShortcutDump(dump, "com.unionpay", "测试")
        assertEquals(3, parsed.size)

        val wx = parsed[0]
        assertEquals("launch_type_scan_qrcode", wx.extras["LauncherUI.Shortcut.LaunchType"])
        assertEquals("com.tencent.mm.ui.ShortCutDispatchAction", wx.action)

        val zfb = parsed[1]
        assertEquals("10000007", zfb.extras["KEY_APP_ID"])
        assertEquals("alipays://platformapi/startapp?appId=10000007&sourceId=scan3dtouch", zfb.extras["KEY_SCHEME"])

        val ysf = parsed[2]
        assertEquals(
            """{"chName":"扫一扫","dest":"/upwallet/scan","shortcutId":"scanCode","type":"native"}""",
            ysf.extras["shortcur_extra"]
        )
    }
}

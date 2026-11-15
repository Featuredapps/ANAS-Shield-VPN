package com.anas.shieldvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var backend: GoBackend
    private var pendingConfig: String? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "anas-shield"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                val state = if (newState == Tunnel.State.UP) "connected" else "disconnected"
                notifyWeb(state, if (state == "connected") "اتصال WireGuard نشط" else "تم إيقاف النفق")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(VpnBridge(), "AndroidVpn")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
    }

    inner class VpnBridge {
        @JavascriptInterface fun connect(raw: String) {
            runOnUiThread {
                try {
                    validateConfig(raw)
                    pendingConfig = raw
                    val prepare = VpnService.prepare(this@MainActivity)
                    if (prepare != null) startActivityForResult(prepare, 7001)
                    else startTunnel()
                } catch (e: Exception) {
                    notifyWeb("disconnected", "ملف الإعداد غير صالح: ${e.message}")
                }
            }
        }
        @JavascriptInterface fun disconnect() {
            Thread {
                try { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                catch (_: Exception) {}
                runOnUiThread { notifyWeb("disconnected", "تم فصل VPN") }
            }.start()
        }
    }

    private fun validateConfig(raw: String) {
        require(raw.contains("[Interface]", true) && raw.contains("[Peer]", true)) { "الأقسام المطلوبة مفقودة" }
        require(Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(raw)) { "PrivateKey مفقود" }
        require(Regex("(?im)^\\s*PublicKey\\s*=").containsMatchIn(raw)) { "PublicKey مفقود" }
        require(Regex("(?im)^\\s*Endpoint\\s*=").containsMatchIn(raw)) { "Endpoint مفقود" }
        require(!raw.contains("anasgdxj-shield.net", true)) { "العناوين الافتراضية في النموذج ليست خوادم فعلية" }
    }

    private fun startTunnel() {
        val raw = pendingConfig ?: return
        Thread {
            try {
                val config = Config.parse(ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))
                backend.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread { notifyWeb("connected", "تم إنشاء نفق WireGuard") }
            } catch (e: Exception) {
                runOnUiThread { notifyWeb("disconnected", "فشل الاتصال: ${e.message ?: "تحقق من بيانات الخادم"}") }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7001) {
            if (resultCode == RESULT_OK) startTunnel()
            else notifyWeb("disconnected", "لم تمنح إذن إنشاء اتصال VPN")
        }
    }

    private fun notifyWeb(state: String, message: String) {
        val safe = org.json.JSONObject.quote(message)
        web.evaluateJavascript("window.onNativeVpnState && window.onNativeVpnState('$state',$safe)", null)
    }

    override fun onDestroy() {
        try { backend.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        web.destroy()
        super.onDestroy()
    }
}

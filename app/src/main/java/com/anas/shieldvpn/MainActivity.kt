package com.anas.shieldvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var backend: GoBackend
    private lateinit var assetLoader: WebViewAssetLoader
    private val vpnExecutor = Executors.newSingleThreadExecutor()
    private val operationInProgress = AtomicBoolean(false)
    @Volatile private var pendingConfig: String? = null
    @Volatile private var activityDestroyed = false

    private val tunnel = object : Tunnel {
        override fun getName() = "anas-shield"
        override fun onStateChange(newState: Tunnel.State) {
            val state = if (newState == Tunnel.State.UP) "connected" else "disconnected"
            runOnUiThread {
                if (!activityDestroyed && ::web.isInitialized) {
                    notifyWeb(state, if (state == "connected") "اتصال WireGuard نشط" else "تم إيقاف النفق")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "appassets.androidplatform.net"
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(VpnBridge(), "AndroidVpn")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
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
                    notifyWeb("disconnected", "ملف الإعداد غير صالح: ${e.message ?: "تحقق من الملف"}")
                }
            }
        }
        @JavascriptInterface fun openConfigPicker() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
                }
                startActivityForResult(intent, 7002)
            }
        }

        @JavascriptInterface fun openExternalUrl(rawUrl: String) {
            runOnUiThread {
                try {
                    val uri = Uri.parse(rawUrl)
                    val allowedHosts = setOf("protonvpn.com", "www.protonvpn.com", "account.protonvpn.com")
                    require(uri.scheme == "https" && uri.host in allowedHosts) { "رابط غير مسموح" }
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    notifyWeb("disconnected", "تعذر فتح صفحة المزود: ${e.message ?: "خطأ غير معروف"}")
                }
            }
        }

        @JavascriptInterface fun disconnect() {
            if (!operationInProgress.compareAndSet(false, true)) return
            vpnExecutor.execute {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    runOnUiThread { notifyWeb("disconnected", "تم فصل VPN") }
                } catch (e: Exception) {
                    runOnUiThread { notifyWeb("disconnected", "تعذر فصل النفق: ${e.message ?: "خطأ غير معروف"}") }
                } finally {
                    operationInProgress.set(false)
                }
            }
        }
    }

    private fun validateConfig(raw: String) {
        require(raw.isNotBlank()) { "الملف فارغ" }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 1024 * 1024) { "حجم الملف يتجاوز 1 MB" }
        require(!raw.contains("\u0000")) { "الملف يحتوي على بيانات غير صالحة" }
        require(!raw.contains("anasgdxj-shield.net", true)) { "العناوين الافتراضية في النموذج ليست خوادم فعلية" }
        Config.parse(ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun startTunnel() {
        val raw = pendingConfig ?: run {
            notifyWeb("disconnected", "استورد ملف WireGuard أولاً")
            return
        }
        if (!operationInProgress.compareAndSet(false, true)) return
        vpnExecutor.execute {
            try {
                val config = Config.parse(ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))
                backend.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread { notifyWeb("connected", "تم تشغيل نفق WireGuard؛ تحقق من نجاح المصافحة") }
            } catch (e: Exception) {
                runOnUiThread { notifyWeb("disconnected", "فشل الاتصال: ${e.message ?: "تحقق من بيانات الخادم والإنترنت"}") }
            } finally {
                operationInProgress.set(false)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7001) {
            if (resultCode == RESULT_OK) startTunnel()
            else notifyWeb("disconnected", "لم تمنح إذن إنشاء اتصال VPN")
        } else if (requestCode == 7002 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBounded(1024 * 1024)
                    require(bytes.isNotEmpty()) { "الملف فارغ" }
                    val raw = bytes.toString(StandardCharsets.UTF_8)
                    validateConfig(raw)
                    web.evaluateJavascript(
                        "window.onNativeConfigImported && window.onNativeConfigImported(${org.json.JSONObject.quote(raw)}, ${org.json.JSONObject.quote(uri.lastPathSegment ?: "wireguard.conf")})",
                        null
                    )
                } ?: throw IllegalArgumentException("تعذر قراءة الملف")
            } catch (e: Exception) {
                notifyWeb("disconnected", "تعذر استيراد الملف: ${e.message}")
            }
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "حجم الملف يتجاوز 1 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun notifyWeb(state: String, message: String) {
        val safe = org.json.JSONObject.quote(message)
        web.evaluateJavascript("window.onNativeVpnState && window.onNativeVpnState('$state',$safe)", null)
    }

    override fun onDestroy() {
        activityDestroyed = true
        try { web.removeJavascriptInterface("AndroidVpn"); web.stopLoading(); web.destroy() } catch (_: Exception) {}
        vpnExecutor.shutdown()
        super.onDestroy()
    }
}

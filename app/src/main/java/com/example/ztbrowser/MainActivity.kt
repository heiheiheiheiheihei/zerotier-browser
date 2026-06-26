package com.example.ztbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.ztbrowser.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 修复记录：
 * - [FIX#11] ProxyServer stop/start 循环中 scope 重建问题（由 ZTProxyServer 自身处理）
 * - [FIX#12] status collect 移除多余的 runOnUiThread
 * - [FIX#13] loadConfig 增加空值防护
 * - [FIX#14] 使用 ContextCompat.getColor 替代废弃的 getColor
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var proxyServer: ZTProxyServer

    private var ztNetworkId: String = ""
    private var ztSubnets: List<String> = listOf("10.147.0.0/16")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 先加载配置，再初始化 proxyServer
        loadConfig()

        // 初始化持久化日志并自动复制到剪贴板（闪退后可粘贴反馈）
        ZeroTierService.log("D", "onCreate: after loadConfig")
        ZeroTierService.setupFileLogging(this)
        ZeroTierService.log("D", "onCreate: after setupFileLogging")
        copyLogToClipboardOnStart()
        ZeroTierService.log("D", "onCreate: after copyLogToClipboardOnStart")

        proxyServer = ZTProxyServer(port = 1080, ztSubnets = ztSubnets)
        ZeroTierService.log("D", "onCreate: after ZTProxyServer init")

        setupWebView()
        ZeroTierService.log("D", "onCreate: after setupWebView")
        setupToolbar()
        ZeroTierService.log("D", "onCreate: after setupToolbar")
        observeZeroTierStatus()
        ZeroTierService.log("D", "onCreate: after observeZeroTierStatus")

        if (ztNetworkId.isNotEmpty() && ZeroTierService.isValidNetworkId(ztNetworkId)) {
            ZeroTierService.log("D", "onCreate: calling startZeroTier with $ztNetworkId")
            startZeroTier()
        } else if (ztNetworkId.isNotEmpty()) {
            Toast.makeText(this, "已保存的 Network ID 格式无效，请重新配置", Toast.LENGTH_LONG).show()
            showConfigDialog()
        } else {
            showConfigDialog()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val url = intent?.dataString ?: return
        webView.loadUrl(url)
    }

    private fun setupWebView() {
        webView = binding.webView

        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString + " ZTBrowser/1.0"
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val webViewClient = ZTWebViewClient(this, proxyPort = 1080).apply {
            ztSubnets = this@MainActivity.ztSubnets
        }

        webView.webViewClient = webViewClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.isVisible = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { binding.titleText.text = it }
            }
        }
    }

    private fun setupToolbar() {
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigateToUrl()
                true
            } else false
        }

        binding.btnGo.setOnClickListener { navigateToUrl() }
        binding.btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        binding.btnRefresh.setOnClickListener { webView.reload() }
        binding.btnHome.setOnClickListener { webView.loadUrl("about:blank") }
        binding.btnSettings.setOnClickListener { showConfigDialog() }
        binding.btnCopyLog.setOnClickListener { copyLogToClipboard() }
        binding.ztStatusIndicator.setOnClickListener { showConfigDialog() }
    }

    private fun navigateToUrl() {
        var url = binding.urlBar.text.toString().trim()
        if (url.isEmpty()) return

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) {
                "https://$url"
            } else {
                "https://www.google.com/search?q=${Uri.encode(url)}"
            }
        }

        binding.urlBar.setText(url)
        webView.loadUrl(url)
    }

    // [FIX#12] lifecycleScope 已在主线程，移除 runOnUiThread
    private fun observeZeroTierStatus() {
        lifecycleScope.launch {
            ZeroTierService.status.collectLatest { status ->
                updateZTStatusIndicator(status)
            }
        }
    }

    private fun updateZTStatusIndicator(status: ZeroTierService.Status) {
        when (status) {
            ZeroTierService.Status.STOPPED -> {
                binding.ztStatusIndicator.text = "ZT: ⬤ OFF"
                binding.ztStatusIndicator.setTextColor(
                    ContextCompat.getColor(this, android.R.color.darker_gray)
                )
            }
            ZeroTierService.Status.CONNECTING -> {
                binding.ztStatusIndicator.text = "ZT: ◉ ..."
                binding.ztStatusIndicator.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_orange_dark)
                )
            }
            ZeroTierService.Status.ONLINE -> {
                val addr = ZeroTierService.getNetworkAddress() ?: ""
                binding.ztStatusIndicator.text = "ZT: ● $addr"
                binding.ztStatusIndicator.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark)
                )
            }
            ZeroTierService.Status.OFFLINE -> {
                binding.ztStatusIndicator.text = "ZT: ○ offline"
                binding.ztStatusIndicator.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_dark)
                )
            }
        }
    }

    private fun startZeroTier() {
        proxyServer.start()
        ZeroTierService.start(this, ztNetworkId)
        Toast.makeText(this, "ZeroTier 启动中...", Toast.LENGTH_SHORT).show()
    }

    private fun showConfigDialog() {
        val dialog = ZTConfigDialog(
            context = this,
            currentNetworkId = ztNetworkId,
            currentSubnets = ztSubnets,
            onSave = { networkId, subnets ->
                ztNetworkId = networkId
                ztSubnets = subnets
                saveConfig()
                stopZeroTier()
                if (networkId.isNotEmpty()) {
                    startZeroTier()
                }
            }
        )
        dialog.show()
    }

    private fun stopZeroTier() {
        proxyServer.stop()
        ZeroTierService.stop()
    }

    private fun copyLogToClipboardOnStart() {
        try {
            val log = ZeroTierService.getLog()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ZeroTier Log", log))
            // 静默复制，不弹 Toast（启动时避免干扰用户）
        } catch (_: Exception) { }
    }

    private fun copyLogToClipboard() {
        try {
            val log = ZeroTierService.getLog()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ZeroTier Log", log))
            Toast.makeText(this, "日志已复制 (${log.length} 字符)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // [FIX#13] 增加空值防护
    private fun loadConfig() {
        val prefs = getSharedPreferences("zt_browser", MODE_PRIVATE)
        ztNetworkId = prefs.getString("zt_network_id", "")?.trim() ?: ""
        val subnetStr = prefs.getString("zt_subnets", "10.147.0.0/16")?.trim() ?: "10.147.0.0/16"
        ztSubnets = subnetStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (ztSubnets.isEmpty()) {
            ztSubnets = listOf("10.147.0.0/16")
        }
    }

    private fun saveConfig() {
        getSharedPreferences("zt_browser", MODE_PRIVATE).edit().apply {
            putString("zt_network_id", ztNetworkId)
            putString("zt_subnets", ztSubnets.joinToString(","))
            apply()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopZeroTier()
        super.onDestroy()
    }
}





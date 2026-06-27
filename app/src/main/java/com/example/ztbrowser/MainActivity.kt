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
    private lateinit var bodyCollector: RequestBodyCollector

    private var ztNetworkId: String = ""
    private var ztSubnets: List<String> = listOf("10.147.0.0/16")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ZeroTierService.logUserAction("App started")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 先加载配置，再初始化日志
        loadConfig()
        ZeroTierService.log("D", "onCreate: after loadConfig")
        ZeroTierService.setupFileLogging(this)
        ZeroTierService.log("D", "onCreate: after setupFileLogging")

        // 初始化 proxyServer（try-catch 防止构造函数中意外崩溃）
        proxyServer = try {
            ZTProxyServer(port = 1080, ztSubnets = ztSubnets)
        } catch (e: Exception) {
            ZeroTierService.log("E", "ZTProxyServer init failed", e)
            ZTProxyServer(port = 1080, ztSubnets = emptyList())
        }
        ZeroTierService.log("D", "onCreate: after ZTProxyServer init")

        // 初始化 POST body 收集器（JS Bridge）
        bodyCollector = RequestBodyCollector()

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
        ZeroTierService.logUserAction("New intent received")
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val url = intent?.dataString ?: return
        ZeroTierService.logUserAction("Intent URL: $url")
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

        // 注册 JS Bridge：POST body 收集器
        webView.addJavascriptInterface(bodyCollector, "AndroidBodyCollector")

        val webViewClient = ZTWebViewClient(this, proxyPort = 1080, bodyCollector = bodyCollector).apply {
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

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    ZeroTierService.log("D", "JS[${it.messageLevel()}]: ${it.message()}")
                }
                return true
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
            ZeroTierService.logUserAction("Button: Back")
            if (webView.canGoBack()) webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            ZeroTierService.logUserAction("Button: Forward")
            if (webView.canGoForward()) webView.goForward()
        }
        binding.btnRefresh.setOnClickListener {
            ZeroTierService.logUserAction("Button: Refresh")
            webView.reload()
        }
        binding.btnHome.setOnClickListener {
            ZeroTierService.logUserAction("Button: Home (about:blank)")
            webView.loadUrl("about:blank")
        }
        binding.btnSettings.setOnClickListener {
            ZeroTierService.logUserAction("Button: Settings")
            showConfigDialog()
        }
        binding.btnCopyLog.setOnClickListener {
            ZeroTierService.logUserAction("Button: Copy log")
            copyLogToClipboard()
        }
        binding.ztStatusIndicator.setOnClickListener {
            ZeroTierService.logUserAction("Button: ZT status indicator")
            showConfigDialog()
        }
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

        ZeroTierService.logUserAction("Navigate: $url")
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
        ZeroTierService.log("D", "ZT status → $status (addr=${ZeroTierService.getNetworkAddress() ?: "none"})")
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
        ZeroTierService.logUserAction("ZT: Starting (networkId=$ztNetworkId)")
        // 用当前最新的 ztSubnets 重建 proxyServer（配置变更后子网可能已更新）
        proxyServer = try {
            ZTProxyServer(port = 1080, ztSubnets = ztSubnets)
        } catch (e: Exception) {
            ZeroTierService.log("E", "ZTProxyServer rebuild failed", e)
            ZTProxyServer(port = 1080, ztSubnets = emptyList())
        }
        // 同步更新 WebViewClient 的子网判断范围
        (webView.webViewClient as? ZTWebViewClient)?.ztSubnets = ztSubnets
        proxyServer.start()
        ZeroTierService.start(this, ztNetworkId)
        Toast.makeText(this, "ZeroTier 启动中...", Toast.LENGTH_SHORT).show()
    }

    private fun showConfigDialog() {
        ZeroTierService.logUserAction("Opening config dialog")
        val dialog = ZTConfigDialog(
            context = this,
            currentNetworkId = ztNetworkId,
            currentSubnets = ztSubnets,
            onSave = { networkId, subnets ->
                ztNetworkId = networkId
                ztSubnets = subnets
                ZeroTierService.logUserAction("Config saved: nwid=$networkId, subnets=$subnets")
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
        ZeroTierService.logUserAction("ZT: Stopping")
        proxyServer.stop()
        ZeroTierService.stop()
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
        ZeroTierService.log("D", "Config loaded: nwid=$ztNetworkId, subnets=$ztSubnets")
    }

    private fun saveConfig() {
        getSharedPreferences("zt_browser", MODE_PRIVATE).edit().apply {
            putString("zt_network_id", ztNetworkId)
            putString("zt_subnets", ztSubnets.joinToString(","))
            apply()
        }
    }

    override fun onBackPressed() {
        ZeroTierService.logUserAction("Back pressed (canGoBack=${webView.canGoBack()})")
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        ZeroTierService.logUserAction("App destroyed")
        stopZeroTier()
        super.onDestroy()
    }
}





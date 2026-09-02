package com.andrower.markerdeck

import android.app.Activity
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var settingsScreen: View
    private lateinit var displayScreen: View
    private lateinit var serviceAddressInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var modeValue: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var webView: WebView
    private lateinit var displayStatusPanel: View
    private lateinit var displayStatusMessage: TextView
    private lateinit var displayProgress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var backToSettingsButton: Button

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private var displayActive = false
    private var displayLoadFailed = false
    private var webViewHasDisplayPage = false
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var settingsDraft = SettingsDraft()
    private var applyingSettingsDraft = false
    private var displayOrigin: String? = null
    private var mainFrameUrl: String? = null
    private var cleanupNavigationPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepository = SettingsRepository(applicationContext.markerdeckDataStore)
        bindViews()
        configureWebView()
        bindActions()
        registerBackHandler()
        showSettingsScreen()
        observeSettings()
    }

    private fun bindViews() {
        settingsScreen = findViewById(R.id.settingsScreen)
        displayScreen = findViewById(R.id.displayScreen)
        serviceAddressInput = findViewById(R.id.serviceAddressInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        modeValue = findViewById(R.id.modeValue)
        settingsStatus = findViewById(R.id.settingsStatus)
        connectButton = findViewById(R.id.connectButton)
        webView = findViewById(R.id.displayWebView)
        displayStatusPanel = findViewById(R.id.displayStatusPanel)
        displayStatusMessage = findViewById(R.id.displayStatusMessage)
        displayProgress = findViewById(R.id.displayProgress)
        retryButton = findViewById(R.id.retryButton)
        backToSettingsButton = findViewById(R.id.backToSettingsButton)
        serviceAddressInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingSettingsDraft) {
                    settingsDraft = updateSettingsDraft(
                        settingsDraft,
                        SettingsField.SERVICE_ADDRESS,
                        s?.toString().orEmpty()
                    )
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        deviceNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingSettingsDraft) {
                    settingsDraft = updateSettingsDraft(
                        settingsDraft,
                        SettingsField.DEVICE_NAME,
                        s?.toString().orEmpty()
                    )
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (consumePendingCleanupNavigation(url)) return
                if (!displayActive) {
                    view.stopLoading()
                    return
                }
                if (!isAllowedDisplayNavigation(url)) {
                    blockUnexpectedNavigation(view)
                    return
                }
                mainFrameUrl = url
                showDisplayLoading()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (consumePendingCleanupNavigation(url)) return
                if (!displayActive) return
                if (!isAllowedDisplayNavigation(url)) {
                    blockUnexpectedNavigation(view)
                    return
                }
                if (!displayLoadFailed) hideDisplayStatus()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleTopLevelNavigation(view, request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleTopLevelNavigation(view, url)

            private fun handleTopLevelNavigation(view: WebView, url: String): Boolean {
                if (!displayActive) {
                    if (isCleanupNavigationPending(url)) return false
                    view.stopLoading()
                    return true
                }
                if (isCleanupNavigationPending(url) || isAllowedDisplayNavigation(url)) return false
                blockUnexpectedNavigation(view)
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (displayActive && request.isForMainFrame && !displayLoadFailed) {
                    showDisplayError("服务不可达或投放页面加载失败。")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (displayActive && request.isForMainFrame && !displayLoadFailed) {
                    showDisplayError("服务返回错误（HTTP ${errorResponse.statusCode}）。")
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                if (displayActive && !displayLoadFailed &&
                    (error.url == mainFrameUrl || error.url == view.url)
                ) {
                    showDisplayError("HTTPS 证书验证失败。")
                }
            }
        }
    }

    private fun isAllowedDisplayNavigation(url: String): Boolean {
        val origin = displayOrigin ?: return false
        return isAllowedTopLevelNavigation(
            url = url,
            allowedOrigin = origin,
            allowCleanup = cleanupNavigationPending
        )
    }

    private fun isCleanupNavigationPending(url: String): Boolean =
        cleanupNavigationPending && url.trim() == WEBVIEW_CLEANUP_URL

    private fun consumePendingCleanupNavigation(url: String): Boolean {
        if (!isCleanupNavigationPending(url)) return false
        cleanupNavigationPending = false
        return true
    }

    private fun blockUnexpectedNavigation(view: WebView) {
        view.stopLoading()
        showDisplayError(getString(R.string.display_unexpected_navigation))
    }

    private fun bindActions() {
        connectButton.setOnClickListener { connectFromSettings() }
        retryButton.setOnClickListener { loadDisplayPage() }
        backToSettingsButton.setOnClickListener { showSettingsScreen() }
    }

    private fun observeSettings() {
        activityScope.launch {
            val saved = settingsRepository.settings.first()
            settingsDraft = hydrateSettingsDraft(settingsDraft, saved)
            if (!displayActive) applySettingsDraftToViews()
            modeValue.text = settingsDraft.mode.label
            if (settingsDraft.editedFields.isEmpty() &&
                (saved.serviceAddress.isNotEmpty() || saved.deviceName.isNotEmpty())
            ) {
                settingsStatus.setText(R.string.settings_restored_status)
            }
        }
    }

    private fun applySettingsDraftToViews() {
        applyingSettingsDraft = true
        try {
            if (serviceAddressInput.text.toString() != settingsDraft.serviceAddress) {
                serviceAddressInput.setText(settingsDraft.serviceAddress)
            }
            if (deviceNameInput.text.toString() != settingsDraft.deviceName) {
                deviceNameInput.setText(settingsDraft.deviceName)
            }
        } finally {
            applyingSettingsDraft = false
        }
    }

    private fun connectFromSettings() {
        val normalizedAddress = try {
            normalizeServiceAddress(serviceAddressInput.text.toString())
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: getString(R.string.invalid_address)
            serviceAddressInput.error = message
            showSettingsError(message)
            return
        }
        serviceAddressInput.error = null
        val normalizedName = normalizeDeviceName(deviceNameInput.text.toString())
        if (normalizedName.isEmpty()) {
            val message = getString(R.string.device_name_required)
            deviceNameInput.error = message
            showSettingsError(message)
            deviceNameInput.requestFocus()
            return
        }
        deviceNameInput.error = null

        val savedSettings = normalizeSettings(
            serviceAddress = normalizedAddress,
            deviceName = normalizedName,
            mode = settingsDraft.mode
        )
        connectButton.isEnabled = false
        serviceAddressInput.isEnabled = false
        deviceNameInput.isEnabled = false
        settingsStatus.setText(R.string.saving_settings_status)
        val saveErrorHandler = CoroutineExceptionHandler { _, error ->
            connectButton.isEnabled = true
            serviceAddressInput.isEnabled = true
            deviceNameInput.isEnabled = true
            showSettingsError(error.message ?: getString(R.string.settings_save_failed))
        }
        activityScope.launch(saveErrorHandler) {
            settingsRepository.save(savedSettings)
            settingsDraft = settingsDraftFromSaved(savedSettings)
            applySettingsDraftToViews()
            showDisplayScreen(savedSettings)
        }
    }

    private fun showDisplayScreen(settings: MarkerDeckSettings) {
        val normalizedAddress = normalizeServiceAddress(settings.serviceAddress)
        displayOrigin = normalizedAddress
        mainFrameUrl = null
        cleanupNavigationPending = false
        displayActive = true
        displayLoadFailed = false
        settingsScreen.visibility = View.GONE
        displayScreen.visibility = View.VISIBLE
        connectButton.isEnabled = true
        applyDisplayWindowState()
        loadDisplayPage(settings.copy(serviceAddress = normalizedAddress))
    }

    private fun loadDisplayPage(settings: MarkerDeckSettings? = null) {
        if (!displayActive) return
        displayLoadFailed = false
        showDisplayLoading()
        webView.onResume()
        webView.resumeTimers()
        val address = settings?.serviceAddress ?: displayOrigin ?: serviceAddressInput.text.toString()
        val name = settings?.deviceName ?: deviceNameInput.text.toString()
        val normalizedAddress = normalizeServiceAddress(address)
        if (displayOrigin == null) displayOrigin = normalizedAddress
        val displayUrl = buildDisplayUrl(normalizedAddress, name)
        webViewHasDisplayPage = true
        mainFrameUrl = displayUrl
        cleanupNavigationPending = false
        webView.loadUrl(displayUrl)
    }

    private fun showDisplayLoading() {
        displayStatusPanel.visibility = View.VISIBLE
        displayProgress.visibility = View.VISIBLE
        displayStatusMessage.setText(R.string.display_loading)
        retryButton.visibility = View.GONE
    }

    private fun hideDisplayStatus() {
        displayStatusPanel.visibility = View.GONE
    }

    private fun showDisplayError(message: String) {
        displayLoadFailed = true
        displayStatusPanel.visibility = View.VISIBLE
        displayProgress.visibility = View.GONE
        displayStatusMessage.text = message
        retryButton.visibility = View.VISIBLE
    }

    private fun showSettingsError(message: String) {
        settingsStatus.text = message
        settingsStatus.setTextColor(getColor(R.color.markerdeck_error))
    }

    private fun showSettingsScreen() {
        displayActive = false
        displayLoadFailed = false
        webView.stopLoading()
        val shouldClearWebView = webViewHasDisplayPage
        webViewHasDisplayPage = false
        displayOrigin = null
        mainFrameUrl = null
        if (shouldClearWebView) {
            cleanupNavigationPending = true
            webView.loadUrl(WEBVIEW_CLEANUP_URL)
        } else {
            cleanupNavigationPending = false
        }
        webView.onPause()
        webView.pauseTimers()
        displayScreen.visibility = View.GONE
        settingsScreen.visibility = View.VISIBLE
        connectButton.isEnabled = true
        serviceAddressInput.isEnabled = true
        deviceNameInput.isEnabled = true
        clearDisplayWindowState()
        settingsStatus.setText(R.string.settings_not_connected_status)
        settingsStatus.setTextColor(getColor(R.color.markerdeck_muted))
    }

    private fun applyDisplayWindowState() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode(window)
    }

    private fun clearDisplayWindowState() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exitImmersiveMode(window)
    }

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = android.window.OnBackInvokedCallback { handleBackPressed() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    private fun handleBackPressed() {
        if (displayActive) showSettingsScreen() else finish()
    }

    private fun enterImmersiveMode(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun exitImmersiveMode(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && displayActive) enterImmersiveMode(window)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (displayActive) enterImmersiveMode(window)
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Use OnBackInvokedDispatcher on newer Android versions.")
    override fun onBackPressed() {
        handleBackPressed()
    }

    override fun onPause() {
        if (displayActive) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (displayActive) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        webView.stopLoading()
        webView.destroy()
        clearDisplayWindowState()
        super.onDestroy()
    }
}

package com.andrower.markerdeck

import android.app.Activity
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    companion object {
        private const val CAPABILITY_WARNING_DURATION_MS = 4_000L
        private const val STATE_PROJECTION_ACTIVE = "projection_active"
        private const val STATE_SERVICE_ADDRESS = "projection_service_address"
        private const val STATE_DEVICE_NAME = "projection_device_name"
    }

    private lateinit var settingsScreen: View
    private lateinit var displayScreen: View
    private lateinit var serviceAddressInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var discoveryStatus: TextView
    private lateinit var discoveryHostsList: LinearLayout
    private lateinit var refreshDiscoveryButton: Button
    private lateinit var settingsStatus: TextView
    private lateinit var systemPermissionSettingsButton: Button
    private lateinit var connectButton: Button
    private lateinit var webView: WebView
    private lateinit var displayStatusPanel: View
    private lateinit var displayStatusMessage: TextView
    private lateinit var displayDiagnosticMessage: TextView
    private lateinit var displayCapabilityWarningMessage: TextView
    private lateinit var displayProgress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var backToSettingsButton: Button

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var discoveryScanner: MarkerDeckDiscoveryScanner
    private var displayActive = false
    private var displayLoadFailed = false
    private var webViewHasDisplayPage = false
    private var displayPageHealthy = false
    private var displayLoadInFlight = false
    private var displayMainFrameFailed = false
    private var displayDeviceName: String? = null
    private var displayDiagnosticState = ProjectionDiagnosticState.IDLE_SETTINGS
    private var screenReceiverRegistered = false
    private var screenReceiverCapabilityWarning = false
    private var displayWindowStateApplied = false
    private var activityResumed = false
    private var webViewLifecycleResumed = false
    private var displayWebViewUsable = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transientCapabilityWarningVisible = false
    private var transientCapabilityWarningToken = 0
    private var transientCapabilityWarningHideCallback: Runnable? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var settingsDraft = SettingsDraft()
    private var settingsHydrated = false
    private var activityStarted = false
    private var discoveryUiState = DiscoveryUiState()
    private var applyingSettingsDraft = false
    private var displayOrigin: String? = null
    private var mainFrameUrl: String? = null
    private var webViewLayoutIndex = 0
    private var webViewLayoutParams: ViewGroup.LayoutParams? = null
    private var cleanupNavigationPending = false
    private var settingsStatusOverride: String? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!displayActive) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> restoreActiveDisplay(ProjectionDiagnosticState.SCREEN_ON_RECOVERY)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepository = SettingsRepository(applicationContext.markerdeckDataStore)
        bindViews()
        configureWebView(webView)
        bindActions()
        discoveryScanner = MarkerDeckDiscoveryScanner(
            context = this,
            scope = activityScope,
            listener = object : MarkerDeckDiscoveryScanner.Listener {
                override fun onScanStarted() {
                    discoveryUiState = mergeDiscoveryUiState(
                        current = discoveryUiState,
                        status = DiscoveryScanStatus.SCANNING,
                        replaceHosts = true
                    )
                    renderDiscoveryUi()
                }

                override fun onHostDiscovered(host: DiscoveryHost) {
                    discoveryUiState = mergeDiscoveryUiState(
                        current = discoveryUiState,
                        status = DiscoveryScanStatus.FOUND,
                        incoming = listOf(host)
                    )
                    renderDiscoveryUi()
                }

                override fun onScanFinished(status: DiscoveryScanStatus, message: String) {
                    discoveryUiState = mergeDiscoveryUiState(
                        current = discoveryUiState,
                        status = status,
                        message = message
                    )
                    renderDiscoveryUi()
                    maybeAutoFillDiscoveredHost()
                }
            }
        )
        renderDiscoveryUi()
        registerBackHandler()
        val savedProjection = validateSavedProjection(
            projectionActive = savedInstanceState?.getBoolean(STATE_PROJECTION_ACTIVE, false) == true,
            serviceAddress = savedInstanceState?.getString(STATE_SERVICE_ADDRESS),
            deviceName = savedInstanceState?.getString(STATE_DEVICE_NAME)
        )
        if (savedProjection == null) {
            showSettingsScreen()
        } else {
            val restored = showDisplayScreen(
                MarkerDeckSettings(
                    serviceAddress = savedProjection.serviceAddress,
                    deviceName = savedProjection.deviceName
                )
            )
            if (!restored) {
                showSettingsScreen()
                showSettingsError(getString(R.string.display_renderer_recovery_failed_settings))
            }
        }
        observeSettings()
    }

    private fun bindViews() {
        settingsScreen = findViewById(R.id.settingsScreen)
        displayScreen = findViewById(R.id.displayScreen)
        serviceAddressInput = findViewById(R.id.serviceAddressInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        discoveryStatus = findViewById(R.id.discoveryStatus)
        discoveryHostsList = findViewById(R.id.discoveryHostsList)
        refreshDiscoveryButton = findViewById(R.id.refreshDiscoveryButton)
        settingsStatus = findViewById(R.id.settingsStatus)
        systemPermissionSettingsButton = findViewById(R.id.systemPermissionSettingsButton)
        connectButton = findViewById(R.id.connectButton)
        webView = findViewById(R.id.displayWebView)
        displayStatusPanel = findViewById(R.id.displayStatusPanel)
        displayStatusMessage = findViewById(R.id.displayStatusMessage)
        displayDiagnosticMessage = findViewById(R.id.displayDiagnosticMessage)
        displayCapabilityWarningMessage = findViewById(R.id.displayCapabilityWarningMessage)
        displayProgress = findViewById(R.id.displayProgress)
        retryButton = findViewById(R.id.retryButton)
        backToSettingsButton = findViewById(R.id.backToSettingsButton)
        webViewLayoutIndex = (webView.parent as? ViewGroup)?.indexOfChild(webView) ?: 0
        webViewLayoutParams = webView.layoutParams
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
    private fun configureWebView(target: WebView) {
        target.setBackgroundColor(Color.BLACK)
        target.settings.apply {
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
        target.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!isCurrentWebView(view)) return
                super.onPageStarted(view, url, favicon)
                if (isCleanupNavigationPending(url)) return
                if (!displayActive) {
                    view.stopLoading()
                    return
                }
                if (!isAllowedDisplayNavigation(url)) {
                    blockUnexpectedNavigation(view)
                    return
                }
                mainFrameUrl = url
                displayLoadInFlight = true
                displayPageHealthy = false
                displayMainFrameFailed = false
                showDisplayLoading()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!isCurrentWebView(view)) return
                super.onPageFinished(view, url)
                if (consumePendingCleanupNavigation(url)) return
                if (!displayActive) return
                if (!isAllowedDisplayNavigation(url)) {
                    blockUnexpectedNavigation(view)
                    return
                }
                displayLoadInFlight = false
                if (!displayLoadFailed) {
                    displayPageHealthy = true
                    displayMainFrameFailed = false
                    displayDiagnosticState = ProjectionDiagnosticState.PROJECTION_ACTIVE
                    hideDisplayStatus()
                    updateCapabilityDiagnostic()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!isCurrentWebView(view)) return true
                if (!request.isForMainFrame) return false
                return handleTopLevelNavigation(view, request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (!isCurrentWebView(view)) return true
                return handleTopLevelNavigation(view, url)
            }

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
                if (!isCurrentWebView(view)) return
                super.onReceivedError(view, request, error)
                if (isCleanupNavigationPending(request.url.toString())) return
                if (displayActive && request.isForMainFrame && !displayLoadFailed) {
                    displayLoadInFlight = false
                    displayPageHealthy = false
                    displayMainFrameFailed = true
                    showDisplayError(
                        "服务不可达或投放页面加载失败。",
                        ProjectionDiagnosticState.PROJECTION_FAILURE
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!isCurrentWebView(view)) return
                super.onReceivedHttpError(view, request, errorResponse)
                if (isCleanupNavigationPending(request.url.toString())) return
                if (displayActive && request.isForMainFrame && !displayLoadFailed) {
                    displayLoadInFlight = false
                    displayPageHealthy = false
                    displayMainFrameFailed = true
                    showDisplayError(
                        "服务返回错误（HTTP ${errorResponse.statusCode}）。",
                        ProjectionDiagnosticState.PROJECTION_FAILURE
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                if (!isCurrentWebView(view)) return
                if (isCleanupNavigationPending(error.url)) return
                if (displayActive && !displayLoadFailed &&
                    (error.url == mainFrameUrl || error.url == view.url)
                ) {
                    displayLoadInFlight = false
                    displayPageHealthy = false
                    displayMainFrameFailed = true
                    showDisplayError(
                        "HTTPS 证书验证失败。",
                        ProjectionDiagnosticState.PROJECTION_FAILURE
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                if (view !== webView || !displayWebViewUsable) {
                    destroyWebViewAfterRendererExit(view)
                    return true
                }

                webViewLifecycleResumed = false
                webViewHasDisplayPage = false

                if (isFinishing || isDestroyed) {
                    destroyWebViewAfterRendererExit(view)
                    return true
                }

                if (!displayActive) {
                    if (!replaceWebViewAfterRendererExit(view)) {
                        showSettingsError(getString(R.string.display_renderer_recovery_failed_settings))
                    }
                    return true
                }

                displayLoadInFlight = false
                displayPageHealthy = false
                displayMainFrameFailed = true
                displayLoadFailed = false
                displayDiagnosticState = ProjectionDiagnosticState.RENDERER_RECOVERY
                clearTransientCapabilityWarning()
                displayScreen.visibility = View.VISIBLE
                displayScreen.bringToFront()
                showDisplayLoading()
                if (!replaceWebViewAfterRendererExit(view)) {
                    displayDiagnosticState = ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE
                    showDisplayError(
                        getString(R.string.display_renderer_recovery_failed),
                        ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE,
                        allowRetry = true
                    )
                    return true
                }

                if (activityResumed) {
                    loadDisplayPage(loadingState = ProjectionDiagnosticState.RENDERER_RECOVERY)
                }
                return true
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
        showDisplayError(
            getString(R.string.display_unexpected_navigation),
            ProjectionDiagnosticState.PROJECTION_FAILURE
        )
    }

    private fun bindActions() {
        connectButton.setOnClickListener { connectFromSettings() }
        systemPermissionSettingsButton.setOnClickListener { openSystemPermissionSettings() }
        refreshDiscoveryButton.setOnClickListener { discoveryScanner.refresh() }
        retryButton.setOnClickListener { loadDisplayPage() }
        backToSettingsButton.setOnClickListener { showSettingsScreen() }
    }

    private fun observeSettings() {
        activityScope.launch {
            val saved = settingsRepository.settings.first()
            settingsDraft = hydrateSettingsDraft(settingsDraft, saved)
            settingsHydrated = true
            if (!displayActive) applySettingsDraftToViews()
            maybeAutoFillDiscoveredHost()
            if (settingsStatusOverride != null) {
                settingsStatus.text = settingsStatusOverride
                settingsStatus.setTextColor(getColor(R.color.markerdeck_error))
                settingsStatusOverride = null
            } else if (settingsDraft.editedFields.isEmpty() &&
                (saved.serviceAddress.isNotEmpty() || saved.deviceName.isNotEmpty())
            ) {
                settingsStatus.setText(R.string.settings_restored_status)
            }
        }
    }

    private fun renderDiscoveryUi() {
        if (!::discoveryStatus.isInitialized) return
        discoveryStatus.text = when (discoveryUiState.status) {
            DiscoveryScanStatus.IDLE -> getString(R.string.discovery_idle)
            DiscoveryScanStatus.SCANNING -> getString(R.string.discovery_scanning)
            DiscoveryScanStatus.FOUND -> getString(
                R.string.discovery_found,
                discoveryUiState.hosts.size
            )
            DiscoveryScanStatus.EMPTY -> getString(R.string.discovery_empty)
            DiscoveryScanStatus.NO_NETWORK -> discoveryUiState.message.ifBlank {
                getString(R.string.discovery_no_network)
            }
            DiscoveryScanStatus.UNAVAILABLE -> discoveryUiState.message.ifBlank {
                getString(R.string.discovery_unavailable)
            }
        }
        discoveryStatus.setTextColor(getColor(R.color.markerdeck_muted))
        refreshDiscoveryButton.isEnabled = discoveryUiState.status != DiscoveryScanStatus.SCANNING
        discoveryHostsList.removeAllViews()
        discoveryUiState.hosts.forEachIndexed { index, host ->
            val hostButton = Button(this).apply {
                background = getDrawable(R.drawable.markerdeck_secondary_button)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                minHeight = (48 * resources.displayMetrics.density).toInt()
                val horizontalPadding = (14 * resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                setTextColor(getColor(R.color.markerdeck_foreground))
                textSize = 13f
                text = "${host.name}\n${host.serviceAddress}"
                contentDescription = "${host.name} ${host.serviceAddress}"
                setOnClickListener { selectDiscoveredHost(host) }
            }
            val layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                layoutParams.topMargin = (8 * resources.displayMetrics.density).toInt()
            }
            discoveryHostsList.addView(hostButton, layoutParams)
        }
    }

    private fun maybeAutoFillDiscoveredHost() {
        if (!settingsHydrated || discoveryUiState.status != DiscoveryScanStatus.FOUND) return
        if (discoveryUiState.hosts.size != 1) return
        if (SettingsField.SERVICE_ADDRESS in settingsDraft.editedFields) return
        if (serviceAddressInput.text.toString().trim().isNotEmpty()) return
        val host = discoveryUiState.hosts.single()
        val normalizedAddress = try {
            normalizeServiceAddress(host.serviceAddress)
        } catch (_: IllegalArgumentException) {
            return
        }
        applyingSettingsDraft = true
        try {
            serviceAddressInput.setText(normalizedAddress)
        } finally {
            applyingSettingsDraft = false
        }
        // Automatic discovery is a suggestion, so a later keystroke remains the user's edit.
        settingsDraft = settingsDraft.copy(serviceAddress = normalizedAddress)
    }

    private fun selectDiscoveredHost(host: DiscoveryHost) {
        val normalizedAddress = try {
            normalizeServiceAddress(host.serviceAddress)
        } catch (error: IllegalArgumentException) {
            showSettingsError(error.message ?: getString(R.string.invalid_address))
            return
        }
        applyingSettingsDraft = true
        try {
            serviceAddressInput.setText(normalizedAddress)
        } finally {
            applyingSettingsDraft = false
        }
        settingsDraft = updateSettingsDraft(
            settingsDraft,
            SettingsField.SERVICE_ADDRESS,
            normalizedAddress
        )
        settingsStatus.text = getString(R.string.discovery_selected, host.name)
        settingsStatus.setTextColor(getColor(R.color.markerdeck_foreground))
    }

    private fun updateDiscoveryLifecycle() {
        if (!::discoveryScanner.isInitialized) return
        if (activityStarted && activityResumed && settingsScreen.visibility == View.VISIBLE) {
            discoveryScanner.start()
        } else {
            discoveryScanner.stop()
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
            deviceName = normalizedName
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
            if (!showDisplayScreen(savedSettings)) {
                showSettingsScreen()
                showSettingsError(getString(R.string.display_renderer_recovery_failed_settings))
            }
        }
    }

    private fun showDisplayScreen(settings: MarkerDeckSettings): Boolean {
        val normalizedAddress = normalizeServiceAddress(settings.serviceAddress)
        val normalizedSettings = settings.copy(
            serviceAddress = normalizedAddress,
            deviceName = normalizeDeviceName(settings.deviceName)
        )
        if (!prepareWebViewForProjection()) {
            return false
        }

        settingsDraft = settingsDraftFromSaved(normalizedSettings)
        applySettingsDraftToViews()
        displayOrigin = normalizedAddress
        displayDeviceName = normalizedSettings.deviceName
        mainFrameUrl = null
        cleanupNavigationPending = false
        displayActive = true
        displayLoadFailed = false
        displayPageHealthy = false
        displayMainFrameFailed = false
        displayLoadInFlight = false
        displayDiagnosticState = ProjectionDiagnosticState.PROJECTION_ACTIVE
        clearTransientCapabilityWarning()
        settingsScreen.visibility = View.GONE
        displayScreen.visibility = View.VISIBLE
        displayScreen.bringToFront()
        updateDiscoveryLifecycle()
        connectButton.isEnabled = true
        applyDisplayWindowState()
        loadDisplayPage(normalizedSettings)
        registerScreenStateReceiver()
        return true
    }

    private fun loadDisplayPage(
        settings: MarkerDeckSettings? = null,
        loadingState: ProjectionDiagnosticState? = null
    ) {
        if (!displayActive) return
        if (!prepareWebViewForProjection()) {
            showDisplayError(
                getString(R.string.display_renderer_recovery_failed),
                ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE,
                allowRetry = true
            )
            return
        }
        if (loadingState != null) {
            displayDiagnosticState = loadingState
        } else if (displayDiagnosticState == ProjectionDiagnosticState.PROJECTION_FAILURE ||
            displayDiagnosticState == ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE
        ) {
            displayDiagnosticState = ProjectionDiagnosticState.PROJECTION_ACTIVE
        }
        displayLoadFailed = false
        displayMainFrameFailed = false
        displayPageHealthy = false
        displayLoadInFlight = true
        showDisplayLoading()
        resumeWebViewIfNeeded()
        val address = settings?.serviceAddress ?: displayOrigin ?: serviceAddressInput.text.toString()
        val name = settings?.deviceName ?: displayDeviceName.orEmpty()
        val normalizedAddress = normalizeServiceAddress(address)
        if (displayOrigin == null) displayOrigin = normalizedAddress
        if (displayDeviceName == null) displayDeviceName = normalizeDeviceName(name)
        val displayUrl = buildDisplayUrl(normalizedAddress, name)
        webViewHasDisplayPage = true
        mainFrameUrl = displayUrl
        cleanupNavigationPending = false
        webView.loadUrl(displayUrl)
    }

    private fun isCurrentWebView(view: WebView): Boolean = view === webView && displayWebViewUsable

    private fun resumeWebViewIfNeeded() {
        if (!activityResumed || !displayWebViewUsable || webViewLifecycleResumed) return
        webView.onResume()
        webView.resumeTimers()
        webViewLifecycleResumed = true
    }

    private fun pauseWebViewIfNeeded() {
        if (!webViewLifecycleResumed) return
        webView.onPause()
        webView.pauseTimers()
        webViewLifecycleResumed = false
    }

    private fun showDisplayLoading() {
        displayStatusPanel.visibility = View.VISIBLE
        displayProgress.visibility = View.VISIBLE
        displayStatusMessage.text = when (displayDiagnosticState) {
            ProjectionDiagnosticState.SCREEN_ON_RECOVERY ->
                getString(R.string.display_screen_on_recovery)
            ProjectionDiagnosticState.RENDERER_RECOVERY ->
                getString(R.string.display_renderer_recovery)
            else -> getString(R.string.display_loading)
        }
        retryButton.visibility = View.GONE
        updateCapabilityDiagnostic()
    }

    private fun hideDisplayStatus() {
        displayStatusPanel.visibility = View.GONE
        updateCapabilityDiagnostic()
    }

    private fun showDisplayError(
        message: String,
        diagnosticState: ProjectionDiagnosticState = ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE,
        allowRetry: Boolean = true
    ) {
        displayLoadFailed = true
        displayLoadInFlight = false
        displayPageHealthy = false
        displayDiagnosticState = diagnosticState
        clearTransientCapabilityWarning()
        displayStatusPanel.visibility = View.VISIBLE
        displayProgress.visibility = View.GONE
        displayStatusMessage.text = message
        retryButton.visibility = if (allowRetry) View.VISIBLE else View.GONE
        retryButton.isEnabled = allowRetry
        updateCapabilityDiagnostic()
    }

    private fun showSettingsError(message: String) {
        settingsStatusOverride = message
        settingsStatus.text = message
        settingsStatus.setTextColor(getColor(R.color.markerdeck_error))
    }

    private fun openSystemPermissionSettings() {
        val appPackageName = applicationContext.packageName
        val miuiEditorAvailable = isXiaomiFamilyDevice(Build.MANUFACTURER, Build.BRAND) &&
            packageManager.resolveActivity(
                buildPermissionSettingsIntent(miuiPermissionSettingsIntentSpec(appPackageName)),
                0
            ) != null
        val selectedSpec = selectPermissionSettingsIntent(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            miuiEditorAvailable = miuiEditorAvailable,
            packageName = appPackageName
        )
        val fallbackSpec = applicationDetailsIntentSpec(appPackageName)
        if (tryStartPermissionSettings(selectedSpec)) return
        if (selectedSpec.route != PermissionSettingsRoute.APPLICATION_DETAILS &&
            tryStartPermissionSettings(fallbackSpec)
        ) {
            return
        }
        showSettingsError(getString(R.string.system_settings_unavailable))
    }

    private fun buildPermissionSettingsIntent(spec: PermissionSettingsIntentSpec): Intent =
        Intent(spec.action).apply {
            if (spec.componentPackage != null && spec.componentClass != null) {
                setClassName(spec.componentPackage, spec.componentClass)
            }
            if (spec.dataUri != null) data = Uri.parse(spec.dataUri)
            if (spec.packageExtraName != null) {
                putExtra(spec.packageExtraName, spec.packageName)
            }
        }

    private fun tryStartPermissionSettings(spec: PermissionSettingsIntentSpec): Boolean {
        return try {
            startActivity(buildPermissionSettingsIntent(spec))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun showSettingsScreen() {
        val shouldClearWebView = webViewHasDisplayPage
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val shouldFinish = shouldFinishBeforeShowingSettings(
            projectionActive = displayActive,
            isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        )
        unregisterScreenStateReceiver()
        clearProjectionRuntimeStateForActivity()
        displayDeviceName = null
        displayOrigin = null
        mainFrameUrl = null
        if (displayWebViewUsable) {
            webView.stopLoading()
            if (shouldClearWebView) {
                cleanupNavigationPending = true
                webView.loadUrl(WEBVIEW_CLEANUP_URL)
            } else {
                cleanupNavigationPending = false
            }
            pauseWebViewIfNeeded()
        } else {
            cleanupNavigationPending = false
            webViewLifecycleResumed = false
        }
        clearDisplayWindowState()
        if (shouldFinish) {
            finish()
            return
        }
        displayScreen.visibility = View.GONE
        settingsScreen.visibility = View.VISIBLE
        connectButton.isEnabled = true
        serviceAddressInput.isEnabled = true
        deviceNameInput.isEnabled = true
        displayDiagnosticMessage.visibility = View.GONE
        settingsStatusOverride = null
        settingsStatus.setText(R.string.settings_not_connected_status)
        settingsStatus.setTextColor(getColor(R.color.markerdeck_muted))
        updateDiscoveryLifecycle()
    }

    private fun clearProjectionRuntimeStateForActivity() {
        val cleared = clearProjectionRuntimeState(
            ProjectionRuntimeState(
                projectionActive = displayActive,
                webViewHasPage = webViewHasDisplayPage,
                pageHealthy = displayPageHealthy,
                mainFrameFailed = displayMainFrameFailed,
                loadInFlight = displayLoadInFlight,
                windowStateApplied = displayWindowStateApplied,
                screenReceiverRegistered = screenReceiverRegistered,
                recoveryPending = false
            )
        )
        displayActive = cleared.projectionActive
        webViewHasDisplayPage = cleared.webViewHasPage
        displayPageHealthy = cleared.pageHealthy
        displayMainFrameFailed = cleared.mainFrameFailed
        displayLoadInFlight = cleared.loadInFlight
        displayWindowStateApplied = cleared.windowStateApplied
        screenReceiverRegistered = cleared.screenReceiverRegistered
        screenReceiverCapabilityWarning = false
        displayLoadFailed = false
        displayDiagnosticState = ProjectionDiagnosticState.IDLE_SETTINGS
        clearTransientCapabilityWarning()
    }

    private fun applyDisplayWindowState() {
        // This gate keeps the settings surface out of the keyguard window path.
        if (!shouldApplyDisplayWindowState(displayActive)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // minSdk is 26, so this legacy path is API 26 only.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        enterImmersiveMode(window)
        displayWindowStateApplied = true
    }

    private fun clearDisplayWindowState() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        @Suppress("DEPRECATION")
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        exitImmersiveMode(window)
        displayWindowStateApplied = false
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
            screenReceiverCapabilityWarning = false
            clearTransientCapabilityWarning()
        } catch (_: SecurityException) {
            recordScreenReceiverRegistrationFailure()
        } catch (_: IllegalArgumentException) {
            recordScreenReceiverRegistrationFailure()
        }
    }

    private fun recordScreenReceiverRegistrationFailure() {
        screenReceiverRegistered = false
        screenReceiverCapabilityWarning = true
        if (shouldShowScreenReceiverWarning(displayActive, screenReceiverRegistered)) {
            showTransientCapabilityWarning(getString(R.string.display_receiver_registration_failed))
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) {
            screenReceiverCapabilityWarning = false
            return
        }
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            // The framework may already have detached a receiver during Activity teardown.
        } finally {
            screenReceiverRegistered = false
            screenReceiverCapabilityWarning = false
        }
    }

    private fun restoreActiveDisplay(recoveryState: ProjectionDiagnosticState) {
        if (!displayActive) return
        // Reapply state to the existing display Activity only; this never launches an Activity
        // or dismisses authentication, and onResume will run the same path after screen-on.
        displayScreen.visibility = View.VISIBLE
        displayScreen.bringToFront()
        applyDisplayWindowState()
        resumeWebViewIfNeeded()
        displayDiagnosticState = recoveryState
        updateCapabilityDiagnostic()

        val pageState = DisplayPageState(
            projectionActive = displayActive,
            pageHealthy = displayPageHealthy && webViewHasDisplayPage && displayWebViewUsable,
            mainFrameFailed = displayMainFrameFailed || displayLoadFailed,
            loadInFlight = displayLoadInFlight
        )
        if (activityResumed && shouldReloadDisplayPage(pageState)) {
            loadDisplayPage(loadingState = recoveryState)
        } else if (displayPageHealthy && displayWebViewUsable) {
            displayDiagnosticState = ProjectionDiagnosticState.PROJECTION_ACTIVE
            hideDisplayStatus()
        }
    }

    private fun replaceWebViewAfterRendererExit(exitedWebView: WebView): Boolean {
        val parent = exitedWebView.parent as? ViewGroup
        val index = parent?.indexOfChild(exitedWebView)?.takeIf { it >= 0 } ?: webViewLayoutIndex
        val layoutParams = exitedWebView.layoutParams ?: webViewLayoutParams
        destroyWebViewAfterRendererExit(exitedWebView)
        return createReplacementWebView(
            parent = parent ?: displayScreen as ViewGroup,
            index = index,
            layoutParams = layoutParams
        )
    }

    private fun prepareWebViewForProjection(): Boolean {
        if (displayWebViewUsable) return true
        (webView.parent as? ViewGroup)?.removeView(webView)
        return createReplacementWebView(
            parent = displayScreen as ViewGroup,
            index = webViewLayoutIndex,
            layoutParams = webViewLayoutParams
        )
    }

    private fun destroyWebViewAfterRendererExit(exitedWebView: WebView) {
        if (exitedWebView === webView) {
            displayWebViewUsable = false
            webViewLifecycleResumed = false
            webViewHasDisplayPage = false
        }
        try {
            (exitedWebView.parent as? ViewGroup)?.removeView(exitedWebView)
        } catch (_: RuntimeException) {
            // The renderer-exit callback can race with hierarchy teardown.
        }
        try {
            exitedWebView.destroy()
        } catch (_: RuntimeException) {
            // The view is already unusable; do not let teardown mask recovery.
        }
    }

    private fun createReplacementWebView(
        parent: ViewGroup,
        index: Int,
        layoutParams: ViewGroup.LayoutParams?
    ): Boolean {
        var replacement: WebView? = null
        return try {
            val insertionIndex = index.coerceIn(0, parent.childCount)
            replacement = WebView(this).apply {
                id = R.id.displayWebView
                this.layoutParams = layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            parent.addView(replacement, insertionIndex)
            configureWebView(replacement)
            webView = replacement
            webViewLayoutIndex = insertionIndex
            webViewLayoutParams = replacement.layoutParams
            displayWebViewUsable = true
            webViewHasDisplayPage = false
            webViewLifecycleResumed = false
            true
        } catch (_: RuntimeException) {
            replacement?.let { failedReplacement ->
                try {
                    (failedReplacement.parent as? ViewGroup)?.removeView(failedReplacement)
                } catch (_: RuntimeException) {
                    // Best-effort cleanup for a partially attached replacement.
                }
                try {
                    failedReplacement.destroy()
                } catch (_: RuntimeException) {
                    // Nothing else can safely be done with a failed replacement.
                }
            }
            displayWebViewUsable = false
            false
        }
    }

    private fun updateCapabilityDiagnostic() {
        if (!displayActive) {
            displayDiagnosticMessage.visibility = View.GONE
            displayCapabilityWarningMessage.visibility = View.GONE
            return
        }
        if (!screenReceiverCapabilityWarning || !transientCapabilityWarningVisible) {
            displayCapabilityWarningMessage.visibility = View.GONE
        }
        val pageHealthy = displayPageHealthy && webViewHasDisplayPage && displayWebViewUsable
        if (pageHealthy) {
            displayDiagnosticMessage.visibility = View.GONE
            return
        }
        if (!shouldShowProjectionDiagnostic(
            projectionActive = displayActive,
            projectionState = displayDiagnosticState,
            pageHealthy = pageHealthy
        )) {
            displayDiagnosticMessage.visibility = View.GONE
            return
        }
        displayDiagnosticMessage.text = projectionDiagnosticWording(displayDiagnosticState)
        displayDiagnosticMessage.visibility = View.VISIBLE
    }

    private fun showTransientCapabilityWarning(message: String) {
        if (!displayActive || !screenReceiverCapabilityWarning) return
        clearTransientCapabilityWarning()
        transientCapabilityWarningToken += 1
        val token = transientCapabilityWarningToken
        transientCapabilityWarningVisible = true
        displayCapabilityWarningMessage.text = message
        displayCapabilityWarningMessage.visibility = View.VISIBLE
        val hideCallback = Runnable {
            if (token == transientCapabilityWarningToken && displayActive) {
                transientCapabilityWarningVisible = false
                transientCapabilityWarningHideCallback = null
                displayCapabilityWarningMessage.visibility = View.GONE
                updateCapabilityDiagnostic()
            }
        }
        transientCapabilityWarningHideCallback = hideCallback
        mainHandler.postDelayed(hideCallback, CAPABILITY_WARNING_DURATION_MS)
    }

    private fun clearTransientCapabilityWarning() {
        transientCapabilityWarningToken += 1
        transientCapabilityWarningVisible = false
        if (::displayCapabilityWarningMessage.isInitialized) {
            displayCapabilityWarningMessage.visibility = View.GONE
        }
        transientCapabilityWarningHideCallback?.let(mainHandler::removeCallbacks)
        transientCapabilityWarningHideCallback = null
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
        when (backNavigationDecision(displayActive)) {
            BackNavigationDecision.CONSUME -> Unit
            BackNavigationDecision.FINISH -> finish()
        }
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
        if (hasFocus && displayActive) {
            restoreActiveDisplay(ProjectionDiagnosticState.PROJECTION_ACTIVE)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (displayActive) {
            applyDisplayWindowState()
            updateCapabilityDiagnostic()
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Use OnBackInvokedDispatcher on newer Android versions.")
    override fun onBackPressed() {
        handleBackPressed()
    }

    override fun onPause() {
        activityResumed = false
        // Keep the active projection state through screen-off so the registered screen-on
        // receiver and onResume can restore the same display surface.
        if (displayActive) pauseWebViewIfNeeded()
        updateDiscoveryLifecycle()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        updateDiscoveryLifecycle()
    }

    override fun onStop() {
        activityStarted = false
        updateDiscoveryLifecycle()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (displayActive) {
            restoreActiveDisplay(ProjectionDiagnosticState.SCREEN_ON_RECOVERY)
        }
        updateDiscoveryLifecycle()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val savedProjection = validateSavedProjection(
            projectionActive = displayActive,
            serviceAddress = displayOrigin,
            deviceName = displayDeviceName
        )
        outState.putBoolean(STATE_PROJECTION_ACTIVE, savedProjection != null)
        savedProjection?.let { projection ->
            outState.putString(STATE_SERVICE_ADDRESS, projection.serviceAddress)
            outState.putString(STATE_DEVICE_NAME, projection.deviceName)
        }
    }

    override fun onDestroy() {
        if (::discoveryScanner.isInitialized) discoveryScanner.stop()
        activityScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        unregisterScreenStateReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        pauseWebViewIfNeeded()
        activityResumed = false
        clearDisplayWindowState()
        displayActive = false
        if (displayWebViewUsable) {
            webView.stopLoading()
            webView.destroy()
            displayWebViewUsable = false
        }
        clearTransientCapabilityWarning()
        super.onDestroy()
    }
}

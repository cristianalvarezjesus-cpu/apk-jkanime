package com.ejemplo.visor

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JsPromptResult
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var contentRoot: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var adBlocker: AdBlocker
    private lateinit var injectJs: String

    private val prefs by lazy { getSharedPreferences("visor", Context.MODE_PRIVATE) }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pageImmersive = false
    private var docStartInstalled = false
    private var lastFailedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge + soporte para notch
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipeRefresh)
        progress = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        contentRoot = findViewById(R.id.contentRoot)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        adBlocker = AdBlocker(this)
        adBlocker.strictIframes = prefs.getBoolean(PREF_STRICT, false)
        injectJs = assets.open("inject.js").bufferedReader().use { it.readText() }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect solo en builds debug
        }

        setupWebView()
        setupUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chromeClient.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        webView.loadUrl(HOME_URL)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = false

        // Sin ventanas emergentes
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false

        // Zoom con pellizco (sin controles en pantalla)
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = prefs.getInt(PREF_TEXT_ZOOM, 100)

        // User-Agent de Chrome móvil, derivado del WebView instalado (no queda desactualizado)
        s.userAgentString = s.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        applyDarkMode(prefs.getBoolean(PREF_DARK, false))

        // Script anti-anuncios al inicio de cada documento, incluidos los iframes
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, injectJs, setOf("*"))
            docStartInstalled = true
        }

        // Descargas deshabilitadas
        webView.setDownloadListener { _, _, _, _, _ -> }

        webView.webViewClient = webClient
        webView.webChromeClient = chromeClient
    }

    private val webClient = object : WebViewClient() {

        /** Cancela las peticiones a dominios de publicidad. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host
            if (adBlocker.isBlockedHost(host)) return emptyResponse()

            if (adBlocker.strictIframes && !request.isForMainFrame && isFrameRequest(request) &&
                !adBlocker.isSiteHost(host) && !adBlocker.isPlayerHost(host)
            ) {
                return emptyResponse()
            }
            return null
        }

        /** Solo se navega dentro del sitio. Todo lo demás (otros dominios, intent://, market://...) se bloquea. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about") return false
            if (scheme != "http" && scheme != "https") return true

            return if (request.isForMainFrame) {
                !adBlocker.isSiteHost(uri.host)
            } else {
                adBlocker.isBlockedHost(uri.host)
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            errorView.visibility = View.GONE
            progress.visibility = View.VISIBLE
            if (!docStartInstalled) view.evaluateJavascript(injectJs, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            swipe.isRefreshing = false
            progress.visibility = View.GONE
            view.evaluateJavascript(injectJs, null) // refuerzo; el script se protege contra doble ejecución
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            lastFailedUrl = request.url.toString()
            errorText.text = if (isOnline()) "No se pudo cargar la página." else "Sin conexión a internet."
            errorView.visibility = View.VISIBLE
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recreate()
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        // Ventanas emergentes: nunca se crean
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
        ): Boolean = false

        // Diálogos JavaScript: se cancelan
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel(); return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel(); return true
        }

        override fun onJsPrompt(
            view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult
        ): Boolean {
            result.cancel(); return true
        }

        // Video a pantalla completa
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            fullscreenContainer.visibility = View.VISIBLE
            contentRoot.visibility = View.INVISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setImmersive(true)
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
            contentRoot.visibility = View.VISIBLE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setImmersive(pageImmersive)
        }

        // Evita un NullPointerException en algunas versiones de WebView
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ UI

    private fun setupUi() {
        swipe.setColorSchemeResources(R.color.visor_accent)
        swipe.setProgressBackgroundColorSchemeResource(R.color.visor_surface)
        swipe.setOnRefreshListener { webView.reload() }
        // Solo permite "tirar para recargar" cuando la página está arriba del todo
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            errorView.visibility = View.GONE
            val target = lastFailedUrl
            if (target.isNullOrEmpty()) webView.loadUrl(HOME_URL) else webView.loadUrl(target)
        }

        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        m.add(0, MENU_HOME, 0, "Inicio")
        m.add(0, MENU_RELOAD, 1, "Recargar")
        m.add(0, MENU_TEXT_UP, 2, "Texto más grande")
        m.add(0, MENU_TEXT_DOWN, 3, "Texto más pequeño")
        m.add(0, MENU_ZOOM_IN, 4, "Acercar (zoom +)")
        m.add(0, MENU_ZOOM_OUT, 5, "Alejar (zoom −)")
        m.add(0, MENU_FULLSCREEN, 6, if (pageImmersive) "Salir de pantalla completa" else "Pantalla completa")
        m.add(0, MENU_DARK, 7, "Modo oscuro de la página").apply {
            isCheckable = true
            isChecked = prefs.getBoolean(PREF_DARK, false)
        }
        m.add(0, MENU_STRICT, 8, "Bloqueo estricto de iframes").apply {
            isCheckable = true
            isChecked = adBlocker.strictIframes
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HOME -> webView.loadUrl(HOME_URL)
                MENU_RELOAD -> webView.reload()
                MENU_TEXT_UP -> changeTextZoom(+10)
                MENU_TEXT_DOWN -> changeTextZoom(-10)
                MENU_ZOOM_IN -> webView.zoomIn()
                MENU_ZOOM_OUT -> webView.zoomOut()
                MENU_FULLSCREEN -> {
                    pageImmersive = !pageImmersive
                    setImmersive(pageImmersive)
                }
                MENU_DARK -> {
                    val enabled = !item.isChecked
                    prefs.edit().putBoolean(PREF_DARK, enabled).apply()
                    applyDarkMode(enabled)
                }
                MENU_STRICT -> {
                    val enabled = !item.isChecked
                    adBlocker.strictIframes = enabled
                    prefs.edit().putBoolean(PREF_STRICT, enabled).apply()
                    webView.reload()
                }
            }
            true
        }
        popup.show()
    }

    private fun changeTextZoom(delta: Int) {
        val s = webView.settings
        s.textZoom = (s.textZoom + delta).coerceIn(70, 200)
        prefs.edit().putInt(PREF_TEXT_ZOOM, s.textZoom).apply()
    }

    private fun applyDarkMode(enabled: Boolean) {
        try {
            val settings = webView.settings
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(
                    settings,
                    if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
        } catch (_: Throwable) {
            // Si el WebView instalado no lo soporta, se ignora.
        }
    }

    private fun setImmersive(enable: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enable) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ Utilidades

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun isFrameRequest(request: WebResourceRequest): Boolean {
        val h = request.requestHeaders
        return h["Sec-Fetch-Dest"] == "iframe" || h["Accept"]?.contains("text/html") == true
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------------ Ciclo de vida

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://jkanime.net/"

        private const val PREF_TEXT_ZOOM = "text_zoom"
        private const val PREF_DARK = "dark"
        private const val PREF_STRICT = "strict_iframes"

        private const val MENU_HOME = 1
        private const val MENU_RELOAD = 2
        private const val MENU_TEXT_UP = 3
        private const val MENU_TEXT_DOWN = 4
        private const val MENU_ZOOM_IN = 5
        private const val MENU_ZOOM_OUT = 6
        private const val MENU_FULLSCREEN = 7
        private const val MENU_DARK = 8
        private const val MENU_STRICT = 9
    }
}

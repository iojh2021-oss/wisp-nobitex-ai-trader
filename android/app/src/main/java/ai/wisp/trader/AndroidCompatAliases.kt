package ai.wisp.trader

/**
 * Local aliases keep MainActivity source compatible without adding a separate
 * browser/WebView UI dependency. ChatGPT authentication is intentionally
 * delegated to the real device browser/Chrome Custom Tab.
 */
typealias WebView = android.webkit.WebView
typealias WebSettings = android.webkit.WebSettings
typealias CookieManager = android.webkit.CookieManager
typealias SuppressLint = android.annotation.SuppressLint

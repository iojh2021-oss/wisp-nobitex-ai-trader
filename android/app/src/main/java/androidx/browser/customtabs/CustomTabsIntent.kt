package androidx.browser.customtabs

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Minimal compatibility implementation used by the standalone app.
 * It launches the URL through Android's external browser resolver, allowing
 * Chrome (when installed) to own authentication, cookies and ChatGPT sessions.
 */
class CustomTabsIntent private constructor(
    private val showTitle: Boolean,
    private val shareState: Int
) {
    fun launchUrl(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (showTitle) putExtra("androidx.browser.customtabs.extra.TITLE_VISIBLE", true)
            putExtra("androidx.browser.customtabs.extra.SHARE_STATE", shareState)
        }
        context.startActivity(intent)
    }

    class Builder {
        private var showTitle = false
        private var shareState = SHARE_STATE_DEFAULT

        fun setShowTitle(value: Boolean): Builder {
            showTitle = value
            return this
        }

        fun setShareState(value: Int): Builder {
            shareState = value
            return this
        }

        fun build(): CustomTabsIntent = CustomTabsIntent(showTitle, shareState)
    }

    companion object {
        const val SHARE_STATE_DEFAULT = 0
        const val SHARE_STATE_ON = 1
        const val SHARE_STATE_OFF = 2
    }
}

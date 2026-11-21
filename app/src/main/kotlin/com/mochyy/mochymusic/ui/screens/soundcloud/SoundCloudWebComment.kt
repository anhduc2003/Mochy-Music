package com.mochyy.mochymusic.ui.screens.soundcloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Posts a comment from inside a WebView, the way the web player does. api-v2 sits behind
 * DataDome, which refuses the app's own HTTP client on some networks; a WebView is a browser,
 * carries the session cookies the sign-in already stored, and its fetch is what the site allows.
 */
class SoundCloudWebComment(private val context: Context) {

    suspend fun post(
        trackId: String,
        body: String,
        session: SoundCloudModule.BrowserSession,
    ): Result<String> = withTimeout(30_000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context)
                val parent = activityOf(context)?.findViewById<ViewGroup>(android.R.id.content)
                parent?.addView(web, ViewGroup.LayoutParams(1, 1))
                var answered = false
                fun finish(result: Result<String>) {
                    if (answered) return
                    answered = true
                    if (continuation.isActive) continuation.resume(result)
                    parent?.removeView(web)
                    web.destroy()
                }
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                web.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun done(value: String) {
                        // the bridge runs off the UI thread; the view and the continuation don't
                        web.post {
                            val parsed = runCatching { JSONObject(value) }.getOrNull()
                            val status = parsed?.optInt("status", 0) ?: 0
                            val text = parsed?.optString("body").orEmpty()
                            if (status in 200..299) {
                                finish(Result.success(text))
                            } else {
                                val detail = text.trim().take(140)
                                finish(
                                    Result.failure(
                                        IllegalStateException(
                                            "SoundCloud từ chối bình luận ($status). " +
                                                detail.ifBlank { "Thử lại sau." },
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }, "MochyComment")
                var loaded = false
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (loaded) return
                        loaded = true
                        view.evaluateJavascript(script(trackId, body, session), null)
                    }
                }
                continuation.invokeOnCancellation {
                    parent?.removeView(web)
                    web.destroy()
                }
                web.loadUrl("https://soundcloud.com/")
            }
        }
    }

    private fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun script(trackId: String, body: String, session: SoundCloudModule.BrowserSession): String {
        val id = JSONObject.quote(trackId)
        val text = JSONObject.quote(body)
        val client = JSONObject.quote(session.clientId)
        val auth = JSONObject.quote("OAuth ${session.token}")
        return """
            (function() {
              var url = 'https://api-v2.soundcloud.com/tracks/' + encodeURIComponent($id) +
                '/comments?client_id=' + encodeURIComponent($client) + '&body=' + encodeURIComponent($text);
              fetch(url, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Authorization': $auth, 'Accept': 'application/json' }
              }).then(function(r) {
                return r.text().then(function(t) { return { status: r.status, body: t }; });
              }).then(function(v) {
                MochyComment.done(JSON.stringify(v));
              }).catch(function(e) {
                MochyComment.done(JSON.stringify({ status: 0, body: String(e) }));
              });
            })();
        """.trimIndent()
    }
}

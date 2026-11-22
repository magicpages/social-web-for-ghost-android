package co.magicpages.socialweb.data.translation

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import co.magicpages.socialweb.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs Bergamot, Firefox's translation engine (WebAssembly), in a hidden WebView: the system's own
 * browser engine, so the app ships no native code for it. The WebView loads nothing from the network;
 * its page and the files it runs on are served by the app. The engine stays loaded while translations
 * keep coming and is closed after a minute without one, which frees its memory.
 */
@Singleton
class TranslationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: TranslationFiles,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val calls = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger()

    // Main thread only.
    private var webView: WebView? = null
    private var loaded: List<InstalledModel>? = null
    private var pageLoaded: CompletableDeferred<Unit>? = null
    private var closing: Job? = null

    /** What the page may fetch: its path, and the file it is. Read on the WebView's own thread. */
    @Volatile
    private var served: Map<String, File> = emptyMap()

    /**
     * Translates [texts] (plain text) with [models]: one, or two through English. Blank texts come back empty.
     *
     * @throws IOException when the engine fails, or doesn't answer in time
     */
    suspend fun translate(models: List<InstalledModel>, texts: List<String>): List<String> = mutex.withLock {
        withContext(Dispatchers.Main) {
            closing?.cancel()
            try {
                if (loaded != models) start(models)
                val answer = call(TranslateTimeoutMillis) { id -> "translate($id, ${JSONArray(texts)})" }
                withContext(Dispatchers.Default) {
                    val array = JSONArray(answer)
                    List(array.length()) { array.getString(it) }
                }
            } catch (e: IOException) {
                close()
                throw e
            } finally {
                closing = scope.launch(Dispatchers.Main) {
                    delay(IdleMillis)
                    mutex.withLock { close() }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun start(models: List<InstalledModel>) {
        close()
        val specs = JSONArray()
        val paths = mutableMapOf("/files/engine.wasm" to files.engine)
        models.forEachIndexed { index, model ->
            val modelFiles = JSONObject()
            for ((type, file) in model.files) {
                val path = "/files/$index/$type"
                paths[path] = file
                modelFiles.put(type, path)
            }
            specs.put(JSONObject().put("from", model.from).put("to", model.to).put("intgemm8", model.intgemm8).put("files", modelFiles))
        }
        served = paths
        val page = CompletableDeferred<Unit>()
        pageLoaded = page
        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            addJavascriptInterface(Bridge(), "Bridge")
            webViewClient = Client()
            loadUrl("https://$Host/engine.html")
        }
        withTimeoutOrNull(PageTimeoutMillis) { page.await() } ?: throw IOException("The translation engine didn't load")
        call(LoadTimeoutMillis) { id -> "load($id, $specs)" }
        loaded = models
    }

    /** Runs the script [call] makes from its id, and waits for the page's answer to that id. */
    private suspend fun call(timeoutMillis: Long, call: (Int) -> String): String {
        val view = webView ?: throw IOException("The translation engine is closed")
        val id = nextId.incrementAndGet()
        val answer = CompletableDeferred<String>()
        calls[id] = answer
        try {
            view.evaluateJavascript(call(id), null)
            return withTimeoutOrNull(timeoutMillis) { answer.await() } ?: throw IOException("The translation engine didn't answer")
        } finally {
            calls.remove(id)
        }
    }

    private fun close() {
        webView?.destroy()
        webView = null
        loaded = null
        served = emptyMap()
        pageLoaded?.cancel()
        pageLoaded = null
        calls.values.forEach { it.completeExceptionally(IOException("The translation engine closed")) }
    }

    /** The page's answers, which arrive on the WebView's own thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun result(id: Int, json: String) {
            calls[id]?.complete(json)
        }

        @JavascriptInterface
        fun error(id: Int, message: String) {
            calls[id]?.completeExceptionally(IOException("Translation engine: $message"))
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (view === webView) pageLoaded?.complete(Unit)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            val path = url.path.orEmpty()
            if (url.scheme != "https" || url.host != Host) return notFound()
            served[path]?.let { file ->
                return try {
                    WebResourceResponse("application/octet-stream", null, file.inputStream())
                } catch (_: IOException) {
                    notFound()
                }
            }
            val mimeType = PageFiles[path] ?: return notFound()
            return WebResourceResponse(mimeType, "utf-8", context.assets.open("translator$path"))
        }

        // The engine's process can run out of memory on a small phone: the app carries on without it.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) close() else view.destroy()
            return true
        }
    }

    private companion object {
        /** Never resolves: whatever the app doesn't serve goes nowhere. */
        const val Host = "translator.invalid"

        /** The page's own files in assets/translator; the loader is Firefox's, for the engine [TranslationFiles] downloads. */
        val PageFiles = mapOf(
            "/engine.html" to "text/html",
            "/engine.js" to "text/javascript",
            "/bergamot-translator.js" to "text/javascript",
        )

        const val PageTimeoutMillis = 10_000L

        /** Reading tens of megabytes of model into the engine, on a slow phone. */
        const val LoadTimeoutMillis = 60_000L

        /** A long article on a slow phone. */
        const val TranslateTimeoutMillis = 180_000L

        const val IdleMillis = 60_000L

        fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

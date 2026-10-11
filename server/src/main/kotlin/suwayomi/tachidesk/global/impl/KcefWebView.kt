package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.callback.CefJSDialogCallback
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefScreenInfo
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.input.CefTouchEvent
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import org.cef.network.CefRequest
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.webkit.CefHelper
import xyz.nulldev.androidcompat.webkit.dispose
import xyz.nulldev.androidcompat.webkit.disposeWithJsHandler
import xyz.nulldev.androidcompat.webkit.evaluateJavaScript
import java.awt.Component
import java.awt.Cursor
import java.awt.HeadlessException
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.swing.JPanel

class KcefWebView {
    private val logger = KotlinLogging.logger {}
    private val renderHandler = RenderHandler()
    private var kcefClient: CefClient? = null
    private var browser: CefBrowser? = null
    private var width = 1000
    private var height = 1000

    private val frameRateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isBoosted = AtomicBoolean(false)

    @Volatile
    private var lastInputNanos = 0L

    @Volatile
    private var isHidden = false

    // pixels per css pixel the page is rendered with, so text is sharp on high density screens
    @Volatile
    private var deviceScale = 1.0

    // video stream of the page; while it is connected the jpeg frames are not produced
    @Volatile
    private var streamer: WebRtcStreamer? = null

    companion object {
        private val networkHelper: NetworkHelper by injectLazy()

        // 0.9 keeps text/edges sharp (lower values get grainy), at a fraction of the size/encoding time of png
        private const val JPEG_QUALITY_SETTLED = 0.9f

        // used while the page keeps repainting (scrolling, animations), where speed matters more than sharpness
        private const val JPEG_QUALITY_MOVING = 0.75f

        // painting more often than this counts as "moving"
        private const val MOVING_PAINT_INTERVAL_NANOS = 50_000_000L

        // pause after the last paint, before resending the last frame in full quality
        private const val SETTLE_DELAY_MS = 150L

        // pause after the last paint, before a sharp picture replaces the video (see "scheduleStill")
        private const val STILL_DELAY_MS = 300L

        // frames sent, but not yet displayed by the client, before waiting for the client (prevents frames from
        // piling up in the websocket queue when the client is slower than the server)
        private const val MAX_FRAMES_IN_FLIGHT = 2
        private const val FRAME_ACK_TIMEOUT_MS = 500L

        // CEF only repaints on change, but animated pages (ads, carousels) repaint at the full rate, which costs cpu for
        // rendering + encoding. So: 30fps while idle, 60fps for a moment after any user input (scrolling has to look
        // smooth), and 1fps while the client's tab is hidden.
        private const val FRAME_RATE_IDLE = 30
        private const val FRAME_RATE_ACTIVE = 60
        private const val FRAME_RATE_HIDDEN = 1
        private const val ACTIVE_BOOST_MS = 1500L

        // rendering at the screen's density is sharper, but every frame has to be converted/encoded/sent: stay below
        // roughly this many pixels per frame (about 1080p), and never above 2x
        private const val MAX_FRAME_PIXELS = 2_500_000.0
        private const val MAX_DEVICE_SCALE = 2.0

        fun Cookie.toCefCookie(): CefCookie {
            val cookie = this
            return CefCookie(
                cookie.name,
                cookie.value,
                if (cookie.hostOnly) {
                    cookie.domain
                } else {
                    "." + cookie.domain
                },
                cookie.path,
                cookie.secure,
                cookie.httpOnly,
                Date(),
                null,
                cookie.expiresAt < 253402300799999L, // okhttp3.internal.http.MAX_DATE
                Date(cookie.expiresAt),
            )
        }
    }

    @Serializable
    sealed class Event

    @Serializable
    @SerialName("consoleMessage")
    private data class ConsoleEvent(
        val severity: Int,
        val message: String,
        val source: String,
        val line: Int,
    ) : Event()

    @Serializable
    @SerialName("addressChange")
    private data class AddressEvent(
        val url: String,
        val title: String,
    ) : Event()

    @Serializable
    @SerialName("statusChange")
    private data class StatusEvent(
        val message: String,
    ) : Event()

    @Serializable
    @SerialName("load")
    private data class LoadEvent(
        val url: String,
        val title: String,
        val status: Int = 0,
        val error: String? = null,
    ) : Event()

    @Serializable
    @SerialName("copy")
    private data class CopyEvent(
        val content: String,
    ) : Event()

    @Serializable
    @SerialName("cursor")
    private data class CursorEvent(
        val cursor: String,
    ) : Event()

    private inner class DisplayHandler : CefDisplayHandlerAdapter() {
        // Windowless rendering, so the cursor has to be shown by the client: map the (AWT) cursor type to a css cursor
        override fun onCursorChange(
            browser: CefBrowser,
            cursorType: Int,
        ): Boolean {
            val cursor =
                when (cursorType) {
                    Cursor.HAND_CURSOR -> "pointer"
                    Cursor.TEXT_CURSOR -> "text"
                    Cursor.CROSSHAIR_CURSOR -> "crosshair"
                    Cursor.WAIT_CURSOR -> "wait"
                    Cursor.MOVE_CURSOR -> "move"
                    Cursor.N_RESIZE_CURSOR, Cursor.S_RESIZE_CURSOR -> "ns-resize"
                    Cursor.E_RESIZE_CURSOR, Cursor.W_RESIZE_CURSOR -> "ew-resize"
                    Cursor.NE_RESIZE_CURSOR, Cursor.SW_RESIZE_CURSOR -> "nesw-resize"
                    Cursor.NW_RESIZE_CURSOR, Cursor.SE_RESIZE_CURSOR -> "nwse-resize"
                    else -> "default"
                }
            WebView.notifyAllClients(Json.encodeToString<Event>(CursorEvent(cursor)))
            return true
        }

        override fun onConsoleMessage(
            browser: CefBrowser,
            level: CefSettings.LogSeverity,
            message: String,
            source: String,
            line: Int,
        ): Boolean {
            WebView.notifyAllClients(
                Json.encodeToString<Event>(
                    ConsoleEvent(level.ordinal, message, source, line),
                ),
            )
            logger.trace { "$source:$line: $message" }
            return true
        }

        override fun onAddressChange(
            browser: CefBrowser,
            frame: CefFrame,
            url: String,
        ) {
            if (!frame.isMain) return
            this@KcefWebView.browser!!.evaluateJavaScript("return document.title") {
                WebView.notifyAllClients(
                    Json.encodeToString<Event>(
                        AddressEvent(url, it ?: ""),
                    ),
                )
            }
            flush()
        }

        override fun onStatusMessage(
            browser: CefBrowser,
            value: String,
        ) {
            WebView.notifyAllClients(
                Json.encodeToString<Event>(
                    StatusEvent(value),
                ),
            )
        }
    }

    private inner class LoadHandler : CefLoadHandlerAdapter() {
        override fun onLoadEnd(
            browser: CefBrowser,
            frame: CefFrame,
            httpStatusCode: Int,
        ) {
            logger.trace { "Load event: ${frame.name} - ${frame.url}" }
            if (httpStatusCode > 0 && frame.isMain) handleLoad(frame.url, httpStatusCode)
            flush()
        }

        override fun onLoadError(
            browser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String,
        ) {
            if (frame.isMain) handleLoad(failedUrl, 0, errorText)
        }
    }

    /**
     * The WebView has no tabs or windows, so a page cannot open a new one. Popups are mostly ads: a link to the same site
     * (e.g. "target=_blank") is opened in the page itself, everything else is dropped.
     */
    private inner class LifeSpanHandler : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(
            browser: CefBrowser,
            frame: CefFrame,
            targetUrl: String,
            targetFrameName: String,
        ): Boolean {
            val currentHost = hostOf(browser.url)
            val isSameSite = currentHost != null && hostOf(targetUrl) == currentHost
            if (isSameSite && !AdBlocker.shouldBlock(targetUrl)) {
                browser.loadURL(targetUrl)
            } else {
                logger.debug { "Blocked popup: $targetUrl" }
            }
            return true
        }

        private fun hostOf(url: String?): String? =
            try {
                java.net.URI(url ?: return null).host?.lowercase()?.removePrefix("www.")
            } catch (_: Exception) {
                null
            }
    }

    /**
     * Nobody is there to answer a JavaScript alert/confirm/prompt of the page, and ad pages use them for fake warnings.
     * They are suppressed (the page is told the dialog was dismissed) and "leave this page?" questions are accepted.
     */
    private inner class JsDialogHandler : CefJSDialogHandlerAdapter() {
        override fun onJSDialog(
            browser: CefBrowser,
            originUrl: String,
            dialogType: CefJSDialogHandler.JSDialogType,
            messageText: String,
            defaultPromptText: String,
            callback: CefJSDialogCallback,
            suppressMessage: BoolRef,
        ): Boolean {
            logger.debug { "Suppressed a $dialogType of $originUrl: $messageText" }
            suppressMessage.set(true)
            return false
        }

        override fun onBeforeUnloadDialog(
            browser: CefBrowser,
            messageText: String,
            isReload: Boolean,
            callback: CefJSDialogCallback,
        ): Boolean {
            callback.Continue(true, "")
            return true
        }
    }

    private inner class ResourceRequestHandler : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): Boolean {
            // the page itself is only blocked when it is sent to a popup ad network (an ad script redirecting the page)
            val isMainFrame = request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME
            if (AdBlocker.shouldBlock(request.url, isMainFrame)) {
                if (isMainFrame) logger.info { "Blocked a redirect to an ad network: ${request.url}" }
                logger.trace { "Blocked ad/tracker: ${request.url}" }
                return true
            }

            val ua = System.getProperty("http.agent")
            request.setHeaderByName("user-agent", ua, true)
            logger.trace { "Using user-agent $ua" }
            return false
        }
    }

    private inner class RequestHandler : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler? {
            logger.trace { "Load resource: ${frame.name} - ${request.url}" }
            return ResourceRequestHandler()
        }
    }

    // Loosely based on
    // https://github.com/JetBrains/jcef/blob/main/java/org/cef/browser/CefBrowserOsr.java
    private inner class RenderHandler : CefRenderHandlerAdapter() {
        // The newest frame CEF painted. Only the latest frame is ever sent, so slow encoding/transmitting skips
        // intermediate frames instead of stalling the CEF render thread or congesting the websocket.
        private val lock = Any()
        private var latestImage: BufferedImage? = null
        private var hasNewFrame = false
        private var lastPaintNanos = 0L
        private var paintIntervalNanos = Long.MAX_VALUE


        private var encodeImage: BufferedImage? = null
        private var videoDirty = false
        private var videoBuffer: ByteBuffer? = null
        private val isVideoPushing = AtomicBoolean(false)
        private val isEncoding = AtomicBoolean(false)
        private val framesInFlight = AtomicInteger(0)
        private val encodeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        override fun getViewRect(browser: CefBrowser): Rectangle = Rectangle(0, 0, width, height)

        override fun getScreenInfo(
            browser: CefBrowser,
            screenInfo: CefScreenInfo,
        ): Boolean {
            screenInfo.device_scale_factor = deviceScale
            screenInfo.depth = 24
            screenInfo.depth_per_component = 8
            screenInfo.is_monochrome = false
            screenInfo.x = 0
            screenInfo.y = 0
            screenInfo.width = width
            screenInfo.height = height
            screenInfo.available_x = 0
            screenInfo.available_y = 0
            screenInfo.available_width = width
            screenInfo.available_height = height
            return true
        }

        override fun onPaint(
            browser: CefBrowser,
            popup: Boolean,
            dirtyRects: Array<Rectangle>,
            buffer: ByteBuffer,
            width: Int,
            height: Int,
        ) {
            // nobody is looking: skip copying and encoding the frame. (Not skipping on an empty dirty area: CEF does not
            // always fill it in, e.g. after navigating, and dropping those frames froze the picture.)
            if (!WebView.hasClients || isHidden) return

            val videoStream = streamer?.takeIf { it.isConnected }

            synchronized(lock) {
                var image = latestImage
                if (image == null || image.width != width || image.height != height) {
                    // opaque RGB, since jpeg has no alpha channel
                    image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                    latestImage = image
                }

                val dst = (image.raster.dataBuffer as DataBufferInt).data
                buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(dst)
                hasNewFrame = true

                val now = System.nanoTime()
                paintIntervalNanos = now - lastPaintNanos
                lastPaintNanos = now
            }

            if (videoStream != null) {
                // the page is shown as video: no jpeg needed. Converting and handing the frame to the encoder happens off
                // this thread, since blocking CEF's paint thread is what lowers the page's frame rate.
                synchronized(lock) { videoDirty = true }
                scheduleVideoPush()
                scheduleStill()
                return
            }

            scheduleEncode()
        }

        private var stillJob: Job? = null
        private val isStillShown = AtomicBoolean(false)

        /**
         * The video encoder smears fine text (it is made for camera video). Once the page stops changing, one sharp picture
         * is sent, which the client shows over the video until the page changes again.
         */
        private fun scheduleStill() {
            stillJob?.cancel()
            if (isStillShown.compareAndSet(true, false)) {
                WebView.notifyAllClients("""{"type":"stillEnd"}""")
            }

            stillJob =
                encodeScope.launch {
                    delay(STILL_DELAY_MS)
                    val frame =
                        synchronized(lock) {
                            val latest = latestImage ?: return@synchronized null
                            BufferedImage(latest.width, latest.height, BufferedImage.TYPE_INT_RGB).also { copy ->
                                val src = (latest.raster.dataBuffer as DataBufferInt).data
                                System.arraycopy(src, 0, (copy.raster.dataBuffer as DataBufferInt).data, 0, src.size)
                            }
                        } ?: return@launch
                    if (streamer?.isConnected != true) return@launch

                    val jpeg = encodeJpeg(frame, JPEG_QUALITY_SETTLED)
                    isStillShown.set(true)
                    framesInFlight.incrementAndGet()
                    WebView.notifyAllClientsBinary(jpeg)
                }
        }

        /** Sends the last painted frame again, e.g. right after the video connected, or to keep a still page alive. */
        fun pushLatestFrame() {
            synchronized(lock) { videoDirty = latestImage != null }
            scheduleVideoPush()
        }

        private fun scheduleVideoPush() {
            if (!isVideoPushing.compareAndSet(false, true)) {
                return
            }

            encodeScope.launch {
                try {
                    while (true) {
                        val stream = streamer?.takeIf { it.isConnected } ?: break
                        val size =
                            synchronized(lock) {
                                val image = latestImage
                                if (!videoDirty || image == null) {
                                    return@synchronized null
                                }
                                videoDirty = false

                                val pixels = (image.raster.dataBuffer as DataBufferInt).data
                                var buf = videoBuffer
                                if (buf == null || buf.capacity() < pixels.size * 4) {
                                    buf = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                                    videoBuffer = buf
                                }
                                buf.clear()
                                buf.asIntBuffer().put(pixels)
                                image.width to image.height
                            } ?: break
                        stream.pushFrame(videoBuffer!!, size.first, size.second)
                    }
                } catch (t: Throwable) {
                    logger.debug(t) { "Failed to push webview video frame" }
                } finally {
                    isVideoPushing.set(false)
                }

                if (synchronized(lock) { videoDirty }) {
                    scheduleVideoPush()
                }
            }
        }

        fun millisSinceLastPaint(): Long = (System.nanoTime() - lastPaintNanos) / 1_000_000L

        /** The video stream ended: continue with jpeg frames, starting with the current one. */
        fun resumeJpegStream() {
            synchronized(lock) { hasNewFrame = true }
            scheduleEncode()
        }

        private fun scheduleEncode() {
            if (!isEncoding.compareAndSet(false, true)) {
                return
            }

            encodeScope.launch {
                try {
                    var wasMoving = false
                    while (WebView.hasClients) {
                        awaitFrameSlot()

                        val frame = takeLatestFrame()
                        if (frame == null) {
                            if (!wasMoving) break

                            // The page stopped changing, while the last frame was sent in low quality. After a short
                            // pause (in case it continues), the same frame is sent again in full quality.
                            delay(SETTLE_DELAY_MS)
                            wasMoving = false
                            synchronized(lock) { hasNewFrame = true }
                            continue
                        }

                        val isMoving = synchronized(lock) { isPageMoving() }
                        wasMoving = isMoving
                        val quality = if (isMoving) JPEG_QUALITY_MOVING else JPEG_QUALITY_SETTLED
                        framesInFlight.incrementAndGet()
                        WebView.notifyAllClientsBinary(encodeJpeg(frame, quality))
                    }
                } catch (t: Throwable) {
                    logger.debug(t) { "Failed to encode/send webview frame" }
                } finally {
                    isEncoding.set(false)
                }

                // a frame could have been painted right after the last check
                if (synchronized(lock) { hasNewFrame }) {
                    scheduleEncode()
                }
            }
        }

        // must be called while holding the lock
        private fun isPageMoving(): Boolean =
            paintIntervalNanos < MOVING_PAINT_INTERVAL_NANOS &&
                System.nanoTime() - lastPaintNanos < SETTLE_DELAY_MS * 1_000_000L

        // called when the client displayed (or dropped) a frame
        fun onFrameAck() {
            framesInFlight.updateAndGet { maxOf(it - 1, 0) }
        }

        // waits until the client caught up, but not forever, in case it does not acknowledge frames (e.g. outdated page)
        private suspend fun awaitFrameSlot() {
            var waitedMs = 0L
            while (framesInFlight.get() >= MAX_FRAMES_IN_FLIGHT && WebView.hasClients) {
                if (waitedMs >= FRAME_ACK_TIMEOUT_MS) {
                    framesInFlight.set(0)
                    return
                }
                delay(2)
                waitedMs += 2
            }
        }

        // copies the latest frame, so that CEF can paint the next one while this one is encoded
        private fun takeLatestFrame(): BufferedImage? =
            synchronized(lock) {
                val latest = latestImage
                if (!hasNewFrame || latest == null) {
                    return@synchronized null
                }
                hasNewFrame = false

                var copy = encodeImage
                if (copy == null || copy.width != latest.width || copy.height != latest.height) {
                    copy = BufferedImage(latest.width, latest.height, BufferedImage.TYPE_INT_RGB)
                    encodeImage = copy
                }

                val src = (latest.raster.dataBuffer as DataBufferInt).data
                System.arraycopy(src, 0, (copy.raster.dataBuffer as DataBufferInt).data, 0, src.size)
                copy
            }

        private fun encodeJpeg(
            image: BufferedImage,
            quality: Float,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                ImageIO.createImageOutputStream(out).use { imageOut ->
                    writer.output = imageOut
                    val params =
                        writer.defaultWriteParam.apply {
                            compressionMode = ImageWriteParam.MODE_EXPLICIT
                            compressionQuality = quality
                        }
                    val metadata =
                        writer
                            .getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), params)
                            .also { disableChromaSubsampling(it) }
                    writer.write(null, IIOImage(image, null, metadata), params)
                }
            } finally {
                writer.dispose()
            }
            return out.toByteArray()
        }

        // By default, jpeg stores the color channels in half the resolution, which makes edges (especially of colored
        // text) look fuzzy/grainy. Storing them in full resolution (4:4:4) costs a bit of size, but looks a lot better.
        private fun disableChromaSubsampling(metadata: IIOMetadata) {
            val format = "javax_imageio_jpeg_image_1.0"
            val root = metadata.getAsTree(format) as IIOMetadataNode
            val components = root.getElementsByTagName("componentSpec")
            for (i in 0 until components.length) {
                (components.item(i) as IIOMetadataNode).apply {
                    setAttribute("HsamplingFactor", "1")
                    setAttribute("VsamplingFactor", "1")
                }
            }
            metadata.setFromTree(format, root)
        }

        fun close() {
            // only cancel the running jobs; the scope has to stay usable, since "destroy" is also called on creation
            encodeScope.coroutineContext.cancelChildren()
            framesInFlight.set(0)
        }
    }

    fun frameAck() = renderHandler.onFrameAck()

    init {
        AdBlocker.start()
        destroy()
        kcefClient =
            runBlocking {
                CefHelper.createClient().apply {
                    addDisplayHandler(DisplayHandler())
                    addLoadHandler(LoadHandler())
                    addRequestHandler(RequestHandler())
                    addLifeSpanHandler(LifeSpanHandler())
                    addJSDialogHandler(JsDialogHandler())
                }
            }

        logger.debug { "Start loading cookies" }
        CefCookieManager.getGlobalManager().apply {
            val cookies = networkHelper.cookieStore.getStoredCookies()
            for (cookie in cookies) {
                try {
                    if (!setCookie(
                            "https://" + cookie.domain,
                            cookie.toCefCookie(),
                        )
                    ) {
                        throw Exception()
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Loading cookie ${cookie.name} failed" }
                }
            }
        }
    }

    fun handleRtcOffer(sdp: String) {
        stopRtc()
        try {
            streamer =
                WebRtcStreamer(
                    sendSignal = { WebView.notifyAllClients(it) },
                    onConnectionChange = ::onRtcConnectionChange,
                ).also { it.handleOffer(sdp) }
        } catch (e: Throwable) {
            // e.g. no native library for this platform: the client keeps using the jpeg frames
            logger.warn(e) { "WebRTC is not available, using jpeg frames" }
            streamer = null
        }
    }

    fun handleRtcIce(
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) {
        streamer?.addRemoteCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    fun stopRtc() {
        val current = streamer ?: return
        streamer = null
        current.close()
    }

    private fun onRtcConnectionChange(connected: Boolean) {
        if (connected) {
            browser?.setWindowlessFrameRate(FRAME_RATE_ACTIVE)
            renderHandler.pushLatestFrame()
            // CEF only paints when the page changes, but the video needs a frame now and then (for new keyframes)
            frameRateScope.launch {
                while (streamer?.isConnected == true) {
                    delay(1000)
                    val current = streamer ?: break
                    if (current.isConnected && renderHandler.millisSinceLastPaint() > 900) {
                        renderHandler.pushLatestFrame()
                    }
                }
            }
        } else {
            renderHandler.resumeJpegStream()
        }
    }

    fun destroy() {
        stopRtc()
        frameRateScope.coroutineContext.cancelChildren()
        isBoosted.set(false)
        renderHandler.close()
        flush()
        browser?.close(true)
        browser?.dispose()
        browser = null
        kcefClient?.disposeWithJsHandler()
        kcefClient = null
    }

    fun loadUrl(url: String) {
        browser?.close(true)
        browser?.dispose()
        browser =
            kcefClient!!
                .createBrowser(
                    url,
                    CefRendering.CefRenderingWithHandler(renderHandler, JPanel()),
                    false,
                    // NOTE: with a context, we don't seem to be getting any cookies
                ).apply {
                    // NOTE: Without this, we don't seem to be receiving any events
                    createImmediately()
                    setWindowlessFrameRate(FRAME_RATE_IDLE)
                }
        logBrowserVersion()
        isBoosted.set(false)
        boostFrameRate()
    }

    // challenge pages compare the user agent that is sent with what the browser really is: log both to be able to check
    private fun logBrowserVersion() {
        try {
            browser?.devToolsClient?.executeDevToolsMethod("Browser.getVersion")?.thenAccept { version ->
                logger.info { "WebView browser: $version, user agent sent: ${System.getProperty("http.agent")}" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Could not read the browser version" }
        }
    }

    // the idle rate makes scrolling look choppy, so input raises it to 60fps until the user has been idle for a moment
    private fun boostFrameRate() {
        lastInputNanos = System.nanoTime()
        if (isHidden || !isBoosted.compareAndSet(false, true)) return

        browser?.setWindowlessFrameRate(FRAME_RATE_ACTIVE)
        frameRateScope.launch {
            while (System.nanoTime() - lastInputNanos < ACTIVE_BOOST_MS * 1_000_000L) {
                delay(250)
            }
            isBoosted.set(false)
            if (!isHidden) {
                browser?.setWindowlessFrameRate(idleFrameRate())
            }
        }
    }

    // the video stream can carry the full rate without the cost of encoding a jpeg per frame
    private fun idleFrameRate() = if (streamer?.isConnected == true) FRAME_RATE_ACTIVE else FRAME_RATE_IDLE

    fun setHidden(hidden: Boolean) {
        logger.info { "WebView tab hidden=$hidden" }
        isHidden = hidden
        if (hidden) {
            browser?.setWindowlessFrameRate(FRAME_RATE_HIDDEN)
            return
        }

        browser?.setWindowlessFrameRate(FRAME_RATE_IDLE)
        // CEF only paints on change, so ask for the page to be painted again for the client that just came back
        browser?.wasResized(width, height)
        isBoosted.set(false)
        boostFrameRate()
    }

    fun resize(
        width: Int,
        height: Int,
        devicePixelRatio: Double = 1.0,
    ) {
        this.width = width
        this.height = height

        val budgetScale = sqrt(MAX_FRAME_PIXELS / (width.toDouble() * height).coerceAtLeast(1.0))
        val scale = devicePixelRatio.coerceIn(1.0, MAX_DEVICE_SCALE).coerceAtMost(budgetScale.coerceAtLeast(1.0))
        val scaleChanged = scale != deviceScale
        deviceScale = scale

        if (scaleChanged) {
            browser?.notifyScreenInfoChanged()
        }
        browser?.wasResized(width, height)
    }

    private fun flush() {
        if (browser == null) return
        logger.trace { "Start cookie flush" }
        CefCookieManager.getGlobalManager().visitAllCookies { it, _, _, _ ->
            try {
                networkHelper.cookieStore.addAll(
                    HttpUrl
                        .Builder()
                        .scheme("http")
                        .host(it.domain.removePrefix("."))
                        .build(),
                    listOf(
                        Cookie
                            .Builder()
                            .name(it.name)
                            .value(it.value)
                            .path(if (it.path.startsWith('/')) it.path else "/" + it.path)
                            .domain(it.domain.removePrefix("."))
                            .apply {
                                if (it.hasExpires) {
                                    expiresAt(it.expires.time)
                                } else {
                                    expiresAt(Long.MAX_VALUE)
                                }
                                if (it.httponly) {
                                    httpOnly()
                                }
                                if (it.secure) {
                                    secure()
                                }
                                if (!it.domain.startsWith('.')) {
                                    hostOnlyDomain(it.domain.removePrefix("."))
                                }
                            }.build(),
                    ),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Writing cookie ${it.name} failed" }
            }
            return@visitAllCookies true
        }
    }

    private fun keyEvent(
        msg: WebView.JsEventMessage,
        component: Component,
        id: Int,
        modifier: Int,
    ): KeyEvent? {
        val char = if (msg.key?.length == 1) msg.key[0] else KeyEvent.CHAR_UNDEFINED
        return keyEvent(char, component, id, modifier, msg.key)
    }

    private fun keyEvent(
        char: Char,
        component: Component,
        id: Int,
        modifier: Int,
        strKey: String? = null,
    ): KeyEvent? {
        val code =
            when (char.uppercaseChar()) {
                in 'A'..'Z', in '0'..'9' -> {
                    char.uppercaseChar().code
                }

                '&' -> {
                    KeyEvent.VK_AMPERSAND
                }

                '*' -> {
                    KeyEvent.VK_ASTERISK
                }

                '@' -> {
                    KeyEvent.VK_AT
                }

                '\\' -> {
                    KeyEvent.VK_BACK_SLASH
                }

                '{' -> {
                    KeyEvent.VK_BRACELEFT
                }

                '}' -> {
                    KeyEvent.VK_BRACERIGHT
                }

                '^' -> {
                    KeyEvent.VK_CIRCUMFLEX
                }

                ']' -> {
                    KeyEvent.VK_CLOSE_BRACKET
                }

                ':' -> {
                    KeyEvent.VK_COLON
                }

                ',' -> {
                    KeyEvent.VK_COMMA
                }

                '$' -> {
                    KeyEvent.VK_DOLLAR
                }

                '=' -> {
                    KeyEvent.VK_EQUALS
                }

                '€' -> {
                    KeyEvent.VK_EURO_SIGN
                }

                '!' -> {
                    KeyEvent.VK_EXCLAMATION_MARK
                }

                '>' -> {
                    KeyEvent.VK_GREATER
                }

                '(' -> {
                    KeyEvent.VK_LEFT_PARENTHESIS
                }

                '<' -> {
                    KeyEvent.VK_LESS
                }

                '-' -> {
                    KeyEvent.VK_MINUS
                }

                '#' -> {
                    KeyEvent.VK_NUMBER_SIGN
                }

                '[' -> {
                    KeyEvent.VK_OPEN_BRACKET
                }

                '.' -> {
                    KeyEvent.VK_PERIOD
                }

                '+' -> {
                    KeyEvent.VK_PLUS
                }

                '\'' -> {
                    KeyEvent.VK_QUOTE
                }

                '"' -> {
                    KeyEvent.VK_QUOTEDBL
                }

                ')' -> {
                    KeyEvent.VK_RIGHT_PARENTHESIS
                }

                ';' -> {
                    KeyEvent.VK_SEMICOLON
                }

                '/' -> {
                    KeyEvent.VK_SLASH
                }

                ' ' -> {
                    KeyEvent.VK_SPACE
                }

                '_' -> {
                    KeyEvent.VK_UNDERSCORE
                }

                else -> {
                    when (strKey) {
                        "Alt" -> KeyEvent.VK_ALT
                        "Backspace" -> KeyEvent.VK_BACK_SPACE
                        "Delete" -> KeyEvent.VK_DELETE
                        "CapsLock" -> KeyEvent.VK_CAPS_LOCK
                        "Control" -> KeyEvent.VK_CONTROL
                        "ArrowDown" -> KeyEvent.VK_DOWN
                        "End" -> KeyEvent.VK_END
                        "Enter" -> KeyEvent.VK_ENTER
                        "Escape" -> KeyEvent.VK_ESCAPE
                        "F1" -> KeyEvent.VK_F1
                        "F2" -> KeyEvent.VK_F2
                        "F3" -> KeyEvent.VK_F3
                        "F4" -> KeyEvent.VK_F4
                        "F5" -> KeyEvent.VK_F5
                        "F6" -> KeyEvent.VK_F6
                        "F7" -> KeyEvent.VK_F7
                        "F8" -> KeyEvent.VK_F8
                        "F9" -> KeyEvent.VK_F9
                        "F10" -> KeyEvent.VK_F10
                        "F11" -> KeyEvent.VK_F11
                        "F12" -> KeyEvent.VK_F12
                        "Home" -> KeyEvent.VK_HOME
                        "Insert" -> KeyEvent.VK_INSERT
                        "ArrowLeft" -> KeyEvent.VK_LEFT
                        "Meta" -> KeyEvent.VK_META
                        "NumLock" -> KeyEvent.VK_NUM_LOCK
                        "PageDown" -> KeyEvent.VK_PAGE_DOWN
                        "PageUp" -> KeyEvent.VK_PAGE_UP
                        "Pause" -> KeyEvent.VK_PAUSE
                        "ArrowRight" -> KeyEvent.VK_RIGHT
                        "ScrollLock" -> KeyEvent.VK_SCROLL_LOCK
                        "Shift" -> KeyEvent.VK_SHIFT
                        "Tab" -> KeyEvent.VK_TAB
                        "ArrowUp" -> KeyEvent.VK_UP
                        else -> KeyEvent.VK_UNDEFINED
                    }
                }
            }
        if (id == KeyEvent.KEY_TYPED) {
            if (char == KeyEvent.CHAR_UNDEFINED && code != KeyEvent.VK_ENTER) return null
            return KeyEvent(
                component,
                id,
                0L,
                modifier,
                KeyEvent.VK_UNDEFINED,
                if (code == KeyEvent.VK_ENTER) code.toChar() else char,
                KeyEvent.KEY_LOCATION_UNKNOWN,
            )
        }
        return KeyEvent(
            component,
            id,
            0L,
            modifier,
            code,
            if (code == KeyEvent.VK_ENTER) code.toChar() else char,
            KeyEvent.KEY_LOCATION_STANDARD,
        )
    }

    fun event(msg: WebView.JsEventMessage) {
        boostFrameRate()
        val component = browser?.uiComponent ?: return
        val type = msg.eventType
        val clickX = msg.clickX
        val clickY = msg.clickY
        val modifier =
            (
                // Alt support is removed, since browsers already translate, so this messes with translation, see #1575
                // (if (msg.altKey == true) InputEvent.ALT_DOWN_MASK else 0) or
                (if (msg.ctrlKey == true) InputEvent.CTRL_DOWN_MASK else 0) or
                    (if (msg.shiftKey == true) InputEvent.SHIFT_DOWN_MASK else 0) or
                    (if (msg.metaKey == true) InputEvent.META_DOWN_MASK else 0)
            )

        if (type == "wheel") {
            val d = msg.deltaY?.toInt() ?: 1
            val ev =
                MouseWheelEvent(
                    component,
                    0,
                    0L,
                    modifier,
                    clickX.toInt(),
                    clickY.toInt(),
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    d,
                    1,
                )
            browser!!.sendMouseWheelEvent(ev)
            return
        }
        if (type == "keydown") {
            browser!!.sendKeyEvent(keyEvent(msg, component, KeyEvent.KEY_PRESSED, modifier)!!)
            keyEvent(msg, component, KeyEvent.KEY_TYPED, modifier)?.let { browser!!.sendKeyEvent(it) }
            return
        }
        if (type == "keyup") {
            browser!!.sendKeyEvent(keyEvent(msg, component, KeyEvent.KEY_RELEASED, modifier)!!)
            return
        }
        if (type == "mousedown" || type == "mouseup" || type == "click") {
            val id =
                when (type) {
                    "mousedown" -> MouseEvent.MOUSE_PRESSED
                    "mouseup" -> MouseEvent.MOUSE_PRESSED
                    "click" -> MouseEvent.MOUSE_CLICKED
                    else -> 0
                }
            val mouseModifier =
                when (msg.button ?: 0) {
                    0 -> MouseEvent.BUTTON1_DOWN_MASK
                    1 -> MouseEvent.BUTTON2_DOWN_MASK
                    2 -> MouseEvent.BUTTON3_DOWN_MASK
                    else -> 0
                }
            val button =
                when (msg.button ?: 0) {
                    0 -> MouseEvent.BUTTON1
                    1 -> MouseEvent.BUTTON2
                    2 -> MouseEvent.BUTTON3
                    else -> 0
                }
            val ev =
                MouseEvent(
                    component,
                    id,
                    0L,
                    modifier or mouseModifier,
                    clickX.toInt(),
                    clickY.toInt(),
                    msg.clientX?.toInt() ?: 0,
                    msg.clientY?.toInt() ?: 0,
                    1,
                    true,
                    button,
                )
            browser!!.sendMouseEvent(ev)
            val evType =
                when (type) {
                    "mousedown" -> CefTouchEvent.EventType.PRESSED
                    "mouseup" -> CefTouchEvent.EventType.RELEASED
                    else -> CefTouchEvent.EventType.MOVED
                }
            val ev2 =
                CefTouchEvent(
                    0,
                    clickX,
                    clickY,
                    10.0f,
                    10.0f,
                    0.0f,
                    1.0f,
                    evType,
                    modifier,
                    CefTouchEvent.PointerType.MOUSE,
                )
            browser!!.sendTouchEvent(ev2)
            return
        }
        if (type == "mousemove") {
            val ev =
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    modifier,
                    clickX.toInt(),
                    clickY.toInt(),
                    msg.clientX?.toInt() ?: 0,
                    msg.clientY?.toInt() ?: 0,
                    0,
                    true,
                    0,
                )
            browser!!.sendMouseEvent(ev)
            return
        }
    }

    fun paste(msg: String) {
        val component = browser?.uiComponent ?: return
        for (c in msg) {
            browser!!.sendKeyEvent(keyEvent(c, component, KeyEvent.KEY_PRESSED, 0)!!)
            keyEvent(c, component, KeyEvent.KEY_TYPED, 0)?.let { browser!!.sendKeyEvent(it) }
            browser!!.sendKeyEvent(keyEvent(c, component, KeyEvent.KEY_RELEASED, 0)!!)
        }
    }

    fun copy() {
        val frame = browser?.focusedFrame ?: return
        frame.copy()
        val clip =
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
            } catch (e: HeadlessException) {
                logger.warn(e) { "Failed to get clipboard" }
                return
            }
        val text =
            try {
                clip.getData(DataFlavor.stringFlavor) as String
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get clipboard contents" }
                return
            }
        WebView.notifyAllClients(
            Json.encodeToString<Event>(
                CopyEvent(text),
            ),
        )
    }

    fun canGoBack(): Boolean = browser!!.canGoBack()

    fun goBack() {
        browser!!.goBack()
    }

    fun canGoForward(): Boolean = browser!!.canGoForward()

    fun goForward() {
        browser!!.goForward()
    }

    private fun handleLoad(
        url: String,
        status: Int = 0,
        error: String? = null,
    ) {
        browser!!.evaluateJavaScript("return document.title") {
            logger.trace { "Load finished with title $it" }
            WebView.notifyAllClients(
                Json.encodeToString<Event>(
                    LoadEvent(url, it ?: "", status, error),
                ),
            )
        }
    }
}

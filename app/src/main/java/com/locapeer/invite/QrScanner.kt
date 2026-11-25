package com.locapeer.invite

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

/** How long to wait before trying the camera again after an attempt failed. */
private const val CAMERA_RETRY_DELAY_MS = 3_000L

/**
 * Camera preview that reports the first readable QR payload it sees.
 *
 * CameraX owns the camera pipeline and its lifecycle; [com.google.zxing.MultiFormatReader] -
 * already a dependency for generating the invite QR code - decodes the frames on-device, so
 * scanning needs no camera wrapper library and no network.
 */
@Composable
internal fun QrScannerView(
    onQrCode: (String) -> Unit,
    onCameraUnavailable: () -> Unit,
    onCameraAvailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The analyzer outlives individual recompositions, so it publishes through a state holder
    // rather than capturing the callback from the composition that started the camera.
    val currentOnQrCode by rememberUpdatedState(onQrCode)
    val currentOnCameraUnavailable by rememberUpdatedState(onCameraUnavailable)
    val currentOnCameraAvailable by rememberUpdatedState(onCameraAvailable)
    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE draws the preview through a TextureView, so it composites inside this
            // window instead of punching a SurfaceView hole through the Compose hierarchy.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // The camera another app holds right now is usually free a moment later, so a failed attempt
    // schedules the next one instead of latching the unavailable message for good. Bumping the
    // counter re-keys the effect below, which disposes this attempt and binds a fresh one.
    val bindAttempt = remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner, bindAttempt.value) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val handler = Handler(Looper.getMainLooper())
        val disposed = AtomicBoolean(false)
        val reportedUnavailable = AtomicBoolean(false)
        var boundCamera: Camera? = null
        var cameraStateObserver: Observer<CameraState>? = null

        fun retryLater() {
            if (disposed.get()) return
            handler.postDelayed({
                if (!disposed.get()) bindAttempt.value += 1
            }, CAMERA_RETRY_DELAY_MS)
        }

        providerFuture.addListener({
            if (disposed.get()) return@addListener
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.w(TAG, "Camera provider unavailable", e)
                mainExecutor.execute { currentOnCameraUnavailable() }
                retryLater()
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                // 720p is ample for a QR code and keeps the per-frame decode cheap.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                // Only the newest frame is worth decoding: dropping stale ones keeps the
                // preview smooth when a code is held still or the CPU is busy.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(
                        analysisExecutor,
                        QrCodeAnalyzer { payload ->
                            mainExecutor.execute { currentOnQrCode(payload) }
                        }
                    )
                }

            val camera = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.getOrElse {
                Log.w(TAG, "QR camera unavailable", it)
                mainExecutor.execute { currentOnCameraUnavailable() }
                retryLater()
                return@addListener
            }

            // bindToLifecycle only throws for synchronous configuration problems. A camera that
            // another app already holds, or that the device refuses to open, fails later and is
            // reported as a camera-state error; without watching for it the preview stays black
            // and the user never learns why.
            val observer = Observer<CameraState> { state ->
                val error = state.error
                if (error != null) {
                    if (reportedUnavailable.compareAndSet(false, true)) {
                        Log.w(TAG, "QR camera failed to open (code ${error.code})")
                        currentOnCameraUnavailable()
                        retryLater()
                    }
                } else if (state.type == CameraState.Type.OPEN) {
                    // A later attempt got the camera, so the failure message no longer applies.
                    // CameraX also reopens a camera that was merely in use on its own; drop
                    // any retry queued by the earlier error, or it would tear down this
                    // working camera and restart the preview a few seconds later.
                    handler.removeCallbacksAndMessages(null)
                    // Re-arm, so a failure after this recovery is reported and retried again.
                    reportedUnavailable.set(false)
                    currentOnCameraAvailable()
                }
            }
            boundCamera = camera
            cameraStateObserver = observer
            camera.cameraInfo.cameraState.observeForever(observer)
        }, mainExecutor)

        onDispose {
            // The provider future can still be pending here; the flag stops it from binding a
            // camera for a screen that no longer exists.
            disposed.set(true)
            handler.removeCallbacksAndMessages(null)
            cameraStateObserver?.let { observer ->
                boundCamera?.cameraInfo?.cameraState?.removeObserver(observer)
            }
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Decodes QR codes from analysis frames; one frame in, at most one payload out. */
private class QrCodeAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /** Last delivered payload, so a code held in frame is parsed once instead of every frame. */
    private var lastPayload: String? = null

    override fun analyze(image: ImageProxy) {
        try {
            val payload = decode(image)
            if (payload != null && payload != lastPayload) {
                lastPayload = payload
                onDecoded(payload)
            }
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.getOrNull(0) ?: return null
        // The luma plane is exactly what the decoder wants; building an RGB source instead
        // would copy four bytes per pixel on every frame.
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return try {
            // Rows can be padded out to `rowStride`, which PlanarYUVLuminanceSource takes as
            // its source width, so a padded plane decodes without being repacked first.
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false
            )
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            // The normal case: this frame simply held no readable code.
            null
        } catch (e: RuntimeException) {
            // A malformed frame must not stop the analyzer for the rest of the screen's life.
            Log.w(TAG, "QR frame analysis failed", e)
            null
        } finally {
            reader.reset()
        }
    }
}

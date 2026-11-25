package com.locapeer.invite

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The analyzer outlives individual recompositions, so it publishes through a state holder
    // rather than capturing the callback from the composition that started the camera.
    val currentOnQrCode by rememberUpdatedState(onQrCode)
    val currentOnCameraUnavailable by rememberUpdatedState(onCameraUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE draws the preview through a TextureView, so it composites inside this
            // window instead of punching a SurfaceView hole through the Compose hierarchy.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val disposed = AtomicBoolean(false)

        providerFuture.addListener({
            if (disposed.get()) return@addListener
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.w(TAG, "Camera provider unavailable", e)
                mainExecutor.execute { currentOnCameraUnavailable() }
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

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure {
                Log.w(TAG, "QR camera unavailable", it)
                mainExecutor.execute { currentOnCameraUnavailable() }
            }
        }, mainExecutor)

        onDispose {
            // The provider future can still be pending here; the flag stops it from binding a
            // camera for a screen that no longer exists.
            disposed.set(true)
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

// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.pairing

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Draws [text] as a QR code, always dark on light so any camera can read it. */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix =
        remember(text) {
            QRCodeWriter()
                .encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    0,
                    0,
                    mapOf(
                        EncodeHintType.MARGIN to 2,
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    ),
                )
        }
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRect(Color.White)
        val cell = minOf(size.width / matrix.width, size.height / matrix.height)
        val left = (size.width - cell * matrix.width) / 2
        val top = (size.height - cell * matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(left + x * cell, top + y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** Camera preview that reports the first QR code it reads. */
@Composable
fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode = rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context) }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener(
            {
                val cameraProvider = providerFuture.get().also { provider = it }
                val preview =
                    Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = decode(image)
                    if (text != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute {
                            currentOnCode.value(text)
                        }
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private val decodeHints =
    mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

private fun decode(image: ImageProxy): String? =
    image.use {
        val plane = it.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining()).also(buffer::get)
        val source =
            PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                it.height,
                0,
                0,
                it.width,
                it.height,
                false,
            )
        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
        } catch (e: ReaderException) {
            null
        }
    }

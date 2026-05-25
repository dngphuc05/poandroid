package com.pocketmocap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.pocketmocap.app.ui.theme.CloudWarm
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.MintDeep
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrLinkScannerScreen(
    onLinkScanned: (String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) onError("Camera permission is required to scan the Pocap QR link")
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            QrCameraPreview(
                onLinkScanned = onLinkScanned,
                onError = onError,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionFallback(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(18.dp),
            shape = RoundedCornerShape(22.dp),
            color = CloudWarm.copy(alpha = 0.96f),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Scan Pocap link",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Point the phone at the QR shown on the PC before creating or joining a session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = 0.72f),
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MintDeep)
                }
            }
        }
    }
}

@Composable
private fun PermissionFallback(
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(24.dp),
        color = CloudWarm,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Camera permission needed",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Grant camera access to scan the PC link QR, or type the IP link manually.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.copy(alpha = 0.72f),
            )
            Button(onClick = onRequestPermission) {
                Text("Grant camera permission")
            }
            TextButton(onClick = onCancel) {
                Text("Type link manually")
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    onLinkScanned: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val executor = ContextCompat.getMainExecutor(viewContext)
                cameraProviderFuture.addListener({
                    val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull()
                    if (cameraProvider == null) {
                        onError("Unable to open camera for QR scan")
                        return@addListener
                    }
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                executor,
                                QrCodeAnalyzer(
                                    scanner = scanner,
                                    onLinkScanned = onLinkScanned,
                                    onScanError = { onError("Unable to read QR code") },
                                ),
                            )
                        }
                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.onFailure {
                        onError("Unable to start QR camera")
                    }
                }, executor)
            }
        },
    )
}

private class QrCodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onLinkScanned: (String) -> Unit,
    private val onScanError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val delivered = AtomicBoolean(false)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val rawValue = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                if (rawValue != null && delivered.compareAndSet(false, true)) {
                    onLinkScanned(rawValue)
                }
            }
            .addOnFailureListener(onScanError)
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

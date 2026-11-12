package com.example.qrscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlin.math.max

// Apple Tarzı Renk Paleti
val AppleYellow = Color(0xFFFFD60A)
val AppleGlassDark = Color(0xFF1C1C1E).copy(alpha = 0.72f)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    QrScannerApp()
                }
            }
        }
    }
}

data class DetectedQr(
    val boundingBox: Rect,
    val imageWidth: Int,
    val imageHeight: Int
)

@Composable
fun QrScannerApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraScannerScreen()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "QR kodları taramak için kamera izni gerekiyor.",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = AppleYellow)
            ) {
                Text("Kamera İznini Ver", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var detectedQr by remember { mutableStateOf<DetectedQr?>(null) }
    var scannedTextResult by remember { mutableStateOf<String?>(null) }
    var isPaused by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    // Galeriden Görsel Seçip QR Kod Okuma
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputImage = InputImage.fromFilePath(context, uri)
                barcodeScanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        val qr = barcodes.firstOrNull()
                        val rawValue = qr?.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scannedTextResult = rawValue
                            isPaused = true
                        } else {
                            Toast.makeText(context, "Görselde QR kod bulunamadı", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, "Görsel okunamadı: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(context, "Görsel yüklenemedi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Uygulamaya geri dönüldüğünde taramayı otomatik devam ettir
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && scannedTextResult == null) {
                isPaused = false
                detectedQr = null
                camera?.cameraControl?.setZoomRatio(1.0f)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    LaunchedEffect(previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isPaused) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                    val uprightWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                    val uprightHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            val qr = barcodes.firstOrNull()
                            val rawValue = qr?.rawValue
                            val box = qr?.boundingBox

                            if (qr != null && !rawValue.isNullOrBlank() && box != null) {
                                val boxCenterX = box.centerX()
                                val boxCenterY = box.centerY()

                                // Sadece Merkez Alanı Tarama (%20 - %80 arası)
                                val isInCenterRegion = boxCenterX >= uprightWidth * 0.2f &&
                                        boxCenterX <= uprightWidth * 0.8f &&
                                        boxCenterY >= uprightHeight * 0.2f &&
                                        boxCenterY <= uprightHeight * 0.8f

                                if (isInCenterRegion) {
                                    detectedQr = DetectedQr(box, uprightWidth, uprightHeight)

                                    // Otomatik Yakınlaştırma (Auto-Zoom): Küçük QR algılandığında zoom yap
                                    val qrArea = box.width().toFloat() * box.height().toFloat()
                                    val totalArea = uprightWidth.toFloat() * uprightHeight.toFloat()
                                    val qrAreaRatio = qrArea / totalArea

                                    if (qrAreaRatio < 0.06f) {
                                        camera?.cameraControl?.setZoomRatio(2.2f)
                                    }

                                    // Tek Karede Anında Tetikleme
                                    if (!isPaused) {
                                        isPaused = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scannedTextResult = rawValue
                                    }
                                } else {
                                    detectedQr = null
                                }
                            } else {
                                detectedQr = null
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                camera = boundCamera

                // Sürekli Hızlı Odaklama Kilidi
                previewView.post {
                    val factory = previewView.meteringPointFactory
                    val centerPoint = factory.createPoint(
                        (previewView.width / 2).toFloat(),
                        (previewView.height / 2).toFloat()
                    )
                    val focusAction = FocusMeteringAction.Builder(
                        centerPoint,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).build()
                    boundCamera.cameraControl.startFocusAndMetering(focusAction)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Kamera başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                }
            }
    ) {
        // Tam ekran kamera görüntüsü
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Kavisli ve Yaylanmalı Apple Sarısı Takip Çerçevesi
        DynamicQrOverlay(detectedQr = detectedQr)

        // Dynamic Island Tarzı Üst Kapsül
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                color = AppleGlassDark,
                shape = CircleShape,
                border = BorderStroke(
                    width = 0.5.dp,
                    color = Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier.animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (scannedTextResult != null || detectedQr != null) AppleYellow else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    )
                    val capsuleText = when {
                        scannedTextResult != null -> {
                            val cleanText = scannedTextResult.orEmpty()
                            val domain = try {
                                val uri = Uri.parse(if (cleanText.startsWith("http", ignoreCase = true)) cleanText else "https://$cleanText")
                                uri.host ?: cleanText
                            } catch (e: Exception) {
                                cleanText
                            }
                            if (domain.isNotBlank()) domain else "QR Kod Okundu"
                        }
                        detectedQr != null -> "QR Kod Algılandı"
                        else -> "Kod Taranıyor"
                    }
                    Text(
                        text = capsuleText,
                        color = if (scannedTextResult != null || detectedQr != null) AppleYellow else Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.2).sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Okunan QR Kartı (Kart tarzı Apple tasarımı)
        if (scannedTextResult != null) {
            val resultText = scannedTextResult ?: ""
            val isUrl = resultText.startsWith("http://", ignoreCase = true) ||
                    resultText.startsWith("https://", ignoreCase = true) ||
                    (resultText.contains(".") && !resultText.contains(" "))

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                colors = CardDefaults.cardColors(containerColor = AppleGlassDark)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Okunan İçerik",
                        color = AppleYellow,
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = (-0.1).sp),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = resultText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isUrl) {
                            Button(
                                onClick = {
                                    try {
                                        val uri = if (resultText.startsWith("http", ignoreCase = true)) {
                                            Uri.parse(resultText)
                                        } else {
                                            Uri.parse("https://$resultText")
                                        }
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleYellow)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.OpenInNew,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Aç",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(resultText))
                                Toast.makeText(context, "Panoya kopyalandı", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isUrl) Color.White.copy(alpha = 0.15f) else AppleYellow
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = if (isUrl) Color.White else Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Kopyala",
                                    color = if (isUrl) Color.White else Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scannedTextResult = null
                                detectedQr = null
                                isPaused = false
                                camera?.cameraControl?.setZoomRatio(1.0f)
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Tekrar",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Alt Butonlar: Sol Altta Galeri (Flaş İle Aynı Boyda), Sağ Altta El Feneri
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp, start = 48.dp, end = 48.dp)
            ) {
                // Sol Altta Galeri Butonu
                IconButton(
                    onClick = {
                        galleryLauncher.launch("image/*")
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(56.dp)
                        .background(
                            color = AppleGlassDark,
                            shape = CircleShape
                        )
                        .border(
                            width = 0.5.dp,
                            color = Color.White.copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = "Galeriden Seç",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Sağ Altta El Feneri Butonu
                IconButton(
                    onClick = {
                        val newState = !isFlashOn
                        isFlashOn = newState
                        camera?.cameraControl?.enableTorch(newState)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(56.dp)
                        .background(
                            color = if (isFlashOn) Color.White else AppleGlassDark,
                            shape = CircleShape
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (isFlashOn) Color.White else Color.White.copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        contentDescription = "El Feneri",
                        tint = if (isFlashOn) Color.Black else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// Kavisli ve Yaylanmalı Apple Sarısı Takip Çerçevesi (Ekran kenarlarından küçülen animasyon)
@Composable
fun DynamicQrOverlay(detectedQr: DetectedQr?) {
    val boxAlpha by animateFloatAsState(
        targetValue = if (detectedQr != null) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "qrBoxAlpha"
    )

    // QR algılandığında ekran kenarlarından (büyük ölçekten) koda doğru küçülen yay animasyonu
    val scaleAnim by animateFloatAsState(
        targetValue = if (detectedQr != null) 1f else 2.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "qrScaleAnim"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val qr = detectedQr ?: return@Canvas
        if (boxAlpha <= 0f) return@Canvas

        val scaleX = size.width / qr.imageWidth.toFloat()
        val scaleY = size.height / qr.imageHeight.toFloat()
        val scale = max(scaleX, scaleY)

        val offsetX = (size.width - qr.imageWidth * scale) / 2f
        val offsetY = (size.height - qr.imageHeight * scale) / 2f

        val rawLeft = qr.boundingBox.left * scale + offsetX
        val rawTop = qr.boundingBox.top * scale + offsetY
        val rawRight = qr.boundingBox.right * scale + offsetX
        val rawBottom = qr.boundingBox.bottom * scale + offsetY

        val centerX = (rawLeft + rawRight) / 2f
        val centerY = (rawTop + rawBottom) / 2f
        val halfWidth = ((rawRight - rawLeft) / 2f) * scaleAnim
        val halfHeight = ((rawBottom - rawTop) / 2f) * scaleAnim

        val left = centerX - halfWidth
        val top = centerY - halfHeight
        val right = centerX + halfWidth
        val bottom = centerY + halfHeight

        val boxWidth = right - left
        val boxHeight = bottom - top
        val cornerRadius = (minOf(boxWidth, boxHeight) * 0.14f).coerceIn(14f, 32f)
        val armLength = (minOf(boxWidth, boxHeight) * 0.22f).coerceIn(22f, 56f)
        val strokeWidth = 8.5f
        val appleYellow = AppleYellow.copy(alpha = boxAlpha)

        // Kavisli (iOS tarzı) 4 köşe parantezi
        val path = Path().apply {
            // Sol Üst
            moveTo(left, top + armLength)
            lineTo(left, top + cornerRadius)
            quadraticTo(left, top, left + cornerRadius, top)
            lineTo(left + armLength, top)

            // Sağ Üst
            moveTo(right - armLength, top)
            lineTo(right - cornerRadius, top)
            quadraticTo(right, top, right, top + cornerRadius)
            lineTo(right, top + armLength)

            // Sağ Alt
            moveTo(right, bottom - armLength)
            lineTo(right, bottom - cornerRadius)
            quadraticTo(right, bottom, right - cornerRadius, bottom)
            lineTo(right - armLength, bottom)

            // Sol Alt
            moveTo(left + armLength, bottom)
            lineTo(left + cornerRadius, bottom)
            quadraticTo(left, bottom, left, bottom - cornerRadius)
            lineTo(left, bottom - armLength)
        }

        drawPath(
            path = path,
            color = appleYellow,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

package com.rheeworks.rivery

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Range
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContent {
            CameraApp()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    fun getExecutor() = cameraExecutor
}

enum class AppScreen { CAMERA, GALLERY }
enum class CameraQuality { HIGH, BALANCED }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    var currentScreen by remember { mutableStateOf(AppScreen.CAMERA) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        when (currentScreen) {
            AppScreen.CAMERA -> CameraScreen(
                onOpenGallery = { currentScreen = AppScreen.GALLERY }
            )
            AppScreen.GALLERY -> GalleryScreen(
                onBack = { currentScreen = AppScreen.CAMERA }
            )
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.permission_required), color = Color.White)
        }
    }
}

@Composable
fun CameraScreen(onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraUtils = remember { CameraUtils(context) }
    
    // Preferences
    val sharedPrefs = remember { context.getSharedPreferences("rivery_prefs", Context.MODE_PRIVATE) }
    
    // Camera State
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    
    // Settings State
    var selectedQuality by remember { 
        mutableStateOf(
            if (sharedPrefs.getString("quality", "HIGH") == "HIGH") CameraQuality.HIGH else CameraQuality.BALANCED
        ) 
    }

    // UI State
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var isShutterPressed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var latestImageUri by remember { mutableStateOf<Uri?>(null) }

    // Exposure
    var exposureIndex by remember { mutableFloatStateOf(0f) }
    var exposureRange by remember { mutableStateOf<Range<Int>?>(null) }
    var showExposureSlider by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val uris = loadMedia(context)
        if (uris.isNotEmpty()) latestImageUri = uris.first()
    }

    val shutterScale by animateFloatAsState(targetValue = if (isShutterPressed) 0.85f else 1f, label = "shutter")

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // 1. Camera Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        showExposureSlider = true
                        exposureIndex = 0f
                        
                        cameraControl?.let { control ->
                            try {
                                val factory = SurfaceOrientedMeteringPointFactory(
                                    size.width.toFloat(), size.height.toFloat()
                                )
                                val point = factory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                control.startFocusAndMetering(action)
                                control.setExposureCompensationIndex(0)
                            } catch (e: Exception) {}
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        if (showExposureSlider && exposureRange != null) {
                            change.consume()
                            val range = exposureRange!!
                            // Sensitivity adjustments
                            val sensitivity = 0.05f
                            val newValue = (exposureIndex - dragAmount * sensitivity).coerceIn(
                                range.lower.toFloat(), range.upper.toFloat()
                            )
                            exposureIndex = newValue
                            cameraControl?.setExposureCompensationIndex(newValue.toInt())
                        }
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                update = { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            
                            // High Quality strategy
                            // High Quality strategy
                            val resolutionSelector = ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    if (selectedQuality == CameraQuality.HIGH) 
                                        ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY 
                                    else 
                                        ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                                )
                                .build()

                            val newImageCapture = ImageCapture.Builder()
                                .setFlashMode(flashMode)
                                .setResolutionSelector(resolutionSelector)
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            
                            imageCapture = newImageCapture

                            val cameraSelector = CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .build()

                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                newImageCapture
                            )
                            
                            cameraControl = camera.cameraControl
                            
                            // Get Zoom Info
                            val zoomState = camera.cameraInfo.zoomState.value
                            if (zoomState != null) {
                                minZoom = zoomState.minZoomRatio
                                maxZoom = zoomState.maxZoomRatio
                            }
                            
                            exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
                            
                            // Apply current zoom
                            camera.cameraControl.setZoomRatio(zoomRatio.coerceIn(minZoom, maxZoom))
                            
                        } catch (exc: Exception) { }
                    }, ContextCompat.getMainExecutor(context))
                }
            )
            
            // Indicators
            if (focusPoint != null) {
                FocusRing(offset = focusPoint!!)
                if (showExposureSlider) {
                    ExposureSunSlider(focusPoint!!, exposureIndex, exposureRange)
                }
                LaunchedEffect(focusPoint) {
                    kotlinx.coroutines.delay(2500)
                    showExposureSlider = false
                    focusPoint = null
                }
            }
        }

        // 2. Top Controls
        TopControlBar(
            flashMode = flashMode,
            onFlashToggle = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            },
            onSettingsClick = { showSettings = true }
        )

        // 3. Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(bottom = 32.dp, top = 16.dp)
        ) {
            // Updated Zoom Switcher (Includes 0.5x if supported)
            ZoomSwitcher(
                currentRatio = zoomRatio,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onZoomChanged = { zoomRatio = it }
            )
            
            Spacer(modifier = Modifier.height(18.dp))
            
            ModeSelector()
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryThumbnail(latestImageUri, onOpenGallery)

                ShutterButton(
                    scale = shutterScale,
                    onPress = { isShutterPressed = true },
                    onRelease = { isShutterPressed = false },
                    onTap = {
                         imageCapture?.let { capture ->
                             cameraUtils.takePhoto(
                                 capture,
                                 onImageCaptured = { uri -> latestImageUri = uri },
                                 onError = {}
                             )
                         }
                    }
                )

                FlipButton {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) 
                        CameraSelector.LENS_FACING_FRONT 
                    else 
                        CameraSelector.LENS_FACING_BACK
                    
                    // Reset zoom when flipping
                    zoomRatio = 1f 
                }
            }
        }

        if (showSettings) {
            SettingsDialog(
                currentQuality = selectedQuality,
                onQualityChanged = { newQuality ->
                    selectedQuality = newQuality
                    sharedPrefs.edit().putString("quality", newQuality.name).apply()
                },
                onDismiss = { showSettings = false }
            )
        }
    }
}

// -----------------------------------------------------------
// Polished UI Components
// -----------------------------------------------------------

@Composable
fun TopControlBar(flashMode: Int, onFlashToggle: () -> Unit, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onFlashToggle) {
            Icon(
                imageVector = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                    else -> Icons.Default.FlashOff
                },
                contentDescription = "Flash",
                tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.White else Color.Yellow,
                modifier = Modifier.size(26.dp)
            )
        }
        
        // Live Photo / RAW (Dynamic Visual)
         Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(1.5.dp, Color.White.copy(alpha=0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Dotted circle effect or simplified icon
            Box(modifier=Modifier.size(4.dp).clip(CircleShape).background(Color.Yellow))
        }

        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

@Composable
fun ZoomSwitcher(
    currentRatio: Float, 
    minZoom: Float, 
    maxZoom: Float, 
    onZoomChanged: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val levels = mutableListOf<Float>()
        if (minZoom < 1.0f) levels.add(0.5f) // Ultra-wide
        levels.add(1f) // Wide
        if (maxZoom >= 2.0f) levels.add(2f) // Tele
        if (maxZoom >= 5.0f) levels.add(5f) // Super Tele

        // Add 0.5x manually if levels is small (simulation fallback handled by button logic if actually supported)
        // But strict list:
        
        // Background bubble
        Box(
            modifier = Modifier
                .height(36.dp)
                .background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row {
                levels.forEach { level ->
                    ZoomButton(
                        text = if (level < 1f) ".5" else "${level.toInt()}",
                        isSelected = (currentRatio == level) || (currentRatio < 1f && level == 0.5f && currentRatio < 0.8f),
                        onClick = { onZoomChanged(level.coerceAtLeast(minZoom)) }
                    )
                    if (level != levels.last()) {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ZoomButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color.Black.copy(alpha = 0.5f) else Color.Transparent)
            .border(
                width = 1.dp, 
                color = if (isSelected) Color.Yellow else Color.Transparent, // No border when unselected
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Yellow else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
fun ModeSelector() {
    val modes = listOf("TIME-LAPSE", "SLO-MO", "VIDEO", "PHOTO", "PORTRAIT", "PANO")
    // Simple Centered for PHOTO
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            modes.forEach { mode ->
                val isSelected = mode == "PHOTO"
                Text(
                    text = mode,
                    color = if (isSelected) Color.Yellow else Color.Gray,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(
    currentQuality: CameraQuality,
    onQualityChanged: (CameraQuality) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // Quality Selector
                Text("Camera Quality", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    QualityOption("High Res", CameraQuality.HIGH, currentQuality, onQualityChanged)
                    QualityOption("Balanced", CameraQuality.BALANCED, currentQuality, onQualityChanged)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Language
                Text(stringResource(R.string.language_option), color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                LanguageItem(stringResource(R.string.lang_system), null)
                LanguageItem(stringResource(R.string.lang_en), "en")
                LanguageItem(stringResource(R.string.lang_ko), "ko")
                LanguageItem(stringResource(R.string.lang_ja), "ja")

                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun QualityOption(
    label: String, 
    quality: CameraQuality, 
    current: CameraQuality, 
    onSelect: (CameraQuality) -> Unit
) {
    val isSelected = quality == current
    Button(
        onClick = { onSelect(quality) },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color.Yellow else Color(0xFF2C2C2E)
        ),
        modifier = Modifier.width(140.dp).height(40.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label, 
            color = if (isSelected) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

// ... Reused components (Icons, Thumbnails) ...
// Copied slightly modified versions to match "Premium" feel

@Composable
fun GalleryThumbnail(uri: Uri?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C2C2E))
            .border(1.5.dp, Color.White.copy(alpha=0.8f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        if (uri != null) {
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun ShutterButton(scale: Float, onPress: () -> Unit, onRelease: () -> Unit, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .padding(5.dp) // Gap
            .pointerInput(Unit) {
                 detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                    onTap = { onTap() }
                 )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.White)
        )
    }
}

@Composable
fun FlipButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF2C2C2E).copy(alpha=0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Loop, contentDescription = "Flip", tint = Color.White)
    }
}

// ... LanguageItem & FocusRing ...
@Composable
fun LanguageItem(name: String, code: String?) {
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val isSelected = if (code == null) currentLocales.isEmpty else currentLocales.toLanguageTags().contains(code)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val list = if (code == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(code)
                AppCompatDelegate.setApplicationLocales(list)
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, color = Color.White, fontSize = 15.sp)
        if (isSelected) Icon(Icons.Default.Check, null, tint = Color.Yellow, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun FocusRing(offset: Offset) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val size = 70.dp.toPx()
        val topLeft = Offset(offset.x - size / 2, offset.y - size / 2)
        drawRect(Color.Yellow, topLeft, Size(size, size), style = Stroke(1.5.dp.toPx()))
        // Top notch
        drawLine(Color.Yellow, Offset(topLeft.x + size/2, topLeft.y), Offset(topLeft.x + size/2, topLeft.y + 10), 2.dp.toPx())
    }
}

@Composable
fun ExposureSunSlider(focusOffset: Offset, value: Float, range: Range<Int>?) {
    if (range == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val h = 140.dp.toPx()
        val x = focusOffset.x + 65.dp.toPx()
        val y = focusOffset.y
        drawLine(Color.Yellow, Offset(x, y - h/2), Offset(x, y + h/2), 1.dp.toPx())
        val norm = (value - range.lower)/(range.upper - range.lower)
        val cy = (y + h/2) - (norm * h)
        drawCircle(Color.Yellow, 6.dp.toPx(), Offset(x, cy))
    }
}

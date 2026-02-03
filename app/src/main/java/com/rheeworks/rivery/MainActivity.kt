package com.rheeworks.rivery

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Range
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

enum class AppScreen {
    CAMERA, GALLERY
}

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
    val mainActivity = context as? MainActivity

    // Camera State
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var cameraInfo: CameraInfo? by remember { mutableStateOf(null) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // UI/Animation State
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var isShutterPressed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var latestImageUri by remember { mutableStateOf<Uri?>(null) }
    
    // Exposure State
    var exposureIndex by remember { mutableFloatStateOf(0f) } // Adjusted EV index
    var exposureRange by remember { mutableStateOf<Range<Int>?>(null) }
    var showExposureSlider by remember { mutableStateOf(false) }
    
    // Load latest image for thumbnail
    LaunchedEffect(Unit) {
        val uris = loadMedia(context)
        if (uris.isNotEmpty()) latestImageUri = uris.first()
    }

    val shutterScale by animateFloatAsState(
        targetValue = if (isShutterPressed) 0.85f else 1f,
        label = "shutter"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ------------------------------------------------------------
        // 1. Camera Preview & Touch Handling
        // ------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        showExposureSlider = true
                        exposureIndex = 0f // Reset EV on new focus
                        
                        if (cameraControl != null) {
                            try {
                                val factory = SurfaceOrientedMeteringPointFactory(
                                    size.width.toFloat(), size.height.toFloat()
                                )
                                val point = factory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                cameraControl?.startFocusAndMetering(action)
                                cameraControl?.setExposureCompensationIndex(0) // Reset EV hardware
                            } catch (e: Exception) { }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        if (showExposureSlider && exposureRange != null) {
                            change.consume()
                            // Drag up -> Increase EV, Down -> Decrease
                            // Map drag pixels to EV range approximately
                            val range = exposureRange!!
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
                            
                            val newImageCapture = ImageCapture.Builder()
                                .setFlashMode(flashMode)
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Fast capture
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
                            cameraInfo = camera.cameraInfo
                            exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
                            camera.cameraControl.setZoomRatio(zoomRatio)
                        } catch (exc: Exception) { }
                    }, ContextCompat.getMainExecutor(context))
                }
            )
            
            // Focus & Exposure UI
            if (focusPoint != null) {
                FocusRing(offset = focusPoint!!)
                if (showExposureSlider) {
                    // Draw Sun icon slider next to focus ring
                    ExposureSunSlider(
                        focusOffset = focusPoint!!, 
                        value = exposureIndex, 
                        range = exposureRange
                    )
                }
                
                // Auto-hide slider after 3 seconds of inactivity
                LaunchedEffect(focusPoint) {
                    kotlinx.coroutines.delay(3000)
                    showExposureSlider = false
                    focusPoint = null
                }
            }
        }

        // ------------------------------------------------------------
        // 2. UI Overlay (Top Bar)
        // ------------------------------------------------------------
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

        // ------------------------------------------------------------
        // 3. Bottom Controls
        // ------------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(bottom = 30.dp, top = 10.dp)
        ) {
            // Zoom Controls
            ZoomControls(zoomRatio) { zoomRatio = it }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            // Mode List (Visual Only)
            ModeSelector()
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Main Controls (Gallery, Shutter, Flip)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail
                GalleryThumbnail(latestImageUri, onOpenGallery)

                // Shutter Button
                ShutterButton(
                    scale = shutterScale,
                    onPress = { isShutterPressed = true },
                    onRelease = { isShutterPressed = false },
                    onTap = {
                         if(imageCapture != null) {
                             cameraUtils.takePhoto(
                                 imageCapture!!,
                                 onImageCaptured = { uri ->
                                     latestImageUri = uri
                                     // Optional: Show "saved" toast or flash
                                 },
                                 onError = { /* Handle error */ }
                             )
                         }
                    }
                )

                // Flip Camera
                FlipButton {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) 
                        CameraSelector.LENS_FACING_FRONT 
                    else 
                        CameraSelector.LENS_FACING_BACK
                }
            }
        }

        if (showSettings) {
            SettingsDialog { showSettings = false }
        }
    }
}

// ----------------------------------------------------------------
// Sub-Components
// ----------------------------------------------------------------

@Composable
fun TopControlBar(flashMode: Int, onFlashToggle: () -> Unit, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onFlashToggle) {
            Icon(
                imageVector = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                    else -> Icons.Default.FlashOff
                },
                contentDescription = "Flash",
                tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.White else Color.Yellow
            )
        }
        
        // Live Photo / Timer indicators (static for now)
         Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.Yellow))
        }

        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

@Composable
fun ZoomControls(currentRatio: Float, onZoomChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        val levels = listOf(0.5f, 1f, 2f, 5f)
        levels.forEach { level ->
            ZoomButton(
                text = "${level.toString().removeSuffix(".0")}x",
                isSelected = currentRatio == level,
                onClick = { onZoomChange(level) }
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
fun ModeSelector() {
    val modes = listOf(
        stringResource(R.string.mode_video),
        stringResource(R.string.mode_photo),
        stringResource(R.string.mode_portrait)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        modes.forEachIndexed { index, mode ->
            Text(
                text = mode,
                color = if (index == 1) Color.Yellow else Color.Gray, // Highlight PHOTO
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun GalleryThumbnail(uri: Uri?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .border(1.dp, Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        if (uri != null) {
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = "Gallery",
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
            .size(72.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .padding(4.dp)
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
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun FlipButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Loop, contentDescription = "Flip", tint = Color.White)
    }
}

@Composable
fun ExposureSunSlider(focusOffset: Offset, value: Float, range: Range<Int>?) {
    if (range == null) return
    
    // Draw a simple vertical line and a sun icon moving up/down
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sliderHeight = 150.dp.toPx()
        val sliderX = focusOffset.x + 60.dp.toPx() // To the right of focus ring
        val centerY = focusOffset.y
        
        // Vertical Line
        drawLine(
            color = Color.Yellow,
            start = Offset(sliderX, centerY - sliderHeight / 2),
            end = Offset(sliderX, centerY + sliderHeight / 2),
            strokeWidth = 2.dp.toPx()
        )
        
        // Sun Indicator
        // Normalize value to y-position
        val normalized = (value - range.lower) / (range.upper - range.lower) // 0..1
        val safeNormalized = if(normalized.isNaN()) 0.5f else normalized
        val indicatorY = (centerY + sliderHeight/2) - (safeNormalized * sliderHeight)
        
        drawCircle(
            color = Color.Yellow,
            radius = 6.dp.toPx(),
            center = Offset(sliderX, indicatorY)
        )
    }
}

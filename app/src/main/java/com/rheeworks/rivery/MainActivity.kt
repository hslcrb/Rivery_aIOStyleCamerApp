package com.rheeworks.rivery

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setContent { CameraApp(true) }
        } else {
            setContent { CameraApp(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraApp(hasPermission: Boolean) {
    if (hasPermission) {
        CameraScreen()
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("Camera permission denied", color = Color.White)
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // UI State
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var selectedModeIndex by remember { mutableIntStateOf(3) } // Default to PHOTO
    val modes = listOf("TIME-LAPSE", "SLO-MO", "VIDEO", "PHOTO", "PORTRAIT", "PANO")
    
    // Camera Control State
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    
    // Focus animation state
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    
    // Animation for Shutter
    var isShutterPressed by remember { mutableStateOf(false) }
    val shutterScale by animateFloatAsState(
        targetValue = if (isShutterPressed) 0.85f else 1f,
        label = "shutter"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        // ------------------------------------------------------------
        // 1. Camera Preview with Gestures (Zoom & Focus)
        // ------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        
                        // Convert UI coordinates to Camera coordinates (0.0 - 1.0)
                        val factory = DisplayOrientedMeteringPointFactory(
                            size.width.toFloat(), size.height.toFloat(),
                            cameraControl!!, // Rough assumption for demo
                            // In real app, bind preview aspect ratio logic
                        )
                        /* 
                           Note: Creating MeteringPoint requires CameraControl logic or 
                           using the internal camera methods. 
                           For this UI demo, we simulate the visual feedback primarily. 
                        */
                        
                        // Reset focus indicator after 1s
                        // logic handled in LaunchedEffect below or separate simplified effect
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
                        val cameraProvider = cameraProviderFuture.get()
                        
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        
                        val imageCapture = ImageCapture.Builder()
                            .setFlashMode(flashMode)
                            .build()

                        val cameraSelector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()

                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                            cameraControl = camera.cameraControl
                            camera.cameraControl.setZoomRatio(zoomRatio)
                            
                        } catch (exc: Exception) {
                            // Log.e("Camera", "Use case binding failed", exc)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )
            
            // Focus Indicator
            if (focusPoint != null) {
                FocusRing(offset = focusPoint!!)
                LaunchedEffect(focusPoint) {
                    kotlinx.coroutines.delay(1000)
                    focusPoint = null
                }
            }
        }

        // ------------------------------------------------------------
        // 2. Top Bar (Glassmorphism / Transparent)
        // ------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 8.dp, end = 8.dp) // Status bar spacing
                .statusBarsPadding(), // Handle notches
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flash Toggle
            IconButton(
                onClick = { 
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    },
                    contentDescription = "Flash",
                    tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.White else Color.Yellow,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Top Center Indicators (Like 'RAW' or 'Live')
            Row(
                 verticalAlignment = Alignment.CenterVertically
            ) {
                 // Live Photo Icon (Circle in Circle)
                 Box(
                     modifier = Modifier
                         .size(24.dp)
                         .clip(CircleShape)
                         .border(1.dp, Color.White, CircleShape),
                     contentAlignment = Alignment.Center
                 ) {
                     Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.Yellow)) // Live ON
                 }
            }
            
            // Settings / More
            IconButton(onClick = { /* TODO */ }) {
                 // Using a placeholder for "Filter" or "Settings"
                 Icon(
                     imageVector = Icons.Default.FlashOff, // Should be exposure or filters
                     contentDescription = "Settings",
                     tint = Color.Transparent, // Hidden for symmetry or replaced
                     modifier = Modifier.size(28.dp)
                 )
            }
        }

        // ------------------------------------------------------------
        // 3. Bottom Control Complex
        // ------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.4f)) // Semi-transparent scrim
                .padding(bottom = 32.dp)
        ) {
            
            // Zoom Controls (0.5x, 1x, 2x, 5x)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 listOf(0.5f, 1f, 2f, 5f).forEach { scale -> 
                     val isSelected = zoomRatio == scale
                     ZoomButton(
                         text = "${scale.toString().removeSuffix(".0")}x",
                         isSelected = isSelected,
                         onClick = { zoomRatio = scale }
                     )
                     Spacer(modifier = Modifier.width(16.dp))
                 }
            }

            // Scrollable Mode Selector
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                 val listState = rememberLazyListState()
                 // Simplification: Displaying static centered list for UI feel
                 // In real app: Calculate offsets and snap
                 LazyRow(
                     state = listState,
                     modifier = Modifier.fillMaxWidth(),
                     contentPadding = PaddingValues(horizontal = 150.dp), // Initial padding to center first items
                     horizontalArrangement = Arrangement.Center
                 ) {
                     itemsIndexed(modes) { index, mode ->
                         Text(
                             text = mode,
                             color = if (index == selectedModeIndex) Color.Yellow else Color.White,
                             fontSize = 13.sp,
                             fontWeight = FontWeight.Bold,
                             letterSpacing = 1.sp,
                             modifier = Modifier
                                 .padding(horizontal = 12.dp)
                                 .clickable { selectedModeIndex = index }
                         )
                     }
                 }
            }
            
            Spacer(modifier = Modifier.height(20.dp))

            // Shutter Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail (left)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.DarkGray)
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                )

                // Shutter Button (center)
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(shutterScale)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(5.dp) // inner spacing
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isShutterPressed = true
                                    tryAwaitRelease()
                                    isShutterPressed = false
                                },
                                onTap = { /* Capture Action */ }
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

                // Flip Camera (right)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) 
                                CameraSelector.LENS_FACING_FRONT 
                            else 
                                CameraSelector.LENS_FACING_BACK
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Loop,
                        contentDescription = "Flip",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ZoomButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
            .border(
                 width = 1.dp, 
                 color = if (isSelected) Color.Yellow else Color.Transparent, 
                 shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Yellow else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
             .size(44.dp)
             .clip(CircleShape)
             .clickable(
                 interactionSource = remember { MutableInteractionSource() },
                 indication = null, // No ripple for cleaner look
                 onClick = onClick
             ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun FocusRing(offset: Offset) {
    // A yellow square that blinks
    Canvas(modifier = Modifier.fillMaxSize()) {
        val size = 70.dp.toPx()
        val topLeft = Offset(offset.x - size / 2, offset.y - size / 2)
        
        drawRect(
            color = Color.Yellow,
            topLeft = topLeft,
            size = androidx.compose.ui.geometry.Size(size, size),
            style = Stroke(width = 1.5.dp.toPx())
        )
        
        // Little "Sun" indicator on the side (Simulated)
        drawLine(
             color = Color.Yellow,
             start = Offset(topLeft.x + size + 10f, topLeft.y + size/2 - 15f),
             end = Offset(topLeft.x + size + 10f, topLeft.y + size/2 + 15f),
             strokeWidth = 1.dp.toPx()
        )
    }
}

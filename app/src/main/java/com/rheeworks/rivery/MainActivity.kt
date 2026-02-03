package com.rheeworks.rivery

import android.Manifest
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Changed to AppCompatActivity for modern Localization support
class MainActivity : AppCompatActivity() {
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
    // Material Theme hook could go here
    if (hasPermission) {
        CameraScreen()
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.permission_denied), color = Color.White)
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
    
    val modes = listOf(
        stringResource(R.string.mode_timelapse),
        stringResource(R.string.mode_slomo),
        stringResource(R.string.mode_video),
        stringResource(R.string.mode_photo),
        stringResource(R.string.mode_portrait),
        stringResource(R.string.mode_pano)
    )
    
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var isShutterPressed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val shutterScale by animateFloatAsState(
        targetValue = if (isShutterPressed) 0.85f else 1f,
        label = "shutter"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        // 1. Camera Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        if (cameraControl != null) {
                            val factory = DisplayOrientedMeteringPointFactory(
                                size.width.toFloat(), size.height.toFloat(),
                                cameraControl!!
                            )
                            // In a real app, apply focus point to cameraControl here
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
            
            if (focusPoint != null) {
                FocusRing(offset = focusPoint!!)
                LaunchedEffect(focusPoint) {
                    kotlinx.coroutines.delay(1000)
                    focusPoint = null
                }
            }
        }

        // 2. Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 8.dp, end = 8.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            // Live Photo Indicator
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.Yellow))
            }
            
            // Settings Button
            IconButton(onClick = { showSettings = true }) {
                 Icon(
                     imageVector = Icons.Default.Settings,
                     contentDescription = "Settings",
                     tint = Color.White,
                     modifier = Modifier.size(28.dp)
                 )
            }
        }

        // 3. Bottom Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(bottom = 32.dp)
        ) {
            // Zoom
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

            // Mode Selector
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                 LazyRow(
                     modifier = Modifier.fillMaxWidth(),
                     contentPadding = PaddingValues(horizontal = (LocalContext.current.resources.displayMetrics.widthPixels / 2).dp - 30.dp), // Approx center
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

            // Shutter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.DarkGray)
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(shutterScale)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(5.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isShutterPressed = true
                                    tryAwaitRelease()
                                    isShutterPressed = false
                                },
                                onTap = { /* Capture */ }
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

        // Settings Dialog
        if (showSettings) {
            SettingsDialog(onDismiss = { showSettings = false })
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), // Dark Grey
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = stringResource(R.string.language_option),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LanguageItem(stringResource(R.string.lang_system), null)
                LanguageItem(stringResource(R.string.lang_en), "en")
                LanguageItem(stringResource(R.string.lang_ko), "ko")
                LanguageItem(stringResource(R.string.lang_ja), "ja")

                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            }
        }
    }
}

@Composable
fun LanguageItem(name: String, code: String?) {
    val context = LocalContext.current
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val isSelected = if (code == null) {
        currentLocales.isEmpty
    } else {
        currentLocales.toLanguageTags().contains(code)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val localeList = if (code == null) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(code)
                }
                AppCompatDelegate.setApplicationLocales(localeList)
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, color = Color.White, fontSize = 16.sp)
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.Yellow
            )
        }
    }
}

// ... Previous helper composables (ZoomButton, IconButton, FocusRing) remain the same ...
// Re-implementing them briefly to ensure file completeness if overwriting

@Composable
fun ZoomButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
            .border(1.dp, if (isSelected) Color.Yellow else Color.Transparent, CircleShape)
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
                 indication = null, 
                 onClick = onClick
             ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun FocusRing(offset: Offset) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val size = 70.dp.toPx()
        val topLeft = Offset(offset.x - size / 2, offset.y - size / 2)
        drawRect(
            color = Color.Yellow,
            topLeft = topLeft,
            size = androidx.compose.ui.geometry.Size(size, size),
            style = Stroke(width = 1.5.dp.toPx())
        )
         drawLine(
             color = Color.Yellow,
             start = Offset(topLeft.x + size + 10f, topLeft.y + size/2 - 15f),
             end = Offset(topLeft.x + size + 10f, topLeft.y + size/2 + 15f),
             strokeWidth = 1.dp.toPx()
        )
    }
}

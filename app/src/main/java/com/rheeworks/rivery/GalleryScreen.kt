package com.rheeworks.rivery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mediaList by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        mediaList = loadMedia(context)
    }

    if (selectedImageUri != null) {
        // Full Image View
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { /* Toggle controls? */ }
        ) {
            Image(
                painter = rememberAsyncImagePainter(selectedImageUri),
                contentDescription = "Full Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                 IconButton(onClick = { selectedImageUri = null }) {
                     Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                 }
            }
        }
    } else {
        // Grid View
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                 IconButton(onClick = onBack) {
                     Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                 }
                 Text("Gallery", color = Color.White, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(1.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(mediaList) { uri ->
                    Image(
                        painter = rememberAsyncImagePainter(uri),
                        contentDescription = null,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .clickable { selectedImageUri = uri },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

suspend fun loadMedia(context: Context): List<Uri> = withContext(Dispatchers.IO) {
    val uris = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    // Query images from standard DCIM/Picture folders
    val cursor = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )

    cursor?.use {
        val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (it.moveToNext()) {
            val id = it.getLong(idColumn)
            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            uris.add(contentUri)
        }
    }
    uris
}

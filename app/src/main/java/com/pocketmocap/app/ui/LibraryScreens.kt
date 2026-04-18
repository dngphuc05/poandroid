package com.pocketmocap.app.ui

import android.net.Uri
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.pocketmocap.app.capture.CaptureSessionRecorder
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.R
import com.pocketmocap.app.ui.theme.CloudWarm
import com.pocketmocap.app.ui.theme.Glass
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.MintDeep
import com.pocketmocap.app.ui.theme.SkeletonMint
import com.pocketmocap.app.ui.theme.Slate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LibraryTab { CAPTURES, MODELS }

// ── Captures ─────────────────────────────────────────────────────────────────

@Composable
fun LibraryCapturesScreen(
    activeTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val captures = CaptureSessionRecorder.allCaptureRoots(context)
        .flatMap { root -> root.listFiles()?.filter { it.isDirectory } ?: emptyList() }
        .filter { it.name.contains(searchQuery, ignoreCase = true) }
        .sortedByDescending { it.lastModified() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(CloudWarm, Color(0xFFEDEEE6))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 96.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LibraryTabSwitcher(activeTab = activeTab, onTabSelected = onTabSelected)

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Search captures...", style = MaterialTheme.typography.bodySmall)
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF306963))
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF7DB6A2),
                    unfocusedBorderColor = Color(0xFF7DB6A2),
                ),
            )

            if (captures.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No captures yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = Slate,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Press REC in Capture to save Technical/Skeleton frame logs",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    captures.forEach { capture ->
                        CaptureFolderCard(capture)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureFolderCard(folder: File) {
    val context = LocalContext.current
    val files = remember(folder) {
        folder.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.White.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Saved ${formatCaptureDate(folder.lastModified())}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate.copy(alpha = 0.72f),
            )
            Text(
                folder.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = Slate.copy(alpha = 0.58f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Share logs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MintDeep,
                    modifier = Modifier.clickable {
                        shareCaptureFiles(context, folder)
                    },
                )
                Text(
                    "Open folder",
                    style = MaterialTheme.typography.labelMedium,
                    color = MintDeep,
                    modifier = Modifier.clickable {
                        openCaptureFolder(context, folder)
                    },
                )
            }
            files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.copy(alpha = 0.84f),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { openCaptureFile(context, file) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatFileSize(file.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate.copy(alpha = 0.70f),
                    )
                }
            }
        }
    }
}

private fun openCaptureFile(context: android.content.Context, file: File) {
    val uri = file.shareUri(context)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/csv")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
    }.onFailure {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareCaptureFiles(context: android.content.Context, folder: File) {
    val files = folder.listFiles()?.filter { it.isFile } ?: emptyList()
    if (files.isEmpty()) {
        Toast.makeText(context, "No files to share", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = ArrayList(files.map { it.shareUri(context) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share capture logs"))
    }.onFailure {
        Toast.makeText(context, "No app can share these files", Toast.LENGTH_SHORT).show()
    }
}

private fun openCaptureFolder(context: android.content.Context, folder: File) {
    val uri = Uri.parse(folder.toURI().toString())
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "resource/folder")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, folder.absolutePath, Toast.LENGTH_LONG).show()
    }
}

private fun File.shareUri(context: android.content.Context): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", this)

private fun formatCaptureDate(timestampMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestampMs))

private fun formatFileSize(bytes: Long): String =
    when {
        bytes >= 1_048_576L -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1024L -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }

// ── Models ───────────────────────────────────────────────────────────────────

@Composable
fun LibraryModelsScreen(
    activeTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    vrmModels: List<PocketMocapViewModel.VrmModel>,
    activeVrmIndex: Int,
    onAddVrm: (String, Uri) -> Unit,
    onSetActiveVrm: (Int) -> Unit,
) {
    val context = LocalContext.current
    val vrmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Query the actual display name from ContentResolver
            val name = try {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                            .removeSuffix(".vrm")
                            .removeSuffix(".VRM")
                            .ifBlank { null }
                    } else null
                } ?: "Model ${vrmModels.size + 1}"
            } catch (e: Exception) {
                "Model ${vrmModels.size + 1}"
            }
            onAddVrm(name, uri)
        }
    }

    // Determine active model name for the card label
    val activeModel = vrmModels.getOrNull(activeVrmIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(CloudWarm, Color(0xFFEAECE4))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 96.dp, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LibraryTabSwitcher(activeTab = activeTab, onTabSelected = onTabSelected)

            // Active model preview card — fixed height so column can scroll
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .shadow(18.dp, MaterialTheme.shapes.extraLarge),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.library_models_backdrop),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                )
                // Mini 3D skeleton preview in the center of the card
                if (activeModel != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.5f
                        val sc = size.height * 0.28f  // scale factor
                        // Draw simplified human silhouette
                        val joints = mapOf(
                            "head" to Offset(cx, cy - sc * 0.72f),
                            "neck" to Offset(cx, cy - sc * 0.46f),
                            "ls"   to Offset(cx - sc * 0.34f, cy - sc * 0.30f),
                            "rs"   to Offset(cx + sc * 0.34f, cy - sc * 0.30f),
                            "le"   to Offset(cx - sc * 0.54f, cy + sc * 0.04f),
                            "re"   to Offset(cx + sc * 0.54f, cy + sc * 0.04f),
                            "lh"   to Offset(cx - sc * 0.17f, cy + sc * 0.12f),
                            "rh"   to Offset(cx + sc * 0.17f, cy + sc * 0.12f),
                            "lk"   to Offset(cx - sc * 0.19f, cy + sc * 0.52f),
                            "rk"   to Offset(cx + sc * 0.19f, cy + sc * 0.52f),
                            "la"   to Offset(cx - sc * 0.16f, cy + sc * 0.88f),
                            "ra"   to Offset(cx + sc * 0.16f, cy + sc * 0.88f),
                        )
                        listOf(
                            "head" to "neck", "neck" to "ls", "neck" to "rs",
                            "ls" to "le", "rs" to "re",
                            "neck" to "lh", "neck" to "rh", "lh" to "rh",
                            "lh" to "lk", "rh" to "rk", "lk" to "la", "rk" to "ra",
                        ).forEach { (a, b) ->
                            drawLine(
                                color = MintBright.copy(alpha = 0.7f),
                                start = joints.getValue(a), end = joints.getValue(b),
                                strokeWidth = 3f, cap = StrokeCap.Round,
                            )
                        }
                        joints.values.forEach { pt ->
                            drawCircle(Color.White.copy(alpha = 0.85f), radius = 5f, center = pt)
                        }
                        // Head circle
                        drawCircle(
                            color = MintBright.copy(alpha = 0.5f),
                            radius = sc * 0.16f,
                            center = joints.getValue("head"),
                            style = Stroke(width = 3f),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (activeModel != null) "Active: ${activeModel.name}" else "No model — tap + to import",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }

            // Model carousel — VRM models only + Add button
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Add New button
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEFEEE5))
                                .clickable { vrmLauncher.launch(arrayOf("*/*")) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Add, "Import VRM", tint = Ink)
                        }
                        Text("Add VRM", style = MaterialTheme.typography.labelSmall, color = Ink)
                    }
                }

                itemsIndexed(vrmModels) { index, model ->
                    val isActive = index == activeVrmIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) Brush.linearGradient(listOf(MintDeep, Mint))
                                    else Brush.linearGradient(listOf(Color(0xFF1A2830), Color(0xFF0D1D26)))
                                )
                                .clickable { onSetActiveVrm(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Avatar face portrait: head circle + face features + shoulders
                            Canvas(modifier = Modifier.size(52.dp)) {
                                val cx = size.width * 0.5f
                                val cy = size.height * 0.42f
                                val headR = size.height * 0.26f
                                val color = if (isActive) Color.White.copy(0.95f) else SkeletonMint.copy(0.85f)
                                val accentColor = if (isActive) MintBright.copy(0.6f) else Color.White.copy(0.3f)
                                // Head
                                drawCircle(color.copy(alpha = 0.25f), radius = headR, center = Offset(cx, cy))
                                drawCircle(color, radius = headR, center = Offset(cx, cy), style = Stroke(width = 2.5f))
                                // Eyes
                                val eyeY = cy - headR * 0.12f
                                drawCircle(color, radius = headR * 0.12f, center = Offset(cx - headR * 0.32f, eyeY))
                                drawCircle(color, radius = headR * 0.12f, center = Offset(cx + headR * 0.32f, eyeY))
                                // Mouth arc

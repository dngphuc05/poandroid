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

package com.sahidcode404.photonex2.ui

import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sahidcode404.photonex2.camera.LensPreferences
import com.sahidcode404.photonex2.camera.LensRoute
import com.sahidcode404.photonex2.camera.PhotonCameraEngine
import com.sahidcode404.photonex2.camera.lensDisplayName
import com.sahidcode404.photonex2.update.DevelopmentUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    engine: PhotonCameraEngine,
    updateManager: DevelopmentUpdateManager,
) {
    val state by engine.state.collectAsState()
    val preferenceVersion by engine.lensPreferences.version.collectAsState()
    val scope = rememberCoroutineScope()
    var previewView by remember { mutableStateOf<TextureView?>(null) }
    var checkedUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<DevelopmentUpdateManager.UpdateInfo?>(null) }
    var updateProgress by remember { mutableIntStateOf(-1) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { previewView?.let(engine::detachPreview) }
    }

    LaunchedEffect(state.previewReady) {
        if (state.previewReady && !checkedUpdate) {
            checkedUpdate = true
            runCatching { withContext(Dispatchers.IO) { updateManager.check() } }
                .onSuccess { info ->
                    updateInfo = info
                    engine.setUpdateAvailability(info != null, info?.versionName)
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopBar(
            updateAvailable = state.updateAvailable,
            onSettings = { engine.setSettingsOpen(true) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF101214)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).also {
                        previewView = it
                        engine.attachPreview(it)
                    }
                },
                update = { if (it.isAvailable) engine.resumeCamera() },
            )

            if (!state.previewReady) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }

            AnimatedVisibility(
                visible = state.capturing && state.progressText != null,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    modifier = Modifier.padding(top = 16.dp),
                    color = Color.Black.copy(alpha = 0.66f),
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Text(
                        state.progressText.orEmpty(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }

            state.error?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp)
                        .clickable(engine::clearError),
                    color = Color(0xCC351111),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        LensStrip(
            lenses = state.lenses,
            selectedKey = state.selectedLensKey,
            enabled = !state.capturing,
            onLens = engine::selectLens,
        )

        state.lastSaved?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = Color(0xFFB8BBC0),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        ShutterBar(
            enabled = state.previewReady && !state.capturing,
            capturing = state.capturing,
            selected = state.selectedLens,
            preferences = engine.lensPreferences,
            preferenceVersion = preferenceVersion,
            onCapture = engine::capture,
        )
    }

    if (state.settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { engine.setSettingsOpen(false) },
            containerColor = Color(0xFF17191C),
            contentColor = Color.White,
        ) {
            SettingsPanel(
                lenses = state.lenses,
                preferences = engine.lensPreferences,
                preferenceVersion = preferenceVersion,
                updateInfo = updateInfo,
                updateProgress = updateProgress,
                updateMessage = updateMessage,
                onClose = { engine.setSettingsOpen(false) },
                onCheckUpdate = {
                    updateMessage = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { updateManager.check() } }
                            .onSuccess { info ->
                                updateInfo = info
                                engine.setUpdateAvailability(info != null, info?.versionName)
                                if (info == null) updateMessage = "Development build is up to date"
                            }
                            .onFailure { updateMessage = it.message ?: "Update check failed" }
                    }
                },
                onInstallUpdate = { info ->
                    updateMessage = null
                    updateProgress = 0
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                updateManager.downloadAndVerify(info) { updateProgress = it }
                            }
                        }.onSuccess { file ->
                            updateProgress = 100
                            if (!updateManager.launchInstaller(file)) {
                                updateMessage = "Allow installs from Camera, then tap Install again"
                            }
                        }.onFailure {
                            updateProgress = -1
                            updateMessage = it.message ?: "Update failed"
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun TopBar(updateAvailable: Boolean, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = Color(0xFF1D2024), shape = RoundedCornerShape(100.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(7.dp))
                Text("RAW · DNG", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (updateAvailable) {
            Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = "Update available", tint = Color.White)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun LensStrip(
    lenses: List<LensRoute>,
    selectedKey: String?,
    enabled: Boolean,
    onLens: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(lenses, key = { it.key }) { lens ->
            val selected = lens.key == selectedKey
            Surface(
                modifier = Modifier.padding(horizontal = 3.dp).clickable(enabled = enabled) { onLens(lens.key) },
                shape = RoundedCornerShape(100.dp),
                color = if (selected) Color.White else Color(0xFF1B1E22),
            ) {
                Text(
                    lensDisplayName(lens, lenses),
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                    color = if (selected) Color.Black else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun ShutterBar(
    enabled: Boolean,
    capturing: Boolean,
    selected: LensRoute?,
    preferences: LensPreferences,
    preferenceVersion: Long,
    onCapture: () -> Unit,
) {
    val manual = selected?.let { preferences.getManualFrameCount(it.key) }
    val mode = if (manual == null) "AUTO FRAMES" else "$manual FRAMES"
    Row(
        modifier = Modifier.fillMaxWidth().height(108.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("COMPUTATIONAL RAW", color = Color(0xFFAEB3BA), fontSize = 10.sp, letterSpacing = 1.sp)
            Text(mode, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .size(78.dp)
                .border(3.dp, if (enabled) Color.White else Color.DarkGray, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color(0xFF55585D))
                .clickable(enabled = enabled, onClick = onCapture),
            contentAlignment = Alignment.Center,
        ) {
            if (capturing) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.Black, strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SettingsPanel(
    lenses: List<LensRoute>,
    preferences: LensPreferences,
    preferenceVersion: Long,
    updateInfo: DevelopmentUpdateManager.UpdateInfo?,
    updateProgress: Int,
    updateMessage: String?,
    onClose: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (DevelopmentUpdateManager.UpdateInfo) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Camera settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("RAW-only capture · per-lens burst policy", color = Color(0xFFA8ADB4), fontSize = 12.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Frames to merge", color = Color(0xFFA8ADB4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.weight(1f)) {
            items(lenses, key = { it.key }) { lens ->
                LensFrameSetting(lens, lenses, preferences, preferenceVersion)
            }
        }
        Surface(color = Color(0xFF202327), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Development OTA", fontWeight = FontWeight.Bold)
                Text(
                    "GitHub development APK is hash, package, version and signer checked before Android installer UI.",
                    color = Color(0xFFB5B9BF),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                updateMessage?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp) }
                if (updateProgress in 0..99) Text("Downloading and verifying · $updateProgress%", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCheckUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34383E)),
                    ) { Text("Check") }
                    updateInfo?.let { info ->
                        Button(onClick = { onInstallUpdate(info) }) { Text("Install ${info.versionName}") }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun LensFrameSetting(
    lens: LensRoute,
    all: List<LensRoute>,
    preferences: LensPreferences,
    preferenceVersion: Long,
) {
    val current = preferences.getManualFrameCount(lens.key)
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lensDisplayName(lens, all), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("${lens.rawSize.width}×${lens.rawSize.height} RAW", color = Color(0xFF8F949B), fontSize = 11.sp)
        }
        Spacer(Modifier.height(7.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            item { FrameChoice("Auto", current == null) { preferences.setManualFrameCount(lens.key, null) } }
            items((LensPreferences.MIN_FRAMES..LensPreferences.MAX_FRAMES).toList()) { count ->
                FrameChoice(count.toString(), current == count) { preferences.setManualFrameCount(lens.key, count) }
            }
        }
    }
}

@Composable
private fun FrameChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) Color.White else Color(0xFF262A2F),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            color = if (selected) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

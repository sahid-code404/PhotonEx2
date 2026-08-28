package com.sahidcode404.photonex2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sahidcode404.photonex2.camera.LensPreferences
import com.sahidcode404.photonex2.camera.PhotonCameraEngine
import com.sahidcode404.photonex2.ui.CameraScreen
import com.sahidcode404.photonex2.ui.PhotonTheme
import com.sahidcode404.photonex2.update.DevelopmentUpdateManager

class MainActivity : ComponentActivity() {
    private lateinit var engine: PhotonCameraEngine
    private lateinit var updateManager: DevelopmentUpdateManager
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = hasRequiredPermissions()
        if (permissionsGranted) engine.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PhotonCameraEngine(this, LensPreferences(this))
        updateManager = DevelopmentUpdateManager(this)
        permissionsGranted = hasRequiredPermissions()
        if (permissionsGranted) engine.start()

        setContent {
            PhotonTheme {
                if (permissionsGranted) {
                    CameraScreen(engine = engine, updateManager = updateManager)
                } else {
                    PermissionScreen(onGrant = { permissionLauncher.launch(requiredPermissions()) })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = hasRequiredPermissions()
        if (::engine.isInitialized && permissionsGranted) {
            engine.start()
            engine.resumeCamera()
        }
    }

    override fun onPause() {
        if (::engine.isInitialized) engine.pauseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.close()
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@androidx.compose.runtime.Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Color(0xFF17191C), shape = RoundedCornerShape(28.dp)) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Camera", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Camera permission is required for live preview and RAW_SENSOR capture. On Android 9 and older, storage permission is also required to save DNG files to DCIM/Camera.",
                    color = Color(0xFFB9BDC3),
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onGrant) { Text("Allow camera") }
            }
        }
    }
}

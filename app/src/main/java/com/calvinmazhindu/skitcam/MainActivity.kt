package com.calvinmazhindu.skitcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB96969))) {
                CameraScreen()
            }
        }
    }
}

@Composable
private fun MainActivity.CameraScreen() {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    val preferences = remember { getSharedPreferences("camera_permission", Context.MODE_PRIVATE) }
    fun hasPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    var requested by rememberSaveable {
        mutableStateOf(preferences.getBoolean("requested", false))
    }
    var canRetry by remember { mutableStateOf(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) }
    var initialRequestHandled by rememberSaveable { mutableStateOf(requested || granted) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        canRetry = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }
    val requestPermission = {
        requested = true
        preferences.edit().putBoolean("requested", true).apply()
        launcher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!initialRequestHandled) {
            initialRequestHandled = true
            requestPermission()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPermission()
                canRetry = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        if (granted) {
            CameraPreview(Modifier.fillMaxSize())
        } else {
            val needsSettings = requested && !canRetry
            CameraMessage(
                message = stringResource(
                    if (needsSettings) R.string.camera_settings
                    else if (requested) R.string.camera_denied
                    else R.string.camera_permission
                ),
                action = stringResource(if (needsSettings) R.string.open_settings else R.string.allow_camera),
                onAction = {
                    if (needsSettings) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    } else requestPermission()
                }
            )
        }
        Box(Modifier.fillMaxWidth().height(140.dp).background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
        ))
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rear_camera), color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall)
        }
        Column(
            (if (landscape) Modifier.align(Alignment.CenterEnd).width(192.dp).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                .background(if (landscape) Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                else Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    if (landscape) WindowInsetsSides.End + WindowInsetsSides.Vertical
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )).padding(horizontal = if (landscape) 16.dp else 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            val label = stringResource(R.string.recording_unavailable)
            Button(
                onClick = {}, enabled = false, shape = CircleShape,
                modifier = Modifier.size(76.dp).border(2.dp, Color(0xFF79797E), CircleShape)
                    .semantics { contentDescription = label },
                contentPadding = PaddingValues(10.dp),
                colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF29292E))
            ) {
                Box(Modifier.size(46.dp).background(Color(0xFF855151), CircleShape))
            }
            Text(label, color = Color(0xFFCBCBD0), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun CameraMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(
        start = 32.dp, end = if (landscape) 192.dp else 32.dp,
        top = if (landscape) 88.dp else 150.dp, bottom = if (landscape) 32.dp else 150.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

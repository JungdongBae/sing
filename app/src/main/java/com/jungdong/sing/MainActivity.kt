package com.jungdong.sing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import com.jungdong.sing.ui.SingApp

class MainActivity : ComponentActivity() {
    private val model: SingViewModel by viewModels()
    private var pendingMic: (() -> Unit)? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMic
        pendingMic = null
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action?.invoke()
        else if (!granted) model.error("마이크 권한이 없어 음정 측정을 시작하지 못했습니다. 권한을 허용하거나 앱 설정에서 변경해 주세요. 듣기와 박자 연습은 계속 사용할 수 있어요.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SingApp(model, onMicrophone = { action ->
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
                else { pendingMic = action; permission.launch(Manifest.permission.RECORD_AUDIO) }
            }, onSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }
}

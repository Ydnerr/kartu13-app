package id.kartu13.app

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        view = GameView(this)
        setContentView(view)
    }

    // selama voice aktif, tombol volume HP mengatur volume panggilan (suara voice), bukan volume media
    fun setVoiceVolumeControl(on: Boolean) {
        volumeControlStream = if (on) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }

    fun requestMicPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 13013)
        } else view.onMicPermissionResult(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 13013) view.onMicPermissionResult(grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.onBack()) super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }

    override fun onPause() {
        view.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        view.stop()
        super.onDestroy()
    }
}

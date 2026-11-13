package id.kartu13.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameView(this)
        setContentView(view)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.onBack()) super.onBackPressed()
    }

    override fun onDestroy() {
        view.stop()
        super.onDestroy()
    }
}

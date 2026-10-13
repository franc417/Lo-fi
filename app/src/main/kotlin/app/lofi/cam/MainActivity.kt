package app.lofi.cam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.lofi.cam.ui.CameraScreen
import app.lofi.cam.ui.LofiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LofiTheme { CameraScreen(viewModel) } }
    }
}

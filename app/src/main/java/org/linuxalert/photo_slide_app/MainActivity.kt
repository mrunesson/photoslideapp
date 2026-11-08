package org.linuxalert.photo_slide_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.linuxalert.photo_slide_app.ui.PhotoReviewApp
import org.linuxalert.photo_slide_app.ui.theme.PhotoSlideAppTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoSlideAppTheme {
                PhotoReviewApp()
            }
        }
    }
}
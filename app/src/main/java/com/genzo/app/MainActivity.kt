package com.genzo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.genzo.app.ui.GenzoNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as GenzoApplication).repository
        setContent {
            MaterialTheme {
                Surface {
                    GenzoNavHost(repository)
                }
            }
        }
    }
}

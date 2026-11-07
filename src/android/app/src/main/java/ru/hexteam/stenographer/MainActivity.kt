package ru.hexteam.stenographer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.hexteam.stenographer.ui.AppScreen
import ru.hexteam.stenographer.ui.AppViewModel
import ru.hexteam.stenographer.ui.StenoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StenoTheme {
                AppScreen(viewModel)
            }
        }
    }
}

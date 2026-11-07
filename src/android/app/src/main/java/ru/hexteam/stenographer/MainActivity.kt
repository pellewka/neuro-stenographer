package ru.hexteam.stenographer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.hexteam.stenographer.ui.MainScreen
import ru.hexteam.stenographer.ui.MainViewModel
import ru.hexteam.stenographer.ui.StenoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StenoTheme {
                MainScreen(viewModel)
            }
        }
    }
}

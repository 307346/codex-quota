package com.lcs.codexquota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lcs.codexquota.ui.AppScreen
import com.lcs.codexquota.ui.CodexQuotaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }

    @Composable
    private fun Root() {
        CodexQuotaTheme {
            val vm: MainViewModel = viewModel()
            AppScreen(vm)
        }
    }
}

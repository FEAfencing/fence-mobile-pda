package it.fence.pda

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import it.fence.pda.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // il display resta acceso durante la gara
        setContent {
            FenceTheme {
                val vm: AppViewModel = viewModel()
                val screen by vm.screen.collectAsState()
                val toast by vm.toast.collectAsState()
                val st by vm.status.collectAsState()
                BackHandler { if (!vm.back()) finish() }
                LaunchedEffect(toast) { toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show(); vm.toast.value = null } }
                // dispositivo revocato/scollegato dal gateway: torna alla schermata di associazione
                LaunchedEffect(st.authFailed) { if (st.authFailed && screen !is Screen.Pair) Toast.makeText(this@MainActivity, "PDA revocato dal gateway: riassociare", Toast.LENGTH_LONG).show() }
                Box(Modifier.fillMaxSize()) {
                    when (val s = screen) {
                        is Screen.Pair -> PairScreen(vm)
                        is Screen.Home -> HomeScreen(vm)
                        is Screen.List -> ListScreen(vm)
                        is Screen.Bout -> BoutScreen(vm, s.unitId)
                        is Screen.Sign -> SignScreen(vm, s.unitId)
                        is Screen.Messages -> MessagesScreen(vm)
                        is Screen.Settings -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

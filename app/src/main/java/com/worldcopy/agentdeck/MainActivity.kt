package com.worldcopy.agentdeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.worldcopy.agentdeck.feature.hosts.HostsScreen
import com.worldcopy.agentdeck.feature.hosts.HostsViewModel
import com.worldcopy.agentdeck.feature.hosts.KeysScreen
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentDeckTheme { AgentDeckApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDeckApp(vm: HostsViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    Scaffold(topBar = { TopAppBar(title = { Text("掌舵 · AgentDeck") }) },
        snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("⌘") }, label = { Text("主机") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("⚿") }, label = { Text("密钥") })
            }
        }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (tab) {
            0 -> HostsScreen(vm) { vm.work { error("电脑服务接入开发中") } }
            1 -> KeysScreen(vm)
        }
    } }
}

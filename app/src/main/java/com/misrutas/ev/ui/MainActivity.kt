package com.misrutas.ev.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.misrutas.ev.trip.TripService
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingAuth = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { AppTheme { AppRoot(pendingAuth) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val data = i?.data
        if (data?.scheme == "misrutasev") pendingAuth.value = data
        if (i?.getBooleanExtra(EXTRA_START_MANUAL, false) == true) {
            i.removeExtra(EXTRA_START_MANUAL)
            TripService.send(this, TripService.ACTION_MANUAL)
        }
    }

    companion object {
        const val EXTRA_START_MANUAL = "start_manual"
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    TRIPS("Rutas", Icons.AutoMirrored.Filled.List),
    STATS("Estadísticas", Icons.Filled.DateRange),
    BATTERY("Batería", Icons.Filled.Favorite),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

@Composable
fun AppRoot(pendingAuth: MutableStateFlow<Uri?>) {
    var tab by rememberSaveable { mutableStateOf(Tab.TRIPS) }
    var openTrip by rememberSaveable { mutableStateOf<Long?>(null) }
    val auth by pendingAuth.collectAsState()

    LaunchedEffect(auth) {
        if (auth != null) {
            tab = Tab.SETTINGS
            openTrip = null
        }
    }
    BackHandler(enabled = openTrip != null) { openTrip = null }
    BackHandler(enabled = openTrip == null && tab != Tab.TRIPS) { tab = Tab.TRIPS }

    Scaffold(
        bottomBar = {
            if (openTrip == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            val id = openTrip
            when {
                id != null -> TripDetailScreen(id, onBack = { openTrip = null })
                tab == Tab.TRIPS -> TripsScreen(onOpen = { openTrip = it }, onGoSettings = { tab = Tab.SETTINGS })
                tab == Tab.STATS -> StatsScreen()
                tab == Tab.BATTERY -> BatteryScreen()
                else -> SettingsScreen(auth, onAuthHandled = { pendingAuth.value = null })
            }
        }
    }
}

package com.carlauncher.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.carlauncher.companion.car.TrophyNotifier
import com.carlauncher.companion.ui.nav.CompanionNavHost
import com.carlauncher.companion.ui.theme.CompanionTheme
import io.github.jan.supabase.auth.handleDeeplinks

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Set when the activity was launched by tapping a trophy-unlocked notification, so the
     * nav host can open the Trophies screen instead of the map.
     */
    private var openTrophiesOnLaunch by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        // Beta-only activity setup (the battery-optimization exemption the push needs) — a no-op
        // object in prod, so that prompt never appears there.
        BetaAppInitializer.initializeActivity(this)
        openTrophiesOnLaunch = intent?.getBooleanExtra(TrophyNotifier.EXTRA_OPEN_TROPHIES, false) == true
        val container = (application as CompanionApp).container
        handleAuthDeepLink(intent)
        setContent {
            CompanionTheme {
                CompanionNavHost(
                    container = container,
                    openTrophies = openTrophiesOnLaunch,
                    onTrophiesOpened = { openTrophiesOnLaunch = false },
                )
            }
        }
    }

    /** The activity is `singleTop`-ish in practice: a second tap re-delivers here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(TrophyNotifier.EXTRA_OPEN_TROPHIES, false)) {
            openTrophiesOnLaunch = true
        }
        handleAuthDeepLink(intent)
    }

    /**
     * Completes a password-reset link tapped in an email.
     *
     * The activity is `singleTask`, so a link tapped while the app is already running arrives
     * at [onNewIntent] rather than a fresh [onCreate] — both paths have to be covered or the
     * reset silently does nothing.
     *
     * supabase-kt exchanges the PKCE code for a session itself; all this adds is telling the
     * repository the arriving session is a recovery, so the UI asks for a new password
     * instead of dropping the user on the map as if they had simply signed in.
     */
    private fun handleAuthDeepLink(intent: Intent?) {
        val client = (application as CompanionApp).container.supabase.client ?: return
        val data = intent?.data ?: return
        if (data.scheme != BuildConfig.AUTH_REDIRECT_SCHEME) return

        val flow = runCatching { data.getQueryParameter("flow") }.getOrNull()
        client.handleDeeplinks(
            intent = intent,
            onSessionSuccess = {
                (application as CompanionApp).container.authRepository.onDeepLinkSession(flow)
            },
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

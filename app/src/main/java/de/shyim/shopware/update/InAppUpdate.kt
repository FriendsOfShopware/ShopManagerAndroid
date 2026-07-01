package de.shyim.shopware.update

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

// Play in-app updates, Compose-friendly. Flexible by default (background download then a
// "Restart" snackbar); immediate (full-screen blocking) for high-priority releases.
//
// Tie the lifetime to the Activity: construct in onCreate (registerForActivityResult must run
// before STARTED), register it as a lifecycle observer so it checks on every resume and cleans up.
// The app reads `downloadReady` to render the Compose snackbar and calls `completeUpdate()`.
class InAppUpdateController(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    // True once a flexible update has finished downloading and is waiting to be installed.
    var downloadReady by mutableStateOf(false)
        private set

    // Priority at/above this triggers a blocking immediate update instead of a flexible one.
    private val highPriorityThreshold = 4

    // We register on a ComponentActivity (not a Fragment), so the Fragment-version lint warns
    // about an API the code never touches — suppress it.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) {
            // Cancelled or failed. Don't nag; the next resume re-checks and offers it again.
            Log.w(TAG, "In-app update flow not completed: resultCode=${result.resultCode}")
        }
    }

    private val installListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> downloadReady = true
            InstallStatus.INSTALLED, InstallStatus.CANCELED, InstallStatus.FAILED -> downloadReady = false
            else -> Unit
        }
    }

    init {
        manager.registerListener(installListener)
    }

    // Re-check on every resume: resume a stalled immediate update, and surface a flexible
    // update that finished downloading while the app was backgrounded.
    override fun onResume(owner: LifecycleOwner) {
        manager.appUpdateInfo
            .addOnSuccessListener { info -> onInfo(info) }
            .addOnFailureListener { e -> Log.w(TAG, "appUpdateInfo failed", e) }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        manager.unregisterListener(installListener)
    }

    private fun onInfo(info: AppUpdateInfo) {
        // A previously started immediate update is mid-flow → resume it.
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            start(info, AppUpdateType.IMMEDIATE)
            return
        }
        // A flexible update already downloaded (e.g. while backgrounded) → prompt to install.
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            downloadReady = true
            return
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return

        val highPriority = info.updatePriority() >= highPriorityThreshold
        when {
            highPriority && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                start(info, AppUpdateType.IMMEDIATE)
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                start(info, AppUpdateType.FLEXIBLE)
        }
    }

    private fun start(info: AppUpdateInfo, @AppUpdateType type: Int) {
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(type).build(),
            )
        }.onFailure { Log.w(TAG, "startUpdateFlowForResult failed", it) }
    }

    // Called from the "Restart" snackbar action to finish a downloaded flexible update.
    fun completeUpdate() {
        manager.completeUpdate()
    }

    companion object {
        private const val TAG = "InAppUpdate"
    }
}

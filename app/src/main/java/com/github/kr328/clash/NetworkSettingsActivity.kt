package com.github.kr328.clash

import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.compat.hasUsageAccess
import com.github.kr328.clash.common.compat.usageAccessSettingsIntent
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        val design = NetworkSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            clashRunning,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.StartAccessControlList ->
                            startActivity(AccessControlActivity::class.intent)

                        NetworkSettingsDesign.Request.StartTunPauseList ->
                            openTunPauseList()
                    }
                }
            }
        }
    }

    private suspend fun openTunPauseList() {
        if (!hasUsageAccess()) {
            startActivityForResult(
                ActivityResultContracts.StartActivityForResult(),
                usageAccessSettingsIntent(),
            )
        }

        if (!hasUsageAccess()) {
            Toast.makeText(
                this,
                com.github.kr328.clash.design.R.string.usage_access_required,
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        startActivity(
            Intent(this, AccessControlActivity::class.java)
                .putExtra(AccessControlActivity.EXTRA_TUN_PAUSE_MODE, true)
        )
    }

}

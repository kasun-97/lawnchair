package app.lawnchair.smartspace.provider

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.provider.Settings
import androidx.core.content.getSystemService
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.util.broadcastReceiverFlow
import com.android.launcher3.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Shows a card while Do Not Disturb is on; tapping it opens the Do Not Disturb settings. */
class DoNotDisturbProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_dnd,
        { smartspaceDnd },
    ) {
    private val notificationManager = context.getSystemService<NotificationManager>()
    private val icon = Icon.createWithResource(context, R.drawable.ic_do_not_disturb)

    override val internalTargets = broadcastReceiverFlow(
        context,
        IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
    )
        .map { }
        .onStart { emit(Unit) }
        .map { listOfNotNull(getSmartspaceTarget()) }
        .distinctUntilChanged()

    private fun getSmartspaceTarget(): SmartspaceTarget? {
        val subtitle = when (notificationManager?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> R.string.dnd_priority_only
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> R.string.dnd_alarms_only
            NotificationManager.INTERRUPTION_FILTER_NONE -> R.string.dnd_total_silence
            else -> return null
        }
        return SmartspaceTarget(
            id = "doNotDisturb",
            headerAction = SmartspaceAction(
                id = "doNotDisturbAction",
                icon = icon,
                title = context.getString(R.string.dnd_on),
                subtitle = context.getString(subtitle),
                intent = settingsIntent(),
            ),
            score = SmartspaceScores.SCORE_DND,
            featureType = SmartspaceTarget.FeatureType.FEATURE_UNDEFINED,
        )
    }

    private fun settingsIntent(): Intent {
        // The Do Not Disturb page has no public action constant; fall back to sound settings.
        val dnd = Intent(ZEN_MODE_SETTINGS)
        return if (dnd.resolveActivity(context.packageManager) != null) dnd else Intent(Settings.ACTION_SOUND_SETTINGS)
    }

    companion object {
        private const val ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
    }
}

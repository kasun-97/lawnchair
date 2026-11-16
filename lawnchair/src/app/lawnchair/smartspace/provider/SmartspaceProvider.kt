package app.lawnchair.smartspace.provider

import android.app.Activity
import android.content.Context
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Smartspace
import app.lawnchair.util.dropWhileBusy
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

@LauncherAppSingleton
class SmartspaceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val googleWidget = SmartspaceWidgetReader(context)
    private val weather = WeatherProvider(context)
    private val calendarEvents = CalendarEventsProvider(context)

    val dataSources = listOf(
        googleWidget,
        weather,
        calendarEvents,
        BatteryStatusProvider(context),
        TorchProvider(context),
        NextAlarmProvider(context),
        DoNotDisturbProvider(context),
        NowPlayingProvider(context),
        OnboardingProvider(context),
    )

    private val state = dataSources
        .map(::stateOf)
        .reduce { acc, flow -> flow.combine(acc) { a, b -> a + b } }
        // With the phone calendar card on, the Google widget only supplies weather.
        .combine(calendarEvents.enabledPref.get()) { state, calendarOn ->
            if (calendarOn) {
                state.copy(targets = state.targets.filterNot { it.id == SmartspaceWidgetReader.EVENT_CARD_ID })
            } else {
                state
            }
        }
        .shareIn(
            MainScope(),
            SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    /**
     * The Open-Meteo weather source replaces the Google widget: while it is on, the widget's
     * cards and setup request are dropped; while it is off, its own placeholder date page is dropped.
     */
    private fun stateOf(source: SmartspaceDataSource): Flow<SmartspaceDataSource.State> = when (source) {
        googleWidget -> source.targets.combine(weather.enabledPref.get()) { state, weatherOn ->
            if (weatherOn) SmartspaceDataSource.State() else state
        }

        weather -> source.targets.combine(weather.enabledPref.get()) { state, weatherOn ->
            if (weatherOn) state else SmartspaceDataSource.State()
        }

        else -> source.targets
    }

    val targets = state
        .map {
            if (it.requiresSetup.isNotEmpty()) {
                listOf(setupTarget) + it.targets
            } else {
                it.targets
            }
        }
    val previewTargets = state
        .map { it.targets }

    private val setupTarget = SmartspaceTarget(
        id = "smartspaceSetup",
        headerAction = SmartspaceAction(
            id = "smartspaceSetupAction",
            title = context.getString(R.string.smartspace_requires_setup),
            intent = PreferenceActivity.createIntent(context, Smartspace),
        ),
        score = 999f,
        featureType = SmartspaceTarget.FeatureType.FEATURE_TIPS,
    )

    suspend fun startSetup(activity: Activity) {
        state
            .map { it.requiresSetup }
            .dropWhileBusy()
            .collect { sources ->
                sources.forEach {
                    it.startSetup(activity)
                    it.onSetupDone()
                }
            }
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    companion object {
        @JvmField val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getSmartspaceProvider)
    }
}

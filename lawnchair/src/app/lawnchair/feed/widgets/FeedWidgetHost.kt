package app.lawnchair.feed.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.util.SparseArray
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.SingletonHolder
import app.lawnchair.util.ensureOnMainThread
import app.lawnchair.util.useApplicationContext
import com.android.launcher3.pm.UserCache
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the widgets of the built-in feed. They are bound under their own host id, so the home
 * screen's widget bookkeeping never sees them.
 *
 * Adding is a small flow, because the system may first ask for permission to bind and the widget
 * may then show its own set-up screen: [add], then [onBindResult] and [onActivityResult] as
 * those screens return. The widget is only saved once both have gone through.
 */
class FeedWidgetHost private constructor(private val context: Context) {

    private class Host(context: Context) : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?,
        ): AppWidgetHostView = FeedWidgetHostView(context)

        fun releaseViews() = clearViews()
    }

    private class Pending(val appWidgetId: Int, val info: AppWidgetProviderInfo)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = PreferenceManager2.getInstance(context)
    private val manager = AppWidgetManager.getInstance(context)
    private val host = Host(context)

    private val views = SparseArray<AppWidgetHostView>()
    private var viewsOwner: Context? = null
    private var listening = false
    private var pending: Pending? = null

    /** Told how an [add] ended, on the main thread. */
    var onAddFinished: ((added: Boolean) -> Unit)? = null

    // Showing widgets

    fun startListening() {
        if (listening) return
        // Can fail when the system holds more pending widget updates than fit in one call.
        runCatching { host.startListening() }
            .onSuccess { listening = true }
            .onFailure { Log.w(TAG, "Failed to start listening for widget updates", it) }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { host.stopListening() }
    }

    /** The live view of a widget, made for [owner]; null when the widget no longer exists. */
    fun getView(owner: Context, appWidgetId: Int): AppWidgetHostView? {
        if (viewsOwner !== owner) {
            views.clear()
            viewsOwner = owner
        }
        views[appWidgetId]?.let { return it }
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        return runCatching { host.createView(owner, appWidgetId, info) }
            .onFailure { Log.w(TAG, "Failed to create the view of widget $appWidgetId", it) }
            .getOrNull()
            ?.also { views.put(appWidgetId, it) }
    }

    /** Drops the views made for [owner], which is going away. */
    fun releaseViews(owner: Context) {
        if (viewsOwner !== owner) return
        views.clear()
        viewsOwner = null
        host.releaseViews()
    }

    // Choosing and adding widgets

    /** Widgets that can be added, from every profile. Slow: call off the main thread. */
    fun availableProviders(): List<AppWidgetProviderInfo> = UserCache.INSTANCE.get(context).userProfiles
        .flatMap { user -> runCatching { manager.getInstalledProvidersForProfile(user) }.getOrDefault(emptyList()) }
        .filter { info ->
            val homeScreen = info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
            val hidden = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0
            homeScreen && !hidden
        }

    /** What [info]'s app offers as a preview, best first. Slow: call off the main thread. */
    fun loadPreview(info: AppWidgetProviderInfo): FeedWidgetPreviewSource {
        val homeScreen = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            info.generatedPreviewCategories and homeScreen != 0
        ) {
            val views = runCatching { manager.getWidgetPreview(info.provider, info.profile, homeScreen) }.getOrNull()
            if (views != null) return FeedWidgetPreviewSource.Layout(info, views)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.previewLayout != 0) {
            // A host view draws the initial layout of a widget that has no content yet.
            val previewInfo = info.clone().apply { initialLayout = info.previewLayout }
            return FeedWidgetPreviewSource.Layout(previewInfo, null)
        }
        val drawable = runCatching { info.loadPreviewImage(context, 0) }.getOrNull()
        return if (drawable != null) FeedWidgetPreviewSource.Image(drawable) else FeedWidgetPreviewSource.None
    }

    /**
     * Starts adding [info]. [launchBind] must show the given system screen and report its result
     * to [onBindResult].
     */
    fun add(activity: Activity, info: AppWidgetProviderInfo, launchBind: (Intent) -> Unit) {
        cancelPending(notify = false)
        val appWidgetId = host.allocateAppWidgetId()
        pending = Pending(appWidgetId, info)
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(appWidgetId, info.profile, info.provider, null) }
            .getOrDefault(false)
        if (bound) {
            onBound(activity)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            runCatching { launchBind(intent) }.onFailure { cancelPending(notify = true) }
        }
    }

    fun onBindResult(activity: Activity, allowed: Boolean) {
        if (allowed) onBound(activity) else cancelPending(notify = true)
    }

    /** @return whether the result belonged to a widget's set-up screen. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CONFIGURE) return false
        if (resultCode == Activity.RESULT_OK) finishAdd() else cancelPending(notify = true)
        return true
    }

    private fun onBound(activity: Activity) {
        val current = pending ?: return
        if (!needsSetUp(current.info)) {
            finishAdd()
            return
        }
        runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, current.appWidgetId, 0, REQUEST_CONFIGURE, null)
        }.onFailure {
            Log.w(TAG, "Failed to open the set-up screen of ${current.info.provider}", it)
            cancelPending(notify = true)
        }
    }

    private fun needsSetUp(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val optional = AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL or
                AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
            if (info.widgetFeatures and optional == optional) return false
        }
        return true
    }

    private fun finishAdd() {
        val current = pending ?: return
        pending = null
        scope.launch {
            val widgets = prefs.feedWidgets.get().first()
            var id = System.currentTimeMillis()
            while (widgets.any { it.id == id }) id++
            val widget = FeedWidget(
                id = id,
                appWidgetId = current.appWidgetId,
                label = current.info.loadLabel(context.packageManager).orEmpty(),
                heightDp = defaultHeightDp(current.info),
            )
            prefs.feedWidgets.set(widgets + widget)
            onAddFinished?.invoke(true)
        }
    }

    private fun cancelPending(notify: Boolean) {
        val current = pending ?: return
        pending = null
        runCatching { host.deleteAppWidgetId(current.appWidgetId) }
        if (notify) onAddFinished?.invoke(false)
    }

    /** Height for a widget that was designed for a grid: about one home screen row per cell. */
    private fun defaultHeightDp(info: AppWidgetProviderInfo): Int = (spanY(info) * CELL_HEIGHT_DP)
        .coerceIn(FeedWidget.MIN_HEIGHT_DP, DEFAULT_HEIGHT_LIMIT_DP)

    fun spanX(info: AppWidgetProviderInfo): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0) return info.targetCellWidth
        return cellsFor(info.minWidth)
    }

    fun spanY(info: AppWidgetProviderInfo): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) return info.targetCellHeight
        return cellsFor(info.minHeight)
    }

    /** The platform's rule of thumb: n cells need 70n - 30 dp. */
    private fun cellsFor(minSizePx: Int): Int {
        val dp = minSizePx / context.resources.displayMetrics.density
        return ceil((dp + 30) / 70.0).toInt().coerceAtLeast(1)
    }

    // Removing widgets

    suspend fun remove(widget: FeedWidget) {
        prefs.feedWidgets.set(prefs.feedWidgets.get().first().filterNot { it.id == widget.id })
        views.remove(widget.appWidgetId)
        runCatching { host.deleteAppWidgetId(widget.appWidgetId) }
    }

    /** Frees ids that were bound but never saved, such as from an add that was interrupted. */
    suspend fun deleteOrphans() {
        val kept = prefs.feedWidgets.get().first().map { it.appWidgetId }.toSet()
        val ids = runCatching { host.appWidgetIds }.getOrNull() ?: return
        ids.filter { it !in kept && it != pending?.appWidgetId }
            .forEach { id -> runCatching { host.deleteAppWidgetId(id) } }
    }

    companion object : SingletonHolder<FeedWidgetHost, Context>(
        ensureOnMainThread(
            useApplicationContext(::FeedWidgetHost),
        ),
    ) {
        private const val TAG = "FeedWidgetHost"

        // The home screen uses 1024 and the launcher's headless widgets 1028.
        private const val HOST_ID = 1029
        private const val REQUEST_CONFIGURE = 0x4657
        private const val CELL_HEIGHT_DP = 96

        // Tall widgets would otherwise start out filling most of the screen.
        private const val DEFAULT_HEIGHT_LIMIT_DP = 384
    }
}

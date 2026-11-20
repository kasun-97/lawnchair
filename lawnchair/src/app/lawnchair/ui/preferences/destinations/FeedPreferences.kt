package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.navigation.HomeScreenFeed
import com.android.launcher3.R

/** Entry in the home screen settings, shown while the built-in feed is selected. */
@Composable
fun FeedPreferenceItem(
    modifier: Modifier = Modifier,
) {
    NavigationActionPreference(
        label = stringResource(R.string.feed_built_in),
        destination = HomeScreenFeed,
        subtitle = stringResource(R.string.feed_built_in_description),
        modifier = modifier,
    )
}

/** Everything the built-in feed shows; also opened from the feed itself. */
@Composable
fun FeedPreferences(
    modifier: Modifier = Modifier,
) {
    PreferenceLayout(
        label = stringResource(R.string.feed_built_in),
        backArrowVisible = true,
        modifier = modifier,
    ) {
        // The same switch as in the home screen settings, so the feed can be turned off from here.
        PreferenceGroup {
            SwitchPreference(
                adapter = preferenceManager2().enableFeed.getAdapter(),
                label = stringResource(R.string.minus_one_enable),
            )
        }
        PreferenceGroup(heading = stringResource(R.string.feed_content_label)) {
            FeedWidgetsPreferenceItem()
            FeedSectionsPreferenceItem()
            FeedSourcesPreferenceItem()
        }
    }
}

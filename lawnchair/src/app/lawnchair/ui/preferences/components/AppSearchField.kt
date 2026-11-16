package app.lawnchair.ui.preferences.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.lawnchair.util.App
import com.android.launcher3.R

/**
 * Lawnchair: a search field for app pickers. It sticks to the top of the list while scrolling,
 * so the filter stays in reach on long app lists.
 */
fun LazyListScope.appSearchField(query: String, onQueryChange: (String) -> Unit) {
    stickyHeader(key = "app_search_field", contentType = "app_search_field") {
        AppSearchField(query = query, onQueryChange = onQueryChange)
    }
}

/** Shows a "no apps found" line when a search leaves nothing to show. */
fun LazyListScope.appSearchEmpty(query: String) {
    item(key = "app_search_empty", contentType = "app_search_empty") {
        Text(
            text = stringResource(R.string.all_apps_no_search_results, query.trim()),
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Apps whose label contains [query], ignoring case. A blank query keeps every app. */
fun List<App>.filterByQuery(query: String): List<App> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.label.contains(q, ignoreCase = true) }
}

@Composable
fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.all_apps_search_bar_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.app_search_clear))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

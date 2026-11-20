package co.magicpages.socialweb.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.BuildConfig
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.onboarding.SilkMark
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter

/** The app's page; the Website row opens it. */
internal const val WebsiteUrl = "https://www.magicpages.co"

/**
 * The page the Privacy policy row opens: this app's own policy, a page on magicpages.co (slug `privacy-android`).
 * Must be the same URL as the one set in the Play Console listing (see RELEASING.md).
 */
internal const val PrivacyPolicyUrl = "https://www.magicpages.co/legal/privacy/android/"

/**
 * The app's icon, name and version, links to its website and privacy policy, the open-source
 * licenses, and a way back to the welcome pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit, onOpenWelcome: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header()
                AboutRows(
                    listOf(
                        AboutRow(AppIcons.Public, R.string.about_website, external = true) { uriHandler.openUri(WebsiteUrl) },
                        AboutRow(AppIcons.PrivacyTip, R.string.about_privacy, external = true) { uriHandler.openUri(PrivacyPolicyUrl) },
                        AboutRow(AppIcons.Description, R.string.about_licenses, onClick = onOpenLicenses),
                    ),
                )
                AboutRows(
                    listOf(AboutRow(AppIcons.WavingHand, R.string.about_welcome, onClick = onOpenWelcome)),
                    Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.about_trademark, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The app's mark, name, and version name with the version code in brackets. */
@Composable
private fun Header() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SilkMark(size = 96.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A row of the About lists; [external] adds the arrow that says it opens outside the app. */
private class AboutRow(
    val icon: ImageVector,
    @StringRes val label: Int,
    val external: Boolean = false,
    val onClick: () -> Unit,
)

/** Rows as one M3 segmented list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutRows(rows: List<AboutRow>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row ->
            SegmentedListItem(
                onClick = row.onClick,
                shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(row.icon, contentDescription = null) },
                trailingContent = if (row.external) {
                    { Icon(AppIcons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    null
                },
            ) {
                Text(stringResource(row.label))
            }
        }
    }
}

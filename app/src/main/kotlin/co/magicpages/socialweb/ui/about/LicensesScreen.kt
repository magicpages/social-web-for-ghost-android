package co.magicpages.socialweb.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.style.LibraryActionBadges

/**
 * Every library the app ships with and its license, as collected at build time by the AboutLibraries
 * Gradle plugin; tapping a library shows its license text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxHeight(),
                // The list scrolls under the system bars; its content starts below the top bar.
                contentPadding = padding,
                // Nothing here is funded through the app, so no sponsor chip; the labels follow the app's language.
                actionLabels = LibraryActionBadges(
                    sponsorEnabled = false,
                    source = stringResource(R.string.about_lib_source),
                    website = stringResource(R.string.about_website),
                    viewLicense = stringResource(R.string.about_lib_view_license),
                ),
                licenseDialogConfirmText = stringResource(R.string.about_done),
            )
        }
    }
}

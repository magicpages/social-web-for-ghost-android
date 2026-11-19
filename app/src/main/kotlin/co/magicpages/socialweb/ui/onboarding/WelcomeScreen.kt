package co.magicpages.socialweb.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.theme.Gutter

/**
 * First run: what the app is, what its four tabs do, what the site needs, and the way on to
 * adding a site. One scrolling column, so large fonts and landscape only make it longer; the
 * button stays pinned below it. Shown again from About as a tour, [onContinue] closes it and
 * [continueLabel] says so.
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit, @StringRes continueLabel: Int = R.string.onboarding_add_site) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SilkFlow(size = 112.dp)
                    Column(
                        Modifier.padding(horizontal = Gutter),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                        Text(
                            stringResource(R.string.onboarding_tagline),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        stringResource(R.string.onboarding_connected),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = Gutter),
                    )
                    Column(Modifier.fillMaxWidth()) {
                        Feature(AppIcons.Notes, R.string.nav_notes, R.string.onboarding_notes)
                        Feature(AppIcons.Reader, R.string.nav_reader, R.string.onboarding_reader)
                        Feature(AppIcons.Explore, R.string.nav_explore, R.string.onboarding_explore)
                        Feature(AppIcons.Notifications, R.string.nav_notifications, R.string.onboarding_notifications)
                    }
                    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.onboarding_ownership), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.onboarding_requirements),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Button(onClick = onContinue, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().height(56.dp)) {
                    Text(stringResource(continueLabel))
                }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, body: Int) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * The app's silk mark: the launcher icon's two layers, masked to a circle. The layers are 108 dp
 * with the artwork inside the central 72 dp, so they are drawn 1.5 times the mark's size. Still;
 * the welcome page's flowing version is [SilkFlow].
 */
@Composable
fun SilkMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}

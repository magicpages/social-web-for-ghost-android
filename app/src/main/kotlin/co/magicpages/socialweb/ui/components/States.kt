package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.fediverse.PublicServerException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.theme.Gutter

@Composable
fun errorMessage(error: Throwable): String = when (error) {
    is GhostException.Unauthorized -> stringResource(R.string.session_ended)
    is GhostException.Network -> stringResource(R.string.error_network)
    // A 2xx here means the body didn't parse, not that the server refused.
    is SocialWebException -> if (error.status in 200..299) {
        stringResource(R.string.error_unreadable)
    } else {
        stringResource(R.string.error_unexpected, error.status)
    }
    is GhostException.Unexpected -> stringResource(R.string.error_unexpected, error.status)
    is PublicServerException -> when {
        error.notPublic -> stringResource(R.string.fediverse_not_public, error.server)
        error.status in 200..299 -> stringResource(R.string.error_unreadable)
        else -> stringResource(R.string.error_unexpected, error.status)
    }
    else -> error.message.orEmpty()
}

/** A whole-screen failure. Offers signing in again when the session ended and [onSignOut] is given. */
@Composable
fun FullScreenError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    sessionEnded: Boolean = false,
    onSignOut: (() -> Unit)? = null,
    secondary: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = Gutter, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (sessionEnded && onSignOut != null) {
            Button(onClick = onSignOut) { Text(stringResource(R.string.sign_in_again)) }
        } else {
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        secondary()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

/** The footer of a paged list: a loading indicator while the next page loads, retry when it failed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppendState(state: LoadState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 16.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is LoadState.Loading -> LoadingIndicator()
            is LoadState.Error -> TextButton(onClick = onRetry) { Text(stringResource(R.string.load_more_failed)) }
            is LoadState.NotLoading -> Unit
        }
    }
}

/** Lives in a LazyColumn so pull-to-refresh still works when there's nothing else to scroll. */
@Composable
fun ScrollableMessage(text: String, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize().padding(horizontal = Gutter, vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

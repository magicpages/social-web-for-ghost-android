package co.magicpages.socialweb.ui.profile

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.posts.rememberCopyToClipboard
import co.magicpages.socialweb.ui.quote.QuoteCardPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the share sheet's "Share as image" produced. */
sealed interface ProfileCardResult {
    class Ready(val image: Uri, val url: String) : ProfileCardResult
    data object Failed : ProfileCardResult
}

/** Draws the profile card for the share sheet, off the main thread; one at a time. */
@HiltViewModel
class ProfileShareViewModel @Inject constructor(private val cards: ProfileCardRenderer) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _results = MutableSharedFlow<ProfileCardResult>(extraBufferCapacity = 1)
    val results: Flow<ProfileCardResult> = _results

    fun shareImage(card: ProfileCardSpec, palette: QuoteCardPalette) {
        if (!_busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val image = try {
                cards.renderShareable(card, palette)
            } finally {
                _busy.update { false }
            }
            _results.emit(if (image == null) ProfileCardResult.Failed else ProfileCardResult.Ready(image, card.url))
        }
    }
}

/**
 * The profile's share sheet: a QR code of its link for someone nearby to scan, the handle with a
 * copy button, and Share link and Share as image (a card with avatar, name, handle and the code).
 * [url] is null only when nothing is known about where the profile lives; the sheet then has the handle alone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileShareSheet(name: String, handle: String, avatarUrl: String?, url: String?, onDismiss: () -> Unit) {
    val viewModel = hiltViewModel<ProfileShareViewModel>()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val copyHandle = rememberCopyToClipboard(stringResource(R.string.share_profile_handle), stringResource(R.string.share_profile_handle_copied))
    val palette = QuoteCardPalette.of(MaterialTheme.colorScheme)
    val failedMessage = stringResource(R.string.share_profile_image_failed)
    val qr = remember(url) { url?.let(QrCode::encode) }
    val displayName = name.ifBlank { handle }

    LaunchedEffect(viewModel) {
        viewModel.results.collect { result ->
            when (result) {
                is ProfileCardResult.Ready -> shareProfileImage(context, result.image, result.url)
                ProfileCardResult.Failed -> android.widget.Toast.makeText(context, failedMessage, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    ActionSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(avatarUrl, displayName, 48.dp)
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (qr != null) QrCodeImage(qr, stringResource(R.string.share_profile_qr, handle), Modifier.size(232.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    handle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = { copyHandle(handle) }) {
                    Icon(AppIcons.ContentCopy, contentDescription = stringResource(R.string.share_profile_copy_handle))
                }
            }
            if (url != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { shareProfileLink(context, url) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.Link, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.share_profile_link), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    Button(
                        onClick = { viewModel.shareImage(ProfileCardSpec(name, handle, avatarUrl, url), palette) },
                        enabled = !busy && qr != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                        } else {
                            Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        }
                        Text(stringResource(R.string.share_profile_image), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
        }
    }
}

/** [matrix] as a QR code: dark modules on a white panel with the quiet zone, whatever the theme, since scanners expect that. */
@Composable
fun QrCodeImage(matrix: QrMatrix, description: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.large).semantics { contentDescription = description }) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(Color.White, Offset.Zero, size, CornerRadius.Zero)
            val modules = matrix.size + 2 * QrMatrix.QuietZone
            // Whole pixels per module, so the modules are crisp and equally sized; the leftover is margin.
            val cell = (minOf(size.width, size.height) / modules).toInt().coerceAtLeast(1).toFloat()
            // A whole-pixel origin too: a fractional one blurs every row edge into a light seam.
            val origin = Offset(((size.width - cell * modules) / 2f).toInt().toFloat(), ((size.height - cell * modules) / 2f).toInt().toFloat())
            for (y in 0 until matrix.size) {
                var x = 0
                while (x < matrix.size) {
                    if (!matrix[x, y]) {
                        x++
                        continue
                    }
                    // A run of dark modules is one rectangle.
                    val start = x
                    while (x < matrix.size && matrix[x, y]) x++
                    drawRect(
                        Color.Black,
                        origin + Offset((start + QrMatrix.QuietZone) * cell, (y + QrMatrix.QuietZone) * cell),
                        Size((x - start) * cell, cell),
                    )
                }
            }
        }
    }
}

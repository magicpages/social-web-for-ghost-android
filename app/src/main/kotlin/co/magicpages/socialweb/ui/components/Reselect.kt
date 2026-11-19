package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Runs [action] each time [reselections] (how often the tab showing this screen was tapped while
 * selected) goes up. A tab leaves composition while another is shown; the count it last handled
 * is saved, so coming back doesn't count as a tap.
 */
@Composable
fun OnReselect(reselections: Int, action: suspend () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(reselections) }
    val currentAction by rememberUpdatedState(action)
    LaunchedEffect(reselections) {
        if (reselections != handled) {
            handled = reselections
            currentAction()
        }
    }
}

/** Back to the first item. From far down it jumps most of the way first: animating past hundreds of items takes seconds. */
suspend fun LazyListState.scrollToTop() {
    if (firstVisibleItemIndex > JUMP_FROM) scrollToItem(JUMP_FROM)
    animateScrollToItem(0)
}

private const val JUMP_FROM = 8

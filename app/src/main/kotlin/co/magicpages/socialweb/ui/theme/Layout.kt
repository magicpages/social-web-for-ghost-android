package co.magicpages.socialweb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The space between the screen edge and content, on every screen. It matches the 16 dp inset of
 * Material list items, so posts, lists, headers and articles all start on the same line.
 */
val Gutter = 16.dp

/** Long-form reading text: article bodies and the post a thread is about. A step up from bodyLarge. */
val Typography.reading: TextStyle get() = bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp)

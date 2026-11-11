package com.glasslauncher.tv.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import com.glasslauncher.tv.R

/**
 * Inter: the open typeface closest to Apple's SF Pro (SF itself may only ship on Apple devices).
 * Letters it lacks, such as Arabic, fall back to the system font automatically.
 */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Bold text (Settings → Menu & themes): every weight one step heavier, everywhere in Glassio. */
val BoldInter = FontFamily(
    Font(R.font.inter_medium, FontWeight.Light),
    Font(R.font.inter_semibold, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object TextLook { val bold = androidx.compose.runtime.mutableStateOf<Boolean?>(null) }

/** Apple TV's secondary text (synopsis, metadata): soft grey rather than white. */
val AppleSecondary = androidx.compose.ui.graphics.Color(0xFFB3B3B8)

@Composable
fun GlassType(content: @Composable () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.glasslauncher.tv.GlassApp
    val bold = TextLook.bold.value ?: (app?.settings?.boldText == true).also { TextLook.bold.value = it }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(
            fontFamily = if (bold) BoldInter else Inter,
            // The TV draws apps at 1080p and scales them up to 4K: Regular strokes go soft, Medium stays crisp.
            fontWeight = FontWeight.Medium,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
        content = content,
    )
}

// The words of the interface in the chosen language.
package com.glasslauncher.tv.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.glasslauncher.tv.data.I18n

/**
 * Every text on Glassio's screens: shown in the chosen language when the language's table has these exact words
 * (Settings → System → Language), as written otherwise. It stands in for Material's Text in this package, so a
 * new screen is translated without doing anything: only its words have to be in the tables (`assets/i18n`).
 *
 * A name that isn't Glassio's own (a film's title, a song, a profile's name) is drawn with [Plain]: a film called
 * Home (the film) must not turn into the word for the Home button.
 */
@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE, style: TextStyle = LocalTextStyle.current,
) {
    // (Read here: with the same words given again nothing would draw this text anew when the language changes.)
    I18n.rev.intValue
    androidx.compose.material3.Text(I18n.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
    letterSpacing = letterSpacing, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines, style = style)
}

/** Words in more than one style (a row's name with its title): as they are. */
@Composable
fun Text(
    text: AnnotatedString, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE, style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(text, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
    letterSpacing = letterSpacing, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines, style = style)

/** A name that isn't Glassio's own word: never translated. */
@Composable
fun Plain(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE, style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(text, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
    letterSpacing = letterSpacing, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines, style = style)

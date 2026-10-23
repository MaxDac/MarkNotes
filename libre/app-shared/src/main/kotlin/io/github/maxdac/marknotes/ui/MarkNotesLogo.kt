package io.github.maxdac.marknotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.maxdac.marknotes.shared.R

/** The MarkNotes mark (page with the markdown "M↓"), generated from branding/marknotes-mark.svg. */
@Composable
fun MarkNotesLogo(modifier: Modifier = Modifier, size: Dp = 32.dp, contentDescription: String? = null) {
    Image(
        painter = painterResource(R.drawable.marknotes_mark),
        contentDescription = contentDescription,
        modifier = modifier.size(size)
    )
}

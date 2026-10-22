package io.github.maxdac.marknotes.ui.chords

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.Instrument
import io.github.maxdac.marknotes.core.notes.StringSelection

const val FRETBOARD_TAG = "fretboard"

private val LABEL_WIDTH = 40.dp
private val CELL = 44.dp
private val FINGER_WIDTH = 64.dp

/**
 * The chord builder fretboard ported from ChordBuilder: one row per string (lowest first) with mute, open,
 * one button per fret and a finger picker. Scrolls horizontally; bass shows frets 9-24 with a red line at 12.
 */
@Composable
fun Fretboard(
    instrument: Instrument,
    strings: List<StringSelection>,
    onChange: (string: Int, selection: StringSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    val frets = ChordSequences.fretRange(instrument).toList()
    val redLine = MaterialTheme.colorScheme.error
    Column(
        modifier
            .testTag(FRETBOARD_TAG)
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("String", LABEL_WIDTH)
            HeaderCell("Mute", CELL)
            HeaderCell("Open", CELL)
            frets.forEach { fret ->
                val marker = fret in ChordSequences.markerFrets
                Text(
                    fret.toString(),
                    modifier = Modifier.width(CELL).redLine(instrument, fret, redLine),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (marker) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.run { if (marker) primary else onSurfaceVariant }
                )
            }
            HeaderCell("Finger", FINGER_WIDTH)
        }
        instrument.tuning.forEachIndexed { index, tuning ->
            val selection = strings.getOrElse(index) { StringSelection.Muted }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tuning,
                    modifier = Modifier.width(LABEL_WIDTH),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                ToggleCell("×", "Mute $tuning", selection == StringSelection.Muted) {
                    onChange(index, StringSelection.Muted)
                }
                ToggleCell("O", "Open $tuning", selection == StringSelection.Open) {
                    onChange(index, StringSelection.Open)
                }
                frets.forEach { fret ->
                    val fretted = selection as? StringSelection.Fretted
                    FretCell(
                        fret = fret,
                        tuning = tuning,
                        finger = fretted?.finger?.takeIf { fretted.fret == fret },
                        marker = fret in ChordSequences.markerFrets,
                        modifier = Modifier.redLine(instrument, fret, redLine)
                    ) {
                        onChange(index, StringSelection.Fretted(fret, fretted?.finger ?: 1))
                    }
                }
                FingerPicker(tuning, selection as? StringSelection.Fretted) { finger ->
                    (selection as? StringSelection.Fretted)?.let { onChange(index, it.copy(finger = finger)) }
                }
            }
        }
    }
}

private fun Modifier.redLine(instrument: Instrument, fret: Int, color: Color): Modifier =
    if (instrument == Instrument.BASS && fret == ChordSequences.RED_LINE_FRET) {
        drawBehind {
            drawLine(color, Offset(size.width - 1.5f, 0f), Offset(size.width - 1.5f, size.height), strokeWidth = 3f)
        }
    } else {
        this
    }

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(
        text,
        modifier = Modifier.width(width),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ToggleCell(symbol: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(CELL)
            .padding(4.dp)
            .background(if (selected) colors.secondaryContainer else Color.Transparent, CircleShape)
            .border(1.dp, if (selected) colors.secondary else colors.outlineVariant, CircleShape)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
    }
}

@Composable
private fun FretCell(
    fret: Int,
    tuning: String,
    finger: Int?,
    marker: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(CELL)
            .background(if (marker) colors.surfaceVariant else Color.Transparent)
            .drawBehind {
                // The string, then the fret wire on the right.
                drawLine(colors.outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
                drawLine(colors.outlineVariant, Offset(size.width, 0f), Offset(size.width, size.height), 2f)
            }
            .semantics {
                contentDescription = "$tuning fret $fret"
                selected = finger != null
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (finger != null) {
            Box(Modifier.size(30.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text(finger.toString(), color = colors.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FingerPicker(tuning: String, fretted: StringSelection.Fretted?, onFinger: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(FINGER_WIDTH)) {
        TextButton(
            onClick = { open = true },
            enabled = fretted != null,
            modifier = Modifier.semantics { contentDescription = "Finger for $tuning" }
        ) {
            Text((fretted?.finger ?: 1).toString())
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (finger in ChordSequences.MIN_FINGER..ChordSequences.MAX_FINGER) {
                DropdownMenuItem(text = { Text("Finger $finger") }, onClick = {
                    open = false
                    onFinger(finger)
                })
            }
        }
    }
}

/** Small chord diagram ported from ChordBuilder's ChordPreview: a 4-fret window, one row per string. */
@Composable
fun ChordDiagram(
    instrument: Instrument,
    strings: List<StringSelection>,
    description: String,
    modifier: Modifier = Modifier
) {
    val start = ChordSequences.previewStart(strings)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column {
            instrument.tuning.indices.forEach { index ->
                val selection = strings.getOrNull(index)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (selection) {
                            StringSelection.Muted -> "×"
                            StringSelection.Open -> "O"
                            else -> ""
                        },
                        modifier = Modifier.width(14.dp),
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariant
                    )
                    for (fret in start until start + ChordSequences.PREVIEW_FRETS) {
                        val finger = (selection as? StringSelection.Fretted)?.takeIf { it.fret == fret }?.finger
                        Box(
                            Modifier
                                .size(width = 18.dp, height = 14.dp)
                                .drawBehind {
                                    drawLine(
                                        colors.outline,
                                        Offset(0f, size.height / 2),
                                        Offset(size.width, size.height / 2),
                                        1f
                                    )
                                    drawLine(
                                        colors.outline,
                                        Offset(size.width, 0f),
                                        Offset(size.width, size.height),
                                        1f
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (finger != null) {
                                Box(Modifier.size(12.dp).background(colors.primary, CircleShape), Alignment.Center) {
                                    Text(finger.toString(), fontSize = 8.sp, color = colors.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (start > 1) Text("${start}fr", fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
}

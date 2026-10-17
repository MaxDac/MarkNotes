package io.github.maxdac.marknotes.ui.editor

import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

const val EDITOR_FIELD_TAG = "editor-field"
const val PREVIEW_TAG = "markdown-preview"
const val FORMAT_TOOLBAR_TAG = "format-toolbar"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: EditorViewModel = viewModel { EditorViewModel(container, path) }
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(vm) {
        onDispose { vm.flush() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(NotePaths.displayName(path)) },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.flush()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::togglePreview) {
                        if (state.preview) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        } else {
                            Icon(Icons.Filled.Visibility, contentDescription = "Preview")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.preview -> MarkdownPreview(state.text)

                else -> Column(Modifier.fillMaxSize()) {
                    TextField(
                        value = state.value,
                        onValueChange = vm::onValueChange,
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag(EDITOR_FIELD_TAG),
                        placeholder = { Text("Write markdown…") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    FormattingToolbar(onAction = vm::applyFormat)
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }
        )
    }
}

private data class FormatAction(val label: String, val icon: ImageVector, val apply: (EditorText) -> EditorText)

private val formatActions = listOf(
    FormatAction("Bold", Icons.Filled.FormatBold) { MarkdownEditing.toggleInline(it, "**") },
    FormatAction("Italic", Icons.Filled.FormatItalic) { MarkdownEditing.toggleInline(it, "_") },
    FormatAction("Strikethrough", Icons.Filled.FormatStrikethrough) { MarkdownEditing.toggleInline(it, "~~") },
    FormatAction("Heading", Icons.Filled.Title, MarkdownEditing::cycleHeading),
    FormatAction("Bullet list", Icons.AutoMirrored.Filled.FormatListBulleted) {
        MarkdownEditing.toggleLinePrefix(it, LineKind.BULLET)
    },
    FormatAction("Numbered list", Icons.Filled.FormatListNumbered) {
        MarkdownEditing.toggleLinePrefix(it, LineKind.NUMBERED)
    },
    FormatAction("Checkbox", Icons.Filled.CheckBox) { MarkdownEditing.toggleLinePrefix(it, LineKind.CHECKBOX) },
    FormatAction("Quote", Icons.Filled.FormatQuote) { MarkdownEditing.toggleLinePrefix(it, LineKind.QUOTE) },
    FormatAction("Inline code", Icons.Filled.Code) { MarkdownEditing.toggleInline(it, "`") },
    FormatAction("Code block", Icons.Filled.DataObject, MarkdownEditing::toggleCodeBlock),
    FormatAction("Link", Icons.Filled.Link, MarkdownEditing::insertLink)
)

@Composable
private fun FormattingToolbar(onAction: ((EditorText) -> EditorText) -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(FORMAT_TOOLBAR_TAG)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            formatActions.forEach { action ->
                IconButton(onClick = { onAction(action.apply) }) {
                    Icon(action.icon, contentDescription = action.label)
                }
            }
        }
    }
}

@Composable
private fun MarkdownPreview(text: String) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val markwon = remember(context, dark) {
        val theme = if (dark) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(SyntaxHighlightPlugin.create(Prism4j(CodeGrammars.create()), theme))
            .build()
    }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(PREVIEW_TAG),
        factory = { ctx -> TextView(ctx).apply { textSize = 16f } },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            markwon.setMarkdown(view, text)
        }
    )
}

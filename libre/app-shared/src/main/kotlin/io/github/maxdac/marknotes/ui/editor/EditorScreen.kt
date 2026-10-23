package io.github.maxdac.marknotes.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.IndexedNote
import io.github.maxdac.marknotes.core.notes.NoteIndex
import io.github.maxdac.marknotes.core.notes.NoteLinks
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CoreProps
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.destination.ImageDestinationProcessor
import io.noties.markwon.image.file.FileSchemeHandler
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import java.io.File
import org.commonmark.node.Link

const val EDITOR_FIELD_TAG = "editor-field"
const val EDITOR_NOTICE_TAG = "editor-notice"
const val PREVIEW_TAG = "markdown-preview"
const val FORMAT_TOOLBAR_TAG = "format-toolbar"
const val MENTION_POPUP_TAG = "mention-popup"

/**
 * The markdown editor. [notice] is shown above the text, e.g. why a chord sequence could not be opened in the chord
 * editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(path: String, onBack: () -> Unit, onOpenNote: (String) -> Unit = {}, notice: String? = null) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: EditorViewModel = viewModel { EditorViewModel(container, path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    DisposableEffect(vm) {
        onDispose { vm.flush() }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    BackHandler(enabled = state.mention != null) { vm.dismissMention() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.insertImage(uri, "image")
    }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingPhoto?.let(::File)
        pendingPhoto = null
        if (file != null) {
            if (saved && file.length() > 0) {
                vm.insertImage(photoUri(context, file), file.nameWithoutExtension, "image/jpeg") { file.delete() }
            } else {
                file.delete()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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

                state.preview -> MarkdownPreview(
                    text = state.text,
                    notesRoot = container.notesRoot,
                    index = state.index,
                    onNoteLink = { vm.openNoteLink(it, onOpenNote) }
                )

                else -> Column(Modifier.fillMaxSize()) {
                    if (state.importingImage) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (notice != null) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                notice,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp).testTag(EDITOR_NOTICE_TAG)
                            )
                        }
                    }
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
                    state.mention?.let { mention ->
                        MentionPopup(
                            mention = mention,
                            onSelect = vm::selectMention,
                            onCopyId = { id ->
                                copyToClipboard(context, id)
                                vm.showMessage("Copied $id")
                            },
                            onDismiss = vm::dismissMention
                        )
                    }
                    FormattingToolbar(
                        onAction = vm::applyFormat,
                        onPickImage = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onTakePhoto = {
                            val file = newPhotoFile(context)
                            pendingPhoto = file.absolutePath
                            takePhoto.launch(photoUri(context, file))
                        }
                    )
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

private fun newPhotoFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    return File(dir, "photo-${System.currentTimeMillis()}.jpg")
}

private fun photoUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Note ID", text))
}

/** Bottom panel listing the notes matching the text typed after `@`. */
@Composable
private fun MentionPopup(
    mention: MentionState,
    onSelect: (IndexedNote) -> Unit,
    onCopyId: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().testTag(MENTION_POPUP_TAG)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    if (mention.query.isEmpty()) "Link to a note" else "Link to a note: ${mention.query}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            if (mention.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider()
            if (mention.results.isEmpty()) {
                Text(
                    if (mention.loading) "Loading notes…" else "No matching notes",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(mention.results, key = { it.path }) { note ->
                        MentionRow(note, onSelect = { onSelect(note) }, onCopyId = { onCopyId(note.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionRow(note: IndexedNote, onSelect: () -> Unit, onCopyId: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 16.dp)
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(note.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                note.displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Text(note.id, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
                IconButton(onClick = onCopyId, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy ID", modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(Modifier.size(8.dp))
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
private fun FormattingToolbar(
    onAction: ((EditorText) -> EditorText) -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit
) {
    var imageMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(FORMAT_TOOLBAR_TAG)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            formatActions.forEach { action ->
                IconButton(onClick = { onAction(action.apply) }) {
                    Icon(action.icon, contentDescription = action.label)
                }
            }
            Box {
                IconButton(onClick = { imageMenu = true }) {
                    Icon(Icons.Filled.Image, contentDescription = "Insert image")
                }
                DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Choose photo") },
                        leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            imageMenu = false
                            onPickImage()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Take photo") },
                        leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                        onClick = {
                            imageMenu = false
                            onTakePhoto()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Markwon plugin for MarkNotes specifics: `attachments/…` images are read from the notes root and
 * `marknotes:` links open the linked note, with links to unknown notes styled as broken.
 */
private class MarkNotesPlugin(
    private val notesRoot: File,
    private val index: NoteIndex?,
    private val brokenColor: Int,
    private val onNoteLink: (String) -> Unit
) : AbstractMarkwonPlugin() {
    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.imageDestinationProcessor(object : ImageDestinationProcessor() {
            override fun process(destination: String): String =
                if (Attachments.isInFolder(destination)) {
                    Uri.fromFile(File(notesRoot, destination)).toString()
                } else {
                    destination
                }
        })
        val fallback = LinkResolverDef()
        builder.linkResolver { view: View, link: String ->
            if (NoteLinks.idOf(link) != null) onNoteLink(link) else fallback.resolve(view, link)
        }
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        val index = index ?: return
        builder.appendFactory(Link::class.java) { _, props ->
            val id = NoteLinks.idOf(CoreProps.LINK_DESTINATION.require(props))
            if (id != null && index.resolve(id) == null) {
                arrayOf<Any>(StrikethroughSpan(), ForegroundColorSpan(brokenColor))
            } else {
                null
            }
        }
    }
}

@Composable
internal fun MarkdownPreview(text: String, notesRoot: File, index: NoteIndex?, onNoteLink: (String) -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val brokenColor = MaterialTheme.colorScheme.error.toArgb()
    val currentOnNoteLink by rememberUpdatedState(onNoteLink)
    val markwon = remember(context, dark, notesRoot, index, brokenColor) {
        val theme = if (dark) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(SyntaxHighlightPlugin.create(Prism4j(CodeGrammars.create()), theme))
            .usePlugin(ImagesPlugin.create { it.addSchemeHandler(FileSchemeHandler.create()) })
            .usePlugin(MarkNotesPlugin(notesRoot, index, brokenColor) { currentOnNoteLink(it) })
            .build()
    }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(PREVIEW_TAG),
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 16f
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            markwon.setMarkdown(view, text)
        }
    )
}

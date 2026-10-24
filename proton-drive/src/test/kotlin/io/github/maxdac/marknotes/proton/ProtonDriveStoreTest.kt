package io.github.maxdac.marknotes.proton

import io.github.maxdac.marknotes.core.sync.RemoteEntry
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.entity.FileUploaderRequest
import me.proton.drive.sdk.entity.FolderNode
import me.proton.drive.sdk.entity.MemberRole
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.OwnedBy
import me.proton.drive.sdk.entity.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonDriveStoreTest {
    private class FakeClient {
        val createdNames = mutableListOf<String>()
        var calls = 0
        var rejectedName: String? = null
        var uploadRequest: FileUploaderRequest? = null

        val client = Proxy.newProxyInstance(
            ProtonDriveClient::class.java.classLoader,
            arrayOf(ProtonDriveClient::class.java)
        ) { _, method, args ->
            calls++
            when {
                method.name.startsWith("getMyFilesFolder") -> folder("My files")

                method.name.startsWith("enumerateFolderChildrenNodeUids") -> emptyFlow<NodeUid>()

                method.name.startsWith("enumerateNodes") -> emptyFlow<FolderNode>()

                method.name.startsWith("createFolder") -> {
                    val name = args[1] as String
                    createdNames += name
                    check(name != rejectedName) { "Name already exists" }
                    folder(name)
                }

                method.name == "uploader" -> {
                    uploadRequest = args[0] as FileUploaderRequest
                    error("Name already exists")
                }

                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as ProtonDriveClient

        private fun folder(name: String) = FolderNode(
            uid = NodeUid("volume~$name"),
            parentUid = null,
            treeEventScopeId = ScopeId("scope"),
            name = Result.success(name),
            creationTime = Instant.ofEpochMilli(1000L),
            trashTime = null,
            nameAuthor = Result.failure(IOException("Not needed by this test")),
            keyAuthor = Result.failure(IOException("Not needed by this test")),
            ownedBy = OwnedBy(null, null),
            isShared = false,
            isSharedByUrl = false,
            directRole = MemberRole.ADMIN,
            membership = null,
            errors = emptyList(),
            deprecatedShareId = null
        )
    }

    @Test
    fun folderDeletionFailsBeforeAnySdkCall() = runTest {
        val sdk = FakeClient()
        val store = ProtonDriveStore(sdk.client, "MarkNotes")

        val error = runCatching { store.delete(RemoteEntry("Work", "folder", true, "", 0L)) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("Safe empty-folder deletion"))
        assertEquals(0, sdk.calls)
    }

    @Test
    fun plannedFolderCreationRejectsACachedExistingFolder() = runTest {
        val sdk = FakeClient()
        val store = ProtonDriveStore(sdk.client, "MarkNotes")
        store.createFolder("Work")
        val before = sdk.calls

        val error = runCatching { store.createFolder("Work") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(before, sdk.calls)
        assertEquals(listOf("MarkNotes", "Work"), sdk.createdNames)
    }

    @Test
    fun plannedFolderCreationPropagatesAConcurrentNameConflict() = runTest {
        val sdk = FakeClient().apply { rejectedName = "Work" }
        val store = ProtonDriveStore(sdk.client, "MarkNotes")

        val error = runCatching { store.createFolder("Work") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Name already exists", error!!.message)
        assertEquals(listOf("MarkNotes", "Work"), sdk.createdNames)
    }

    @Test
    fun newUploadKeepsTheEditTimeAndDoesNotOverrideAConflictingDraft() = runTest {
        val sdk = FakeClient()
        val store = ProtonDriveStore(sdk.client, "MarkNotes")

        val error = runCatching { store.uploadNew("Note.md", "note".toByteArray(), 1234L) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Name already exists", error!!.message)
        assertEquals("Note.md", sdk.uploadRequest!!.name)
        assertEquals(Instant.ofEpochMilli(1234L), sdk.uploadRequest!!.lastModificationTime)
        assertEquals(false, sdk.uploadRequest!!.overrideExistingDraftByOtherClient)
    }

    @Test
    fun plannedFolderCreationCanReuseParentsButNotTheLeaf() = runTest {
        val sdk = FakeClient()
        val store = ProtonDriveStore(sdk.client, "MarkNotes")
        store.createFolder("Work")

        val created = store.createFolder("Work/New")

        assertEquals("Work/New", created.path)
        assertEquals(listOf("MarkNotes", "Work", "New"), sdk.createdNames)
    }
}

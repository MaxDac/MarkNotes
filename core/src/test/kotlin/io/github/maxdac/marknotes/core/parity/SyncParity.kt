package io.github.maxdac.marknotes.core.parity

import io.github.maxdac.marknotes.sync.Action
import io.github.maxdac.marknotes.sync.ConflictChoice
import io.github.maxdac.marknotes.sync.LocalEntry
import io.github.maxdac.marknotes.sync.Manifest
import io.github.maxdac.marknotes.sync.ManifestEntry
import io.github.maxdac.marknotes.sync.ReconcileInput
import io.github.maxdac.marknotes.sync.ReconcileOutcome
import io.github.maxdac.marknotes.sync.RemoteEntry
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.SyncInput
import io.github.maxdac.marknotes.sync.SyncRecord
import io.github.maxdac.marknotes.sync.SyncScheduler
import io.github.maxdac.marknotes.sync.buildManifest
import io.github.maxdac.marknotes.sync.conflictCopyName
import io.github.maxdac.marknotes.sync.merge3
import io.github.maxdac.marknotes.sync.mergeText
import io.github.maxdac.marknotes.sync.parseManifest
import io.github.maxdac.marknotes.sync.plan
import io.github.maxdac.marknotes.sync.reconcile
import io.github.maxdac.marknotes.sync.serializeManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Sync fixtures (plan, reconcile, scheduling, manifest, merge), run through the shared sync core's Kotlin bindings
 * that the app uses (`io.github.maxdac.marknotes.sync`, from marknotes-sync).
 */
object SyncParity {
    val mappings: Map<String, ParityMapping> = mapOf(
        "sync/plan" to ParityMapping.Run { c -> planJson(c.input) },
        "sync/reconcile" to ParityMapping.Run { c -> reconcileJson(c.input) },
        "sync/multi-drive" to ParityMapping.Run { c -> scheduleJson(c.input) },
        "manifest/parse" to ParityMapping.Run { c ->
            buildJsonObject { put("manifest", parseManifest(c.string("json"))?.let(::manifestJson) ?: JsonNull) }
        },
        "manifest/serialize" to ParityMapping.Run { c ->
            val files = c.input.getValue("files").jsonObject.mapValues { (_, value) ->
                val e = value.jsonObject
                ManifestEntry(e.str("sha256"), e.str("version"), e.long("syncedAt"))
            }
            val manifest = Manifest(c.string("deviceId"), c.input.long("updatedAt"), files)
            buildJsonObject { put("json", serializeManifest(manifest)) }
        },
        "manifest/build" to ParityMapping.Run { c ->
            val json = buildManifest(
                c.input.getValue("records").jsonArray.map { record(it.jsonObject) },
                c.string("deviceId"),
                c.input.long("nowMillis"),
                c.stringOrNull("previous")
            )
            buildJsonObject { put("json", json) }
        },
        "merge/merge3" to ParityMapping.Run { c ->
            val result = merge3(c.stringOrNull("base"), c.string("local"), c.string("remote"))
            val choices = (c.input["choices"] as? JsonObject).orEmpty().entries.associate { (index, choice) ->
                index.toInt() to ConflictChoice.valueOf(choice.jsonPrimitive.content.uppercase())
            }
            buildJsonObject {
                put("text", mergeText(result.chunks, choices))
                put("conflicts", result.conflicts)
            }
        },
        "merge/conflict-copy-name" to ParityMapping.Run { c ->
            buildJsonObject { put("path", conflictCopyName(c.string("path"), c.input.strings("existingPaths"))) }
        }
    )

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strOrNull(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.long ?: 0L

    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.strings(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun remote(o: JsonObject) =
        RemoteEntry(o.str("path"), o.str("id"), o.bool("isFolder"), o.str("version"), o.long("modifiedMillis"))

    private fun record(o: JsonObject) = SyncRecord(
        o.str("path"),
        o.bool("isFolder"),
        o.str("remoteId"),
        o.str("remoteVersion"),
        o.str("localHash"),
        o.long("syncedAt")
    )

    private fun planJson(i: JsonObject): JsonElement {
        val input = SyncInput(
            local = i.getValue("local").jsonArray.map {
                val o = it.jsonObject
                LocalEntry(o.str("path"), o.bool("isFolder"), o.long("modifiedMillis"), o.strOrNull("sha256"))
            },
            remote = i.getValue("remote").jsonArray.map { remote(it.jsonObject) },
            records = i.getValue("records").jsonArray.map { record(it.jsonObject) },
            manifest = i.strOrNull("manifest")
        )
        return buildJsonObject { put("actions", JsonArray(plan(input).actions.map(::actionJson))) }
    }

    private fun reconcileJson(i: JsonObject): JsonElement {
        val outcome = reconcile(
            ReconcileInput(
                path = i.str("path"),
                local = i.str("local"),
                localModifiedMillis = i.long("localModifiedMillis"),
                remote = i.str("remote"),
                remoteEntry = remote(i.getValue("remoteEntry").jsonObject),
                base = i.strOrNull("base"),
                existingPaths = i.strings("existingPaths"),
                newId = i.str("newId")
            )
        )
        return when (outcome) {
            is ReconcileOutcome.Same -> buildJsonObject { put("kind", "same") }

            is ReconcileOutcome.Merged -> buildJsonObject {
                put("kind", "merged")
                put("text", outcome.text)
            }

            is ReconcileOutcome.NewestWins -> buildJsonObject {
                put("kind", "newestWins")
                put("winner", if (outcome.winner == Side.LOCAL) "local" else "remote")
                put("copyPath", outcome.copyPath)
                put("copyText", outcome.copyText)
            }
        }
    }

    private fun scheduleJson(i: JsonObject): JsonElement {
        val reports = i.getValue("reports").jsonArray
        val order = mutableListOf<String>()
        SyncScheduler(i.strings("driveIds"), i.getValue("maxPasses").jsonPrimitive.int).use { scheduler ->
            while (true) {
                val id = scheduler.next() ?: break
                val report = reports[order.size].jsonObject
                scheduler.report(id, report.getValue("localChanges").jsonPrimitive.int, report.bool("failed"))
                order += id
            }
        }
        return buildJsonObject { put("order", strings(order)) }
    }

    private fun manifestJson(m: Manifest): JsonElement = buildJsonObject {
        put("deviceId", m.deviceId)
        put("updatedAt", m.updatedAt)
        put(
            "files",
            JsonObject(
                m.files.mapValues { (_, e) ->
                    buildJsonObject {
                        put("sha256", e.sha256)
                        put("version", e.version)
                        put("syncedAt", e.syncedAt)
                    }
                }
            )
        )
    }

    private fun remoteJson(r: RemoteEntry) = buildJsonObject {
        put("path", r.path)
        put("id", r.id)
        put("isFolder", r.isFolder)
        put("version", r.version)
        put("modifiedMillis", r.modifiedMillis)
    }

    private fun JsonObjectBuilder.action(kind: String, path: String, remote: RemoteEntry? = null) {
        put("kind", kind)
        put("path", path)
        remote?.let { put("remote", remoteJson(it)) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun actionJson(a: Action): JsonObject = buildJsonObject {
        when (a) {
            is Action.Error -> {
                action("error", a.path)
                put("message", a.message)
            }

            is Action.RecordFolder -> {
                action("recordFolder", a.path)
                put("remoteId", a.remoteId)
            }

            is Action.CreateRemoteFolder -> action("createRemoteFolder", a.path)

            is Action.CreateLocalFolder -> {
                action("createLocalFolder", a.path)
                put("remoteId", a.remoteId)
            }

            is Action.Forget -> action("forget", a.path)

            is Action.UploadNew -> action("uploadNew", a.path)

            is Action.UploadRevision -> action("uploadRevision", a.path, a.remote)

            is Action.Download -> action("download", a.path, a.remote)

            is Action.Record -> {
                action("record", a.path, a.remote)
                put("sha256", a.sha256)
                put("updateBase", a.updateBase)
            }

            is Action.DeleteLocal -> action("deleteLocal", a.path)

            is Action.DeleteRemote -> action("deleteRemote", a.path, a.remote)

            is Action.DeleteLocalFolder -> {
                action("deleteLocalFolder", a.path)
                put("forget", strings(a.forget))
            }

            is Action.DeleteRemoteFolder -> {
                action("deleteRemoteFolder", a.path, a.remote)
                put("forget", strings(a.forget))
            }

            is Action.Reconcile -> {
                action("reconcile", a.path, a.remote)
                put("hasRecord", a.hasRecord)
            }
        }
    }
}

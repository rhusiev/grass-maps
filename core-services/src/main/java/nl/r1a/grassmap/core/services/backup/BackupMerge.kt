package nl.r1a.grassmap.core.services.backup

import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.map
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * One kind's access to its store. [read] is what the store holds now, [apply]
 * writes only the entries the import selected, so an entry the user kept stays
 * exactly as it is.
 */
interface BundleKindSource {
    val kind: String

    suspend fun read(): Outcome<List<BundleEntry>>

    suspend fun apply(entries: List<BundleEntry>): Outcome<Unit>

    /** Kind-wide attachments that are not an entry, such as the library's own GPX. */
    suspend fun files(): Outcome<List<BundleFile>> = Outcome.Success(emptyList())

    /** How many records this kind would write, for the screen. [read] by default. */
    suspend fun count(): Outcome<Int> = read().map { entries -> entries.size }
}

/** One id whose current and incoming values differ. */
data class BundleConflict(
    val kind: String,
    val entryId: String,
    val existing: JsonObject,
    val incoming: JsonObject,
)

/** What an import would do before the user answers a single question. */
data class BundleMergePlan(
    val conflicts: List<BundleConflict>,
    val additions: Map<String, List<BundleEntry>>,
    val unchanged: Int,
) {
    val additionsCount: Int
        get() = additions.values.sumOf { entries -> entries.size }
}

/** The user's choice for one conflict, or for every remaining conflict of a kind. */
enum class BundleConflictChoice {
    /** Write the bundle's value over the one on this device. */
    INCOMING,

    /** Keep the value on this device. */
    EXISTING,
}

/**
 * The answers given so far. A checkbox applies one answer to the remaining
 * conflicts of that kind; a conflict answered before the checkbox keeps its own
 * answer, and the other kinds still ask.
 */
class BundleConflictDecisions {
    private val byEntry = mutableMapOf<String, BundleConflictChoice>()
    private val byKind = mutableMapOf<String, BundleConflictChoice>()

    fun answer(
        conflict: BundleConflict,
        choice: BundleConflictChoice,
        applyToAllInKind: Boolean,
    ) {
        byEntry[key(conflict)] = choice
        if (applyToAllInKind) {
            byKind[conflict.kind] = choice
        }
    }

    fun choiceFor(conflict: BundleConflict): BundleConflictChoice? = byEntry[key(conflict)] ?: byKind[conflict.kind]

    /** The first conflict that has no answer yet, in manifest order. */
    fun next(plan: BundleMergePlan): BundleConflict? = plan.conflicts.firstOrNull { conflict -> choiceFor(conflict) == null }

    fun remaining(plan: BundleMergePlan): Int = plan.conflicts.count { conflict -> choiceFor(conflict) == null }

    private fun key(conflict: BundleConflict): String = conflict.kind + "/" + conflict.entryId
}

/** What one kind did, or the typed reason it did nothing. */
data class BundleKindResult(
    val kind: String,
    val added: Int,
    val replaced: Int,
    val kept: Int,
    val error: AppError? = null,
)

/** The outcome of writing every kind. A failed kind does not stop the others. */
data class BundleImportSummary(
    val kinds: List<BundleKindResult>,
    val warnings: List<BackupWarning>,
) {
    val added: Int
        get() = kinds.sumOf { kind -> kind.added }

    val replaced: Int
        get() = kinds.sumOf { kind -> kind.replaced }

    val kept: Int
        get() = kinds.sumOf { kind -> kind.kept }

    val failed: Boolean
        get() = kinds.any { kind -> kind.error != null }
}

/** Reads a bundle and plans the merge against the stores this build has. */
class BackupImporter(
    sources: List<BundleKindSource>,
) {
    private val sources: Map<String, BundleKindSource> = sources.associateBy { source -> source.kind }

    init {
        require(this.sources.size == sources.size) { "one source per kind" }
    }

    /** The kinds this build can restore, in the same order as [BackupKinds.ALL]. */
    fun kinds(): List<BundleKindSource> = BackupKinds.ALL.mapNotNull { id -> this.sources[id] }

    /**
     * Reads [bytes] and compares every incoming id with the store. Nothing is
     * written here, so the conflict list the screen shows cannot already have
     * changed a value. A newer bundle is [AppError.NewerSchema].
     */
    suspend fun prepare(bytes: ByteArray): Outcome<PreparedBackup> = prepare(ByteArrayInputStream(bytes))

    /** Reads [input] with the bounded ZIP reader; the stream is closed with the ZIP. */
    suspend fun prepare(input: InputStream): Outcome<PreparedBackup> {
        val bundle =
            when (val read = BundleZipReader.read(input)) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return read
            }
        val planned =
            when (val result = plan(bundle)) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return result
            }
        return Outcome.Success(PreparedBackup(bundle, planned))
    }

    private suspend fun plan(bundle: BackupBundle): Outcome<BundleMergePlan> {
        val conflicts = mutableListOf<BundleConflict>()
        val additions = mutableMapOf<String, List<BundleEntry>>()
        var unchanged = 0
        for (kind in bundle.kinds) {
            val source = sources[kind.id] ?: continue
            val existing =
                when (val read = source.read()) {
                    is Outcome.Success -> read.value.associateBy { entry -> entry.id }
                    is Outcome.Failure -> return read
                }
            val added = mutableListOf<BundleEntry>()
            for (incoming in kind.entries) {
                val current = existing[incoming.id]
                when {
                    current == null -> {
                        added += incoming
                    }

                    current.value == incoming.value -> {
                        unchanged += 1
                    }

                    else -> {
                        conflicts +=
                            BundleConflict(
                                kind = kind.id,
                                entryId = incoming.id,
                                existing = current.value,
                                incoming = incoming.value,
                            )
                    }
                }
            }
            if (added.isNotEmpty()) {
                additions[kind.id] = added
            }
        }
        return Outcome.Success(
            BundleMergePlan(
                conflicts = conflicts,
                additions = additions,
                unchanged = unchanged,
            ),
        )
    }

    /**
     * Writes every kind with the selected entries. Every conflict must have an
     * answer; otherwise nothing is written and the result is [AppError.Conflict].
     * A kind that fails keeps its store untouched and the other kinds still run,
     * so the summary names exactly what happened.
     */
    suspend fun apply(
        prepared: PreparedBackup,
        decisions: BundleConflictDecisions,
    ): Outcome<BundleImportSummary> {
        if (decisions.next(prepared.plan) != null) {
            return Outcome.Failure(AppError.Conflict)
        }
        val results = mutableListOf<BundleKindResult>()
        for (kind in prepared.bundle.kinds) {
            val source = sources[kind.id] ?: continue
            val added = prepared.plan.additions[kind.id].orEmpty()
            val chosenIds = added.mapTo(mutableSetOf()) { entry -> entry.id }
            prepared.plan.conflicts
                .filter { conflict ->
                    conflict.kind == kind.id && decisions.choiceFor(conflict) == BundleConflictChoice.INCOMING
                }.forEach { conflict -> chosenIds += conflict.entryId }
            val chosen = kind.entries.filter { entry -> entry.id in chosenIds }
            val kept =
                prepared.plan.conflicts.count { conflict ->
                    conflict.kind == kind.id && decisions.choiceFor(conflict) == BundleConflictChoice.EXISTING
                }
            if (chosen.isEmpty()) {
                results += BundleKindResult(kind.id, added = 0, replaced = 0, kept = kept)
                continue
            }
            when (val written = source.apply(chosen)) {
                is Outcome.Success -> {
                    results +=
                        BundleKindResult(
                            kind = kind.id,
                            added = added.size,
                            replaced = chosen.size - added.size,
                            kept = kept,
                        )
                }

                is Outcome.Failure -> {
                    results += BundleKindResult(kind.id, added = 0, replaced = 0, kept = kept, error = written.error)
                }
            }
        }
        return Outcome.Success(BundleImportSummary(results, prepared.bundle.warnings))
    }
}

/** The read bundle and its planned merge, before any write. */
data class PreparedBackup(
    val bundle: BackupBundle,
    val plan: BundleMergePlan,
)

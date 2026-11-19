package nl.r1a.grassmap.app.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmap.core.services.backup.BackupWarning
import nl.r1a.grassmap.core.services.backup.BundleConflict
import nl.r1a.grassmap.core.services.backup.BundleConflictChoice
import nl.r1a.grassmap.core.services.backup.BundleConflictDecisions
import nl.r1a.grassmap.core.services.backup.BundleImportSummary
import nl.r1a.grassmap.core.services.backup.PreparedBackup
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.InputStream

/** What the backup screen is doing. */
enum class BackupPhase {
    IDLE,
    WORKING,
    CONFLICTS,
    DONE,
}

/** Everything the backup screen draws, produced off the UI thread. */
data class BackupUiState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val counts: List<BackupKindCount> = emptyList(),
    val currentConflict: BundleConflict? = null,
    val existingText: String = "",
    val incomingText: String = "",
    val conflictNumber: Int = 0,
    val conflictTotal: Int = 0,
    val exportCounts: List<BackupKindCount> = emptyList(),
    val exportBytes: Int = 0,
    val pendingExport: BackupExport? = null,
    val summary: BundleImportSummary? = null,
    val warnings: List<BackupWarning> = emptyList(),
    val error: AppError? = null,
)

/**
 * Drives the backup screen and the import's conflict queue (T-0609). The
 * controller reads and plans before it writes anything; each answer is kept
 * until the queue is empty, and a checkbox sets one choice for the rest of that
 * kind only. The state stays process-scoped, so a recomposition does not lose
 * a half-answered import.
 */
class BackupUiController(
    private val coordinator: BackupCoordinator,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutable.asStateFlow()

    private var prepared: PreparedBackup? = null
    private var decisions = BundleConflictDecisions()

    /** Re-reads the store counts when the screen opens; a half-answered import is left alone. */
    fun open() {
        if (mutable.value.phase == BackupPhase.IDLE) {
            refresh()
        }
    }

    private fun refresh() {
        scope.launch(Dispatchers.IO) {
            when (val counted = coordinator.summary()) {
                is Outcome.Success -> mutable.value = mutable.value.copy(counts = counted.value)
                is Outcome.Failure -> mutable.value = mutable.value.copy(error = counted.error)
            }
        }
    }

    /** The export runs off the UI thread; the screen saves the bytes it leaves in the state. */
    fun export() {
        mutable.value = mutable.value.copy(phase = BackupPhase.WORKING, error = null)
        scope.launch(Dispatchers.IO) {
            when (val exported = coordinator.export()) {
                is Outcome.Success -> {
                    mutable.value =
                        mutable.value.copy(
                            phase = BackupPhase.IDLE,
                            exportCounts = exported.value.counts,
                            exportBytes = exported.value.bytes.size,
                            pendingExport = exported.value,
                        )
                }

                is Outcome.Failure -> {
                    mutable.value = mutable.value.copy(phase = BackupPhase.IDLE, error = exported.error)
                }
            }
        }
    }

    /** The save picker returned or the write finished; the bytes are no longer pending. */
    fun exportHandled() {
        mutable.value = mutable.value.copy(pendingExport = null)
    }

    /** The save picker was dismissed; nothing was written, so no "ready" line remains. */
    fun exportCancelled() {
        mutable.value = mutable.value.copy(pendingExport = null, exportCounts = emptyList(), exportBytes = 0)
    }

    /** A debug-driven export already wrote the file; the screen shows what it held. */
    fun exported(export: BackupExport) {
        mutable.value =
            mutable.value.copy(
                exportCounts = export.counts,
                exportBytes = export.bytes.size,
                pendingExport = null,
            )
    }

    /** The save picker could not write, or the file picker returned nothing usable. */
    fun exportFailed() {
        mutable.value = mutable.value.copy(phase = BackupPhase.IDLE, pendingExport = null, error = AppError.Internal(EXPORT_ERROR))
    }

    /** The import picker returned a URI this app cannot read. */
    fun importFailed() {
        mutable.value = mutable.value.copy(phase = BackupPhase.IDLE, error = AppError.Internal(IMPORT_ERROR))
    }

    /** Reads and plans the bundle; nothing is written until the queue is empty. */
    fun startImport(input: InputStream) {
        mutable.value = mutable.value.copy(phase = BackupPhase.WORKING, error = null)
        scope.launch(Dispatchers.IO) {
            when (val planned = coordinator.prepare(input)) {
                is Outcome.Success -> {
                    prepared = planned.value
                    decisions = BundleConflictDecisions()
                    showNextOrApply()
                }

                is Outcome.Failure -> {
                    mutable.value = mutable.value.copy(phase = BackupPhase.IDLE, error = planned.error)
                }
            }
        }
    }

    /** Answers the conflict the screen shows; the checkbox covers this kind's remaining ones. */
    fun answer(
        choice: BundleConflictChoice,
        applyToAll: Boolean,
    ) {
        val conflict = mutable.value.currentConflict ?: return
        if (prepared == null) {
            return
        }
        decisions.answer(conflict, choice, applyToAll)
        showNextOrApply()
    }

    fun reset() {
        prepared = null
        decisions = BundleConflictDecisions()
        mutable.value = BackupUiState()
        refresh()
    }

    private fun showNextOrApply() {
        val plan = prepared ?: return
        val next = decisions.next(plan.plan)
        if (next == null) {
            apply()
            return
        }
        val total = plan.plan.conflicts.size
        val remaining = decisions.remaining(plan.plan)
        mutable.value =
            mutable.value.copy(
                phase = BackupPhase.CONFLICTS,
                currentConflict = next,
                existingText = pretty(next.existing),
                incomingText = pretty(next.incoming),
                conflictNumber = total - remaining + 1,
                conflictTotal = total,
            )
    }

    private fun apply() {
        val plan = prepared ?: return
        val answers = decisions
        mutable.value = mutable.value.copy(phase = BackupPhase.WORKING, currentConflict = null)
        scope.launch(Dispatchers.IO) {
            when (val applied = coordinator.apply(plan, answers)) {
                is Outcome.Success -> {
                    mutable.value =
                        mutable.value.copy(
                            phase = BackupPhase.DONE,
                            summary = applied.value,
                            warnings = applied.value.warnings,
                        )
                }

                is Outcome.Failure -> {
                    mutable.value = mutable.value.copy(phase = BackupPhase.IDLE, error = applied.error)
                }
            }
        }
    }

    private fun pretty(value: JsonObject): String = PRETTY.encodeToString(JsonObject.serializer(), value)

    private companion object {
        val PRETTY = Json { prettyPrint = true }
        const val EXPORT_ERROR = "app.backup.export"
        const val IMPORT_ERROR = "app.backup.import"
    }
}

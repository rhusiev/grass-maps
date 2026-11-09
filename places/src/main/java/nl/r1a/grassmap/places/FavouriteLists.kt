package nl.r1a.grassmap.places

/**
 * A scoped undo receipt for one lists action (T-0213).
 *
 * The token is opaque to callers. It is created by the action, held by the
 * lists screen, and consumed once by [FavouritesStore.undoListAction]. It is
 * deliberately not a general undo history that is T-0304's.
 */
@JvmInline
value class FavouriteUndoToken(
    val value: String,
) {
    override fun toString(): String = value
}

/** One persisted lists mutation: the new value and the receipt that undoes it. */
data class FavouriteMutation<T>(
    val value: T,
    val undo: FavouriteUndoToken,
)

/** Whether consuming a scoped receipt changed the library. */
enum class FavouriteUndo {
    APPLIED,

    /** The action can no longer apply; the library is unchanged. */
    NOT_APPLICABLE,
}

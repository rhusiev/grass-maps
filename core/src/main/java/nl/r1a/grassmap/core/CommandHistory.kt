package nl.r1a.grassmap.core

import nl.r1a.grassmap.plugin.api.command.CommandInvocation

/**
 * Undo and redo stacks of dynamically built inverse invocations.
 */
internal class CommandHistory {
    private val undoStack = ArrayDeque<CommandInvocation>()
    private val redoStack = ArrayDeque<CommandInvocation>()

    fun record(inverse: CommandInvocation) {
        undoStack.addLast(inverse)
        redoStack.clear()
    }

    fun popUndo(): CommandInvocation? = undoStack.removeLastOrNull()

    fun pushUndo(invocation: CommandInvocation) {
        undoStack.addLast(invocation)
    }

    fun popRedo(): CommandInvocation? = redoStack.removeLastOrNull()

    fun pushRedo(invocation: CommandInvocation) {
        redoStack.addLast(invocation)
    }
}

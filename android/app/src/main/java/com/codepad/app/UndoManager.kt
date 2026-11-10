package com.codepad.app

/**
 * Histórico de desfazer/refazer do editor nativo. Cada entrada é uma substituição:
 * em [start], o texto [removed] foi trocado por [inserted]. Digitação seguida (um caractere de cada vez,
 * ou Backspace repetido) funde-se numa só entrada, como no editor web.
 */
class UndoManager(private val limit: Int = 500, private val mergeMs: Long = 1000L) {
    class Entry(var start: Int, var removed: String, var inserted: String, var time: Long)

    private val undo = ArrayList<Entry>()
    private val redo = ArrayList<Entry>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun clear() {
        undo.clear()
        redo.clear()
    }

    fun record(start: Int, removed: String, inserted: String, now: Long) {
        if (removed.isEmpty() && inserted.isEmpty()) return
        redo.clear()
        val last = undo.lastOrNull()
        if (last != null && now - last.time <= mergeMs) {
            // digitar seguido
            if (last.removed.isEmpty() && removed.isEmpty() && inserted.length == 1 && inserted != "\n" &&
                last.inserted.isNotEmpty() && last.inserted.last() != '\n' && start == last.start + last.inserted.length && last.inserted.length < 80
            ) {
                last.inserted += inserted
                last.time = now
                return
            }
            // Backspace seguido
            if (last.inserted.isEmpty() && inserted.isEmpty() && removed.length == 1 && start + 1 == last.start && last.removed.length < 80) {
                last.start = start
                last.removed = removed + last.removed
                last.time = now
                return
            }
        }
        undo.add(Entry(start, removed, inserted, now))
        if (undo.size > limit) undo.removeAt(0)
    }

    fun popUndo(): Entry? = undo.removeLastOrNull()?.also { redo.add(it) }
    fun popRedo(): Entry? = redo.removeLastOrNull()?.also { undo.add(it) }
}

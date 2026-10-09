package app.koreshok.core.sync

/** A bookmark or highlight as it travels between devices. */
data class SyncNote(
    val kind: String,
    val chapter: Int,
    val block: Int,
    val start: Int,
    val end: Int,
    val color: Int,
    val note: String?,
    val text: String,
    val chapterTitle: String?,
    val createdAt: Long,
) {
    /** The same mark made on two devices is one mark. */
    val key: String get() = "$kind:$chapter:$block:$start"
}

/** Reading state of one book; [openedAt] decides whose position wins. */
data class SyncBook(
    val key: String,
    val title: String,
    val position: String?,
    val progress: Float,
    val openedAt: Long,
    val notes: List<SyncNote>,
)

data class SyncSnapshot(val books: Map<String, SyncBook>) {
    companion object {
        val EMPTY = SyncSnapshot(emptyMap())
    }
}

object SyncMerge {

    /**
     * Books are matched by title and author rather than by file, since every device keeps its
     * own copy of the file in its own folder.
     */
    fun bookKey(title: String, authors: String): String {
        fun norm(text: String) = text.lowercase().replace('ё', 'е')
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        // Word order of names differs between sources ("Виктор Пелевин" / "Пелевин Виктор").
        val author = norm(authors).split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")
        return norm(title) + "|" + author
    }

    /**
     * Three-way merge. [base] is what both sides agreed on at the last sync, which tells a mark
     * deleted on one side from a mark added on the other.
     */
    fun merge(base: SyncSnapshot, local: SyncSnapshot, remote: SyncSnapshot): SyncSnapshot {
        val keys = local.books.keys + remote.books.keys
        val books = keys.associateWith { key ->
            val l = local.books[key]
            val r = remote.books[key]
            val b = base.books[key]
            when {
                l == null -> r!!
                r == null -> l
                else -> {
                    val newer = if (r.openedAt > l.openedAt) r else l
                    newer.copy(notes = mergeNotes(b?.notes.orEmpty(), l.notes, r.notes))
                }
            }
        }
        return SyncSnapshot(books)
    }

    private fun mergeNotes(base: List<SyncNote>, local: List<SyncNote>, remote: List<SyncNote>): List<SyncNote> {
        val baseByKey = base.associateBy { it.key }
        val baseKeys = baseByKey.keys
        val localByKey = local.associateBy { it.key }
        val remoteByKey = remote.associateBy { it.key }
        return (localByKey.keys + remoteByKey.keys).mapNotNull { key ->
            val l = localByKey[key]
            val r = remoteByKey[key]
            when {
                // Changed on one side only: keep the change.
                l != null && r != null -> if (l == baseByKey[key]) r else l
                // Known before and now gone from the other side: it was deleted there.
                l != null -> l.takeIf { key !in baseKeys }
                else -> r?.takeIf { key !in baseKeys }
            }
        }.sortedWith(compareBy({ it.chapter }, { it.block }, { it.start }))
    }
}

package app.koreshok.sync

import app.koreshok.core.sync.SyncBook
import app.koreshok.core.sync.SyncNote
import app.koreshok.core.sync.SyncSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** The file on the server: plain JSON, so it stays readable and survives app versions. */
object SyncJson {
    fun encode(snapshot: SyncSnapshot): ByteArray {
        val books = JSONArray()
        snapshot.books.values.forEach { book ->
            books.put(
                JSONObject()
                    .put("key", book.key)
                    .put("title", book.title)
                    .put("position", book.position ?: JSONObject.NULL)
                    .put("progress", book.progress.toDouble())
                    .put("openedAt", book.openedAt)
                    .put("notes", JSONArray().apply { book.notes.forEach { put(note(it)) } }),
            )
        }
        return JSONObject().put("version", 1).put("books", books).toString().toByteArray()
    }

    fun decode(bytes: ByteArray): SyncSnapshot {
        val root = JSONObject(String(bytes))
        val books = root.optJSONArray("books") ?: return SyncSnapshot.EMPTY
        val list = (0 until books.length()).map { i ->
            val o = books.getJSONObject(i)
            val notes = o.optJSONArray("notes") ?: JSONArray()
            SyncBook(
                key = o.getString("key"),
                title = o.optString("title"),
                position = o.optString("position").takeIf { !o.isNull("position") && it.isNotEmpty() },
                progress = o.optDouble("progress", 0.0).toFloat(),
                openedAt = o.optLong("openedAt"),
                notes = (0 until notes.length()).map { note(notes.getJSONObject(it)) },
            )
        }
        return SyncSnapshot(list.associateBy { it.key })
    }

    private fun note(n: SyncNote) = JSONObject()
        .put("kind", n.kind).put("chapter", n.chapter).put("block", n.block)
        .put("start", n.start).put("end", n.end).put("color", n.color)
        .put("note", n.note ?: JSONObject.NULL).put("text", n.text)
        .put("chapterTitle", n.chapterTitle ?: JSONObject.NULL).put("createdAt", n.createdAt)

    private fun note(o: JSONObject) = SyncNote(
        kind = o.getString("kind"),
        chapter = o.getInt("chapter"),
        block = o.getInt("block"),
        start = o.getInt("start"),
        end = o.getInt("end"),
        color = o.optInt("color"),
        note = if (o.isNull("note")) null else o.optString("note"),
        text = o.optString("text"),
        chapterTitle = if (o.isNull("chapterTitle")) null else o.optString("chapterTitle"),
        createdAt = o.optLong("createdAt"),
    )
}

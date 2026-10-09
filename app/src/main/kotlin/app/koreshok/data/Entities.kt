package app.koreshok.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.koreshok.core.library.ShelfItem

@Entity(tableName = "books", indices = [Index("folderUri")])
data class BookEntity(
    /** Document URI of the file; stable as long as the folder permission is kept. */
    @PrimaryKey val uri: String,
    val folderUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    override val format: String,
    override val title: String,
    val authors: String,
    override val authorSortName: String,
    override val series: String?,
    override val seriesIndex: Float?,
    override val genres: List<String>,
    override val language: String?,
    override val year: Int?,
    val publisher: String?,
    val description: String?,
    val coverPath: String?,
    override val addedAt: Long,
    override val lastOpenedAt: Long? = null,
    override val progress: Float = 0f,
    /** Where reading stopped, as Position.serialize() from the core document model. */
    val position: String? = null,
) : ShelfItem

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val addedAt: Long,
)

class Converters {
    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun toList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}

enum class AnnotationKind { BOOKMARK, HIGHLIGHT }

/** A bookmark (a point) or a highlight (a range inside one block), with an optional note. */
@Entity(tableName = "annotations", indices = [Index("bookUri")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUri: String,
    val kind: AnnotationKind,
    val chapter: Int,
    val block: Int,
    val start: Int,
    val end: Int,
    /** Index into the highlight palette; ignored for bookmarks. */
    val color: Int = 0,
    val note: String? = null,
    /** The quoted text, or the first words of the page for a bookmark. */
    val text: String,
    val chapterTitle: String?,
    val createdAt: Long,
)

/** An OPDS catalog the user added; built-in ones live in OpdsPresets. */
@Entity(tableName = "catalogs")
data class CatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val addedAt: Long,
)

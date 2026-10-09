package app.bookreader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.bookreader.core.library.ShelfItem

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

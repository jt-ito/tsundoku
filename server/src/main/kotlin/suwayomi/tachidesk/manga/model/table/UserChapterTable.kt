package suwayomi.tachidesk.manga.model.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.server.user.model.UserTable

object UserChapterTable : IntIdTable("user_chapter") {
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE)
    val chapter = reference("chapter_id", ChapterTable, ReferenceOption.CASCADE)
    val isRead = bool("read").default(false)
    val isBookmarked = bool("bookmark").default(false)
    val lastPageRead = integer("last_page_read").default(0)
    val lastReadAt = long("last_read_at").default(0)
    val lastPageOffset = double("last_page_offset").default(0.0)

    init {
        uniqueIndex("uq_user_chapter", user, chapter)
    }
}

data class UserChapterDataClass(
    val id: Int,
    val userId: Int,
    val chapterId: Int,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val lastPageRead: Int,
    val lastReadAt: Long,
)

fun UserChapterTable.toDataClass(row: ResultRow) =
    UserChapterDataClass(
        id = row[UserChapterTable.id].value,
        userId = row[user].value,
        chapterId = row[chapter].value,
        isRead = row[isRead],
        isBookmarked = row[isBookmarked],
        lastPageRead = row[lastPageRead],
        lastReadAt = row[lastReadAt],
    )

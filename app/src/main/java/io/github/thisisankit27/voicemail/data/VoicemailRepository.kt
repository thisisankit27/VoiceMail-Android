package io.github.thisisankit27.voicemail.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Voicemail rows plus their audio files. Deletes are soft first, so the inbox can offer Undo. */
class VoicemailRepository(context: Context, private val dao: VoicemailDao) {
    private val dir = File(context.filesDir, "voicemails")

    val all: Flow<List<Voicemail>> = dao.observeAll()
    val unreadCount: Flow<Int> = dao.observeUnreadCount()

    fun audio(v: Voicemail): File? = v.audioFile?.let { File(dir, it) }?.takeIf { it.exists() }

    /** Diagnostics copy of what the caller heard, if one was kept for this call. */
    fun callerSide(v: Voicemail): File? = File(dir, "vm_${v.id}_caller.wav").takeIf { it.exists() && it.length() > 44 }

    suspend fun get(id: String) = dao.get(id)
    suspend fun setRead(id: String, read: Boolean) = dao.setRead(id, read)
    suspend fun markAllRead() = dao.markAllRead()
    suspend fun setStarred(id: String, starred: Boolean) = dao.setStarred(id, starred)
    suspend fun softDelete(id: String) = dao.setDeleted(id, true)
    suspend fun undoDelete(id: String) = dao.setDeleted(id, false)

    /** Removes soft-deleted rows and their files (called once the Undo window has passed). */
    suspend fun purgeDeleted() {
        for (v in dao.deleted()) {
            v.audioFile?.let { File(dir, it).delete() }
            File(dir, "vm_${v.id}_caller.wav").delete()
            dao.purge(v.id)
        }
    }

    /** Total bytes of stored voicemail audio. */
    fun storageBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0
}

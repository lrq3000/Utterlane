package io.github.lrq3000.utterlane.transcribe

import android.database.sqlite.SQLiteDatabase
import io.github.lrq3000.utterlane.archive.ArchiveDisk
import io.github.lrq3000.utterlane.history.ImportRetentionCategory
import io.github.lrq3000.utterlane.history.ImportRetentionChoice
import java.io.Closeable
import java.io.File
import java.util.UUID

/** A receipt is also the stable identity for the next private copy AND its queue job. No provider URI is needed to recover either once the copy has been published. */
internal data class ImportReceipt(val id: String, val cursor: Int, val total: Int, val accepted: Int, val firstJob: String?,
    val speakers: Boolean, val choices: Map<ImportRetentionCategory, ImportRetentionChoice>, val uri: String?, val deferred: Boolean) {
    val jobId get() = ArchiveDisk.key("$id:$cursor")
    val done get() = cursor == total
}

/** Only unfinished imports live here, under noBackupFilesDir. One indexed member write per file; never serialize the whole selection for every cursor advance. */
internal class ImportBatchStore(root: File) : Closeable {
    private val db by lazy {
        check(root.isDirectory || root.mkdirs() || root.isDirectory)
        SQLiteDatabase.openOrCreateDatabase(File(root, "imports.db"), null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS batch(id TEXT PRIMARY KEY, pos INTEGER DEFAULT 0, total INTEGER, accepted INTEGER DEFAULT 0, firstJob TEXT, speakers INTEGER, duration TEXT, size TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS member(batch TEXT, pos INTEGER, job TEXT UNIQUE, uri TEXT, fingerprint TEXT, deferred INTEGER DEFAULT 0, PRIMARY KEY(batch,pos), UNIQUE(batch,fingerprint))")
            execSQL("CREATE INDEX IF NOT EXISTS member_uri ON member(uri)")
            execSQL("CREATE INDEX IF NOT EXISTS member_next ON member(batch,deferred,pos) WHERE uri IS NOT NULL")
            execSQL("CREATE TABLE IF NOT EXISTS ownedGrant(uri TEXT PRIMARY KEY)")
        }
    }
    private inline fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun strings(sql: String, vararg args: String): List<String> = db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    fun create(id: String, uris: List<String>, speakers: Boolean) = transaction {
        require(UUID.fromString(id).toString() == id && uris.isNotEmpty())
        db.execSQL("INSERT INTO batch(id,total,speakers) VALUES(?,?,?)", arrayOf<Any>(id, uris.size, if (speakers) 1 else 0))
        uris.forEachIndexed { pos, uri ->
            db.execSQL("INSERT INTO member(batch,pos,job,uri,fingerprint) VALUES(?,?,?,?,?)", arrayOf<Any>(id, pos, ArchiveDisk.key("$id:$pos"), uri, ArchiveDisk.key(uri)))
        }
    }
    fun ids() = strings("SELECT id FROM batch ORDER BY rowid")
    fun get(id: String): ImportReceipt? = db.rawQuery("SELECT b.pos,b.total,b.accepted,b.firstJob,b.speakers,b.duration,b.size,m.uri,m.deferred FROM batch b LEFT JOIN member m ON m.batch=b.id AND m.pos=b.pos WHERE b.id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else ImportReceipt(id, c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3), c.getInt(4) != 0,
            buildMap { c.getString(5)?.let { put(ImportRetentionCategory.DURATION, ImportRetentionChoice.valueOf(it)) }
                c.getString(6)?.let { put(ImportRetentionCategory.SIZE, ImportRetentionChoice.valueOf(it)) } }, c.getString(7), c.getInt(8) != 0)
    }
    fun protectsAudio(id: String) = db.rawQuery("SELECT 1 FROM member WHERE job=? AND uri IS NOT NULL", arrayOf(id)).use { it.moveToFirst() }
    fun answer(id: String, category: ImportRetentionCategory, choice: ImportRetentionChoice) {
        val column = if (category == ImportRetentionCategory.SIZE) "size" else "duration"
        db.execSQL("UPDATE batch SET $column=? WHERE id=?", arrayOf(choice.name, id))
    }
    fun replace(item: ImportReceipt, uri: String): Boolean = transaction {
        val hash = ArchiveDisk.key(uri)
        if (get(item.id)?.cursor != item.cursor || strings("SELECT job FROM member WHERE batch=? AND fingerprint=? AND job<>?", item.id, hash, item.jobId).isNotEmpty()) return@transaction false
        db.execSQL("UPDATE member SET uri=?,fingerprint=?,deferred=0 WHERE job=?", arrayOf(uri, hash, item.jobId))
        true
    }
    private fun next(id: String) {
        db.execSQL("UPDATE batch SET pos=COALESCE((SELECT pos FROM member WHERE batch=? AND uri IS NOT NULL ORDER BY deferred,pos LIMIT 1),total) WHERE id=?", arrayOf(id, id))
    }
    fun defer(item: ImportReceipt) = transaction {
        if (get(item.id)?.cursor != item.cursor || item.done) return@transaction
        db.execSQL("UPDATE member SET deferred=1 WHERE job=?", arrayOf(item.jobId))
        next(item.id)
    }
    /** Drain never-tried members in selection order. A revoked source is deferred, not replayed in a hot loop; returning false means the user still owes a retention decision. */
    suspend fun runPass(id: String, retry: String?, stopped: () -> Boolean, visit: suspend (ImportReceipt) -> Boolean): ImportReceipt {
        var retryOnce = retry
        while (true) {
            val item = checkNotNull(get(id))
            if (stopped() || item.done || item.deferred && item.jobId != retryOnce) return item
            retryOnce = null
            try {
                if (!visit(item)) return item
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: ImportSourceFailure) { defer(item) }
        }
    }
    fun advance(item: ImportReceipt, accepted: Boolean) = transaction {
        if (get(item.id)?.cursor != item.cursor || item.done) return@transaction
        db.execSQL("UPDATE batch SET accepted=accepted+?,firstJob=COALESCE(firstJob,?) WHERE id=?",
            arrayOf<Any?>(if (accepted) 1 else 0, if (accepted) item.jobId else null, item.id))
        // Retain only the fingerprint until the batch closes, to reject reselecting an already accepted member.
        db.execSQL("UPDATE member SET uri=NULL WHERE job=?", arrayOf(item.jobId))
        next(item.id)
    }
    fun uncopiedSources(id: String): List<String> = strings(
        "SELECT uri FROM member WHERE batch=? AND uri IS NOT NULL", id)
    fun reconcile(queue: QueueJournal) {
        for (id in ids()) {
            val item = get(id) ?: continue
            if (!item.done && queue.find(item.jobId) != null) advance(item, accepted = true)
        }
    }
    fun forget(id: String) = transaction {
        db.execSQL("DELETE FROM member WHERE batch=?", arrayOf(id))
        db.execSQL("DELETE FROM batch WHERE id=?", arrayOf(id))
    }

    /** Record ownership before asking Android: process death between grant acquisition and bookkeeping must not leak access. Pre-existing grants belong to their original feature. */
    @Synchronized fun takeGrant(uri: String, alreadyHeld: () -> Boolean, take: () -> Unit) {
        if (alreadyHeld()) return
        db.execSQL("INSERT OR IGNORE INTO ownedGrant VALUES(?)", arrayOf(uri))
        try { take() } catch (_: SecurityException) { db.execSQL("DELETE FROM ownedGrant WHERE uri=?", arrayOf(uri)) }
    }
    @Synchronized fun releaseUnused(release: (String) -> Unit) {
        for (uri in strings("SELECT uri FROM ownedGrant WHERE NOT EXISTS(SELECT 1 FROM member WHERE member.uri=ownedGrant.uri)")) {
            try { release(uri) } catch (_: SecurityException) { /* Already revoked. */ }
            db.execSQL("DELETE FROM ownedGrant WHERE uri=?", arrayOf(uri))
        }
    }
    override fun close() = db.close()
}

internal class ImportSourceFailure(cause: Exception) : Exception(cause)

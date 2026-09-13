package de.meegread.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.meegread.app.analysis.ThermodynamicLoadEngine
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import de.meegread.app.model.SessionSummary
import de.meegread.app.model.SubjectProfile
import de.meegread.app.model.ThermodynamicSessionSummary
import java.io.File
import java.util.UUID

class ArchiveStore(private val context: Context) : SQLiteOpenHelper(context, "meegread-medical.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE subjects (id TEXT PRIMARY KEY, pseudonym TEXT NOT NULL, notes TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, subject_id TEXT NOT NULL, recording_name TEXT NOT NULL, modality TEXT NOT NULL, sample_rate REAL NOT NULL, channel_count INTEGER NOT NULL, sample_count INTEGER NOT NULL, duration REAL NOT NULL, created_at INTEGER NOT NULL, recording_path TEXT NOT NULL, FOREIGN KEY(subject_id) REFERENCES subjects(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX idx_sessions_subject ON sessions(subject_id, created_at DESC)")
        createThermodynamicMetricsTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createThermodynamicMetricsTable(db) }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }

    fun saveSubject(subject: SubjectProfile): SubjectProfile {
        val values = ContentValues().apply { put("id", subject.id); put("pseudonym", subject.pseudonym); put("notes", subject.notes); put("created_at", subject.createdAtEpochMs) }
        writableDatabase.insertWithOnConflict("subjects", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return subject
    }
    fun createSubject(pseudonym: String, notes: String = ""): SubjectProfile = saveSubject(SubjectProfile(pseudonym = pseudonym.trim(), notes = notes.trim()))
    fun listSubjects(): List<SubjectProfile> = readableDatabase.query("subjects", arrayOf("id","pseudonym","notes","created_at"), null,null,null,null,"created_at DESC").use { c -> buildList { while (c.moveToNext()) add(SubjectProfile(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) } }
    fun deleteSubject(subjectId: String) { listSessions(subjectId).forEach { sessionFile(it.id).delete() }; writableDatabase.delete("subjects", "id=?", arrayOf(subjectId)) }

    fun saveSession(subjectId: String, recording: MeegRecording): SessionSummary {
        require(listSubjects().any { it.id == subjectId }) { "Proband nicht gefunden." }
        val id = UUID.randomUUID().toString(); val created = System.currentTimeMillis(); val file = sessionFile(id)
        RecordingCodec.write(file, recording)
        val values = ContentValues().apply {
            put("id", id); put("subject_id", subjectId); put("recording_name", recording.name); put("modality", recording.modality.name); put("sample_rate", recording.sampleRateHz); put("channel_count", recording.channelCount); put("sample_count", recording.sampleCount); put("duration", recording.durationSeconds); put("created_at", created); put("recording_path", file.absolutePath)
        }
        writableDatabase.insertOrThrow("sessions", null, values)
        runCatching { ThermodynamicLoadEngine.analyze(recording) }.getOrNull()?.let { analysis ->
            val t = ContentValues().apply {
                put("session_id", id); put("algorithm_version", ThermodynamicLoadEngine.ALGORITHM_VERSION); put("omega_crit", analysis.omegaCrit); put("final_raw_load", analysis.finalRawLoad); put("final_bounded_load", analysis.finalBoundedLoad); put("max_raw_load", analysis.maxRawLoad); put("mean_faa", analysis.meanFaa); put("omega_breach", if (analysis.omegaBreach) 1 else 0); analysis.firstBreachTimeSeconds?.let { put("first_breach_time", it) }
            }
            writableDatabase.insertWithOnConflict("thermodynamic_metrics", null, t, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return SessionSummary(id, subjectId, recording.name, recording.modality, recording.sampleRateHz, recording.channelCount, recording.sampleCount, recording.durationSeconds, created)
    }

    fun listSessions(subjectId: String? = null): List<SessionSummary> {
        val selection = subjectId?.let { "subject_id=?" }; val args = subjectId?.let { arrayOf(it) }
        return readableDatabase.query("sessions", arrayOf("id","subject_id","recording_name","modality","sample_rate","channel_count","sample_count","duration","created_at"), selection,args,null,null,"created_at DESC").use { c -> buildList { while (c.moveToNext()) add(SessionSummary(c.getString(0), c.getString(1), c.getString(2), runCatching { Modality.valueOf(c.getString(3)) }.getOrDefault(Modality.UNKNOWN), c.getDouble(4), c.getInt(5), c.getInt(6), c.getDouble(7), c.getLong(8))) } }
    }

    fun listThermodynamicMetrics(subjectId: String? = null): List<ThermodynamicSessionSummary> {
        val where = subjectId?.let { "WHERE s.subject_id = ?" }.orEmpty(); val args = subjectId?.let { arrayOf(it) }
        val sql = "SELECT tm.session_id,s.subject_id,s.created_at,tm.algorithm_version,tm.omega_crit,tm.final_raw_load,tm.final_bounded_load,tm.max_raw_load,tm.mean_faa,tm.omega_breach,tm.first_breach_time FROM thermodynamic_metrics tm JOIN sessions s ON s.id=tm.session_id $where ORDER BY s.created_at DESC"
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(ThermodynamicSessionSummary(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getInt(9) != 0, if (c.isNull(10)) null else c.getDouble(10))) } }
    }

    fun loadSession(sessionId: String): MeegRecording = RecordingCodec.read(sessionFile(sessionId))
    fun deleteSession(sessionId: String) { sessionFile(sessionId).delete(); writableDatabase.delete("sessions", "id=?", arrayOf(sessionId)) }
    private fun createThermodynamicMetricsTable(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS thermodynamic_metrics (session_id TEXT PRIMARY KEY, algorithm_version TEXT NOT NULL, omega_crit REAL NOT NULL, final_raw_load REAL NOT NULL, final_bounded_load REAL NOT NULL, max_raw_load REAL NOT NULL, mean_faa REAL NOT NULL, omega_breach INTEGER NOT NULL, first_breach_time REAL, FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE)") }
    private fun sessionFile(sessionId: String): File = File(File(context.filesDir, "recordings").apply { mkdirs() }, "$sessionId.mgr")
}

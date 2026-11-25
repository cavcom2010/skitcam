package com.calvinmazhindu.skitcam.export

/** A successful empty query is different from a missing/failed provider response. */
internal sealed interface PublicationLookup {
    data object Absent : PublicationLookup
    data object Unknown : PublicationLookup
    data class Pending(val row: Long) : PublicationLookup
    data class Published(val row: Long) : PublicationLookup
}

internal data class PublicationRow(
    val id: Long, val name: String?, val path: String?, val owner: String?,
    val marker: String?, val pending: Int?, val trashed: Int? = 0,
)

internal data class PublicationIdentity(val name: String, val path: String, val owner: String, val marker: String) {
    fun resolve(rows: List<PublicationRow>?, savedRow: Long?, insertMayHaveRun: Boolean): PublicationLookup {
        if (rows == null) return PublicationLookup.Unknown
        if (rows.isEmpty()) return if (savedRow != null || insertMayHaveRun) PublicationLookup.Unknown else PublicationLookup.Absent
        val row = rows.singleOrNull() ?: return PublicationLookup.Unknown
        if (row.id <= 0 || (savedRow != null && row.id != savedRow) || row.name != name ||
            row.path != path || row.owner != owner || row.trashed != 0 ||
            (savedRow == null && row.marker != marker)) return PublicationLookup.Unknown
        return when (row.pending) {
            1 -> PublicationLookup.Pending(row.id)
            0 -> PublicationLookup.Published(row.id)
            else -> PublicationLookup.Unknown
        }
    }
}

/** No provider write is reachable from Unknown or Published. */
internal class PublicationRecovery(private val port: Port) {
    interface Port {
        fun lookup(): PublicationLookup
        fun prepareCopy()
        fun insertPending(): Long
        fun saveRow(row: Long)
        fun pendingMatches(row: Long): Boolean
        fun writePending(row: Long)
        fun verifyReadOnly(row: Long)
        fun makeVisible(row: Long)
    }

    fun finish(): Long {
        var prepared = false
        val row = when (val initial = port.lookup()) {
            PublicationLookup.Unknown -> uncertain()
            is PublicationLookup.Published -> {
                port.verifyReadOnly(initial.row)
                if (port.lookup() != initial) uncertain()
                return initial.row
            }
            is PublicationLookup.Pending -> initial.row
            PublicationLookup.Absent -> {
                port.prepareCopy()
                prepared = true
                port.insertPending()
            }
        }
        port.saveRow(row)
        // Re-read immediately before any writable open, including after insert and URI commit.
        when (port.lookup()) {
            PublicationLookup.Published(row) -> {
                port.verifyReadOnly(row)
                if (port.lookup() != PublicationLookup.Published(row)) uncertain()
                return row
            }
            PublicationLookup.Pending(row) -> if (!port.pendingMatches(row)) {
                if (!prepared) port.prepareCopy()
                if (port.lookup() != PublicationLookup.Pending(row)) uncertain()
                port.writePending(row)
            }
            else -> uncertain()
        }
        port.verifyReadOnly(row)
        check(port.lookup() == PublicationLookup.Pending(row)) {
            "Gallery row changed before publication. Original clips are preserved."
        }
        port.makeVisible(row)
        if (port.lookup() != PublicationLookup.Published(row)) uncertain()
        return row
    }

    private fun uncertain(): Nothing =
        error("Cannot confirm this video's Gallery status. Original clips are preserved.")
}

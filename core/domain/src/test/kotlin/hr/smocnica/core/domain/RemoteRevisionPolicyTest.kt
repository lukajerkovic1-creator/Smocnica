package hr.smocnica.core.domain

import hr.smocnica.core.model.SyncState
import org.junit.Assert.*
import org.junit.Test

class RemoteRevisionPolicyTest {
    @Test fun delayedSnapshotCannotReplaceNewerRevision() {
        assertFalse(RemoteRevisionPolicy.accepts(SyncState.SYNCED, 8, 7))
    }
    @Test fun optimisticLocalChangeSurvivesEvenNewerServerRevision() {
        assertFalse(RemoteRevisionPolicy.accepts(SyncState.PENDING, 7, 9))
    }
    @Test fun acknowledgedRevisionCanReceiveCanonicalServerFields() {
        assertTrue(RemoteRevisionPolicy.accepts(SyncState.SYNCED, 8, 8))
        assertTrue(RemoteRevisionPolicy.accepts(SyncState.SYNCED, 8, 9))
        assertTrue(RemoteRevisionPolicy.accepts(null, 0, 1))
    }
}

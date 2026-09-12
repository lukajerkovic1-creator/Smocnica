package hr.smocnica.core.domain

import hr.smocnica.core.model.SyncState

object RemoteRevisionPolicy {
    fun accepts(localState: SyncState?, localRevision: Long, remoteRevision: Long): Boolean =
        localState == null || (localState == SyncState.SYNCED && remoteRevision >= localRevision)
}

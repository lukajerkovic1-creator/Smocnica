package hr.smocnica.core.data.repository

import hr.smocnica.core.data.local.MemberEntity
import hr.smocnica.core.data.local.PantryEntity
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.model
import hr.smocnica.core.data.remote.FirebaseCallableClient
import hr.smocnica.core.domain.Invitation
import hr.smocnica.core.domain.PantryRepository
import hr.smocnica.core.model.Member
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebasePantryRepository @Inject constructor(
    private val database: SmocnicaDatabase,
    private val client: FirebaseCallableClient,
) : PantryRepository {
    override fun observePantries(): Flow<List<Pantry>> =
        database.pantryDao().observeActive().map { list -> list.map { it.model() } }

    override fun observeMembers(pantryId: String): Flow<List<Member>> =
        database.memberDao().observe(pantryId).map { list -> list.map { it.model() } }

    override suspend fun refreshPantries() {
        val response = client.call("listMyPantries")
        val values = response["pantries"] as? List<*> ?: error("Poslužitelj nije vratio popis smočnica.")
        val activeIds = mutableListOf<String>()
        values.forEach { raw ->
            val value = raw as? Map<*, *> ?: error("Neispravan zapis smočnice.")
            val pantry = cachePantry(value.mapKeys { it.key.toString() })
            activeIds += pantry.id
        }
        if (activeIds.isEmpty()) database.pantryDao().hideAll(System.currentTimeMillis())
        else database.pantryDao().hideExcept(activeIds, System.currentTimeMillis())
    }

    override suspend fun createPantry(name: String, deviceName: String): Pantry {
        val cleanName = name.trim()
        require(cleanName.length in 1..60) { "Naziv smočnice mora imati između 1 i 60 znakova." }
        val result = client.call("createPantry", mapOf("name" to cleanName, "deviceDisplayName" to deviceName))
        return cachePantry(result)
    }

    override suspend fun createInvitation(pantryId: String): Invitation {
        val result = client.call("createInvitation", mapOf("pantryId" to pantryId))
        return Invitation(
            code = result.string("code"),
            expiresAt = result.long("expiresAt"),
        )
    }

    override suspend fun joinPantry(code: String, deviceName: String): Pantry {
        val normalized = code.filter(Char::isLetterOrDigit).uppercase()
        require(normalized.length in 6..32) { "Pozivni kod nije ispravan." }
        val result = client.call("joinPantry", mapOf("code" to normalized, "deviceDisplayName" to deviceName))
        return cachePantry(result)
    }

    override suspend fun removeMember(pantryId: String, uid: String, deviceId: String, deviceName: String) {
        client.call("manageMember", mapOf("pantryId" to pantryId, "memberUid" to uid, "action" to "REMOVE", "deviceId" to deviceId, "deviceDisplayName" to deviceName))
        database.memberDao().deactivate(pantryId, uid)
    }

    override suspend fun transferOwnership(pantryId: String, uid: String, deviceId: String, deviceName: String) {
        client.call("transferOwnership", mapOf("pantryId" to pantryId, "newOwnerUid" to uid, "deviceId" to deviceId, "deviceDisplayName" to deviceName))
        database.memberDao().transferOwnership(pantryId, uid)
    }

    override suspend fun deletePantry(pantryId: String, deviceId: String, deviceName: String) {
        client.call("deletePantry", mapOf("pantryId" to pantryId, "deviceId" to deviceId, "deviceDisplayName" to deviceName))
        database.pantryDao().deleteLocal(pantryId)
    }

    private suspend fun cachePantry(result: Map<String, Any?>): Pantry {
        val pantryMap = result["pantry"] as? Map<*, *> ?: result
        val pantry = Pantry(
            id = pantryMap.string("id"),
            name = pantryMap.string("name"),
            ownerUid = pantryMap.string("ownerUid"),
            revision = pantryMap.long("revision"),
            createdAt = pantryMap.long("createdAt"),
            updatedAt = pantryMap.long("updatedAt"),
            syncState = SyncState.SYNCED,
        )
        database.pantryDao().upsert(
            PantryEntity(pantry.id, pantry.name, pantry.ownerUid, pantry.revision, pantry.createdAt, pantry.updatedAt, null, null, SyncState.SYNCED),
        )
        val member = result["member"] as? Map<*, *>
        if (member != null) {
            database.memberDao().upsertAll(
                listOf(
                    MemberEntity(
                        pantryId = pantry.id,
                        uid = member.string("uid"),
                        displayName = member["displayName"]?.toString().orEmpty(),
                        photoUrl = member["photoUrl"]?.toString(),
                        role = member.string("role"),
                        joinedAt = member.long("joinedAt"),
                        active = true,
                    ),
                ),
            )
        }
        return pantry
    }
}

internal fun Map<*, *>.string(key: String): String = this[key]?.toString() ?: error("Nedostaje polje $key.")
internal fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: error("Nedostaje brojčano polje $key.")

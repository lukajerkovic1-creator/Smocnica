package hr.smocnica.core.data.repository

import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.remote.FirebaseCallableClient
import hr.smocnica.core.domain.TrashRepository
import hr.smocnica.core.model.AggregateType
import hr.smocnica.core.model.TrashItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTrashRepository @Inject constructor(
    private val database: SmocnicaDatabase,
    private val client: FirebaseCallableClient,
) : TrashRepository {
    override fun observe(pantryId: String): Flow<List<TrashItem>> = combine(
        database.productDao().observeDeleted(pantryId),
        database.shelfDao().observeDeleted(pantryId),
        database.categoryDao().observeDeleted(pantryId),
    ) { products, shelves, categories ->
        buildList {
            products.forEach { row -> row.deletedAt?.let { deleted -> row.purgeAfter?.let { purge -> add(TrashItem(AggregateType.PRODUCT, row.id, pantryId, row.name, deleted, purge)) } } }
            shelves.forEach { row -> row.deletedAt?.let { deleted -> row.purgeAfter?.let { purge -> add(TrashItem(AggregateType.SHELF, row.id, pantryId, row.name, deleted, purge)) } } }
            categories.forEach { row -> row.deletedAt?.let { deleted -> row.purgeAfter?.let { purge -> add(TrashItem(AggregateType.CATEGORY, row.id, pantryId, row.name, deleted, purge)) } } }
        }.sortedByDescending(TrashItem::deletedAt)
    }

    override suspend fun purgeNow(pantryId: String, item: TrashItem) {
        require(item.pantryId == pantryId) { "Zapis ne pripada odabranoj smočnici." }
        client.call("purgeTrash", mapOf("pantryId" to pantryId, "type" to item.type.name, "id" to item.id))
        when (item.type) {
            AggregateType.PRODUCT -> database.productDao().deleteHard(item.id)
            AggregateType.SHELF -> database.shelfDao().deleteHard(item.id)
            AggregateType.CATEGORY -> database.categoryDao().deleteHard(item.id)
            else -> error("Nepodržana vrsta zapisa u košu.")
        }
    }
}

@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.xianxia.sect.data.incremental

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.UnifiedSerializationConstants
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.*
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class Delta(
    val formatVersion: Int = DeltaCompressor.FORMAT_VERSION,
    val baseVersion: Long,
    val deltaVersion: Long,
    val timestamp: Long,
    val operations: List<DeltaOperation>,
    val checksum: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Delta) return false
        return baseVersion == other.baseVersion && 
               deltaVersion == other.deltaVersion && 
               checksum.contentEquals(other.checksum)
    }

    override fun hashCode(): Int {
        var result = baseVersion.hashCode()
        result = 31 * result + deltaVersion.hashCode()
        result = 31 * result + checksum.contentHashCode()
        return result
    }
}

data class FieldDelta(
    val fieldName: String,
    val oldValue: Any?,
    val newValue: Any?,
    val serializedOldValue: ByteArray? = null,
    val serializedNewValue: ByteArray? = null
)

sealed class DeltaOperation {
    abstract val key: String
    abstract val dataType: DataType
    abstract val entityId: String
    
    data class Create(
        override val key: String,
        override val dataType: DataType,
        override val entityId: String,
        val serializedData: ByteArray
    ) : DeltaOperation() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Create) return false
            return key == other.key && serializedData.contentEquals(other.serializedData)
        }
        override fun hashCode(): Int = 31 * key.hashCode() + serializedData.contentHashCode()
    }
    
    data class Update(
        override val key: String,
        override val dataType: DataType,
        override val entityId: String,
        val fieldDeltas: Map<String, FieldDelta>
    ) : DeltaOperation()
    
    data class Delete(
        override val key: String,
        override val dataType: DataType,
        override val entityId: String
    ) : DeltaOperation()
}

data class DeltaChain(
    val slot: Int,
    val baseSnapshotVersion: Long,
    val deltas: List<com.xianxia.sect.data.incremental.DeltaMetadata>,
    val chainLength: Int,
    val totalSize: Long,
    val createdAt: Long,
    val lastModified: Long
)

class DeltaCompressor {
    companion object {
        private const val TAG = "DeltaCompressor"
        private const val COMPRESSION_LEVEL = Deflater.BEST_SPEED
        private const val MAX_DELTA_CHAIN_LENGTH = 50
        const val COMPACTION_THRESHOLD = 10
        const val FORMAT_VERSION = 2
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    fun computeDelta(changes: DataChanges, baseVersion: Long): Delta {
        val operations = mutableListOf<DeltaOperation>()
        
        changes.changes.forEach { change ->
            val operation = when (change.changeType) {
                ChangeType.CREATED -> {
                    val serializedData = serializeEntity(change.newValue)
                    DeltaOperation.Create(
                        key = change.key,
                        dataType = change.dataType,
                        entityId = change.entityId,
                        serializedData = serializedData
                    )
                }
                ChangeType.UPDATED -> {
                    val fieldDeltas = change.fieldChanges.map { (fieldName, fieldChange) ->
                        fieldName to FieldDelta(
                            fieldName = fieldName,
                            oldValue = fieldChange.oldValue,
                            newValue = fieldChange.newValue,
                            serializedOldValue = fieldChange.oldValue?.let { serializeEntity(it) },
                            serializedNewValue = fieldChange.newValue?.let { serializeEntity(it) }
                        )
                    }.toMap()
                    
                    DeltaOperation.Update(
                        key = change.key,
                        dataType = change.dataType,
                        entityId = change.entityId,
                        fieldDeltas = fieldDeltas
                    )
                }
                ChangeType.DELETED -> {
                    DeltaOperation.Delete(
                        key = change.key,
                        dataType = change.dataType,
                        entityId = change.entityId
                    )
                }
            }
            operations.add(operation)
        }
        
        val checksum = computeChecksum(operations)
        
        return Delta(
            baseVersion = baseVersion,
            deltaVersion = changes.version,
            timestamp = changes.timestamp,
            operations = operations,
            checksum = checksum
        )
    }
    
    fun applyDelta(baseData: SaveData, delta: Delta): SaveData {
        if (delta.formatVersion > FORMAT_VERSION) {
            Log.w(TAG, "Delta format version ${delta.formatVersion} is newer than supported $FORMAT_VERSION")
        }
        
        var currentData = baseData
        
        delta.operations.forEach { operation ->
            currentData = when (operation) {
                is DeltaOperation.Create -> applyCreate(currentData, operation)
                is DeltaOperation.Update -> applyUpdate(currentData, operation)
                is DeltaOperation.Delete -> applyDelete(currentData, operation)
            }
        }
        
        return currentData
    }
    
    private fun applyCreate(data: SaveData, operation: DeltaOperation.Create): SaveData {
        val entity = deserializeEntity(operation.serializedData, operation.dataType)
            ?: return data
        
        return when (operation.dataType) {
            DataType.DISCIPLE -> {
                if (entity is Disciple) data.copy(disciples = data.disciples + entity) else data
            }
            DataType.EQUIPMENT -> {
                if (entity is Equipment) data.copy(equipment = data.equipment + entity) else data
            }
            DataType.MANUAL -> {
                if (entity is Manual) data.copy(manuals = data.manuals + entity) else data
            }
            DataType.PILL -> {
                if (entity is Pill) data.copy(pills = data.pills + entity) else data
            }
            DataType.MATERIAL -> {
                if (entity is Material) data.copy(materials = data.materials + entity) else data
            }
            DataType.HERB -> {
                if (entity is Herb) data.copy(herbs = data.herbs + entity) else data
            }
            DataType.SEED -> {
                if (entity is Seed) data.copy(seeds = data.seeds + entity) else data
            }
            DataType.TEAM -> {
                if (entity is ExplorationTeam) data.copy(teams = data.teams + entity) else data
            }
            DataType.EVENT -> {
                if (entity is GameEvent) data.copy(events = data.events + entity) else data
            }
            DataType.BATTLE_LOG -> {
                if (entity is BattleLog) data.copy(battleLogs = data.battleLogs + entity) else data
            }
            DataType.ALLIANCE -> {
                if (entity is Alliance) data.copy(alliances = data.alliances + entity) else data
            }
            DataType.GAME_DATA -> {
                if (entity is GameData) data.copy(gameData = entity) else data
            }
            else -> data
        }
    }
    
    private fun applyUpdate(data: SaveData, operation: DeltaOperation.Update): SaveData {
        return when (operation.dataType) {
            DataType.DISCIPLE -> {
                val updatedDisciples = data.disciples.map { disciple ->
                    if (disciple.id.toString() == operation.entityId) {
                        applyFieldUpdates(disciple, operation.fieldDeltas, Disciple::class.java) ?: disciple
                    } else disciple
                }
                data.copy(disciples = updatedDisciples)
            }
            DataType.EQUIPMENT -> {
                val updatedEquipment = data.equipment.map { equip ->
                    if (equip.id.toString() == operation.entityId) {
                        applyFieldUpdates(equip, operation.fieldDeltas, Equipment::class.java) ?: equip
                    } else equip
                }
                data.copy(equipment = updatedEquipment)
            }
            DataType.MANUAL -> {
                val updatedManuals = data.manuals.map { manual ->
                    if (manual.id.toString() == operation.entityId) {
                        applyFieldUpdates(manual, operation.fieldDeltas, Manual::class.java) ?: manual
                    } else manual
                }
                data.copy(manuals = updatedManuals)
            }
            DataType.PILL -> {
                val updatedPills = data.pills.map { pill ->
                    if (pill.id.toString() == operation.entityId) {
                        applyFieldUpdates(pill, operation.fieldDeltas, Pill::class.java) ?: pill
                    } else pill
                }
                data.copy(pills = updatedPills)
            }
            DataType.MATERIAL -> {
                val updatedMaterials = data.materials.map { material ->
                    if (material.id.toString() == operation.entityId) {
                        applyFieldUpdates(material, operation.fieldDeltas, Material::class.java) ?: material
                    } else material
                }
                data.copy(materials = updatedMaterials)
            }
            DataType.HERB -> {
                val updatedHerbs = data.herbs.map { herb ->
                    if (herb.id.toString() == operation.entityId) {
                        applyFieldUpdates(herb, operation.fieldDeltas, Herb::class.java) ?: herb
                    } else herb
                }
                data.copy(herbs = updatedHerbs)
            }
            DataType.SEED -> {
                val updatedSeeds = data.seeds.map { seed ->
                    if (seed.id.toString() == operation.entityId) {
                        applyFieldUpdates(seed, operation.fieldDeltas, Seed::class.java) ?: seed
                    } else seed
                }
                data.copy(seeds = updatedSeeds)
            }
            DataType.TEAM -> {
                val updatedTeams = data.teams.map { team ->
                    if (team.id.toString() == operation.entityId) {
                        applyFieldUpdates(team, operation.fieldDeltas, ExplorationTeam::class.java) ?: team
                    } else team
                }
                data.copy(teams = updatedTeams)
            }
            DataType.GAME_DATA -> {
                val updatedGameData = applyFieldUpdates(data.gameData, operation.fieldDeltas, GameData::class.java)
                if (updatedGameData != null) data.copy(gameData = updatedGameData) else data
            }
            else -> data
        }
    }
    
    private fun applyDelete(data: SaveData, operation: DeltaOperation.Delete): SaveData {
        return when (operation.dataType) {
            DataType.DISCIPLE -> data.copy(disciples = data.disciples.filter { it.id.toString() != operation.entityId })
            DataType.EQUIPMENT -> data.copy(equipment = data.equipment.filter { it.id.toString() != operation.entityId })
            DataType.MANUAL -> data.copy(manuals = data.manuals.filter { it.id.toString() != operation.entityId })
            DataType.PILL -> data.copy(pills = data.pills.filter { it.id.toString() != operation.entityId })
            DataType.MATERIAL -> data.copy(materials = data.materials.filter { it.id.toString() != operation.entityId })
            DataType.HERB -> data.copy(herbs = data.herbs.filter { it.id.toString() != operation.entityId })
            DataType.SEED -> data.copy(seeds = data.seeds.filter { it.id.toString() != operation.entityId })
            DataType.TEAM -> data.copy(teams = data.teams.filter { it.id.toString() != operation.entityId })
            DataType.EVENT -> data.copy(events = data.events.filter { it.id.toString() != operation.entityId })
            DataType.BATTLE_LOG -> data.copy(battleLogs = data.battleLogs.filter { it.id.toString() != operation.entityId })
            DataType.ALLIANCE -> data.copy(alliances = data.alliances.filter { it.id.toString() != operation.entityId })
            else -> data
        }
    }
    
    private fun <T : Any> applyFieldUpdates(
        entity: T, 
        fieldDeltas: Map<String, FieldDelta>, 
        targetClass: Class<T>
    ): T? {
        if (fieldDeltas.isEmpty()) return entity
        
        return try {
            @Suppress("UNCHECKED_CAST")
            val serializer = entity::class.serializer() as KSerializer<T>
            val jsonElement = json.encodeToJsonElement(serializer, entity)
            val jsonObject = jsonElement as? kotlinx.serialization.json.JsonObject 
                ?: return applyFieldUpdatesViaReflection(entity, fieldDeltas, targetClass)
            
            val mutableMap = jsonObject.toMutableMap()
            
            fieldDeltas.forEach { (fieldName, delta) ->
                val newValue = delta.newValue ?: delta.serializedNewValue?.let { 
                    deserializeFieldValue(it, fieldName, targetClass) 
                }
                
                if (newValue != null) {
                    @Suppress("UNCHECKED_CAST")
                    val valueSerializer = newValue::class.serializer() as KSerializer<Any>
                    mutableMap[fieldName] = json.encodeToJsonElement(valueSerializer, newValue)
                } else {
                    mutableMap.remove(fieldName)
                }
            }
            
            val newJsonObject = kotlinx.serialization.json.JsonObject(mutableMap)
            json.decodeFromJsonElement(serializer, newJsonObject)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply field updates via Kotlinx for ${targetClass.simpleName}: ${e.message}")
            applyFieldUpdatesViaReflection(entity, fieldDeltas, targetClass)
        }
    }
    
    private fun <T : Any> applyFieldUpdatesViaReflection(
        entity: T, 
        fieldDeltas: Map<String, FieldDelta>, 
        targetClass: Class<T>
    ): T? {
        return try {
            val klass = entity::class.java
            val fields = klass.declaredFields.filter { 
                !it.name.startsWith("$") && it.name != "serialVersionUID" 
            }
            
            val constructor = klass.declaredConstructors.maxByOrNull { it.parameterCount } 
                ?: return entity
            
            constructor.isAccessible = true
            
            val params = constructor.parameters.map { param ->
                val paramName = param.name
                if (paramName == null) {
                    val field = fields.find { it.type == param.type }
                    if (field != null) {
                        field.isAccessible = true
                        field.get(entity)
                    } else null
                } else {
                    val delta = fieldDeltas[paramName]
                    if (delta != null) {
                        delta.newValue ?: delta.serializedNewValue?.let { 
                            deserializeFieldValue(it, paramName, targetClass) 
                        }
                    } else {
                        val field = fields.find { it.name == paramName }
                        if (field != null) {
                            field.isAccessible = true
                            field.get(entity)
                        } else null
                    }
                }
            }.toTypedArray()
            
            @Suppress("UNCHECKED_CAST")
            constructor.newInstance(*params) as? T ?: entity
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply field updates via reflection for ${targetClass.simpleName}: ${e.message}")
            null
        }
    }
    
    private fun <T> deserializeFieldValue(data: ByteArray, fieldName: String, targetClass: Class<T>): Any? {
        return try {
            val jsonStr = String(data, Charsets.UTF_8)
            json.decodeFromString<kotlinx.serialization.json.JsonElement>(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize field $fieldName: ${e.message}")
            null
        }
    }
    
    fun compactDeltas(deltas: List<Delta>): Delta? {
        if (deltas.isEmpty()) return null
        if (deltas.size == 1) return deltas.first()
        
        val entityStates = mutableMapOf<String, CompactEntityState>()
        val deleteOperations = mutableListOf<DeltaOperation.Delete>()
        
        deltas.sortedBy { it.deltaVersion }.forEach { delta ->
            delta.operations.forEach { op ->
                when (op) {
                    is DeltaOperation.Create -> {
                        entityStates[op.key] = CompactEntityState.Created(op.serializedData)
                    }
                    is DeltaOperation.Update -> {
                        val existing = entityStates[op.key]
                        when (existing) {
                            is CompactEntityState.Created -> {
                                val updatedData = mergeCreateWithUpdate(existing.serializedData, op.fieldDeltas)
                                if (updatedData != null) {
                                    entityStates[op.key] = CompactEntityState.Created(updatedData)
                                } else {
                                    entityStates[op.key] = CompactEntityState.Updated(
                                        existing.serializedData, op.fieldDeltas
                                    )
                                }
                            }
                            is CompactEntityState.Updated -> {
                                val mergedFields = existing.fieldDeltas.toMutableMap()
                                mergedFields.putAll(op.fieldDeltas)
                                entityStates[op.key] = CompactEntityState.Updated(
                                    existing.baseSerializedData, mergedFields
                                )
                            }
                            null -> {
                                entityStates[op.key] = CompactEntityState.Updated(
                                    ByteArray(0), op.fieldDeltas
                                )
                            }
                        }
                    }
                    is DeltaOperation.Delete -> {
                        entityStates.remove(op.key)
                        deleteOperations.add(op)
                    }
                }
            }
        }
        
        val allOperations = entityStates.map { (key, state) ->
            when (state) {
                is CompactEntityState.Created -> DeltaOperation.Create(
                    key = key,
                    dataType = DataType.DISCIPLE,
                    entityId = key.substringAfter(":"),
                    serializedData = state.serializedData
                )
                is CompactEntityState.Updated -> DeltaOperation.Update(
                    key = key,
                    dataType = DataType.DISCIPLE,
                    entityId = key.substringAfter(":"),
                    fieldDeltas = state.fieldDeltas
                )
            }
        } + deleteOperations
        
        val baseVersion = deltas.first().baseVersion
        val targetVersion = deltas.last().deltaVersion
        
        return Delta(
            baseVersion = baseVersion,
            deltaVersion = targetVersion,
            timestamp = System.currentTimeMillis(),
            operations = allOperations,
            checksum = computeChecksum(allOperations)
        )
    }
    
    private sealed class CompactEntityState {
        data class Created(val serializedData: ByteArray) : CompactEntityState()
        data class Updated(
            val baseSerializedData: ByteArray,
            val fieldDeltas: Map<String, FieldDelta>
        ) : CompactEntityState()
    }
    
    private fun mergeCreateWithUpdate(
        serializedData: ByteArray, 
        fieldDeltas: Map<String, FieldDelta>
    ): ByteArray? {
        return try {
            val jsonStr = String(serializedData, Charsets.UTF_8)
            val jsonObject = json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                ?: return null
            
            val mutableMap = jsonObject.toMutableMap()
            
            fieldDeltas.forEach { (fieldName, delta) ->
                val newValue = delta.newValue ?: delta.serializedNewValue?.let { 
                    String(it, Charsets.UTF_8) 
                }
                if (newValue != null) {
                    try {
                        val parsedValue = json.parseToJsonElement(newValue.toString())
                        mutableMap[fieldName] = parsedValue
                    } catch (e: Exception) {
                        mutableMap[fieldName] = kotlinx.serialization.json.JsonPrimitive(newValue.toString())
                    }
                }
            }
            
            val newJsonObject = kotlinx.serialization.json.JsonObject(mutableMap)
            json.encodeToString(newJsonObject).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to merge Create with Update: ${e.message}")
            null
        }
    }
    
    fun needsCompaction(deltaChain: DeltaChain): Boolean {
        return deltaChain.chainLength >= COMPACTION_THRESHOLD
    }
    
    fun compressDelta(delta: Delta): ByteArray {
        val baos = ByteArrayOutputStream()
        val serialized = serializeDelta(delta)
        
        DeflaterOutputStream(baos, Deflater(COMPRESSION_LEVEL)).use { dos ->
            dos.write(serialized)
        }
        
        return baos.toByteArray()
    }
    
    fun decompressDelta(data: ByteArray): Delta? {
        return try {
            val bais = ByteArrayInputStream(data)
            val baos = ByteArrayOutputStream()
            
            InflaterInputStream(bais).use { iis ->
                val buffer = ByteArray(4096)
                var read: Int
                while (iis.read(buffer).also { read = it } > 0) {
                    baos.write(buffer, 0, read)
                }
            }
            
            deserializeDelta(baos.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress delta", e)
            null
        }
    }
    
    private fun serializeEntity(entity: Any?): ByteArray {
        if (entity == null) return ByteArray(0)
        return try {
            @Suppress("UNCHECKED_CAST")
            val serializer = entity::class.serializer() as KSerializer<Any>
            json.encodeToString(serializer, entity).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize entity: ${e.message}")
            ByteArray(0)
        }
    }
    
    private fun deserializeEntity(data: ByteArray, dataType: DataType): Any? {
        if (data.isEmpty()) return null
        val jsonStr = String(data, Charsets.UTF_8)
        return try {
            when (dataType) {
                DataType.GAME_DATA -> json.decodeFromString<GameData>(jsonStr)
                DataType.DISCIPLE -> json.decodeFromString<Disciple>(jsonStr)
                DataType.EQUIPMENT -> json.decodeFromString<Equipment>(jsonStr)
                DataType.MANUAL -> json.decodeFromString<Manual>(jsonStr)
                DataType.PILL -> json.decodeFromString<Pill>(jsonStr)
                DataType.MATERIAL -> json.decodeFromString<Material>(jsonStr)
                DataType.HERB -> json.decodeFromString<Herb>(jsonStr)
                DataType.SEED -> json.decodeFromString<Seed>(jsonStr)
                DataType.TEAM -> json.decodeFromString<ExplorationTeam>(jsonStr)
                DataType.EVENT -> json.decodeFromString<GameEvent>(jsonStr)
                DataType.BATTLE_LOG -> json.decodeFromString<BattleLog>(jsonStr)
                DataType.ALLIANCE -> json.decodeFromString<Alliance>(jsonStr)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize entity of type $dataType: ${e.message}")
            null
        }
    }
    
    private fun serializeDelta(delta: Delta): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(FORMAT_VERSION)
            dos.writeLong(delta.baseVersion)
            dos.writeLong(delta.deltaVersion)
            dos.writeLong(delta.timestamp)
            dos.writeInt(delta.operations.size)
            
            delta.operations.forEach { op ->
                serializeOperation(dos, op)
            }
            
            dos.write(delta.checksum)
        }
        return baos.toByteArray()
    }
    
    private fun serializeOperation(dos: DataOutputStream, op: DeltaOperation) {
        dos.writeUTF(op.key)
        dos.writeUTF(op.dataType.name)
        dos.writeUTF(op.entityId)
        
        when (op) {
            is DeltaOperation.Create -> {
                dos.writeUTF("CREATE")
                dos.writeInt(op.serializedData.size)
                dos.write(op.serializedData)
            }
            is DeltaOperation.Update -> {
                dos.writeUTF("UPDATE")
                dos.writeInt(op.fieldDeltas.size)
                op.fieldDeltas.forEach { (fieldName, fieldDelta) ->
                    dos.writeUTF(fieldName)
                    serializeFieldDelta(dos, fieldDelta)
                }
            }
            is DeltaOperation.Delete -> {
                dos.writeUTF("DELETE")
            }
        }
    }
    
    private fun serializeFieldDelta(dos: DataOutputStream, delta: FieldDelta) {
        dos.writeUTF(delta.fieldName)
        
        val hasOldValue = delta.serializedOldValue != null
        val hasNewValue = delta.serializedNewValue != null
        
        dos.writeBoolean(hasOldValue)
        if (hasOldValue) {
            val oldValue = delta.serializedOldValue ?: return
            dos.writeInt(oldValue.size)
            dos.write(oldValue)
        }

        dos.writeBoolean(hasNewValue)
        if (hasNewValue) {
            val newValue = delta.serializedNewValue ?: return
            dos.writeInt(newValue.size)
            dos.write(newValue)
        }
    }
    
    private fun deserializeDelta(data: ByteArray): Delta? {
        return try {
            val bais = ByteArrayInputStream(data)
            DataInputStream(bais).use { dis ->
                val formatVersion = dis.readInt()
                
                if (formatVersion > FORMAT_VERSION) {
                    Log.w(TAG, "Delta format version $formatVersion is newer than supported $FORMAT_VERSION")
                }
                
                val baseVersion = dis.readLong()
                val deltaVersion = dis.readLong()
                val timestamp = dis.readLong()
                val operationCount = dis.readInt()
                
                val operations = mutableListOf<DeltaOperation>()
                repeat(operationCount) {
                    operations.add(deserializeOperation(dis))
                }
                
                val checksum = ByteArray(32)
                dis.read(checksum)
                
                Delta(
                    formatVersion = formatVersion,
                    baseVersion = baseVersion,
                    deltaVersion = deltaVersion,
                    timestamp = timestamp,
                    operations = operations,
                    checksum = checksum
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize delta", e)
            null
        }
    }
    
    private fun deserializeOperation(dis: DataInputStream): DeltaOperation {
        val key = dis.readUTF()
        val dataTypeName = dis.readUTF()
        val entityId = dis.readUTF()
        val opType = dis.readUTF()
        val dataType = DataType.valueOf(dataTypeName)
        
        return when (opType) {
            "CREATE" -> {
                val dataSize = dis.readInt()
                val serializedData = ByteArray(dataSize)
                dis.read(serializedData)
                DeltaOperation.Create(key, dataType, entityId, serializedData)
            }
            "UPDATE" -> {
                val fieldCount = dis.readInt()
                val fieldDeltas = mutableMapOf<String, FieldDelta>()
                repeat(fieldCount) {
                    val fieldName = dis.readUTF()
                    val fieldDelta = deserializeFieldDelta(dis)
                    fieldDeltas[fieldName] = fieldDelta
                }
                DeltaOperation.Update(key, dataType, entityId, fieldDeltas)
            }
            "DELETE" -> {
                DeltaOperation.Delete(key, dataType, entityId)
            }
            else -> throw IllegalArgumentException("Unknown operation type: $opType")
        }
    }
    
    private fun deserializeFieldDelta(dis: DataInputStream): FieldDelta {
        val fieldName = dis.readUTF()
        
        val hasOldValue = dis.readBoolean()
        val serializedOldValue = if (hasOldValue) {
            val size = dis.readInt()
            ByteArray(size).also { dis.read(it) }
        } else null
        
        val hasNewValue = dis.readBoolean()
        val serializedNewValue = if (hasNewValue) {
            val size = dis.readInt()
            ByteArray(size).also { dis.read(it) }
        } else null
        
        return FieldDelta(
            fieldName = fieldName,
            oldValue = null,
            newValue = null,
            serializedOldValue = serializedOldValue,
            serializedNewValue = serializedNewValue
        )
    }
    
    private fun computeChecksum(operations: List<DeltaOperation>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        operations.forEach { op ->
            digest.update(op.key.toByteArray())
            digest.update(op.dataType.name.toByteArray())
            digest.update(op.entityId.toByteArray())
            
            when (op) {
                is DeltaOperation.Create -> {
                    digest.update(op.serializedData)
                }
                is DeltaOperation.Update -> {
                    op.fieldDeltas.forEach { (fieldName, delta) ->
                        digest.update(fieldName.toByteArray())
                        delta.serializedOldValue?.let { digest.update(it) }
                        delta.serializedNewValue?.let { digest.update(it) }
                    }
                }
                is DeltaOperation.Delete -> {
                    digest.update("DELETE".toByteArray())
                }
            }
        }
        return digest.digest()
    }
}

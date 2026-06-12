package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ExploredSectInfo
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.repository.HeavyDataDecoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeavyDataDecoderImpl @Inject constructor() : HeavyDataDecoder {

    private val converters = ProtobufConverters

    override fun decodeDiscipleListMapFromRows(rows: List<GameHeavyData>, key: String): Map<String, List<Disciple>> =
        converters.decodeDiscipleListMapFromRows(rows, key)

    override fun decodeSectDetailMapFromRows(rows: List<GameHeavyData>, key: String): Map<String, SectDetail> =
        converters.decodeSectDetailMapFromRows(rows, key)

    override fun decodeExploredSectInfoMapFromRows(rows: List<GameHeavyData>, key: String): Map<String, ExploredSectInfo> =
        converters.decodeExploredSectInfoMapFromRows(rows, key)

    override fun decodeSectScoutInfoMapFromRows(rows: List<GameHeavyData>, key: String): Map<String, SectScoutInfo> =
        converters.decodeSectScoutInfoMapFromRows(rows, key)

    override fun decodeManualProficiencyMapFromRows(rows: List<GameHeavyData>, key: String): Map<String, List<ManualProficiencyData>> =
        converters.decodeManualProficiencyMapFromRows(rows, key)

    override fun decodeDiscipleListFromRows(rows: List<GameHeavyData>, key: String): List<Disciple> =
        converters.decodeDiscipleListFromRows(rows, key)

    override fun decodeWorldSectListFromRows(rows: List<GameHeavyData>, key: String): List<WorldSect> =
        converters.decodeWorldSectListFromRows(rows, key)
}

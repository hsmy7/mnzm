import re
BASE = 'c:/Mnzm/XianxiaSectNative/android/'
log = []

def rd(p):
    with open(BASE + p, encoding='utf-8', newline='') as f:
        return f.read()

def wr(p, s):
    with open(BASE + p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

def sub(p, pat, new, tag, flags=0):
    s = rd(p)
    s2, n = re.subn(pat, new, s, flags=flags)
    if n == 0:
        log.append('MISS ' + tag)
    else:
        wr(p, s2); log.append('OK(%d) %s' % (n, tag))

p = 'app/src/main/java/com/xianxia/sect/di/AppModule.kt'
sub(p, r'import com\.xianxia\.sect\.data\.incremental\.ChangeTracker\r?\n', '', 'AM-imp')
sub(p, r'    @Provides\r?\n    @Singleton\r?\n    fun provideChangeTracker\(\): ChangeTracker \{\r?\n        return ChangeTracker\(\)\r?\n    \}\r?\n\r?\n', '', 'AM-prov')

p = 'core/data/src/main/java/com/xianxia/sect/data/archive/ArchiveDaos.kt'
sub(p, r'    @Query\("SELECT \* FROM archived_battle_logs WHERE slot_id = :slotId ORDER BY timestamp DESC"\)\r?\n    suspend fun getBySlot\(slotId: Int\): List<ArchivedBattleLog>\r?\n\r?\n', '', 'AD-b-getBySlot')
sub(p, r'    @Query\("SELECT \* FROM archived_battle_logs WHERE slot_id = :slotId AND timestamp BETWEEN :startMs AND :endMs " \+\r?\n        "ORDER BY timestamp DESC"\)\r?\n    suspend fun getByTimeRange\(slotId: Int, startMs: Long, endMs: Long\): List<ArchivedBattleLog>\r?\n\r?\n', '', 'AD-b-getByTimeRange')
sub(p, r'    @Query\("SELECT COUNT\(\*\) FROM archived_battle_logs WHERE slot_id = :slotId"\)\r?\n    suspend fun getCountBySlot\(slotId: Int\): Int\r?\n\r?\n', '', 'AD-b-getCountBySlot')
sub(p, r'    @Query\("SELECT \* FROM archived_disciples WHERE slot_id = :slotId ORDER BY archived_at DESC"\)\r?\n    suspend fun getBySlot\(slotId: Int\): List<ArchivedDisciple>\r?\n\r?\n', '', 'AD-d-getBySlot')
sub(p, r"    @Query\(\"SELECT \* FROM archived_disciples WHERE slot_id = :slotId AND name LIKE '%' \|\| :keyword \|\| '%'\"\)\r?\n    suspend fun searchByName\(slotId: Int, keyword: String\): List<ArchivedDisciple>\r?\n\r?\n", '', 'AD-d-searchByName')
sub(p, r'    @Query\("SELECT COUNT\(\*\) FROM archived_disciples WHERE slot_id = :slotId"\)\r?\n    suspend fun getCountBySlot\(slotId: Int\): Int\r?\n\r?\n', '', 'AD-d-getCountBySlot')

p = 'app/src/main/java/com/xianxia/sect/core/util/AppErrorExt.kt'
s = rd(p)
i = s.find('fun com.xianxia.sect.data.crypto.VerificationResult.toAppError')
if i >= 0:
    head = s[:i].rstrip('\r\n')
    wr(p, head + '\r\n'); log.append('OK AEE-ext')
else:
    log.append('MISS AEE-ext')

with open('c:/Mnzm/XianxiaSectNative/.clash-repair/_editlog2.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(log) + '\n')
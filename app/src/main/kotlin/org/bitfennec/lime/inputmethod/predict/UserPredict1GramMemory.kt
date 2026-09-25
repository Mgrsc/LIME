package org.bitfennec.lime.inputmethod.predict

/**
 * User 1-gram dynamic pair memory container (A -> B).
 * Core contract:
 * 1. Word length filter: prev.length in 2..6 && next.length in 2..6 to eliminate single-char noise;
 * 2. Time window: commit interval <= 15 seconds;
 * 3. In-memory capacity: 300 LRU entries, sorted by frequency and recency.
 */
class UserPredict1GramMemory(private val maxCapacity: Int = 300) {

    data class PairRecord(
        val nextWord: String,
        var count: Int,
        var lastUsedTime: Long
    )

    // key: prevWord -> value: list of PairRecord
    private val memoryMap = LinkedHashMap<String, MutableList<PairRecord>>(16, 0.75f, true)

    @Synchronized
    fun recordPair(prevWord: String, nextWord: String, timestamp: Long) {
        if (prevWord.length !in 2..6 || nextWord.length !in 2..6) return
        if (prevWord == nextWord) return

        val list = memoryMap.computeIfAbsent(prevWord) { mutableListOf() }
        val existing = list.firstOrNull { it.nextWord == nextWord }
        if (existing != null) {
            existing.count++
            existing.lastUsedTime = timestamp
        } else {
            list.add(PairRecord(nextWord, 1, timestamp))
        }

        // Re-rank by frequency and recency
        list.sortByDescending { it.count * 1000000L + it.lastUsedTime }

        trimToCapacity()
    }

    @Synchronized
    fun getPredictions(prevWord: String): List<String> {
        if (prevWord.length !in 2..6) return emptyList()
        val list = memoryMap[prevWord] ?: return emptyList()
        val now = System.currentTimeMillis()
        list.forEach { it.lastUsedTime = now }
        list.sortByDescending { it.count * 1000000L + it.lastUsedTime }
        return list.map { it.nextWord }
    }

    @Synchronized
    fun removePair(prevWord: String, nextWord: String): Boolean {
        val list = memoryMap[prevWord] ?: return false
        val removed = list.removeAll { it.nextWord == nextWord }
        if (list.isEmpty()) {
            memoryMap.remove(prevWord)
        }
        return removed
    }

    @Synchronized
    fun clear() {
        memoryMap.clear()
    }

    internal fun pairCount(): Int = memoryMap.values.sumOf { it.size }

    private fun trimToCapacity() {
        while (pairCount() > maxCapacity) {
            val oldest = memoryMap.entries
                .flatMap { (prevWord, records) -> records.map { prevWord to it } }
                .minByOrNull { (_, record) -> record.lastUsedTime }
                ?: return
            val records = memoryMap[oldest.first] ?: return
            records.remove(oldest.second)
            if (records.isEmpty()) memoryMap.remove(oldest.first)
        }
    }
}

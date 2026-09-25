package org.bitfennec.lime.inputmethod.predict

/**
 * User 2-gram dynamic triplet memory container (A + B -> C).
 * Core contract:
 * 1. Word length limits: w1, w2, w3 between 2..6 chars;
 * 2. Time window: consecutive commit interval <= 15 seconds;
 * 3. Capacity: 1000 triplet entries with frequency and decay eviction.
 */
class UserPredict2GramMemory(private val maxCapacity: Int = 1000) {

    private companion object {
        const val RECENCY_DECAY_MS = 24 * 60 * 60 * 1000L
    }

    data class TriRecord(
        val nextWord: String,
        var count: Int,
        var lastUsedTime: Long
    )

    // key: "w1\t\tw2" -> value: list of TriRecord
    private val memoryMap = LinkedHashMap<String, MutableList<TriRecord>>(16, 0.75f, true)

    private fun makeKey(w1: String, w2: String) = "$w1\t\t$w2"

    @Synchronized
    fun recordTri(w1: String, w2: String, w3: String, timestamp: Long) {
        if (w1.length !in 2..6 || w2.length !in 2..6 || w3.length !in 2..6) return

        val key = makeKey(w1, w2)
        val list = memoryMap.computeIfAbsent(key) { mutableListOf() }
        val existing = list.firstOrNull { it.nextWord == w3 }
        if (existing != null) {
            existing.count++
            existing.lastUsedTime = timestamp
        } else {
            list.add(TriRecord(w3, 1, timestamp))
        }

        list.sortByDescending { it.count * 1000000L + it.lastUsedTime }

        trimToCapacity(timestamp)
    }

    @Synchronized
    fun getPredictions(w1: String, w2: String): List<String> {
        if (w1.length !in 2..6 || w2.length !in 2..6) return emptyList()
        val key = makeKey(w1, w2)
        val list = memoryMap[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        list.forEach { it.lastUsedTime = now }
        list.sortByDescending { it.count * 1000000L + it.lastUsedTime }
        return list.map { it.nextWord }
    }

    @Synchronized
    fun removeTri(w1: String, w2: String, w3: String): Boolean {
        val key = makeKey(w1, w2)
        val list = memoryMap[key] ?: return false
        val removed = list.removeAll { it.nextWord == w3 }
        if (list.isEmpty()) {
            memoryMap.remove(key)
        }
        return removed
    }

    @Synchronized
    fun clear() {
        memoryMap.clear()
    }

    internal fun triCount(): Int = memoryMap.values.sumOf { it.size }

    private fun trimToCapacity(now: Long) {
        while (triCount() > maxCapacity) {
            val weakest = memoryMap.entries
                .flatMap { (key, records) -> records.map { key to it } }
                .minByOrNull { (_, record) ->
                    record.count - (now - record.lastUsedTime).coerceAtLeast(0).toDouble() / RECENCY_DECAY_MS
                }
                ?: return
            val records = memoryMap[weakest.first] ?: return
            records.remove(weakest.second)
            if (records.isEmpty()) memoryMap.remove(weakest.first)
        }
    }
}

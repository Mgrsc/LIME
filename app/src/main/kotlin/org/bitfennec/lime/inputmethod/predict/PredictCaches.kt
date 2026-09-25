package org.bitfennec.lime.inputmethod.predict

/**
 * Prediction caches and function word filter.
 * 1. HotContextCache: Cached query results for frequent contexts;
 * 2. NegativeCache: 5-second negative cache for contexts yielding no candidates;
 * 3. FUNCTION_WORDS: Function words filtered out from top next-word predictions.
 */
object PredictCaches {

    val FUNCTION_WORDS = setOf("的", "了", "在", "是", "着", "过", "和", "地", "得", "么", "吗", "呢", "吧", "啊")

    class HotContextCache(private val maxCapacity: Int = 100) {
        private val cache = LinkedHashMap<String, Array<String>>(16, 0.75f, true)

        @Synchronized
        fun get(key: String): Array<String>? = cache[key]

        @Synchronized
        fun put(key: String, value: Array<String>) {
            cache[key] = value
            if (cache.size > maxCapacity) {
                val oldest = cache.keys.firstOrNull()
                if (oldest != null) cache.remove(oldest)
            }
        }

        @Synchronized
        fun remove(key: String) {
            cache.remove(key)
        }

        @Synchronized
        fun clear() = cache.clear()
    }

    class NegativeCache(private val maxCapacity: Int = 200, private val ttlMs: Long = 5000L) {
        private val negativeMap = LinkedHashMap<String, Long>(16, 0.75f, true)

        @Synchronized
        fun isNegative(key: String, now: Long = System.currentTimeMillis()): Boolean {
            val expireTime = negativeMap[key] ?: return false
            if (now > expireTime) {
                negativeMap.remove(key)
                return false
            }
            return true
        }

        @Synchronized
        fun markNegative(key: String, now: Long = System.currentTimeMillis()) {
            negativeMap[key] = now + ttlMs
            if (negativeMap.size > maxCapacity) {
                val oldest = negativeMap.keys.firstOrNull()
                if (oldest != null) negativeMap.remove(oldest)
            }
        }

        @Synchronized
        fun clear() = negativeMap.clear()
    }
}

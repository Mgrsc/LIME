package org.bitfennec.lime.inputmethod.predict

/**
 * High-performance facade engine for next-word prediction.
 * Core contract:
 * 1. Type A (post-commit next-word): user 2-gram -> user 1-gram -> system 2-gram, deduplicated to Top-8;
 * 2. Anti-pollution: function word filtering, 15s commit window, 5s negative cache;
 * 3. Queries run on PredictDispatcher; commit context updates are synchronized.
 */
object PredictEngine {

    val user1Gram = UserPredict1GramMemory(maxCapacity = 300)
    val user2Gram = UserPredict2GramMemory(maxCapacity = 1000)
    private val hotCache = PredictCaches.HotContextCache(maxCapacity = 100)
    private val negativeCache = PredictCaches.NegativeCache(maxCapacity = 200, ttlMs = 5000L)

    @Volatile
    private var lastWord: String = ""
    @Volatile
    private var secondLastWord: String = ""
    @Volatile
    private var lastCommitTime: Long = 0L

    const val TIME_WINDOW_MS = 15000L // 15-second association time window

    fun getSecondLastWord(): String = secondLastWord

    fun getLastWord(): String = lastWord

    /** Records committed text for user phrase learning */
    @Synchronized
    fun recordCommit(word: String, timestamp: Long = System.currentTimeMillis()) {
        val isWithinTimeWindow = lastWord.isNotEmpty() &&
            timestamp >= lastCommitTime &&
            timestamp - lastCommitTime <= TIME_WINDOW_MS

        if (word.length in 2..6 && isWithinTimeWindow && lastWord.length in 2..6) {
            // Record user 1-gram and invalidate corresponding cache
            user1Gram.recordPair(lastWord, word, timestamp)
            hotCache.remove(lastWord)

            // Record user 2-gram (A + B -> C)
            if (secondLastWord.length in 2..6) {
                user2Gram.recordTri(secondLastWord, lastWord, word, timestamp)
            }
        }

        secondLastWord = if (isWithinTimeWindow) lastWord else ""
        lastWord = word
        lastCommitTime = timestamp
    }

    /** Clears all caches (used in unit test isolation or dictionary rebuilds) */
    fun resetCaches() {
        hotCache.clear()
        negativeCache.clear()
    }

    /** Resets committed context (e.g. on input field switch or clear) */
    @Synchronized
    fun resetContext() {
        lastWord = ""
        secondLastWord = ""
        lastCommitTime = 0L
        resetCaches()
    }

    /** Queries Type A prediction: returns Top-8 next-word candidates for committed word */
    fun predictTypeA(contextWord: String, lastWord2: String = secondLastWord): List<String> =
        predictTypeACandidates(contextWord, lastWord2).map(PredictionCandidate::text)

    fun predictTypeACandidates(
        contextWord: String,
        lastWord2: String = secondLastWord,
    ): List<PredictionCandidate> {
        if (contextWord.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        val u2List = if (lastWord2.isNotEmpty()) {
            user2Gram.getPredictions(lastWord2, contextWord)
        } else {
            emptyList()
        }
        val u1List = user1Gram.getPredictions(contextWord)

        // Only system results are cached; user word associations remain dynamic for deletion.
        if (u2List.isEmpty() && u1List.isEmpty()) {
            // A previous miss must not hide new learning or another trigram context.
            if (negativeCache.isNegative(contextWord, now)) return emptyList()
            hotCache.get(contextWord)?.let { cached ->
                return cached.map { PredictionCandidate(it, PredictionSource.SYSTEM) }
            }
        }

        val resultSet = LinkedHashMap<String, PredictionSource>()
        fun addCandidate(word: String, source: PredictionSource) {
            if (word !in PredictCaches.FUNCTION_WORDS) resultSet.putIfAbsent(word, source)
        }

        // 3. User 2-gram priority (A + B -> C)
        u2List.forEach { addCandidate(it, PredictionSource.USER_2GRAM) }

        // 4. User 1-gram (A -> B)
        u1List.forEach { addCandidate(it, PredictionSource.USER_1GRAM) }

        // 5. System 2-gram
        val sysList = SystemPredictTable.getSystemPredictions(contextWord)
        if (sysList != null) {
            sysList.forEach { addCandidate(it, PredictionSource.SYSTEM) }
        }

        // Character suffixes are not word boundaries; leave unknown contexts empty.

        val result = resultSet.entries.take(8).map { PredictionCandidate(it.key, it.value) }

        if (result.isEmpty()) {
            negativeCache.markNegative(contextWord, now)
        } else if (u2List.isEmpty() && u1List.isEmpty()) {
            hotCache.put(contextWord, result.map(PredictionCandidate::text).toTypedArray())
        }

        return result
    }

    /** Deletes user prediction association; system dictionary remains untouched */
    fun removeUserPair(prevWord: String, nextWord: String): Boolean {
        val removed1 = user1Gram.removePair(prevWord, nextWord)
        hotCache.clear()
        return removed1
    }

    fun removeUserPrediction(source: PredictionSource, nextWord: String): Boolean {
        val removed = when (source) {
            PredictionSource.USER_1GRAM -> user1Gram.removePair(lastWord, nextWord)
            PredictionSource.USER_2GRAM -> user2Gram.removeTri(secondLastWord, lastWord, nextWord)
            PredictionSource.SYSTEM -> false
        }
        if (removed) hotCache.clear()
        return removed
    }
}

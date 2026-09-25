package org.bitfennec.lime.inputmethod.predict

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object SystemPredictTable {
    private const val ASSET_PATH = "predict/system_predict.tsv"
    private val loading = AtomicBoolean(false)

    @Volatile
    private var predictions: Map<String, Array<String>> = emptyMap()
    @Volatile
    private var enabled = true

    private val fallbackPredictions = mapOf(
        "你好" to arrayOf("朋友", "世界"),
        "中国" to arrayOf("人民", "银行"),
        "人民" to arrayOf("英雄", "群众"),
        "今天" to arrayOf("天气", "晚上", "早上"),
    )

    fun initialize(context: Context, enabled: Boolean = true) {
        this.enabled = enabled
        if (!enabled) {
            predictions = emptyMap()
            return
        }
        if (predictions.isNotEmpty() || !loading.compareAndSet(false, true)) return
        try {
            val loaded = LinkedHashMap<String, MutableList<String>>()
            context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val fields = line.split('\t', limit = 3)
                    if (fields.size == 3 && fields[1].isNotBlank()) {
                        loaded.getOrPut(fields[0]) { mutableListOf() }.add(fields[1])
                    }
                }
            }
            predictions = loaded.mapValues { (_, values) -> values.take(8).toTypedArray() }
        } finally {
            loading.set(false)
        }
    }

    fun getSystemPredictions(word: String): Array<String>? {
        if (!enabled) return null
        return predictions[word] ?: fallbackPredictions[word]
    }

    internal fun replaceForTest(values: Map<String, Array<String>>) {
        enabled = true
        predictions = values
    }
}

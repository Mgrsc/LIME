package org.bitfennec.lime.entity

class StringQueue(private val maxSize: Int = 10) {
    private val items = ArrayDeque<String>()

    fun push(item: String) {
        if (items.size >= maxSize) {
            items.removeFirst()
        }
        items.add(item)
    }

    fun popInReverseOrder(): String? {
        return items.removeLastOrNull()
    }
    fun size(): Int = items.size
    fun isEmpty(): Boolean = items.isEmpty()

    fun clear() {
        items.clear()
    }
}
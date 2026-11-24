package com.mochyy.mochymusic.module

/**
 * A map that keeps at most [max] entries and drops the least recently used one when a new entry
 * would go over that. Modules remember the songs they matched and the ones they missed for the
 * session, and a session has no end, so those maps are bounded instead of growing with everything
 * ever played. Reads and writes are safe from any thread.
 */
class BoundedCache<K : Any, V : Any>(private val max: Int) {

    init {
        require(max > 0) { "a cache needs room for at least one entry, not $max" }
    }

    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > max
    }

    operator fun get(key: K): V? = synchronized(entries) { entries[key] }

    operator fun set(key: K, value: V) {
        synchronized(entries) { entries[key] = value }
    }

    /** Drops every entry whose key matches, for a module that takes a server away and wants its songs forgotten. */
    fun removeWhere(predicate: (K) -> Boolean) {
        synchronized(entries) { entries.keys.removeIf(predicate) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    val size: Int get() = synchronized(entries) { entries.size }
}

@file:Suppress("unused")

package android.util

/** Log → Standardfehlerausgabe. */
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private fun out(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        System.err.println("$level/$tag: ${msg ?: ""}")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?): Int = out("V", tag, msg, null)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = out("V", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = out("D", tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = out("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = out("I", tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = out("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = out("W", tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = out("W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = out("W", tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = out("E", tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = out("E", tag, msg, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = out("F", tag, msg, null)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String =
        tr?.let { java.io.StringWriter().also { w -> it.printStackTrace(java.io.PrintWriter(w)) }.toString() } ?: ""
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = level >= INFO
}

/** LRU-Cache mit Größenbegrenzung wie android.util.LruCache (thread-sicher). */
open class LruCache<K : Any, V : Any>(maxSize: Int) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var size = 0
    private var maxSize = maxSize
    private var hitCount = 0
    private var missCount = 0

    init { require(maxSize > 0) { "maxSize <= 0" } }

    fun get(key: K): V? {
        synchronized(this) {
            map[key]?.let { hitCount++; return it }
            missCount++
        }
        val created = create(key) ?: return null
        synchronized(this) {
            val previous = map.put(key, created)
            if (previous != null) map[key] = previous else size += safeSizeOf(key, created)
            if (previous != null) { entryRemoved(false, key, created, previous); return previous }
        }
        trimToSize(maxSize)
        return created
    }

    fun put(key: K, value: V): V? {
        val previous: V?
        synchronized(this) {
            size += safeSizeOf(key, value)
            previous = map.put(key, value)
            if (previous != null) size -= safeSizeOf(key, previous)
        }
        if (previous != null) entryRemoved(false, key, previous, value)
        trimToSize(maxSize)
        return previous
    }

    fun remove(key: K): V? {
        val previous: V?
        synchronized(this) {
            previous = map.remove(key)
            if (previous != null) size -= safeSizeOf(key, previous)
        }
        if (previous != null) entryRemoved(false, key, previous, null)
        return previous
    }

    open fun trimToSize(maxSize: Int) {
        while (true) {
            var key: K
            var value: V
            synchronized(this) {
                if (size <= maxSize || map.isEmpty()) return
                val e = map.entries.iterator().next()
                key = e.key; value = e.value
                map.remove(key)
                size -= safeSizeOf(key, value)
            }
            entryRemoved(true, key, value, null)
        }
    }

    fun resize(maxSize: Int) { synchronized(this) { this.maxSize = maxSize }; trimToSize(maxSize) }

    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}
    protected open fun create(key: K): V? = null
    protected open fun sizeOf(key: K, value: V): Int = 1

    private fun safeSizeOf(key: K, value: V): Int = sizeOf(key, value).also { check(it >= 0) { "Negative size: $key=$value" } }

    fun evictAll() = trimToSize(-1)
    @Synchronized fun size(): Int = size
    @Synchronized fun maxSize(): Int = maxSize
    @Synchronized fun hitCount(): Int = hitCount
    @Synchronized fun missCount(): Int = missCount
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(map)
}

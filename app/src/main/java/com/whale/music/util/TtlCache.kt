package com.whale.music.util

/**
 * 带过期时间的线程安全缓存 —— 负缓存（「搜不到」也记一笔）的载体。
 *
 * 使用约定：**语义空结果**（搜不到这首歌 / 接口明确说没有）可以进缓存长期记，
 * **传输失败**（超时 / 风控 / 5xx）不要进缓存 —— 否则一次网络抖动会把
 * 结果封死到过期为止。
 *
 * 时钟可注入，方便 JVM 单测（见 TtlCacheTest）。
 */
class TtlCache<V>(
    private val maxSize: Int = 512,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    private class Entry<V>(val value: V, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>): Boolean =
            size > maxSize
    }

    /** 命中返回值；不存在或已过期返回 null（过期条目顺手清掉） */
    @Synchronized
    operator fun get(key: String): V? {
        val e = map[key] ?: return null
        if (nowMs() >= e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.value
    }

    /** 写入一条 ttlMs 后过期的记录 */
    @Synchronized
    fun put(key: String, value: V, ttlMs: Long) {
        if (ttlMs <= 0) return
        map[key] = Entry(value, nowMs() + ttlMs)
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}

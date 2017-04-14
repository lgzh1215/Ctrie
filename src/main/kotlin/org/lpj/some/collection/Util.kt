package org.lpj.some.collection

internal typealias Gen = Any
internal typealias Iterator<T> = MutableIterator<T>
internal typealias Entry<K, V> = MutableMap.MutableEntry<K, V>

internal val Any?.hash: Int get() {
    val h = this?.hashCode() ?: return 0
    return (Integer.reverseBytes((h * 0x9e3775cd).toInt()) * 0x9e3775cd).toInt()
}

internal fun Any?.equal(that: Any?) = this == that

internal val RESTART = Any()
internal val KEY_ABSENT = Any()
internal val KEY_PRESENT = Any()
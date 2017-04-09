package org.lpj.some.collection

import java.util.*
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.NoSuchElementException

typealias Gen = Any
typealias Iterator<T> = MutableIterator<T>
typealias Entry<K, V> = MutableMap.MutableEntry<K, V>

@Suppress("UNCHECKED_CAST", "unused")
class TrieMap<K, V> : AbstractMutableMap<K, V>, ConcurrentMap<K, V> {

    @Volatile
    private lateinit var root: Any
    private val readOnly: Boolean

    constructor() : this(INode.newRootNode<K, V>(), readOnly = false)

    private constructor(readOnly: Boolean) {
        this.readOnly = readOnly
    }

    private constructor(root: Any, readOnly: Boolean) : this(readOnly) {
        this.root = root
    }

    companion object {
        private val ROOT_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TrieMap::class.java, Any::class.java, "root")

        internal val RESTART = Any()
        internal val KEY_ABSENT = Any()
        internal val KEY_PRESENT = Any()

        internal fun hash(k: Any?): Int {
            var h = k?.hashCode() ?: return 0
            h = h xor ((h ushr 20) xor (h ushr 12))
            h = h xor ((h ushr 7) xor (h ushr 4))
            return h
        }

        internal fun equal(k1: Any?, k2: Any?): Boolean = k1 == k2
    }

    private abstract class Branch

    private abstract class MainNode<K, V> {
        @Volatile
        var prev: MainNode<K, V>? = null

        abstract fun cachedSize(ct: Any): Int

        fun CAS_PREV(oldValue: MainNode<K, V>, newValue: MainNode<K, V>?) = updater.compareAndSet(this, oldValue, newValue)

        fun WRITE_PREV(newValue: MainNode<K, V>) = updater.set(this, newValue)

        companion object {
            private val updater = AtomicReferenceFieldUpdater.newUpdater(MainNode::class.java, MainNode::class.java, "prev")
        }
    }

    private interface Pair<K, V> : Entry<K, V> {
        override fun setValue(newValue: V): V = throw UnsupportedOperationException()
    }

    private class INode<K, V>(val gen: Gen) : Branch() {
        @Volatile lateinit
        var mainNode: MainNode<K, V>

        constructor(mainNode: MainNode<K, V>, gen: Gen) : this(gen) {
            WRITE_MainNode(mainNode)
        }

        fun WRITE_MainNode(newValue: MainNode<K, V>) = updater.set(this, newValue)

        fun CAS_MainNode(old: MainNode<K, V>, n: MainNode<K, V>) = updater.compareAndSet(this, old, n)

        fun GCAS_READ(ct: TrieMap<K, V>): MainNode<K, V> {
            val m = mainNode
            val prevValue = m.prev
            return if (prevValue == null) m else GCAS_Commit(m, ct)
        }

        tailrec fun GCAS_Commit(m: MainNode<K, V>, ct: TrieMap<K, V>): MainNode<K, V> {
            val ctr = ct.RDCSS_READ_ROOT(true)
            val prev = m.prev ?: return m

            when (prev) {
                is FailedNode<*, *> -> {
                    val fn = prev as FailedNode<K, V>
                    if (CAS_MainNode(m, fn.p))
                        return fn.p
                    else {
                        return GCAS_Commit(mainNode, ct)
                    }
                }
                is MainNode<*, *> -> if (ctr.gen == gen && !ct.readOnly) {
                    // try to commit
                    if (m.CAS_PREV(prev, null))
                        return m
                    else {
                        return GCAS_Commit(m, ct)
                    }
                } else {
                    m.CAS_PREV(prev, FailedNode(prev))
                    return GCAS_Commit(mainNode, ct)
                }
                else -> throw RuntimeException("Should not happen")
            }
        }

        fun GCAS(old: MainNode<K, V>, n: MainNode<K, V>, ct: TrieMap<K, V>): Boolean {
            n.WRITE_PREV(old)
            if (CAS_MainNode(old, n)) {
                GCAS_Commit(n, ct)
                return n.prev == null
            } else return false
        }

        fun iNode(cn: MainNode<K, V>): INode<K, V> {
            val nin = INode<K, V>(gen)
            nin.WRITE_MainNode(cn)
            return nin
        }

        fun copyToGen(newGen: Gen, ct: TrieMap<K, V>): INode<K, V> {
            val nin = INode<K, V>(newGen)
            val main = GCAS_READ(ct)
            nin.WRITE_MainNode(main)
            return nin
        }

        fun insertLNode(ln: LNode<K, V>, k: K, v: V, hc: Int, ct: TrieMap<K, V>): Boolean = GCAS(ln, ln.inserted(k, v, hc), ct)

        fun cachedSize(ct: TrieMap<K, V>): Int = GCAS_READ(ct).cachedSize(ct)

        companion object {
            private val updater = AtomicReferenceFieldUpdater.newUpdater(INode::class.java, MainNode::class.java, "mainNode")

            fun <K, V> newRootNode(): INode<K, V> {
                val gen = Gen()
                val cn = CNode<K, V>(0, arrayOf<Branch?>(), gen)
                return INode(cn, gen)
            }
        }
    }

    private class CNode<K, V>(val bitmap: Int, val array: Array<Branch?>, val gen: Gen) : MainNode<K, V>() {
        @Volatile var size = -1

        override fun cachedSize(ct: Any): Int {
            val currentSize = size
            if (currentSize != -1)
                return currentSize
            else {
                val newSize = computeSize(ct as TrieMap<K, V>)
                while (size == -1)
                    CAS_SIZE(-1, newSize)
                return size
            }
        }

        fun computeSize(ct: TrieMap<K, V>): Int {
            var i = 0
            var sz = 0

            val offset = 0
            while (i < array.size) {
                val pos = (i + offset) % array.size
                val elem = array[pos]
                if (elem is SNode<*, *>)
                    sz += 1
                else if (elem is INode<*, *>)
                    sz += (elem as INode<K, V>).cachedSize(ct)
                i += 1
            }
            return sz
        }

        fun insertedAt(pos: Int, flag: Int, nn: Branch, gen: Gen): CNode<K, V> {
            val len = array.size
            val bmp = bitmap
            val narr = arrayOfNulls<Branch>(len + 1)
            System.arraycopy(array, 0, narr, 0, pos)
            narr[pos] = nn
            System.arraycopy(array, pos, narr, pos + 1, len - pos)
            return CNode(bmp or flag, narr, gen)
        }

        fun updatedAt(pos: Int, nn: Branch, gen: Gen): CNode<K, V> {
            val len = array.size
            val narr = arrayOfNulls<Branch>(len)
            System.arraycopy(array, 0, narr, 0, len)
            narr[pos] = nn
            return CNode(bitmap, narr, gen)
        }

        fun removedAt(pos: Int, flag: Int, gen: Gen): CNode<K, V> {
            val arr = array
            val len = arr.size
            val narr = arrayOfNulls<Branch>(len - 1)
            System.arraycopy(arr, 0, narr, 0, pos)
            System.arraycopy(arr, pos + 1, narr, pos, len - pos - 1)
            return CNode(bitmap xor flag, narr, gen)
        }

        fun renewed(newGen: Gen, ct: TrieMap<K, V>): CNode<K, V> {
            var i = 0
            val arr = array
            val len = arr.size
            val newArray = arrayOfNulls<Branch>(len)
            while (i < len) {
                val elem = arr[i]
                if (elem is INode<*, *>) {
                    val `in` = elem as INode<K, V>
                    newArray[i] = `in`.copyToGen(newGen, ct)
                } else if (elem is Branch)
                    newArray[i] = elem
                i += 1
            }
            return CNode(bitmap, newArray, newGen)
        }

        fun resurrect(iNode: INode<K, V>, iNodeMain: Any): Branch {
            if (iNodeMain is TNode<*, *>) {
                val tn = iNodeMain as TNode<K, V>
                return tn.copyUntombed()
            } else return iNode
        }

        fun toContracted(lev: Int): MainNode<K, V> = if (array.size == 1 && lev > 0) {
            if (array[0] is SNode<*, *>) {
                val sn = array[0] as SNode<K, V>
                sn.copyTombed()
            } else this
        } else this

        fun toCompressed(ct: TrieMap<K, V>, lev: Int, gen: Gen): MainNode<K, V> {
            val bmp = bitmap
            val arr = array
            val tempArray = arrayOfNulls<Branch>(arr.size)
            for (i in arr.indices) { // construct new bitmap
                val sub = arr[i]
                when (sub) {
                    is INode<*, *> -> {
                        val `in` = sub as INode<K, V>
                        val iNodeMain = `in`.GCAS_READ(ct)
                        tempArray[i] = resurrect(`in`, iNodeMain)
                    }
                    is SNode<*, *> -> tempArray[i] = sub
                }
            }

            return CNode<K, V>(bmp, tempArray, gen).toContracted(lev)
        }

        fun CAS_SIZE(oldValue: Int, newValue: Int) = updater.compareAndSet(this, oldValue, newValue)

        companion object {
            private val updater = AtomicIntegerFieldUpdater.newUpdater(CNode::class.java, "size")

            internal fun <K, V> dual(x: SNode<K, V>, xhc: Int, y: SNode<K, V>, yhc: Int, lev: Int, gen: Gen): MainNode<K, V> {
                return if (lev < 35) {
                    val xIndex = xhc ushr lev and 0b11111
                    val yIndex = yhc ushr lev and 0b11111
                    val bmp = 1 shl xIndex or (1 shl yIndex)

                    if (xIndex == yIndex) {
                        val subINode = INode<K, V>(gen)
                        subINode.mainNode = dual(x, xhc, y, yhc, lev + 5, gen)
                        CNode(bmp, arrayOf<Branch?>(subINode), gen)
                    } else {
                        if (xIndex < yIndex)
                            CNode(bmp, arrayOf<Branch?>(x, y), gen)
                        else
                            CNode(bmp, arrayOf<Branch?>(y, x), gen)
                    }
                } else {
                    LNode(x.key, x.value, xhc, LNode(y.key, y.value, yhc))
                }
            }
        }
    }

    private class LNode<K, V>(override val key: K, override val value: V, val hash: Int, var next: LNode<K, V>? = null) : MainNode<K, V>(), Pair<K, V> {

        override fun cachedSize(ct: Any): Int = size()

        operator fun get(key: K): V? = if (this.key == key) value else next?.get(key)

        fun add(key: K, value: V, hash: Int): LNode<K, V> = LNode(key, value, hash, remove(key))

        fun remove(key: K): LNode<K, V>? = if (!contains(key)) this else {
            if (this.key == key) this.next else {
                val newNode = LNode(this.key, this.value, hash, null)
                var current: LNode<K, V>? = this.next
                var lastNode = newNode
                while (current != null) {
                    if (key != current.key) {
                        val temp = LNode(current.key, current.value, hash, null)
                        lastNode.next = temp
                        lastNode = temp
                    }
                    current = current.next
                }
                newNode
            }
        }

        fun size(): Int {
            var acc = 1
            var next = this.next
            while (next != null) {
                acc++
                next = next.next
            }
            return acc
        }

        fun inserted(k: K, v: V, hash: Int): LNode<K, V> = add(k, v, hash)

        fun removed(k: K): MainNode<K, V> {
            val updatedLNode = remove(k)
            if (updatedLNode != null) {
                if (updatedLNode.size() > 1)
                    return updatedLNode
                else {
                    // create it tombed so that it gets compressed on subsequent accesses
                    return TNode(updatedLNode.key, updatedLNode.value, updatedLNode.hash)
                }
            } else throw Exception("Should not happen")
        }

        operator fun contains(k: K): Boolean = if (k == this.key) true else next?.contains(k) ?: false

        operator fun iterator() = NodeIterator(this)

        class NodeIterator<K, V>(var n: LNode<K, V>?) : Iterator<Entry<K, V>> {
            override fun remove() = throw UnsupportedOperationException()

            override fun hasNext(): Boolean {
                return n != null
            }

            override fun next(): Entry<K, V> {
                val temp: LNode<K, V>? = n
                if (temp != null) {
                    this.n = temp.next
                    return temp
                } else throw NoSuchElementException()
            }
        }
    }

    private class SNode<K, V>(override val key: K, override val value: V, val hc: Int) : Branch(), Pair<K, V> {
        fun copyTombed(): TNode<K, V> = TNode(key, value, hc)
    }

    private class TNode<K, V>(override val key: K, override val value: V, val hc: Int) : MainNode<K, V>(), Pair<K, V> {
        fun copyUntombed(): SNode<K, V> = SNode(key, value, hc)

        override fun cachedSize(ct: Any): Int = 1
    }

    private class FailedNode<K, V>(val p: MainNode<K, V>) : MainNode<K, V>() {
        init {
            WRITE_PREV(p)
        }

        override fun cachedSize(ct: Any): Int = throw UnsupportedOperationException()
    }

    private class RDCSS_Descriptor<K, V>(var old: INode<K, V>, var expectedMain: MainNode<K, V>, var new: INode<K, V>) {
        @Volatile internal var committed = false
    }

    private fun CAS_ROOT(ov: Any, nv: Any): Boolean {
        if (readOnly) {
            throw IllegalStateException("Attempted to modify a read-only snapshot")
        }
        return ROOT_UPDATER.compareAndSet(this, ov, nv)
    }

    private fun RDCSS_READ_ROOT(abort: Boolean = false): INode<K, V> {
        val r = root
        when (r) {
            is INode<*, *> -> return r as INode<K, V>
            is RDCSS_Descriptor<*, *> -> return RDCSS_Complete(abort)
            else -> throw RuntimeException("Should not happen")
        }
    }

    private tailrec fun RDCSS_Complete(abort: Boolean): INode<K, V> {
        val v = root
        when (v) {
            is INode<*, *> -> return v as INode<K, V>
            is RDCSS_Descriptor<*, *> -> {
                val desc = v as RDCSS_Descriptor<K, V>

                val oldValue = desc.old
                val exp = desc.expectedMain
                val newValue = desc.new

                if (abort) {
                    return if (CAS_ROOT(desc, oldValue)) oldValue else RDCSS_Complete(abort)
                } else {
                    val oldMain = oldValue.GCAS_READ(this)
                    if (oldMain === exp) {
                        if (CAS_ROOT(desc, newValue)) {
                            desc.committed = true
                            return newValue
                        } else return RDCSS_Complete(abort)
                    } else {
                        return if (CAS_ROOT(desc, oldValue)) oldValue else RDCSS_Complete(abort)
                    }

                }
            }
            else -> throw RuntimeException("Should not happen")
        }
    }

    private fun RDCSS_ROOT(ov: INode<K, V>, expectedMain: MainNode<K, V>, nv: INode<K, V>): Boolean {
        val desc = RDCSS_Descriptor(ov, expectedMain, nv)
        if (CAS_ROOT(ov, desc)) {
            RDCSS_Complete(false)
            return desc.committed
        } else return false
    }

    private fun INode<K, V>.cleanReadOnly(tn: TNode<K, V>, lev: Int, parent: INode<K, V>, k: K, hc: Int): Any? {
        if (!this@TrieMap.readOnly) {
            clean(parent, lev - 5)
            return RESTART
        } else {
            return if (tn.hc == hc && tn.key === k) tn.value else null
        }
    }

    private fun INode<K, V>.clean(nd: INode<K, V>, lev: Int) {
        val m = nd.GCAS_READ(this@TrieMap)
        if (m is CNode<*, *>) {
            val cn = m as CNode<K, V>
            nd.GCAS(cn, cn.toCompressed(this@TrieMap, lev, gen), this@TrieMap)
        }
    }

    private tailrec fun INode<K, V>.cleanParent(nonLive: Any, parent: INode<K, V>, hc: Int, lev: Int, startgen: Gen) {
        val pm = parent.GCAS_READ(this@TrieMap)

        if (pm is CNode<*, *>) {
            val cn = pm as CNode<K, V>
            val idx = hc.ushr(lev - 5) and 0x1f
            val bmp = cn.bitmap
            val flag = 1 shl idx
            if (bmp and flag == 0) {
            } else {
                val pos = Integer.bitCount(bmp and flag - 1)
                val sub = cn.array[pos]
                if (sub === this) {
                    if (nonLive is TNode<*, *>) {
                        val tn = nonLive as TNode<K, V>
                        val ncn = cn.updatedAt(pos, tn.copyUntombed(), gen).toContracted(lev - 5)
                        if (!parent.GCAS(cn, ncn, this@TrieMap)) {
                            if (this@TrieMap.RDCSS_READ_ROOT(false).gen == startgen) {
                                cleanParent(nonLive, parent, hc, lev, startgen)
                            }
                        }
                    }
                }
            }
        }
    }

    private tailrec fun INode<K, V>.rec_lookup(k: K, hc: Int, lev: Int, parent: INode<K, V>?, startGen: Gen): Any? {
        val m = GCAS_READ(this@TrieMap)

        when (m) {
            is CNode<*, *> -> {
                val cn = m as CNode<K, V>
                val idx = hc ushr lev and 0b11111 // (hc >>> lev) & 0b11111
                val flag = 1 shl idx              // 1 << idx
                val bmp = cn.bitmap
                if (bmp and flag == 0)
                    return null
                else {
                    val pos = if (bmp == 0xffffffff.toInt()) idx else Integer.bitCount(bmp and flag - 1)
                    val sub = cn.array[pos]
                    if (sub is INode<*, *>) {
                        val `in` = sub as INode<K, V>
                        if (this@TrieMap.readOnly || startGen == (sub as INode<Any, Any>).gen)
                            return `in`.rec_lookup(k, hc, lev + 5, this, startGen)
                        else {
                            if (GCAS(cn, cn.renewed(startGen, this@TrieMap), this@TrieMap)) {
                                return rec_lookup(k, hc, lev, parent, startGen)
                            } else
                                return RESTART
                        }
                    } else if (sub is SNode<*, *>) {
                        val sn = sub as SNode<K, V>
                        if (sn.hc == hc && equal(sn.key, k))
                            return sn.value
                        else
                            return null
                    }
                }
            }
            is TNode<*, *> -> {
                return cleanReadOnly(m as TNode<K, V>, lev, parent!!, k, hc)
            }
            is LNode<*, *> -> {
                return (m as LNode<K, V>)[k]
            }
        }
        throw RuntimeException("Should not happen")
    }

    private tailrec fun INode<K, V>.rec_insert(k: K, v: V, hc: Int, cond: Any?, lev: Int, parent: INode<K, V>?, startgen: Gen): Any? {
        val m = GCAS_READ(this@TrieMap)

        when (m) {
            is CNode<*, *> -> {
                val cn = m as CNode<K, V>
                val idx = hc ushr lev and 0b11111 // (hc >>> lev) & 0b11111
                val flag = 1 shl idx              // 1 << idx
                val bmp = cn.bitmap
                val mask = flag - 1
                val pos = Integer.bitCount(bmp and mask)
                if (bmp and flag != 0) {
                    val cnAtPos = cn.array[pos]
                    when (cnAtPos) {
                        is INode<*, *> -> {
                            val `in` = cnAtPos as INode<K, V>
                            if (startgen == `in`.gen)
                                return `in`.rec_insert(k, v, hc, cond, lev + 5, this, startgen)
                            else {
                                if (GCAS(cn, cn.renewed(startgen, this@TrieMap), this@TrieMap))
                                    return rec_insert(k, v, hc, cond, lev, parent, startgen)
                                else
                                    return RESTART
                            }
                        }
                        is SNode<*, *> -> {
                            val sn = cnAtPos as SNode<K, V>
                            when (cond) {
                                null -> {
                                    if (sn.hc == hc && equal(sn.key, k)) {
                                        if (GCAS(cn, cn.updatedAt(pos, SNode(k, v, hc), gen), this@TrieMap))
                                            return sn.value
                                        else
                                            return RESTART
                                    } else {
                                        val rn = if (cn.gen == gen) cn else cn.renewed(gen, this@TrieMap)
                                        val nn = rn.updatedAt(pos, iNode(CNode.dual(sn, sn.hc, SNode(k, v, hc), hc, lev + 5, gen)), gen)
                                        if (GCAS(cn, nn, this@TrieMap))
                                            return null // None;
                                        else
                                            return RESTART
                                    }
                                }
                                KEY_ABSENT -> {
                                    if (sn.hc == hc && equal(sn.key, k))
                                        return sn.value
                                    else {
                                        val rn = if (cn.gen == gen) cn else cn.renewed(gen, this@TrieMap)
                                        val nn = rn.updatedAt(pos, iNode(CNode.dual(sn, sn.hc, SNode(k, v, hc), hc, lev + 5, gen)), gen)
                                        if (GCAS(cn, nn, this@TrieMap))
                                            return null // None
                                        else
                                            return RESTART
                                    }
                                }
                                KEY_PRESENT -> {
                                    if (sn.hc == hc && equal(sn.key, k)) {
                                        if (GCAS(cn, cn.updatedAt(pos, SNode(k, v, hc), gen), this@TrieMap))
                                            return sn.value
                                        else
                                            return RESTART
                                    } else
                                        return null
                                }
                                else -> {
                                    if (sn.hc == hc && equal(sn.key, k) && sn.value === cond) {
                                        if (GCAS(cn, cn.updatedAt(pos, SNode(k, v, hc), gen), this@TrieMap))
                                            return sn.value
                                        else
                                            return RESTART
                                    } else
                                        return null
                                }
                            }
                        }
                    }
                } else if (cond == null || cond === KEY_ABSENT) {
                    val rn = if (cn.gen == gen) cn else cn.renewed(gen, this@TrieMap)
                    val ncnode = rn.insertedAt(pos, flag, SNode(k, v, hc), gen)
                    if (GCAS(cn, ncnode, this@TrieMap))
                        return null
                    else
                        return RESTART
                } else
                    return null
            }
            is TNode<*, *> -> {
                clean(parent!!, lev - 5)
                return RESTART
            }
            is LNode<*, *> -> {
                // 3) an l-node
                val ln = m as LNode<K, V>
                when (cond) {
                    null -> {
                        val value = ln[k]
                        if (insertLNode(ln, k, v, hc, this@TrieMap))
                            return value
                        else
                            return RESTART
                    }
                    KEY_ABSENT -> {
                        val t = ln[k]
                        if (t == null) {
                            if (insertLNode(ln, k, v, hc, this@TrieMap))
                                return null
                            else
                                return RESTART
                        } else
                            return t
                    }
                    KEY_PRESENT -> {
                        val t = ln[k]
                        if (t != null) {
                            if (insertLNode(ln, k, v, hc, this@TrieMap))
                                return t
                            else
                                return RESTART
                        } else
                            return null
                    }
                    else -> {
                        val t = ln[k]
                        if (t != null) {
                            if (t === cond) {
                                if (insertLNode(ln, k, v, hc, this@TrieMap))
                                    return cond
                                else
                                    return RESTART
                            } else
                                return null
                        }
                    }
                }
            }
        }
        throw RuntimeException("Should not happen")
    }

    private fun INode<K, V>.rec_delete(k: K, v: V?, hc: Int, lev: Int, parent: INode<K, V>?, startgen: Gen): Any? {
        val m = GCAS_READ(this@TrieMap)

        when (m) {
            is CNode<*, *> -> {
                val cn = m as CNode<K, V>
                val idx = hc.ushr(lev) and 0x1f
                val bmp = cn.bitmap
                val flag = 1 shl idx
                if (bmp and flag == 0)
                    return null
                else {
                    val pos = Integer.bitCount(bmp and flag - 1)
                    val sub = cn.array[pos]
                    val res: Any? = when (sub) {
                        is INode<*, *> -> {
                            val `in` = sub as INode<K, V>
                            if (startgen == `in`.gen)
                                `in`.rec_delete(k, v, hc, lev + 5, this, startgen)
                            else {
                                if (GCAS(cn, cn.renewed(startgen, this@TrieMap), this@TrieMap))
                                    rec_delete(k, v, hc, lev, parent, startgen)
                                else
                                    RESTART
                            }

                        }
                        is SNode<*, *> -> {
                            val sn = sub as SNode<K, V>
                            if (sn.hc == hc && equal(sn.key, k) && (v == null || v == sn.value)) {
                                val ncn = cn.removedAt(pos, flag, gen).toContracted(lev)
                                if (GCAS(cn, ncn, this@TrieMap))
                                    sn.value
                                else
                                    RESTART
                            } else null
                        }
                        else -> throw RuntimeException("Should not happen")
                    }

                    if (res == null || res === RESTART)
                        return res
                    else {
                        if (parent != null) { // never tomb at root
                            val n = GCAS_READ(this@TrieMap)
                            if (n is TNode<*, *>) cleanParent(n, parent, hc, lev, startgen)
                        }
                        return res
                    }
                }
            }
            is TNode<*, *> -> {
                clean(parent!!, lev - 5)
                return RESTART
            }
            is LNode<*, *> -> {
                val ln = m as LNode<K, V>
                val value = ln[k]
                if (v == null) {
                    val nn = ln.removed(k)
                    return if (GCAS(ln, nn, this@TrieMap)) value else RESTART
                } else {
                    if (v === value) {
                        val nn = ln.removed(k)
                        return if (GCAS(ln, nn, this@TrieMap)) value else RESTART
                    }
                }
            }
        }
        throw RuntimeException("Should not happen")
    }

    internal tailrec fun lookup(key: K, hash: Int): V? {
        val root = RDCSS_READ_ROOT()
        val res = root.rec_lookup(key, hash, 0, null, root.gen)
        return if (res !== RESTART) res as V? else lookup(key, hash)
    }

    internal tailrec fun insert(key: K, value: V, hash: Int, cond: Any?): V? {
        val root = RDCSS_READ_ROOT()
        val ret = root.rec_insert(key, value, hash, cond, 0, null, root.gen)
        return if (ret !== RESTART) ret as V? else insert(key, value, hash, cond)
    }

    internal tailrec fun delete(key: K, value: V?, hash: Int): V? {
        val root = RDCSS_READ_ROOT()
        val res = root.rec_delete(key, value, hash, 0, null, root.gen)
        return if (res !== RESTART) res as V? else delete(key, value, hash)
    }

    private fun ensureReadWrite() {
        if (readOnly) {
            throw UnsupportedOperationException("Attempted to modify a read-only view")
        }
    }

    override tailrec fun clear() {
        val root = RDCSS_READ_ROOT()
        return if (RDCSS_ROOT(root, root.GCAS_READ(this), INode.newRootNode<K, V>())) Unit else clear()
    }

    override operator fun get(key: K): V? = lookup(key, hash(key))

    override fun put(key: K, value: V): V? {
        ensureReadWrite()
        return insert(key, value, hash(key), null)
    }

    override fun putIfAbsent(key: K, value: V): V? {
        ensureReadWrite()
        return insert(key, value, hash(key), KEY_ABSENT)
    }

    override fun replace(k: K, v: V): V? {
        ensureReadWrite()
        return insert(k, v, hash(k), KEY_PRESENT)
    }

    override fun replace(key: K, oldValue: V, newValue: V): Boolean {
        ensureReadWrite()
        return insert(key, newValue, hash(key), oldValue) != null
    }

    override fun remove(key: K): V? {
        ensureReadWrite()
        return delete(key, null, hash(key))
    }

    override fun remove(key: K, value: V): Boolean {
        ensureReadWrite()
        return delete(key, value, hash(key)) != null
    }

    override fun containsKey(key: K): Boolean = get(key) != null

    override val size: Int get() = if (!readOnly) readOnlySnapshot().size else RDCSS_READ_ROOT().cachedSize(this)

    override val entries: MutableSet<Entry<K, V>> = EntrySet()

    fun isReadOnly() = readOnly

    tailrec fun snapshot(): TrieMap<K, V> {
        val root = RDCSS_READ_ROOT()
        val expectedMain = root.GCAS_READ(this)
        return if (RDCSS_ROOT(root, expectedMain, root.copyToGen(Gen(), this)))
            TrieMap(root.copyToGen(Gen(), this), readOnly = readOnly) else snapshot()
    }

    tailrec fun readOnlySnapshot(): TrieMap<K, V> {
        if (readOnly) return this
        val root = RDCSS_READ_ROOT()
        val expectedMain = root.GCAS_READ(this)
        return if (RDCSS_ROOT(root, expectedMain, root.copyToGen(Gen(), this)))
            TrieMap(root, readOnly = true) else readOnlySnapshot()
    }

    operator fun iterator(): Iterator<Entry<K, V>> = if (readOnly) readOnlySnapshot().readOnlyIterator() else TrieMapIterator(this)

    fun readOnlyIterator(): Iterator<Entry<K, V>> = if (!readOnly) readOnlySnapshot().readOnlyIterator() else TrieMapReadOnlyIterator(this)

    private open class TrieMapIterator<K, V>(val ct: TrieMap<K, V>) : Iterator<Entry<K, V>> {
        private val stack = arrayOfNulls<Array<Branch?>>(7)
        private val stackPos = IntArray(7)
        private var depth = -1
        private var subIter: Iterator<Entry<K, V>>? = null
        private var current: Entry<K, V>? = null
        private var lastReturned: Entry<K, V>? = null

        init {
            readINode(ct.RDCSS_READ_ROOT())
        }

        override fun hasNext(): Boolean = current != null || subIter != null

        override fun next(): Entry<K, V> {
            if (hasNext()) {
                val r: Entry<K, V>?
                if (subIter != null) {
                    r = subIter!!.next()
                    checkSubIter()
                } else {
                    r = current!!
                    advance()
                }

                lastReturned = r
                if (r is Entry<*, *>) {
                    return nextEntry(r)
                }
                return r
            } else {
                throw NoSuchElementException()
            }
        }

        open protected fun nextEntry(r: Entry<K, V>): Entry<K, V> = object : Entry<K, V> {
            override val key: K get() = r.key

            override val value: V get() = updated ?: r.value

            private var updated: V? = null

            override fun setValue(newValue: V): V {
                val value = this.value
                updated = newValue
                ct.put(key, newValue)
                return value
            }
        }

        private fun readINode(`in`: INode<K, V>) {
            val m = `in`.GCAS_READ(ct)
            when (m) {
                is CNode<*, *> -> {
                    val cn = m as CNode<K, V>
                    depth += 1
                    stack[depth] = cn.array
                    stackPos[depth] = -1
                    advance()
                }
                is TNode<*, *> -> {
                    current = m as TNode<K, V>
                }
                is LNode<*, *> -> {
                    subIter = (m as LNode<K, V>).iterator()
                    checkSubIter()
                }
                else -> current = null
            }
        }

        private fun checkSubIter() {
            if (!subIter!!.hasNext()) {
                subIter = null
                advance()
            }
        }

        private fun advance() {
            if (depth >= 0) {
                val nPos = stackPos[depth] + 1
                if (nPos < stack[depth]!!.size) {
                    stackPos[depth] = nPos
                    val elem = stack[depth]!![nPos]
                    when (elem) {
                        is SNode<*, *> -> current = elem as SNode<K, V>
                        is INode<*, *> -> readINode(elem as INode<K, V>)
                    }
                } else {
                    depth -= 1
                    advance()
                }
            } else {
                current = null
            }
        }

        override fun remove() {
            val lastReturned = lastReturned
            if (lastReturned != null) {
                ct.remove(lastReturned.key)
                this.lastReturned = null
            } else throw IllegalStateException()
        }
    }

    private class TrieMapReadOnlyIterator<K, V>(ct: TrieMap<K, V>) : TrieMapIterator<K, V>(ct) {
        init {
            assert(ct.readOnly)
        }

        override fun remove(): Unit = throw UnsupportedOperationException()

        override fun nextEntry(r: Entry<K, V>): Entry<K, V> = r
    }

    private inner class EntrySet : AbstractSet<Entry<K, V>>() {
        override val size: Int get() = count()

        override fun iterator(): Iterator<Entry<K, V>> = this@TrieMap.iterator()

        override fun contains(element: Entry<K, V>) = if (element !is Entry<*, *>) false else get(element.key) != null

        override fun remove(element: Entry<K, V>) = if (element !is Entry<*, *>) false else this@TrieMap.remove(element.key) != null

        override fun clear() = this@TrieMap.clear()
    }

}

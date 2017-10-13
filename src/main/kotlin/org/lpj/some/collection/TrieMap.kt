@file:Suppress("ClassName", "FunctionName", "unused", "UNCHECKED_CAST", "USELESS_CAST", "LiftReturnOrAssignment", "RemoveExplicitTypeArguments")

package org.lpj.some.collection

import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater

private typealias Gen = Any
private typealias Iterator<T> = MutableIterator<T>
private typealias Entry<K, V> = MutableMap.MutableEntry<K, V>

class TrieMap<K : Any, V : Any> private constructor(@Volatile private var root: Root<K, V>, private val readOnly: Boolean)
    : AbstractMutableMap<K, V>(), ConcurrentMap<K, V> {

    constructor() : this(INode.newRootNode<K, V>(), readOnly = false)

    companion object {
        private val Any.hash: Int get() = (Integer.reverseBytes((hashCode() * 0x9e3775cd).toInt()) * 0x9e3775cd).toInt()

        private val RESTART = Any()
        private val KEY_ABSENT = Any()
        private val KEY_PRESENT = Any()

        private val ROOT_UPDATER = newUpdater(TrieMap::class.java, Root::class.java, "root")
    }

    private interface Root<K : Any, V : Any>

    private interface Leaf<K : Any, V : Any> : Entry<K, V> {
        override fun setValue(newValue: V): Nothing = throw UnsupportedOperationException()
    }

    private abstract class Branch<K : Any, V : Any>

    private class INode<K : Any, V : Any>(val gen: Gen) : Branch<K, V>(), Root<K, V> {
        @Volatile lateinit var mainNode: MainNode<K, V>

        constructor(mainNode: MainNode<K, V>, gen: Gen) : this(gen) {
            WRITE_MainNode(mainNode)
        }

        fun WRITE_MainNode(newValue: MainNode<K, V>) = updater.set(this, newValue)

        fun CAS_MainNode(old: MainNode<K, V>, n: MainNode<K, V>) = updater.compareAndSet(this, old, n)

        fun GCAS_READ(ct: TrieMap<K, V>): MainNode<K, V> {
            val m = mainNode
            val prev = m.prev
            return if (prev == null) m else GCAS_Commit(m, ct)
        }

        tailrec fun GCAS_Commit(m: MainNode<K, V>, ct: TrieMap<K, V>): MainNode<K, V> {
            val ctr = ct.RDCSS_READ_ROOT(true)
            val prev = m.prev ?: return m

            return when (prev) {
                is FailedNode -> {
                    if (CAS_MainNode(m, prev.p)) prev.p else GCAS_Commit(mainNode, ct)
                }
                else -> {
                    if (ctr.gen == gen && !ct.readOnly) {
                        if (m.CAS_PREV(prev, null)) m else GCAS_Commit(m, ct)
                    } else {
                        m.CAS_PREV(prev, FailedNode(prev))
                        GCAS_Commit(mainNode, ct)
                    }
                }
            }
        }

        fun GCAS(old: MainNode<K, V>, n: MainNode<K, V>, ct: TrieMap<K, V>): Boolean {
            n.WRITE_PREV(old)
            return if (CAS_MainNode(old, n)) {
                GCAS_Commit(n, ct)
                n.prev == null
            } else false
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
            private val updater = newUpdater(INode::class.java, MainNode::class.java, "mainNode")

            fun <K : Any, V : Any> newRootNode(): INode<K, V> {
                val gen = Gen()
                return INode(CNode(0, emptyArray(), gen), gen)
            }
        }
    }

    private class SNode<K : Any, V : Any>(override val key: K, override val value: V, val hc: Int) : Branch<K, V>(), Leaf<K, V> {
        fun copyTombed(): TNode<K, V> = TNode(key, value, hc)
    }

    private abstract class MainNode<K : Any, V : Any> {
        @Volatile
        var prev: MainNode<K, V>? = null

        abstract fun cachedSize(ct: TrieMap<K, V>): Int

        fun CAS_PREV(oldValue: MainNode<K, V>, newValue: MainNode<K, V>?) = updater.compareAndSet(this, oldValue, newValue)

        fun WRITE_PREV(newValue: MainNode<K, V>) = updater.set(this, newValue)

        companion object {
            private val updater = newUpdater(MainNode::class.java, MainNode::class.java, "prev")
        }
    }

    private class CNode<K : Any, V : Any>(val bitmap: Int, val array: Array<Branch<K, V>?>, val gen: Gen) : MainNode<K, V>() {
        @Volatile
        var size = -1

        override fun cachedSize(ct: TrieMap<K, V>): Int {
            val currentSize = size
            return if (currentSize != -1) currentSize else {
                val newSize = computeSize(ct)
                while (size == -1)
                    CAS_SIZE(-1, newSize)
                size
            }
        }

        fun computeSize(ct: TrieMap<K, V>): Int {
            var i = 0
            var sz = 0

            while (i < array.size) {
                val pos = i % array.size
                val elem = array[pos]
                if (elem is SNode)
                    sz += 1
                else if (elem is INode)
                    sz += elem.cachedSize(ct)
                i += 1
            }
            return sz
        }

        fun insertedAt(pos: Int, flag: Int, nn: Branch<K, V>, gen: Gen): CNode<K, V> {
            val len = array.size
            val bmp = bitmap
            val narr = arrayOfNulls<Branch<K, V>>(len + 1)
            System.arraycopy(array, 0, narr, 0, pos)
            narr[pos] = nn
            System.arraycopy(array, pos, narr, pos + 1, len - pos)
            return CNode(bmp or flag, narr, gen)
        }

        fun updatedAt(pos: Int, nn: Branch<K, V>, gen: Gen): CNode<K, V> {
            val len = array.size
            val narr = arrayOfNulls<Branch<K, V>>(len)
            System.arraycopy(array, 0, narr, 0, len)
            narr[pos] = nn
            return CNode(bitmap, narr, gen)
        }

        fun removedAt(pos: Int, flag: Int, gen: Gen): CNode<K, V> {
            val arr = array
            val len = arr.size
            val narr = arrayOfNulls<Branch<K, V>>(len - 1)
            System.arraycopy(arr, 0, narr, 0, pos)
            System.arraycopy(arr, pos + 1, narr, pos, len - pos - 1)
            return CNode(bitmap xor flag, narr, gen)
        }

        fun renewed(newGen: Gen, ct: TrieMap<K, V>): CNode<K, V> {
            var i = 0
            val arr = array
            val len = arr.size
            val newArray = arrayOfNulls<Branch<K, V>>(len)
            while (i < len) {
                val elem = arr[i]
                if (elem is INode)
                    newArray[i] = elem.copyToGen(newGen, ct)
                else if (elem is Branch<K, V>)
                    newArray[i] = elem
                i += 1
            }
            return CNode(bitmap, newArray, newGen)
        }

        fun resurrect(iNode: INode<K, V>, iNodeMain: MainNode<K, V>) =
                @Suppress("IfThenToElvis") if (iNodeMain is TNode) iNodeMain.copyUntombed() else iNode

        fun toContracted(lev: Int): MainNode<K, V> = if (array.size == 1 && lev > 0) {
            val branch = array[0]
            @Suppress("IfThenToElvis") if (branch is SNode) branch.copyTombed() else this
        } else this

        fun toCompressed(ct: TrieMap<K, V>, lev: Int, gen: Gen): MainNode<K, V> {
            val bmp = bitmap
            val arr = array
            val tempArray = arrayOfNulls<Branch<K, V>>(arr.size)
            for (i in arr.indices) { // construct new bitmap
                val sub = arr[i]
                when (sub) {
                    is INode -> {
                        val iNodeMain = sub.GCAS_READ(ct)
                        tempArray[i] = resurrect(sub, iNodeMain)
                    }
                    is SNode -> tempArray[i] = sub
                }
            }

            return CNode(bmp, tempArray, gen).toContracted(lev)
        }

        fun CAS_SIZE(oldValue: Int, newValue: Int) = updater.compareAndSet(this, oldValue, newValue)

        companion object {
            private val updater = AtomicIntegerFieldUpdater.newUpdater(CNode::class.java, "size")

            fun <K : Any, V : Any> dual(x: SNode<K, V>, xhc: Int, y: SNode<K, V>, yhc: Int, lev: Int, gen: Gen): MainNode<K, V> {
                return if (lev < 35) {
                    val xIndex = xhc ushr lev and 0b11111
                    val yIndex = yhc ushr lev and 0b11111
                    val bmp = 1 shl xIndex or (1 shl yIndex)

                    if (xIndex == yIndex) {
                        val subINode = INode<K, V>(gen)
                        subINode.mainNode = dual(x, xhc, y, yhc, lev + 5, gen)
                        CNode<K, V>(bmp, arrayOf(subINode), gen)
                    } else {
                        if (xIndex < yIndex)
                            CNode<K, V>(bmp, arrayOf(x, y), gen)
                        else
                            CNode<K, V>(bmp, arrayOf(y, x), gen)
                    }
                } else {
                    LNode(x.key, x.value, xhc, LNode(y.key, y.value, yhc, null))
                }
            }
        }
    }

    private class LNode<K : Any, V : Any>(override val key: K, override val value: V, val hash: Int, var next: LNode<K, V>?) : MainNode<K, V>(), Leaf<K, V> {

        override fun cachedSize(ct: TrieMap<K, V>): Int = size()

        operator fun get(key: K): V? = if (this.key == key) value else next?.get(key)

        fun size(): Int {
            var acc = 1
            var next = this.next
            while (next != null) {
                acc++
                next = next.next
            }
            return acc
        }

        fun inserted(k: K, v: V, hash: Int): LNode<K, V> = LNode(k, v, hash, remove(k))

        private fun remove(key: K): LNode<K, V>? = if (!contains(key)) this else {
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

        fun removed(k: K): MainNode<K, V> {
            val lNode = remove(k)!!
            return if (lNode.size() > 1) lNode
            else TNode(lNode.key, lNode.value, lNode.hash)
        }

        fun contains(k: K): Boolean = if (k == this.key) true else next?.contains(k) ?: false

        fun iterator() = NodeIterator(this)

        class NodeIterator<K : Any, V : Any>(var n: LNode<K, V>?) : Iterator<Entry<K, V>> {
            override fun remove() = throw UnsupportedOperationException()

            override fun hasNext() = n != null

            override fun next(): Entry<K, V> {
                val temp: LNode<K, V>? = n
                if (temp != null) {
                    this.n = temp.next
                    return temp
                } else throw NoSuchElementException()
            }
        }
    }

    private class TNode<K : Any, V : Any>(override val key: K, override val value: V, val hc: Int) : MainNode<K, V>(), Leaf<K, V> {
        fun copyUntombed(): SNode<K, V> = SNode(key, value, hc)

        override fun cachedSize(ct: TrieMap<K, V>): Int = 1
    }

    private class FailedNode<K : Any, V : Any>(val p: MainNode<K, V>) : MainNode<K, V>() {
        init {
            WRITE_PREV(p)
        }

        override fun cachedSize(ct: TrieMap<K, V>) = throw UnsupportedOperationException()
    }

    private class RDCSS_Descriptor<K : Any, V : Any>(var old: INode<K, V>, var expectedMain: MainNode<K, V>, var new: INode<K, V>) : Root<K, V> {
        @Volatile var committed = false
    }

    private fun CAS_ROOT(ov: Root<K, V>, nv: Root<K, V>): Boolean {
        if (readOnly) {
            throw IllegalStateException("Attempted to modify a read-only snapshot")
        }
        return ROOT_UPDATER.compareAndSet(this, ov, nv)
    }

    private fun RDCSS_READ_ROOT(abort: Boolean = false): INode<K, V> {
        val r = root
        return when (r) {
            is INode -> r
            is RDCSS_Descriptor -> RDCSS_Complete(abort)
            else -> throw NoWhenBranchMatchedException() // Should not happen
        }
    }

    private tailrec fun RDCSS_Complete(abort: Boolean): INode<K, V> {
        val r = root
        when (r) {
            is INode -> return r
            is RDCSS_Descriptor -> {

                val oldValue = r.old
                val exp = r.expectedMain
                val newValue = r.new

                return if (abort)
                    if (CAS_ROOT(r, oldValue)) oldValue else RDCSS_Complete(abort)
                else {
                    val oldMain = oldValue.GCAS_READ(this)
                    if (oldMain === exp)
                        if (CAS_ROOT(r, newValue)) newValue.also { r.committed = true } else RDCSS_Complete(abort)
                    else
                        if (CAS_ROOT(r, oldValue)) oldValue else RDCSS_Complete(abort)
                }
            }
            else -> throw NoWhenBranchMatchedException() // Should not happen
        }
    }

    private fun RDCSS_ROOT(ov: INode<K, V>, expectedMain: MainNode<K, V>, nv: INode<K, V>): Boolean {
        val desc = RDCSS_Descriptor(ov, expectedMain, nv)
        return if (CAS_ROOT(ov, desc)) {
            RDCSS_Complete(false)
            desc.committed
        } else false
    }

    private fun INode<K, V>.cleanReadOnly(tn: TNode<K, V>, lev: Int, parent: INode<K, V>, k: K, hc: Int): Any? {
        return if (!readOnly) {
            clean(parent, lev - 5)
            RESTART
        } else
            if (tn.hc == hc && tn.key === k) tn.value else null
    }

    private fun INode<K, V>.clean(nd: INode<K, V>, lev: Int) {
        val m = nd.GCAS_READ(this@TrieMap)
        if (m is CNode) nd.GCAS(m, m.toCompressed(this@TrieMap, lev, gen), this@TrieMap)
    }

    private tailrec fun INode<K, V>.cleanParent(nonLive: MainNode<K, V>, parent: INode<K, V>, hc: Int, lev: Int, startgen: Gen) {
        val pm = parent.GCAS_READ(this@TrieMap)

        if (pm is CNode) {
            val cn = pm as CNode
            val idx = hc.ushr(lev - 5) and 0x1f
            val bmp = cn.bitmap
            val flag = 1 shl idx
            if (bmp and flag != 0) {
                val pos = Integer.bitCount(bmp and flag - 1)
                val sub = cn.array[pos]
                if (sub === this && nonLive is TNode) {
                    val ncn = cn.updatedAt(pos, nonLive.copyUntombed(), gen).toContracted(lev - 5)
                    if (!parent.GCAS(cn, ncn, this@TrieMap) && this@TrieMap.RDCSS_READ_ROOT(false).gen == startgen)
                        cleanParent(nonLive, parent, hc, lev, startgen)
                }
            }
        }
    }

    private tailrec fun INode<K, V>.recLookup(k: K, hc: Int, lev: Int, parent: INode<K, V>?, startGen: Gen): Any? {
        val m = GCAS_READ(this@TrieMap)

        when (m) {
            is CNode -> {
                val cn = m as CNode
                val idx = hc ushr lev and 0b11111 // (hc >>> lev) & 0b11111
                val flag = 1 shl idx              // 1 << idx
                val bmp = cn.bitmap
                if (bmp and flag == 0)
                    return null
                else {
                    val pos = if (bmp == -1) idx else Integer.bitCount(bmp and flag - 1)
                    val sub = cn.array[pos]
                    when (sub) {
                        is INode -> {
                            val iNode = sub as INode
                            return if (this@TrieMap.readOnly || startGen == iNode.gen)
                                iNode.recLookup(k, hc, lev + 5, this, startGen)
                            else {
                                if (GCAS(cn, cn.renewed(startGen, this@TrieMap), this@TrieMap))
                                    recLookup(k, hc, lev, parent, startGen)
                                else
                                    RESTART
                            }
                        }
                        is SNode -> {
                            val sNode = sub as SNode
                            return if (sNode.hc == hc && sNode.key == k)
                                sNode.value
                            else
                                null
                        }
                        else -> throw NoWhenBranchMatchedException() // Should not happen
                    }
                }
            }
            is TNode -> {
                return cleanReadOnly(m, lev, parent!!, k, hc)
            }
            is LNode -> {
                return m[k]
            }
            else -> throw NoWhenBranchMatchedException() // Should not happen
        }
    }

    private tailrec fun INode<K, V>.recInsert(k: K, v: V, hc: Int, cond: Any?, lev: Int, parent: INode<K, V>?, startgen: Gen): Any? {
        val m = GCAS_READ(this@TrieMap)

        when (m) {
            is CNode -> {
                val cn = m as CNode
                val idx = hc ushr lev and 0b11111 // (hc >>> lev) & 0b11111
                val flag = 1 shl idx              // 1 << idx
                val bmp = cn.bitmap
                val mask = flag - 1
                val pos = Integer.bitCount(bmp and mask)
                if (bmp and flag != 0) {
                    val cnAtPos = cn.array[pos]
                    when (cnAtPos) {
                        is INode -> {
                            val `in` = cnAtPos as INode
                            return if (startgen == `in`.gen)
                                `in`.recInsert(k, v, hc, cond, lev + 5, this, startgen)
                            else {
                                if (GCAS(cn, cn.renewed(startgen, this@TrieMap), this@TrieMap))
                                    recInsert(k, v, hc, cond, lev, parent, startgen)
                                else
                                    RESTART
                            }
                        }
                        is SNode -> {
                            val sn = cnAtPos as SNode
                            when {
                                cond == null -> {
                                    if (sn.hc == hc && sn.key == k) {
                                        return if (GCAS(cn, cn.updatedAt(pos, SNode(k, v, hc), gen), this@TrieMap)) sn.value else RESTART
                                    } else {
                                        val rn = if (cn.gen == gen) cn else cn.renewed(gen, this@TrieMap)
                                        val nn = rn.updatedAt(pos, iNode(CNode.dual(sn, sn.hc, SNode(k, v, hc), hc, lev + 5, gen)), gen)
                                        return if (GCAS(cn, nn, this@TrieMap)) null else RESTART
                                    }
                                }
                                cond === KEY_ABSENT -> {
                                    if (sn.hc == hc && sn.key == k) {
                                        return sn.value
                                    } else {
                                        val rn = if (cn.gen == gen) cn else cn.renewed(gen, this@TrieMap)
                                        val nn = rn.updatedAt(pos, iNode(CNode.dual(sn, sn.hc, SNode(k, v, hc), hc, lev + 5, gen)), gen)
                                        return if (GCAS(cn, nn, this@TrieMap)) null else RESTART
                                    }
                                }
                                cond === KEY_PRESENT -> {
                                    if (sn.hc == hc && sn.key == k) {
                                        return if (GCAS(cn, cn.updatedAt(pos, SNode(k, v, hc), gen), this@TrieMap)) sn.value else RESTART
                                    } else {
                                        return null
                                    }
                                }
                                else -> {
                                    if (sn.hc == hc && sn.key == k && sn.value === cond) {
                                        return if (GCAS(cn, cn.updatedAt(pos, SNode(k, v, hc), gen), this@TrieMap)) sn.value else RESTART
                                    } else {
                                        return null
                                    }
                                }
                            }
                        }
                        else -> throw NoWhenBranchMatchedException() // Should not happen
                    }
                } else if (cond === null || cond === KEY_ABSENT) {
                    val rn = if (cn.gen == gen) cn else cn.renewed(gen, this@TrieMap)
                    val ncnode = rn.insertedAt(pos, flag, SNode(k, v, hc), gen)
                    return if (GCAS(cn, ncnode, this@TrieMap)) null else RESTART
                } else {
                    return null
                }
            }
            is TNode -> {
                clean(parent!!, lev - 5)
                return RESTART
            }
            is LNode -> {
                val ln = m as LNode
                when {
                    cond == null -> {
                        val value = ln[k]
                        return if (insertLNode(ln, k, v, hc, this@TrieMap)) value else RESTART
                    }
                    cond === KEY_ABSENT -> {
                        val t = ln[k]
                        if (t == null) {
                            return if (insertLNode(ln, k, v, hc, this@TrieMap)) null else RESTART
                        } else {
                            return t
                        }
                    }
                    cond === KEY_PRESENT -> {
                        val t = ln[k]
                        if (t != null) {
                            return if (insertLNode(ln, k, v, hc, this@TrieMap)) t else RESTART
                        } else {
                            return null
                        }
                    }
                    else -> {
                        val t = ln[k] ?: return null // return null should not happen
                        if (t === cond) {
                            return if (insertLNode(ln, k, v, hc, this@TrieMap)) cond else RESTART
                        } else {
                            return null
                        }
                    }
                }
            }
            else -> throw NoWhenBranchMatchedException() // Should not happen
        }
    }

    private fun INode<K, V>.recDelete(k: K, v: V?, hc: Int, lev: Int, parent: INode<K, V>?, startgen: Gen): Any? {
        val m = GCAS_READ(this@TrieMap)

        when (m) {
            is CNode -> {
                val cn = m as CNode
                val idx = hc.ushr(lev) and 0x1f
                val bmp = cn.bitmap
                val flag = 1 shl idx
                if (bmp and flag == 0)
                    return null
                else {
                    val pos = Integer.bitCount(bmp and flag - 1)
                    val sub = cn.array[pos]
                    val res: Any? = when (sub) {
                        is INode -> {
                            val iNode = sub as INode
                            if (startgen == iNode.gen)
                                iNode.recDelete(k, v, hc, lev + 5, this, startgen)
                            else {
                                if (GCAS(cn, cn.renewed(startgen, this@TrieMap), this@TrieMap))
                                    recDelete(k, v, hc, lev, parent, startgen)
                                else
                                    RESTART
                            }

                        }
                        is SNode -> {
                            val sNode = sub as SNode
                            if (sNode.hc == hc && sNode.key == k && (v == null || v == sNode.value)) {
                                val ncn = cn.removedAt(pos, flag, gen).toContracted(lev)
                                if (GCAS(cn, ncn, this@TrieMap))
                                    sNode.value
                                else
                                    RESTART
                            } else null
                        }
                        else -> throw NoWhenBranchMatchedException() // Should not happen
                    }

                    if (res === null || res === RESTART)
                        return res
                    else {
                        if (parent != null) { // never tomb at root
                            val n = GCAS_READ(this@TrieMap)
                            if (n is TNode) cleanParent(n, parent, hc, lev, startgen)
                        }
                        return res
                    }
                }
            }
            is TNode -> {
                clean(parent!!, lev - 5)
                return RESTART
            }
            is LNode -> {
                val ln = m as LNode
                val value = ln[k]
                if (v == null) {
                    val nn = ln.removed(k)
                    return if (GCAS(ln, nn, this@TrieMap)) value else RESTART
                } else {
                    if (v === value) {
                        val nn = ln.removed(k)
                        return if (GCAS(ln, nn, this@TrieMap)) value else RESTART
                    } else {
                        return null // Should not happen
                    }
                }
            }
            else -> throw NoWhenBranchMatchedException() // Should not happen
        }
    }

    private tailrec fun lookup(key: K, hash: Int): V? {
        val root = RDCSS_READ_ROOT()
        val res = root.recLookup(key, hash, 0, null, root.gen)
        return if (res !== RESTART) res as V? else lookup(key, hash)
    }

    private tailrec fun insert(key: K, value: V, hash: Int, cond: Any?): V? {
        val root = RDCSS_READ_ROOT()
        val ret = root.recInsert(key, value, hash, cond, 0, null, root.gen)
        return if (ret !== RESTART) ret as V? else insert(key, value, hash, cond)
    }

    private tailrec fun delete(key: K, value: V?, hash: Int): V? {
        val root = RDCSS_READ_ROOT()
        val res = root.recDelete(key, value, hash, 0, null, root.gen)
        return if (res !== RESTART) res as V? else delete(key, value, hash)
    }

    private fun ensureReadWrite() {
        if (readOnly) throw UnsupportedOperationException("Attempted to modify a read-only view")
    }

    tailrec override fun clear() {
        val root = RDCSS_READ_ROOT()
        return if (RDCSS_ROOT(root, root.GCAS_READ(this), INode.newRootNode())) Unit else clear()
    }

    override operator fun get(key: K): V? = lookup(key, key.hash)

    override fun put(key: K, value: V): V? {
        ensureReadWrite()
        return insert(key, value, key.hash, null)
    }

    override fun putIfAbsent(key: K, value: V): V? {
        ensureReadWrite()
        return insert(key, value, key.hash, KEY_ABSENT)
    }

    override fun replace(key: K, value: V): V? {
        ensureReadWrite()
        return insert(key, value, key.hash, KEY_PRESENT)
    }

    override fun replace(key: K, oldValue: V, newValue: V): Boolean {
        ensureReadWrite()
        return insert(key, newValue, key.hash, oldValue) != null
    }

    override fun remove(key: K): V? {
        ensureReadWrite()
        return delete(key, null, key.hash)
    }

    override fun remove(key: K, value: V): Boolean {
        ensureReadWrite()
        return delete(key, value, key.hash) != null
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

    operator fun iterator(): Iterator<Entry<K, V>> = if (readOnly) readOnlySnapshot().readOnlyIterator() else TrieMapIterator()

    fun readOnlyIterator(): Iterator<Entry<K, V>> = if (!readOnly) readOnlySnapshot().readOnlyIterator() else TrieMapReadOnlyIterator()

    private open inner class TrieMapIterator : Iterator<Entry<K, V>> {
        private val stack = arrayOfNulls<Array<Branch<K, V>?>>(7)
        private val stackPos = IntArray(7)
        private var depth = -1
        private var subIter: Iterator<Entry<K, V>>? = null
        private var current: Entry<K, V>? = null
        private var lastReturned: Entry<K, V>? = null

        init {
            readINode(RDCSS_READ_ROOT())
        }

        override fun hasNext(): Boolean = current != null || subIter != null

        override fun next(): Entry<K, V> {
            if (hasNext()) {
                val r: Entry<K, V>
                if (subIter != null) {
                    r = subIter!!.next()
                    checkSubIter()
                } else {
                    r = current!!
                    advance()
                }
                lastReturned = r
                return nextEntry(r)
            } else throw NoSuchElementException()
        }

        open protected fun nextEntry(r: Entry<K, V>): Entry<K, V> = object : Entry<K, V> {
            override val key: K get() = r.key

            override val value: V get() = updated ?: r.value

            private var updated: V? = null

            override fun setValue(newValue: V): V {
                val value = this.value
                updated = newValue
                insert(key, newValue, key.hash, null)
                return value
            }
        }

        private fun readINode(iNode: INode<K, V>) {
            val m = iNode.GCAS_READ(this@TrieMap)
            when (m) {
                is CNode -> {
                    depth += 1
                    stack[depth] = m.array
                    stackPos[depth] = -1
                    advance()
                }
                is TNode -> {
                    current = m
                }
                is LNode -> {
                    subIter = m.iterator()
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
                        is SNode -> current = elem
                        is INode -> readINode(elem)
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
            val lastReturned = lastReturned!!
            delete(lastReturned.key, null, lastReturned.key.hash)
            this.lastReturned
        }
    }

    private inner class TrieMapReadOnlyIterator : TrieMapIterator() {
        override fun remove() = throw UnsupportedOperationException()

        override fun nextEntry(r: Entry<K, V>): Entry<K, V> = r
    }

    private inner class EntrySet : java.util.AbstractSet<Entry<K, V>>() {
        override val size: Int get() = this@TrieMap.size

        override fun iterator(): Iterator<Entry<K, V>> = this@TrieMap.iterator()

        override fun contains(element: Entry<K, V>) = this@TrieMap.lookup(element.key, element.key.hash) != null

        override fun remove(element: Entry<K, V>) = this@TrieMap.delete(element.key, null, element.key.hash) != null

        override fun clear() = this@TrieMap.clear()
    }
}

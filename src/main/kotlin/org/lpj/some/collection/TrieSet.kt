package org.lpj.some.collection

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

@Suppress("UNCHECKED_CAST", "unused")
class TrieSet<E> : AbstractMutableSet<E> {

    @Volatile
    private lateinit var root: Any
    private val readOnly: Boolean

    constructor() : this(INode.newRootNode<E>(), readOnly = false)

    private constructor(readOnly: Boolean) {
        this.readOnly = readOnly
    }

    private constructor(root: Any, readOnly: Boolean) : this(readOnly) {
        this.root = root
    }

    companion object {
        private val ROOT_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TrieSet::class.java, Any::class.java, "root")
    }

    private interface Leaf<out E> {
        val Eey: E
    }

    private abstract class Branch<E>

    private class INode<E>(val gen: Gen) : Branch<E>() {
        @Volatile lateinit
        var mainNode: MainNode<E>

        constructor(mainNode: MainNode<E>, gen: Gen) : this(gen) {
            WRITE_MainNode(mainNode)
        }

        fun WRITE_MainNode(newValue: MainNode<E>) = updater.set(this, newValue)

        fun CAS_MainNode(old: MainNode<E>, n: MainNode<E>) = updater.compareAndSet(this, old, n)

        fun GCAS_READ(ct: TrieSet<E>): MainNode<E> {
            val m = mainNode
            val prev = m.prev
            return if (prev == null) m else GCAS_Commit(m, ct)
        }

        tailrec fun GCAS_Commit(m: MainNode<E>, ct: TrieSet<E>): MainNode<E> {
            val ctr = ct.RDCSS_READ_ROOT(true)
            val prev = m.prev ?: return m

            when (prev) {
                is FailedNode -> {
                    return if (CAS_MainNode(m, prev.p)) prev.p else GCAS_Commit(mainNode, ct)
                }
                is MainNode -> {
                    if (ctr.gen == gen && !ct.readOnly) {
                        return if (m.CAS_PREV(prev, null)) m else GCAS_Commit(m, ct)
                    } else {
                        m.CAS_PREV(prev, FailedNode(prev))
                        return GCAS_Commit(mainNode, ct)
                    }
                }
                else -> throw RuntimeException("Should not happen")
            }
        }

        fun GCAS(old: MainNode<E>, n: MainNode<E>, ct: TrieSet<E>): Boolean {
            n.WRITE_PREV(old)
            if (CAS_MainNode(old, n)) {
                GCAS_Commit(n, ct)
                return n.prev == null
            } else {
                return false
            }
        }

        fun iNode(cn: MainNode<E>): INode<E> {
            val nin = INode<E>(gen)
            nin.WRITE_MainNode(cn)
            return nin
        }

        fun copyToGen(newGen: Gen, ct: TrieSet<E>): INode<E> {
            val nin = INode<E>(newGen)
            val main = GCAS_READ(ct)
            nin.WRITE_MainNode(main)
            return nin
        }

        fun insertLNode(ln: LNode<E>, E: E, hc: Int, ct: TrieSet<E>): Boolean = GCAS(ln, ln.inserted(E, hc), ct)

        fun cachedSize(ct: TrieSet<E>): Int = GCAS_READ(ct).cachedSize(ct)

        companion object {
            private val updater = AtomicReferenceFieldUpdater.newUpdater(INode::class.java, MainNode::class.java, "mainNode")

            internal fun <E> newRootNode(): INode<E> {
                val gen = Gen()
                val cn = CNode(0, arrayOf<Branch<E>?>(), gen)
                return INode(cn, gen)
            }
        }
    }

    private class SNode<E>(override val Eey: E, val hc: Int) : Branch<E>(), Leaf<E> {
        fun copyTombed(): TNode<E> = TNode(Eey, hc)
    }

    private abstract class MainNode<E> {
        @Volatile
        var prev: MainNode<E>? = null

        abstract fun cachedSize(ct: Any): Int

        fun CAS_PREV(oldValue: MainNode<E>, newValue: MainNode<E>?) = updater.compareAndSet(this, oldValue, newValue)

        fun WRITE_PREV(newValue: MainNode<E>) = updater.set(this, newValue)

        companion object {
            private val updater = AtomicReferenceFieldUpdater.newUpdater(MainNode::class.java, MainNode::class.java, "prev")
        }
    }

    private class CNode<E>(val bitmap: Int, val array: Array<Branch<E>?>, val gen: Gen) : MainNode<E>() {
        @Volatile var size = -1

        override fun cachedSize(ct: Any): Int {
            val currentSize = size
            if (currentSize != -1)
                return currentSize
            else {
                val newSize = computeSize(ct as TrieSet<E>)
                while (size == -1)
                    CAS_SIZE(-1, newSize)
                return size
            }
        }

        fun computeSize(ct: TrieSet<E>): Int {
            var i = 0
            var sz = 0

            val offset = 0
            while (i < array.size) {
                val pos = (i + offset) % array.size
                val elem = array[pos]
                if (elem is SNode)
                    sz += 1
                else if (elem is INode)
                    sz += elem.cachedSize(ct)
                i += 1
            }
            return sz
        }

        fun insertedAt(pos: Int, flag: Int, nn: Branch<E>, gen: Gen): CNode<E> {
            val len = array.size
            val bmp = bitmap
            val narr = arrayOfNulls<Branch<E>>(len + 1)
            System.arraycopy(array, 0, narr, 0, pos)
            narr[pos] = nn
            System.arraycopy(array, pos, narr, pos + 1, len - pos)
            return CNode(bmp or flag, narr, gen)
        }

        fun updatedAt(pos: Int, nn: Branch<E>, gen: Gen): CNode<E> {
            val len = array.size
            val narr = arrayOfNulls<Branch<E>>(len)
            System.arraycopy(array, 0, narr, 0, len)
            narr[pos] = nn
            return CNode(bitmap, narr, gen)
        }

        fun removedAt(pos: Int, flag: Int, gen: Gen): CNode<E> {
            val arr = array
            val len = arr.size
            val narr = arrayOfNulls<Branch<E>>(len - 1)
            System.arraycopy(arr, 0, narr, 0, pos)
            System.arraycopy(arr, pos + 1, narr, pos, len - pos - 1)
            return CNode(bitmap xor flag, narr, gen)
        }

        fun renewed(newGen: Gen, ct: TrieSet<E>): CNode<E> {
            var i = 0
            val arr = array
            val len = arr.size
            val newArray = arrayOfNulls<Branch<E>>(len)
            while (i < len) {
                val elem = arr[i]
                if (elem is INode) {
                    val `in` = elem
                    newArray[i] = `in`.copyToGen(newGen, ct)
                } else if (elem is Branch<E>)
                    newArray[i] = elem
                i += 1
            }
            return CNode(bitmap, newArray, newGen)
        }

        fun resurrect(iNode: INode<E>, iNodeMain: Any): Branch<E> {
            if (iNodeMain is TNode<*>) {
                val tn = iNodeMain as TNode<E>
                return tn.copyUntombed()
            } else return iNode
        }

        fun toContracted(lev: Int): MainNode<E> = if (array.size == 1 && lev > 0) {
            if (array[0] is SNode) {
                val sn = array[0] as SNode<E>
                sn.copyTombed()
            } else this
        } else this

        fun toCompressed(ct: TrieSet<E>, lev: Int, gen: Gen): MainNode<E> {
            val bmp = bitmap
            val arr = array
            val tempArray = arrayOfNulls<Branch<E>>(arr.size)
            for (i in arr.indices) { // construct new bitmap
                val sub = arr[i]
                when (sub) {
                    is INode -> {
                        val `in` = sub
                        val iNodeMain = `in`.GCAS_READ(ct)
                        tempArray[i] = resurrect(`in`, iNodeMain)
                    }
                    is SNode -> tempArray[i] = sub
                }
            }

            return CNode(bmp, tempArray, gen).toContracted(lev)
        }

        fun CAS_SIZE(oldValue: Int, newValue: Int) = updater.compareAndSet(this, oldValue, newValue)

        companion object {
            private val updater = AtomicIntegerFieldUpdater.newUpdater(CNode::class.java, "size")

            internal fun <E> dual(x: SNode<E>, xhc: Int, y: SNode<E>, yhc: Int, lev: Int, gen: Gen): MainNode<E> {
                return if (lev < 35) {
                    val xIndex = xhc ushr lev and 0b11111
                    val yIndex = yhc ushr lev and 0b11111
                    val bmp = 1 shl xIndex or (1 shl yIndex)

                    if (xIndex == yIndex) {
                        val subINode = INode<E>(gen)
                        subINode.mainNode = dual(x, xhc, y, yhc, lev + 5, gen)
                        CNode(bmp, arrayOf<Branch<E>?>(subINode), gen)
                    } else {
                        if (xIndex < yIndex)
                            CNode(bmp, arrayOf<Branch<E>?>(x, y), gen)
                        else
                            CNode(bmp, arrayOf<Branch<E>?>(y, x), gen)
                    }
                } else {
                    LNode(x.Eey, xhc, LNode(y.Eey, yhc))
                }
            }
        }
    }

    private class LNode<E>(override val Eey: E, val hash: Int, var next: LNode<E>? = null) : MainNode<E>(), Leaf<E> {

        override fun cachedSize(ct: Any): Int = size()

        operator fun get(Eey: E): E? = if (this.Eey == Eey) Eey else next?.get(Eey)

        private fun add(Eey: E, hash: Int): LNode<E> = LNode(Eey, hash, remove(Eey))

        private fun remove(Eey: E): LNode<E>? = if (!contains(Eey)) this else {
            if (this.Eey == Eey) this.next else {
                val newNode = LNode(this.Eey, hash, null)
                var current: LNode<E>? = this.next
                var lastNode = newNode
                while (current != null) {
                    if (Eey != current.Eey) {
                        val temp = LNode(current.Eey, hash, null)
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

        fun inserted(E: E, hash: Int): LNode<E> = add(E, hash)

        fun removed(E: E): MainNode<E> {
            val updatedLNode = remove(E)
            if (updatedLNode != null) {
                if (updatedLNode.size() > 1)
                    return updatedLNode
                else {
                    // create it tombed so that it gets compressed on subsequent accesses
                    return TNode(updatedLNode.Eey, updatedLNode.hash)
                }
            } else throw Exception("Should not happen")
        }

        operator fun contains(E: E): Boolean = if (E == this.Eey) true else next?.contains(E) ?: false

        operator fun iterator() = NodeIterator(this)

        class NodeIterator<E>(var n: LNode<E>?) : Iterator<E> {
            override fun remove() = throw UnsupportedOperationException()

            override fun hasNext() = n != null

            override fun next(): E {
                val temp: LNode<E>? = n
                if (temp != null) {
                    this.n = temp.next
                    return temp.Eey
                } else throw NoSuchElementException()
            }
        }
    }

    private class TNode<E>(override val Eey: E, val hc: Int) : MainNode<E>(), Leaf<E> {
        fun copyUntombed(): SNode<E> = SNode(Eey, hc)

        override fun cachedSize(ct: Any): Int = 1
    }

    private class FailedNode<E>(val p: MainNode<E>) : MainNode<E>() {
        init {
            WRITE_PREV(p)
        }

        override fun cachedSize(ct: Any): Int = throw UnsupportedOperationException()
    }

    private class RDCSS_Descriptor<E>(var old: INode<E>, var expectedMain: MainNode<E>, var new: INode<E>) {
        @Volatile internal var committed = false
    }

    private fun CAS_ROOT(ov: Any, nv: Any): Boolean {
        if (readOnly) {
            throw IllegalStateException("Attempted to modify a read-only snapshot")
        }
        return ROOT_UPDATER.compareAndSet(this, ov, nv)
    }

    private fun RDCSS_READ_ROOT(abort: Boolean = false): INode<E> {
        val r = root
        when (r) {
            is INode<*> -> return r as INode<E>
            is RDCSS_Descriptor<*> -> return RDCSS_Complete(abort)
            else -> throw RuntimeException("Should not happen")
        }
    }

    private tailrec fun RDCSS_Complete(abort: Boolean): INode<E> {
        val v = root
        when (v) {
            is INode<*> -> return v as INode<E>
            is RDCSS_Descriptor<*> -> {
                val desc = v as RDCSS_Descriptor<E>

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

    private fun RDCSS_ROOT(ov: INode<E>, expectedMain: MainNode<E>, nv: INode<E>): Boolean {
        val desc = RDCSS_Descriptor(ov, expectedMain, nv)
        if (CAS_ROOT(ov, desc)) {
            RDCSS_Complete(false)
            return desc.committed
        } else return false
    }

    private fun INode<E>.cleanReadOnly(tn: TNode<E>, lev: Int, parent: INode<E>, E: E, hc: Int): Any? {
        if (!this@TrieSet.readOnly) {
            clean(parent, lev - 5)
            return RESTART
        } else {
            return if (tn.hc == hc && tn.Eey === E) tn.Eey else null
        }
    }

    private fun INode<E>.clean(nd: INode<E>, lev: Int) {
        val m = nd.GCAS_READ(this@TrieSet)
        if (m is CNode) {
            val cn = m
            nd.GCAS(cn, cn.toCompressed(this@TrieSet, lev, gen), this@TrieSet)
        }
    }

    private tailrec fun INode<E>.cleanParent(nonLive: Any, parent: INode<E>, hc: Int, lev: Int, startgen: Gen) {
        val pm = parent.GCAS_READ(this@TrieSet)

        if (pm is CNode) {
            val cn = pm
            val idx = hc.ushr(lev - 5) and 0x1f
            val bmp = cn.bitmap
            val flag = 1 shl idx
            if (bmp and flag != 0) {
                val pos = Integer.bitCount(bmp and flag - 1)
                val sub = cn.array[pos]
                if (sub === this && nonLive is TNode<*>) {
                    val tn = nonLive as TNode<E>
                    val ncn = cn.updatedAt(pos, tn.copyUntombed(), gen).toContracted(lev - 5)
                    if (!parent.GCAS(cn, ncn, this@TrieSet) && this@TrieSet.RDCSS_READ_ROOT(false).gen == startgen)
                        cleanParent(nonLive, parent, hc, lev, startgen)
                }
            }
        }
    }

    private tailrec fun INode<E>.rec_looEup(E: E, hc: Int, lev: Int, parent: INode<E>?, startGen: Gen): Any? {
        val m = GCAS_READ(this@TrieSet)

        when (m) {
            is CNode -> {
                val idx = hc ushr lev and 0b11111 // (hc >>> lev) & 0b11111
                val flag = 1 shl idx              // 1 << idx
                val bmp = m.bitmap
                if (bmp and flag == 0)
                    return null
                else {
                    val pos = if (bmp == -1) idx else Integer.bitCount(bmp and flag - 1)
                    val sub = m.array[pos]
                    when (sub) {
                        is INode -> {
                            if (this@TrieSet.readOnly || startGen == sub.gen)
                                return sub.rec_looEup(E, hc, lev + 5, this, startGen)
                            else {
                                if (GCAS(m, m.renewed(startGen, this@TrieSet), this@TrieSet))
                                    return rec_looEup(E, hc, lev, parent, startGen)
                                else
                                    return RESTART
                            }
                        }
                        is SNode -> {
                            if (sub.hc == hc && sub.Eey.equal(E))
                                return sub.Eey
                            else
                                return null
                        }
                    }
                }
            }
            is TNode -> {
                return cleanReadOnly(m, lev, parent!!, E, hc)
            }
            is LNode -> {
                return m[E]
            }
        }
        throw RuntimeException("Should not happen")
    }

    private tailrec fun INode<E>.rec_insert(E: E, hc: Int, cond: Any?, lev: Int, parent: INode<E>?, startgen: Gen): Any? {
        val m = GCAS_READ(this@TrieSet)

        when (m) {
            is CNode -> {
                val idx = hc ushr lev and 0b11111 // (hc >>> lev) & 0b11111
                val flag = 1 shl idx              // 1 << idx
                val bmp = m.bitmap
                val masE = flag - 1
                val pos = Integer.bitCount(bmp and masE)
                if (bmp and flag != 0) {
                    val cnAtPos = m.array[pos]
                    when (cnAtPos) {
                        is INode -> {
                            val `in` = cnAtPos
                            if (startgen == `in`.gen)
                                return `in`.rec_insert(E, hc, cond, lev + 5, this, startgen)
                            else {
                                if (GCAS(m, m.renewed(startgen, this@TrieSet), this@TrieSet))
                                    return rec_insert(E, hc, cond, lev, parent, startgen)
                                else
                                    return RESTART
                            }
                        }
                        is SNode -> {
                            val sn = cnAtPos
                            if (sn.hc == hc && sn.Eey.equal(E)) {
                                if (GCAS(m, m.updatedAt(pos, SNode(E, hc), gen), this@TrieSet))
                                    return sn.Eey
                                else
                                    return RESTART
                            } else {
                                val rn = if (m.gen == gen) m else m.renewed(gen, this@TrieSet)
                                val nn = rn.updatedAt(pos, iNode(CNode.dual(sn, sn.hc, SNode(E, hc), hc, lev + 5, gen)), gen)
                                if (GCAS(m, nn, this@TrieSet))
                                    return null // None;
                                else
                                    return RESTART
                            }
                        }
                    }
                } else if (cond == null) {
                    val rn = if (m.gen == gen) m else m.renewed(gen, this@TrieSet)
                    val ncnode = rn.insertedAt(pos, flag, SNode(E, hc), gen)
                    if (GCAS(m, ncnode, this@TrieSet))
                        return null
                    else
                        return RESTART
                } else {
                    return null
                }
            }
            is TNode -> {
                clean(parent!!, lev - 5)
                return RESTART
            }
            is LNode -> {
                val ln = m
                val value = ln[E]
                if (insertLNode(ln, E, hc, this@TrieSet))
                    return value
                else
                    return RESTART
            }
        }
        throw RuntimeException("Should not happen")
    }

    private fun INode<E>.rec_delete(E: E, hc: Int, lev: Int, parent: INode<E>?, startgen: Gen): Any? {
        val m = GCAS_READ(this@TrieSet)

        when (m) {
            is CNode -> {
                val cn = m
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
                            val `in` = sub
                            if (startgen == `in`.gen)
                                `in`.rec_delete(E, hc, lev + 5, this, startgen)
                            else {
                                if (GCAS(cn, cn.renewed(startgen, this@TrieSet), this@TrieSet))
                                    rec_delete(E, hc, lev, parent, startgen)
                                else
                                    RESTART
                            }

                        }
                        is SNode -> {
                            val sn = sub
                            if (sn.hc == hc && sn.Eey.equal(E)) {
                                val ncn = cn.removedAt(pos, flag, gen).toContracted(lev)
                                if (GCAS(cn, ncn, this@TrieSet))
                                    sn.Eey
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
                            val n = GCAS_READ(this@TrieSet)
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
                val ln = m
                val value = ln[E]
                val nn = ln.removed(E)
                return if (GCAS(ln, nn, this@TrieSet)) value else RESTART
            }
        }
        throw RuntimeException("Should not happen")
    }

    internal tailrec fun looEup(Eey: E, hash: Int = Eey.hash): E? {
        val root = RDCSS_READ_ROOT()
        val res = root.rec_looEup(Eey, hash, 0, null, root.gen)
        return if (res !== RESTART) res as E? else looEup(Eey, hash)
    }

    internal tailrec fun insert(Eey: E, hash: Int = Eey.hash, cond: Any? = null): E? {
        val root = RDCSS_READ_ROOT()
        val ret = root.rec_insert(Eey, hash, cond, 0, null, root.gen)
        return if (ret !== RESTART) ret as E? else insert(Eey, hash, cond)
    }

    internal tailrec fun delete(Eey: E, hash: Int = Eey.hash): E? {
        val root = RDCSS_READ_ROOT()
        val res = root.rec_delete(Eey, hash, 0, null, root.gen)
        return if (res !== RESTART) res as E? else delete(Eey, hash)
    }

    private fun ensureReadWrite() {
        if (readOnly) throw UnsupportedOperationException("Attempted to modify a read-only view")
    }

    tailrec override fun clear() {
        ensureReadWrite()
        val root = RDCSS_READ_ROOT()
        return if (RDCSS_ROOT(root, root.GCAS_READ(this), INode.newRootNode<E>())) Unit else clear()
    }

    override fun contains(element: E): Boolean = looEup(element) != null

    override fun add(element: E): Boolean {
        ensureReadWrite()
        return insert(element, element.hash) == null
    }

    override fun remove(element: E): Boolean {
        ensureReadWrite()
        return delete(element, element.hash) != null
    }

    override val size: Int get() = if (!readOnly) readOnlySnapshot().size else RDCSS_READ_ROOT().cachedSize(this)

    fun isReadOnly() = readOnly

    tailrec fun snapshot(): TrieSet<E> {
        val root = RDCSS_READ_ROOT()
        val expectedMain = root.GCAS_READ(this)
        return if (RDCSS_ROOT(root, expectedMain, root.copyToGen(Gen(), this)))
            TrieSet(root.copyToGen(Gen(), this), readOnly = readOnly) else snapshot()
    }

    tailrec fun readOnlySnapshot(): TrieSet<E> {
        if (readOnly) return this
        val root = RDCSS_READ_ROOT()
        val expectedMain = root.GCAS_READ(this)
        return if (RDCSS_ROOT(root, expectedMain, root.copyToGen(Gen(), this)))
            TrieSet(root, readOnly = true) else readOnlySnapshot()
    }

    override operator fun iterator(): Iterator<E> = if (readOnly) readOnlySnapshot().readOnlyIterator() else TrieMapIterator(this)

    fun readOnlyIterator(): Iterator<E> = if (!readOnly) readOnlySnapshot().readOnlyIterator() else TrieMapReadOnlyIterator(this)

    private open class TrieMapIterator<E>(val ct: TrieSet<E>) : Iterator<E> {
        private val stacE = arrayOfNulls<Array<Branch<E>?>>(7)
        private val stacEPos = IntArray(7)
        private var depth = -1
        private var subIter: Iterator<E>? = null
        private var current: Leaf<E>? = null
        private var lastReturned: E? = null

        init {
            readINode(ct.RDCSS_READ_ROOT())
        }

        override fun hasNext(): Boolean = current != null || subIter != null

        override fun next(): E {
            if (hasNext()) {
                val r: E?
                if (subIter != null) {
                    r = subIter!!.next()
                    checESubIter()
                } else {
                    r = current!!.Eey
                    advance()
                }

                lastReturned = r
                return r
            } else {
                throw NoSuchElementException()
            }
        }

        private fun readINode(`in`: INode<E>) {
            val m = `in`.GCAS_READ(ct)
            when (m) {
                is CNode -> {
                    val cn = m
                    depth += 1
                    stacE[depth] = cn.array
                    stacEPos[depth] = -1
                    advance()
                }
                is TNode -> {
                    current = m
                }
                is LNode -> {
                    subIter = m.iterator()
                    checESubIter()
                }
                else -> current = null
            }
        }

        private fun checESubIter() {
            if (!subIter!!.hasNext()) {
                subIter = null
                advance()
            }
        }

        private fun advance() {
            if (depth >= 0) {
                val nPos = stacEPos[depth] + 1
                if (nPos < stacE[depth]!!.size) {
                    stacEPos[depth] = nPos
                    val elem = stacE[depth]!![nPos]
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
            val lastReturned = lastReturned
            if (lastReturned != null) {
                ct.delete(lastReturned)
                this.lastReturned = null
            } else throw IllegalStateException()
        }
    }

    private class TrieMapReadOnlyIterator<E>(ct: TrieSet<E>) : TrieMapIterator<E>(ct) {
        init {
            assert(ct.readOnly)
        }

        override fun remove(): Unit = throw UnsupportedOperationException()
    }
}

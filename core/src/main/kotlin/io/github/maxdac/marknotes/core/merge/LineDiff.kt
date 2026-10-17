package io.github.maxdac.marknotes.core.merge

/** A line of a git-style diff. */
data class DiffLine(val type: Type, val text: String) {
    enum class Type { SAME, ADDED, REMOVED }
}

/** Line-based diff using Myers' O(ND) algorithm. */
object LineDiff {
    /** Above this edit distance the inputs are treated as completely different, to bound memory. */
    private const val MAX_EDIT_DISTANCE = 2000

    /**
     * Splits [text] into lines, keeping each line terminator so that joining the result gives back
     * exactly [text].
     */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                result += text.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < text.length) result += text.substring(start)
        return result
    }

    /**
     * Returns the pairs of matching indexes (a, b) of a longest common subsequence of [a] and [b],
     * in increasing order.
     */
    fun matches(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        // Trim the common prefix and suffix: cheap and very common for notes.
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) {
            suffix++
        }
        val result = ArrayList<Pair<Int, Int>>()
        for (i in 0 until prefix) result += i to i
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        myers(midA, midB).forEach { (x, y) -> result += (x + prefix) to (y + prefix) }
        for (i in 0 until suffix) result += (a.size - suffix + i) to (b.size - suffix + i)
        return result
    }

    /** Git-style line diff from [old] to [new]. */
    fun diff(old: String, new: String): List<DiffLine> {
        val a = lines(old)
        val b = lines(new)
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        for ((x, y) in matches(a, b)) {
            while (i < x) result += DiffLine(DiffLine.Type.REMOVED, a[i++])
            while (j < y) result += DiffLine(DiffLine.Type.ADDED, b[j++])
            result += DiffLine(DiffLine.Type.SAME, a[i])
            i++
            j++
        }
        while (i < a.size) result += DiffLine(DiffLine.Type.REMOVED, a[i++])
        while (j < b.size) result += DiffLine(DiffLine.Type.ADDED, b[j++])
        return result
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun myers(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        // trace[d] holds V for diagonals -d..d before step d.
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..max) {
            if (d > MAX_EDIT_DISTANCE) return emptyList()
            trace += v.copyOfRange(offset - d - 1, offset + d + 2)
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = true
                    break
                }
                k += 2
            }
            if (found) break
        }

        // Backtrack through the saved V slices to recover the matched (snake) points.
        val result = ArrayList<Pair<Int, Int>>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val slice = trace[d]
            fun vd(k: Int) = slice[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd(k - 1) < vd(k + 1))) k + 1 else k - 1
            val prevX = if (d == 0) 0 else vd(prevK)
            val prevY = if (d == 0) 0 else prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
                result += x to y
            }
            x = prevX
            y = prevY
        }
        result.reverse()
        return result
    }
}

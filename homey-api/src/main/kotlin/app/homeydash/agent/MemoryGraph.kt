package app.homeydash.agent

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The memory as a picture, for Settings → Memory → Graph, after Graphify's view: every person
 * (and the whole house) is a hub, the things they do, like and said are dots around it, and
 * each fact is a line between its subject and object, so things several facts share (a
 * radio station, a room) tie them together.
 */
object MemoryGraph {
    /** A dot: a person or the house when [hub], else a thing facts are about. */
    data class Node(
        val key: String,
        val label: String,
        val hub: Boolean,
        /** Times the facts touching it were handed to the AI. */
        val used: Int,
        /** The category of most of its facts, for its colour. */
        val kind: FactKind,
        val facts: List<String>,
    )

    /** A line: one fact from [a] to [b], or a hub's tie to a subject of its own ([fact] null). */
    data class Edge(val a: Int, val b: Int, val fact: String?, val kind: FactKind?, val count: Int)

    data class Graph(val nodes: List<Node>, val edges: List<Edge>) {
        /** The dots joined to node [i]. */
        fun neighbours(i: Int): Set<Int> = edges.mapNotNullTo(HashSet()) { e -> if (e.a == i) e.b else if (e.b == i) e.a else null }
    }

    const val HOUSE_LABEL = "Whole house"
    private val HOUSE_SUBJECTS = setOf("the house", "someone in the house", "huset", "whole house", "everyone")

    fun build(facts: List<Fact>): Graph {
        class Acc(val key: String, val label: String, val hub: Boolean) {
            var used = 0
            val kinds = HashMap<FactKind, Int>()
            val facts = mutableListOf<String>()
        }
        val acc = LinkedHashMap<String, Acc>()
        fun at(key: String, label: String, hub: Boolean) = acc.getOrPut(key) { Acc(key, label.trim().take(40), hub) }
        val edges = mutableListOf<Triple<String, String, Fact?>>()
        val ties = HashSet<Pair<String, String>>()
        for (f in facts) {
            // A person's own facts and what they shared with the house hang off one hub, by name.
            val person = f.whoName.isNotBlank()
            val hubKey = "who:" + if (person) Routines.normalize(f.whoName) else Memory.HOUSE
            at(hubKey, if (person) f.whoName else HOUSE_LABEL, hub = true)
            val subject = Routines.normalize(f.subject)
            val from = when {
                subject in HOUSE_SUBJECTS -> ("who:" + Memory.HOUSE).also { at(it, HOUSE_LABEL, hub = true) }
                person && subject == Routines.normalize(f.whoName) -> hubKey
                else -> ("n:" + subject).also { at(it, f.subject, hub = false) }
            }
            val to = "n:" + Routines.normalize(f.obj)
            at(to, f.obj, hub = false)
            edges += Triple(from, to, f)
            if (!from.startsWith("who:") && ties.add(hubKey to from)) edges += Triple(hubKey, from, null)
            for (k in setOf(from, to)) acc.getValue(k).apply {
                used += f.used
                kinds[f.kind] = (kinds[f.kind] ?: 0) + 1
                this.facts += f.id
            }
        }
        val index = acc.keys.withIndex().associate { it.value to it.index }
        val nodes = acc.values.map { a ->
            Node(a.key, a.label, a.hub, a.used, a.kinds.maxByOrNull { it.value }?.key ?: FactKind.Learned, a.facts)
        }
        return Graph(
            nodes,
            edges.map { (a, b, f) -> Edge(index.getValue(a), index.getValue(b), f?.id, f?.kind, f?.count ?: 1) },
        )
    }

    /**
     * Places the dots with a plain force layout (Fruchterman–Reingold): dots push each other
     * apart, lines pull their ends together. Gives x, y pairs in -1..1. Starts from spots fixed
     * by each dot's name, so the same memory draws the same way every time.
     */
    fun layout(graph: Graph, iterations: Int = defaultIterations(graph.nodes.size)): FloatArray {
        val n = graph.nodes.size
        val pos = FloatArray(n * 2)
        if (n == 0) return pos
        for (i in 0 until n) {
            val r = Random(graph.nodes[i].key.hashCode())
            pos[i * 2] = r.nextFloat() * 2 - 1
            pos[i * 2 + 1] = r.nextFloat() * 2 - 1
        }
        if (n == 1) return floatArrayOf(0f, 0f)
        val k = sqrt(4.0 / n).toFloat()
        val disp = FloatArray(n * 2)
        var temp = 0.25f
        repeat(iterations) {
            disp.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                val dx = pos[i * 2] - pos[j * 2]
                val dy = pos[i * 2 + 1] - pos[j * 2 + 1]
                val d2 = max(dx * dx + dy * dy, 1e-4f)
                val f = k * k / d2
                disp[i * 2] += dx * f; disp[i * 2 + 1] += dy * f
                disp[j * 2] -= dx * f; disp[j * 2 + 1] -= dy * f
            }
            for (e in graph.edges) {
                if (e.a == e.b) continue
                val dx = pos[e.a * 2] - pos[e.b * 2]
                val dy = pos[e.a * 2 + 1] - pos[e.b * 2 + 1]
                val d = max(sqrt(dx * dx + dy * dy), 1e-3f)
                val f = d / k
                disp[e.a * 2] -= dx * f; disp[e.a * 2 + 1] -= dy * f
                disp[e.b * 2] += dx * f; disp[e.b * 2 + 1] += dy * f
            }
            for (i in 0 until n) {
                // A little pull to the middle keeps loose parts of the graph on screen.
                disp[i * 2] -= pos[i * 2] * 0.6f
                disp[i * 2 + 1] -= pos[i * 2 + 1] * 0.6f
                val dx = disp[i * 2]
                val dy = disp[i * 2 + 1]
                val d = max(sqrt(dx * dx + dy * dy), 1e-6f)
                val step = min(d, temp)
                pos[i * 2] += dx / d * step
                pos[i * 2 + 1] += dy / d * step
            }
            temp = max(temp * 0.97f, 0.002f)
        }
        // Fit into -1..1, keeping the shape.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, pos[i * 2]); maxX = max(maxX, pos[i * 2])
            minY = min(minY, pos[i * 2 + 1]); maxY = max(maxY, pos[i * 2 + 1])
        }
        val half = max(max(maxX - minX, maxY - minY) / 2, 1e-3f)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        for (i in 0 until n) {
            pos[i * 2] = (pos[i * 2] - cx) / half
            pos[i * 2 + 1] = (pos[i * 2 + 1] - cy) / half
        }
        return pos
    }

    /** Fewer rounds for a big memory, so placing up to [Memory.MAX_FACTS] facts stays quick on the tablet. */
    fun defaultIterations(n: Int): Int = when {
        n <= 80 -> 300
        n <= 250 -> 150
        else -> 70
    }

    /** A dot's radius from how often it was used, 1 for the least to about 2.6 for the most. */
    fun weight(node: Node): Float = if (node.hub) 2.6f else (1.0 + ln(1.0 + node.used) / 2.2).coerceAtMost(2.4).toFloat()
}

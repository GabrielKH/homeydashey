package app.homeydash.agent

import kotlin.math.ln

/**
 * The know-how as a picture, for Settings → What the assistant sees → Know-how → Graph, drawn
 * like the memory graph: each topic is a big dot, joined to the tools it hands the AI, the
 * Homey devices it is about and the topics it brings along. Tools several topics share (like
 * get_device) tie them together.
 *
 * It is a second graph beside the memory, not the same one: the memory holds facts (what
 * people do, like and said), the know-how holds how to act. With [build]'s facts the two are
 * drawn together: a fact joins the devices it names and the topic a question with its words
 * would bring, and hangs off its person, so "Gabriel → kaffe at 07:00 → Kaffebryggare →
 * Kitchen appliances" reads as one path.
 */
object KnowHowGraph {
    enum class Type { Topic, Tool, Device, Fact, Person }

    /** Where a topic comes from, for its colour. */
    enum class Source { BuiltIn, Homey, Yours }

    data class Node(
        val key: String,
        val label: String,
        val type: Type,
        /** A topic's source; null for other dots. */
        val source: Source? = null,
        /** Times a topic came with a question, or a fact was handed to the AI. */
        val used: Int = 0,
        /** A fact's category, for its colour. */
        val kind: FactKind? = null,
        /** The topic id, tool name, device id or fact id it stands for. */
        val ref: String = "",
    )

    /** A line between two dots; [type] is the kind of dot at the far end ([b]). */
    data class Edge(val a: Int, val b: Int, val type: Type)

    data class Graph(val nodes: List<Node>, val edges: List<Edge>) {
        fun neighbours(i: Int): Set<Int> = edges.mapNotNullTo(HashSet()) { e -> if (e.a == i) e.b else if (e.b == i) e.a else null }
    }

    /**
     * The graph of [skills] (as [KnowHow.graph] makes them, with [used] counts), their devices
     * from [view], and the [facts] that touch them. A fact that names no device and whose words
     * bring no topic stays out: it is in the memory graph only.
     */
    fun build(skills: List<Skill>, used: Map<String, Int> = emptyMap(), view: HomeView? = null, facts: List<Fact> = emptyList()): Graph {
        val nodes = LinkedHashMap<String, Node>()
        val edges = LinkedHashSet<Triple<String, String, Type>>()
        fun add(n: Node) = nodes.getOrPut(n.key) { n }.key
        fun join(a: String, b: String, type: Type) { if (a != b) edges += Triple(a, b, type) }

        val devices = view?.devices.orEmpty().associateBy { it.id }
        val topicOf = HashMap<String, String>()
        for (s in skills) {
            val source = when {
                s.own -> Source.Yours
                s.fromHomey -> Source.Homey
                else -> Source.BuiltIn
            }
            val t = add(Node("t:" + s.id, s.title, Type.Topic, source, used[s.id] ?: 0, ref = s.id))
            topicOf[s.id] = t
            topicOf[Routines.normalize(s.title)] = t
            s.tools.forEach { join(t, add(Node("tool:$it", it, Type.Tool, ref = it)), Type.Tool) }
            val mine = s.devices.mapNotNull { devices[it] } + devices.values.filter { it.deviceClass in s.classes }
            mine.distinct().forEach { d -> join(t, add(Node("d:" + d.id, d.name, Type.Device, ref = d.id)), Type.Device) }
        }
        for (s in skills) s.related.forEach { r -> topicOf[r]?.let { join(topicOf.getValue(s.id), it, Type.Topic) } }

        for (f in facts) {
            val text = f.subject + " " + f.obj
            val norm = " " + Routines.normalize(text) + " "
            val tokens = norm.trim().split(' ').filter { it.isNotEmpty() }
            val named = view?.let { KnowHow.named(it, norm, tokens) }.orEmpty().filter { "d:" + it.id in nodes }
            // A new kind of device noticed in the house is a fact about its topic, by title.
            val topic = topicOf[Routines.normalize(f.subject)]
                ?: KnowHow.find(skills, text, view, limit = 1).firstOrNull { it.score > 0 }?.let { topicOf[it.skill.id] }
            if (named.isEmpty() && topic == null) continue
            val k = add(Node("f:" + f.id, Memory.line(f).take(48), Type.Fact, used = f.used, kind = f.kind, ref = f.id))
            named.forEach { join(k, "d:" + it.id, Type.Device) }
            // Naming a device already says which topic; the line to the topic is for the rest.
            if (topic != null && named.none { d -> edges.any { it.first == topic && it.second == "d:" + d.id } }) join(k, topic, Type.Topic)
            val person = f.whoName.isNotBlank()
            val who = add(Node("who:" + if (person) Routines.normalize(f.whoName) else Memory.HOUSE, if (person) f.whoName else MemoryGraph.HOUSE_LABEL, Type.Person))
            join(who, k, Type.Fact)
        }

        val index = nodes.keys.withIndex().associate { it.value to it.index }
        return Graph(nodes.values.toList(), edges.map { (a, b, t) -> Edge(index.getValue(a), index.getValue(b), t) })
    }

    fun layout(graph: Graph): FloatArray = MemoryGraph.layout(graph.nodes.map { it.key }, graph.edges.map { it.a to it.b })

    /** A dot's radius: topics and people big, a topic growing with its use, tools and devices small. */
    fun weight(node: Node): Float = when (node.type) {
        Type.Topic -> (1.5 + ln(1.0 + node.used) / 2.2).coerceAtMost(2.6).toFloat()
        Type.Person -> 2.2f
        Type.Tool -> 1.1f
        Type.Device -> 0.9f
        Type.Fact -> (0.8 + ln(1.0 + node.used) / 3.0).coerceAtMost(1.6).toFloat()
    }
}

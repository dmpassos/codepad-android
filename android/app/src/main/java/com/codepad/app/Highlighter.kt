package com.codepad.app

import java.util.regex.Pattern

/** Uma regra do realce: classe do token (tok-<cls>), regex e o estado que deixa aberto (1 = comentário de bloco, 2 = template string). */
class HlRule(val cls: String, val rx: Pattern, val open: Int)

class HlSpan(val start: Int, val end: Int, val cls: String) {
    override fun equals(other: Any?) = other is HlSpan && other.start == start && other.end == end && other.cls == cls
    override fun hashCode() = (start * 31 + end) * 31 + cls.hashCode()
}

class HlLine(val spans: List<HlSpan>, val endState: Int)

/** Realce linha a linha — port de hlLine() do JS (mesmas regras, vindas do próprio HTML). */
class Highlighter(private val rules: Map<String, List<HlRule>>) {

    fun hasLang(lang: String) = rules.containsKey(lang)

    fun line(lang: String, text: String, state: Int): HlLine {
        val rs = rules[lang] ?: return HlLine(emptyList(), 0)
        if (text.length > 2000) return HlLine(emptyList(), state)
        val len = text.length
        val spans = ArrayList<HlSpan>(8)
        var off = 0
        var open = 0

        if (state == 1) {
            val k = text.indexOf("*/")
            if (k < 0) return HlLine(listOf(HlSpan(0, len, "comment")), 1)
            spans.add(HlSpan(0, k + 2, "comment"))
            off = k + 2
        } else if (state == 2) {
            var t = 0
            while (t < len) {
                val c = text[t]
                if (c != '\\') {
                    if (c == '`') break
                    t++
                } else t += 2
            }
            if (t >= len) return HlLine(listOf(HlSpan(0, len, "string")), 2)
            spans.add(HlSpan(0, t + 1, "string"))
            off = t + 1
        }

        val ms = Array(rs.size) { rs[it].rx.matcher(text) }
        while (off < len) {
            var bestStart = Int.MAX_VALUE
            var bestEnd = -1
            var best = -1
            for (i in rs.indices) {
                val m = ms[i]
                m.region(off, len)
                if (m.find() && m.start() < bestStart) {
                    bestStart = m.start()
                    bestEnd = m.end()
                    best = i
                }
            }
            if (best < 0) break
            if (bestEnd == bestStart) {          // regra vazia: o JS ficaria em ciclo; aqui só avança
                off = bestStart + 1
                continue
            }
            spans.add(HlSpan(bestStart, bestEnd, rs[best].cls))
            if (rs[best].open != 0) open = rs[best].open
            off = bestEnd
        }
        return HlLine(spans, open)
    }
}

/** Uma linha já realçada. */
class HlEntry(val text: String, val si: Int, val res: HlLine)

/**
 * Realce incremental (port de hlSync): só refaz as linhas alteradas e propaga o estado de comentário/template
 * para baixo até estabilizar. Devolve apenas as linhas (índices do texto NOVO) cujo realce mudou.
 */
class HlTracker(private val hl: Highlighter) {
    class Change(val line: Int, val res: HlLine)

    private var lines: List<HlEntry> = emptyList()
    private var lang: String? = null

    fun reset() {
        lines = emptyList()
        lang = null
    }

    fun update(newLang: String, text: String): List<Change> {
        val old = if (lang == newLang) lines else emptyList()
        val c = text.split("\n")
        val out = ArrayList<Change>()
        var l = 0
        val u = minOf(old.size, c.size)
        while (l < u && old[l].text == c[l]) l++
        var d = 0
        val f = u - l
        while (d < f && old[old.size - 1 - d].text == c[c.size - 1 - d]) d++
        val h = old.size - d
        val nStart = c.size - d
        val y = ArrayList<HlEntry>(c.size)
        for (k in 0 until l) y.add(old[k])
        var b = if (l > 0) old[l - 1].res.endState else 0
        for (t in l until nStart) {
            val res = hl.line(newLang, c[t], b)
            y.add(HlEntry(c[t], b, res))
            out.add(Change(t, res))
            b = res.endState
        }
        var t = 0
        while (t < d) {
            val e = old[h + t]
            if (e.si == b) {
                while (t < d) { y.add(old[h + t]); t++ }
                break
            }
            val res = hl.line(newLang, e.text, b)
            if (res.spans != e.res.spans) out.add(Change(nStart + t, res))
            y.add(HlEntry(e.text, b, res))
            b = res.endState
            t++
        }
        lines = y
        lang = newLang
        return out
    }
}

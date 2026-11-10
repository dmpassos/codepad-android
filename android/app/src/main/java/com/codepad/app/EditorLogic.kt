package com.codepad.app

/** Substitui [start, end) por [text] e deixa a seleção em [selStart, selEnd]. */
data class Edit(val start: Int, val end: Int, val text: String, val selStart: Int, val selEnd: Int)

sealed class KeyResult {
    /** O editor faz o comportamento normal (inserir o caractere digitado). */
    object Pass : KeyResult()
    data class Apply(val edit: Edit) : KeyResult()
    /** Só move o cursor (saltar sobre um fecho já existente). */
    data class Move(val pos: Int) : KeyResult()
}

/**
 * Regras de edição do CodePad, portadas 1:1 do JS (AC_PAIRS, acSmartEnter, acIndent, acInStringOrComment e o
 * handler de keydown). Kotlin puro (sem android.*), para poder ser testado na JVM contra o JS real.
 */
object EditorLogic {
    const val INDENT = "    "

    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`', '<' to '>')
    private val CLOSERS = setOf(')', ']', '}', '"', '\'', '`', '>')
    private val ANGLE_OPEN = Regex("^\\s*#\\s*include\\s*$")
    private val ANGLE_CLOSE = Regex("^\\s*#\\s*include\\s*<[^>]*$")
    private val OUTDENT_SPACES = Regex(" {1,4}$")
    private val LEADING_SPACES = Regex("^ {1,4}")
    private const val NONE = '\u0000'

    private fun isWord(c: Char) = c == '_' || c == '$' || (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    /** Texto da linha até [pos] (acLineBefore). */
    fun lineBefore(v: CharSequence, pos: Int): String {
        if (pos <= 0) return ""
        var i = pos - 1
        while (i >= 0 && v[i] != '\n') i--
        return v.subSequence(i + 1, pos).toString()
    }

    /** acInStringOrComment: o ponto [e] está dentro de string ou comentário? */
    fun inStringOrComment(t: CharSequence, e: Int): Boolean {
        var state = 0   // 0 código, 1 linha, 2 bloco, 3 string
        var q = NONE
        var r = 0
        val len = t.length
        while (r < e) {
            val c = t[r]
            val n = if (r + 1 < len) t[r + 1] else NONE
            when (state) {
                0 -> if (c == '/' && n == '/') { state = 1; r++ }
                else if (c == '/' && n == '*') { state = 2; r++ }
                else if (c == '"' || c == '\'' || c == '`') { state = 3; q = c }
                1 -> if (c == '\n') state = 0
                2 -> if (c == '*' && n == '/') { state = 0; r++ }
                else -> if (c == '\\') r++ else if (c == q) { state = 0; q = NONE }
            }
            r++
        }
        return state != 0
    }

    /** Uma tecla de um só caractere. [lang] = "c" | "javascript". */
    fun typed(v: CharSequence, selS: Int, selE: Int, key: Char, lang: String): KeyResult {
        val isC = lang == "c"
        val pair = PAIRS[key]
        if (pair == null || (key == '`' && lang != "javascript")) {
            if (key !in CLOSERS) return KeyResult.Pass
            if (key == '>' && (!isC || !ANGLE_CLOSE.containsMatchIn(lineBefore(v, selS)))) return KeyResult.Pass
            return if (selS == selE && selS < v.length && v[selS] == key) KeyResult.Move(selS + 1) else KeyResult.Pass
        }
        if (key == '<' && (!isC || selS != selE || !ANGLE_OPEN.containsMatchIn(lineBefore(v, selS)))) return KeyResult.Pass
        val inside = inStringOrComment(v, selS)
        if (selS != selE) {
            if (inside) return KeyResult.Pass
            val sel = v.subSequence(selS, selE)
            return KeyResult.Apply(Edit(selS, selE, key.toString() + sel + pair, selS + 1, selE + 1))
        }
        val c = if (selS < v.length) v[selS] else NONE
        if (pair == key && c == key) return KeyResult.Move(selS + 1)
        val quote = key == '"' || key == '\'' || key == '`'
        if (inside || (c != NONE && isWord(c) && quote)) return KeyResult.Pass
        return KeyResult.Apply(Edit(selS, selE, key.toString() + pair, selS + 1, selS + 1))
    }

    /** Backspace: apaga o par vazio "()" "{}" "''"... de uma vez. */
    fun backspace(v: CharSequence, selS: Int, selE: Int): KeyResult {
        if (selS != selE || selS == 0) return KeyResult.Pass
        val close = PAIRS[v[selS - 1]] ?: return KeyResult.Pass
        if (selS < v.length && v[selS] == close) return KeyResult.Apply(Edit(selS - 1, selS + 1, "", selS - 1, selS - 1))
        return KeyResult.Pass
    }

    /** acSmartEnter */
    fun enter(v: CharSequence, selS: Int, selE: Int): Edit {
        val s = v.subSequence(0, selS).toString()
        val ls = s.lastIndexOf('\n') + 1
        var k = ls
        while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
        val a = s.substring(ls, k)
        val trimmed = s.trimEnd(' ', '\t')
        val c = if (trimmed.isEmpty()) NONE else trimmed.last()
        val after = if (selE < v.length) v[selE] else NONE
        val l: String
        val u: Int
        if (c == '{' && after == '}') {
            l = "\n" + a + INDENT + "\n" + a
            u = 1 + a.length + 4
        } else if (c == '{') {
            l = "\n" + a + INDENT
            u = l.length
        } else if (c == '}' || c == ')' || c == ']') {
            l = "\n" + a.replace(Regex("\t$"), "").replace(OUTDENT_SPACES, "")
            u = l.length
        } else {
            l = "\n" + a
            u = l.length
        }
        return Edit(selS, selE, l, selS + u, selS + u)
    }

    /** acIndent: Tab / Shift+Tab, com ou sem seleção. */
    fun tab(v: CharSequence, selS: Int, selE: Int, shift: Boolean): Edit {
        val n = selS
        val s = selE
        if (n == s && !shift) return Edit(n, s, INDENT, n + 4, n + 4)
        val e = v.toString()
        val r = if (n == 0) 0 else e.lastIndexOf('\n', n - 1) + 1
        var i = e.indexOf('\n', maxOf(s - 1, n))
        if (i == -1) i = e.length
        val a = e.substring(r, i)
        val lines = a.split("\n")
        var l = 0
        val u = lines.mapIndexed { idx, ln ->
            val out = if (shift) {
                if (ln.startsWith("\t")) ln.substring(1) else ln.replace(LEADING_SPACES, "")
            } else if (ln.isNotEmpty()) INDENT + ln else ln
            if (idx == 0) l = out.length - ln.length
            out
        }.joinToString("\n")
        val d = u.length - a.length
        return if (n == s) {
            val c = maxOf(r, n + l)
            Edit(r, i, u, c, c)
        } else {
            val t = maxOf(r, n + l)
            Edit(r, i, u, t, maxOf(t, s + d))
        }
    }
}

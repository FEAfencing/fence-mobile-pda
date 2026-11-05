package it.fence.pda.logic

import it.fence.pda.core.PairCell
import it.fence.pda.core.UnitDto

/** Logica del girone: matrice a doppia entrata, ordine degli assalti, indici. Tutto per NumFis. */
object PoolLogic {
    /** Ordine standard degli assalti per girone da 3 a 7 atleti (posizioni 1-based). */
    val ORDERS: Map<Int, List<Pair<Int, Int>>> = mapOf(
        3 to listOf(1 to 2, 2 to 3, 3 to 1),
        4 to listOf(1 to 4, 2 to 3, 3 to 1, 4 to 2, 1 to 2, 3 to 4),
        5 to listOf(1 to 2, 3 to 4, 5 to 1, 2 to 3, 5 to 4, 1 to 3, 2 to 5, 4 to 1, 3 to 5, 4 to 2),
        6 to listOf(1 to 2, 4 to 5, 2 to 3, 5 to 6, 3 to 1, 6 to 4, 2 to 5, 1 to 4, 5 to 3, 1 to 6, 4 to 2, 3 to 6, 5 to 1, 4 to 3, 6 to 2),
        7 to listOf(1 to 4, 2 to 5, 3 to 6, 7 to 1, 5 to 4, 2 to 3, 6 to 7, 5 to 1, 4 to 3, 6 to 2, 5 to 7, 3 to 1, 4 to 6, 7 to 2, 1 to 6, 3 to 5, 2 to 4, 7 to 3, 1 to 2, 6 to 5, 4 to 7)
    )

    fun key(a: Int, b: Int): Pair<Int, Int> = if (a < b) a to b else b to a

    /** Chiave logica del lato i-esimo: NumFis per atleti singoli, 1/2 per le squadre. */
    fun sideKey(u: UnitDto, i: Int): Int = if (u.isTeam) i + 1 else u.athletes[i].numFis

    fun allPairs(n: Int): List<Pair<Int, Int>> = ORDERS[n] ?: (1..n).flatMap { i -> (i + 1..n).map { j -> i to j } }

    fun order(u: UnitDto): List<Pair<Int, Int>> = if (u.isPool) allPairs(u.athletes.size) else listOf(1 to 2)

    /** Legge dal database le celle gia' compilate (indice colonna = posizione dell'avversario). */
    fun fromUnit(u: UnitDto): Map<Pair<Int, Int>, PairResult> {
        val out = HashMap<Pair<Int, Int>, PairResult>()
        val n = u.athletes.size
        for (i in 0 until n) for (j in i + 1 until n) {
            val ci = if (u.isPool) j else 0
            val cj = if (u.isPool) i else 0
            val ca = u.athletes[i].ris.getOrNull(ci)
            val cb = u.athletes[j].ris.getOrNull(cj)
            val k = sideKey(u, i); val l = sideKey(u, j)
            Rules.fromCells(k, l, ca, cb, u.fenceMax)?.let { out[key(k, l)] = it }
        }
        return out
    }

    fun toCells(u: UnitDto, m: Map<Pair<Int, Int>, PairResult>): List<PairCell> =
        m.values.filter { it.isComplete }.map { Rules.toCell(it, u.maxTouches, u.fenceMax) }

    /** Coppie ancora da compilare (escluse quelle coperte da A/R/E). */
    fun missing(u: UnitDto, m: Map<Pair<Int, Int>, PairResult>): List<Pair<Int, Int>> {
        val n = u.athletes.size
        val res = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until n) for (j in i + 1 until n) if (m[key(sideKey(u, i), sideKey(u, j))]?.isComplete != true) res += (i + 1) to (j + 1)
        return res
    }

    /** Applica A/R/E a un atleta: A a tutti i suoi assalti; R/E solo a quelli non ancora disputati. */
    fun applySpecial(u: UnitDto, m: Map<Pair<Int, Int>, PairResult>, who: Int, code: String): Map<Pair<Int, Int>, PairResult> {
        val out = HashMap(m)
        for (i in u.athletes.indices) {
            val o = sideKey(u, i)
            if (o == who) continue
            val k = key(who, o)
            val ex = out[k]
            if (code == "A" || ex == null || !ex.isComplete) out[k] = PairResult(k.first, k.second, specialWho = who, specialCode = code)
        }
        return out
    }

    data class Standing(val key: Int, val name: String, val v: Int, val m: Int, val ts: Int, val tr: Int) {
        val ind get() = ts - tr
        val ratio get() = if (m == 0) 0.0 else v.toDouble() / m
    }

    fun standings(u: UnitDto, res: Map<Pair<Int, Int>, PairResult>): List<Standing> =
        u.athletes.indices.map { i ->
            val me = sideKey(u, i)
            var v = 0; var m = 0; var ts = 0; var tr = 0
            for (j in u.athletes.indices) {
                if (i == j) continue
                val p = res[key(me, sideKey(u, j))] ?: continue
                if (p.isSpecial || p.winner == null) continue
                m++
                if (p.winner == me) v++
                ts += p.touchesOf(me) ?: 0
                tr += p.touchesOf(sideKey(u, j)) ?: 0
            }
            Standing(me, u.athletes[i].cognome.ifEmpty { u.athletes[i].sigla }, v, m, ts, tr)
        }

    /** Testo della cella dal punto di vista dell'atleta di riga. */
    fun cellText(p: PairResult?, me: Int, max: Int): String {
        if (p == null || !p.isComplete) return ""
        if (p.isSpecial) return if (p.specialWho == me) p.specialCode!! else "·"
        val tw = p.winnerTouches() ?: return ""
        return if (p.winner == me) (if (tw == max) "V" else "V$tw") else (p.touchesOf(me) ?: 0).toString()
    }
}

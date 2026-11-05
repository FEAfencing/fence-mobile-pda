package it.fence.pda.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Incontro a squadre in 9 frazioni, riportato da frmIncontri di Fence!:
 * la squadra 1 usa i tiratori con indice {2,0,1,0,2,1,0,1,2}, la squadra 2 {2,1,0,2,0,1,0,2,1};
 * il punteggio e' progressivo e dopo la frazione n non puo' superare 5*n.
 */
@Serializable
data class TeamState(
    val legsA: List<Int?> = List(9) { null },   // stoccate segnate nella frazione (non progressive)
    val legsB: List<Int?> = List(9) { null },
    val lineA: List<Int> = listOf(0, 1, 2),     // indice del membro (0..3) che occupa ciascuna posizione
    val lineB: List<Int> = listOf(0, 1, 2),
    val priority: Int? = null                   // 1 o 2: chi vince a parita' (priorita')
)

object TeamLogic {
    val ORDER_A = listOf(2, 0, 1, 0, 2, 1, 0, 1, 2)
    val ORDER_B = listOf(2, 1, 0, 2, 0, 1, 0, 2, 1)
    const val LEG_TOUCHES = 5
    private val json = Json { ignoreUnknownKeys = true }

    fun cap(leg: Int) = LEG_TOUCHES * (leg + 1)

    fun cumulative(list: List<Int?>, upTo: Int): Int = (0..upTo).sumOf { list[it] ?: 0 }

    /** Ultima frazione compilata (-1 se nessuna). */
    fun lastLeg(s: TeamState): Int = (8 downTo 0).firstOrNull { s.legsA[it] != null || s.legsB[it] != null } ?: -1

    fun totals(s: TeamState): Pair<Int, Int> {
        val l = lastLeg(s)
        return if (l < 0) 0 to 0 else cumulative(s.legsA, l) to cumulative(s.legsB, l)
    }

    fun withLeg(s: TeamState, leg: Int, a: Int?, b: Int?): TeamState =
        s.copy(legsA = s.legsA.toMutableList().also { it[leg] = a }, legsB = s.legsB.toMutableList().also { it[leg] = b })

    /** Massimo valore consentito per la frazione, dato il progressivo delle precedenti. */
    fun maxForLeg(list: List<Int?>, leg: Int): Int = cap(leg) - (0 until leg).sumOf { list[it] ?: 0 }

    fun finished(s: TeamState, max: Int): Boolean {
        val (a, b) = totals(s)
        return a >= max || b >= max || (0..8).all { s.legsA[it] != null && s.legsB[it] != null }
    }

    /** Converte nello stesso formato degli altri incontri (lati 1 e 2) per scrivere Ris1. */
    fun toPair(s: TeamState): PairResult {
        val (a, b) = totals(s)
        val w = when { a > b -> 1; b > a -> 2; else -> s.priority }
        return PairResult(1, 2, a, b, winner = w)
    }

    fun fromPair(p: PairResult?): TeamState? = null  // le frazioni non sono memorizzate in Gironi4Mobile: restano nella bozza locale e nel registro del gateway

    fun encode(s: TeamState) = json.encodeToString(s)
    fun decode(t: String): TeamState = runCatching { json.decodeFromString<TeamState>(t) }.getOrDefault(TeamState())
}

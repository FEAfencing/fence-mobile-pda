package it.fence.pda.logic

import it.fence.pda.core.PairCell
import kotlinx.serialization.Serializable

/**
 * Esito di un assalto tra due lati (a, b). Le chiavi sono i NumFis degli atleti (mai la posizione visiva);
 * per ED/squadre i lati sono 1 e 2.
 */
@Serializable
data class PairResult(
    val a: Int, val b: Int, val ta: Int? = null, val tb: Int? = null,
    val winner: Int? = null, val specialWho: Int? = null, val specialCode: String? = null
) {
    val isSpecial get() = specialCode != null
    val isComplete get() = isSpecial || winner != null
    fun touchesOf(key: Int): Int? = if (key == a) ta else tb
    fun winnerTouches(): Int? = winner?.let { touchesOf(it) }
    fun loserTouches(): Int? = winner?.let { touchesOf(if (it == a) b else a) }
}

/** Notazione celle Ris* di Fence!: V, P<n>, numero, A/R/E (vedi TrattaPunti nel decompilato). */
object Rules {
    val SPECIALS = listOf("A" to "Assente", "R" to "Ritirato", "E" to "Espulso")

    fun normalize(c: String?): String? {
        val t = c?.trim()?.uppercase() ?: return null
        if (t.isEmpty()) return null
        if (t in listOf("V", "A", "R", "E")) return t
        if (t.length in 2..4 && (t[0] == 'P' || t[0] == 'V') && t.drop(1).all { it.isDigit() }) return "P" + t.drop(1).toInt()
        if (t.length <= 3 && t.all { it.isDigit() }) return t.toInt().toString()
        return "?"
    }

    fun validate(p: PairResult, max: Int): String? {
        if (p.isSpecial) return null
        val w = p.winner ?: return "Scegli il vincitore"
        val tw = p.touchesOf(w) ?: return "Inserisci le stoccate del vincitore"
        val tl = p.loserTouches() ?: 0
        if (tw <= 0) return "Il vincitore deve avere almeno una stoccata"
        if (tw > max) return "Oltre il limite di $max stoccate"
        if (tl > tw) return "Il perdente non puo' avere piu' stoccate del vincitore"
        return null
    }

    /**
     * Celle da inviare a Fence!. Il vincitore con stoccate = limite storico di Fence! (5 gironi / 15 ED) diventa "V";
     * negli altri casi "P<n>", cosi' Fence! legge sempre il valore esatto anche con limiti diversi dal default.
     */
    fun toCell(p: PairResult, max: Int, fenceMax: Int): PairCell {
        if (p.specialCode != null) {
            return if (p.specialWho == p.a) PairCell(p.a, p.b, p.specialCode, null) else PairCell(p.a, p.b, null, p.specialCode)
        }
        val w = p.winner!!
        val tw = p.touchesOf(w) ?: 0
        val tl = p.loserTouches() ?: 0
        val win = if (tw == fenceMax && tw == max) "V" else "P$tw"
        return if (w == p.a) PairCell(p.a, p.b, win, tl.toString()) else PairCell(p.a, p.b, tl.toString(), win)
    }

    /** Ricostruisce l'esito da due celle Ris* gia' presenti nel database. */
    fun fromCells(a: Int, b: Int, ca: String?, cb: String?, fenceMax: Int): PairResult? {
        val na = normalize(ca); val nb = normalize(cb)
        if (na == "?" || nb == "?") return null
        if (na in listOf("A", "R", "E")) return PairResult(a, b, specialWho = a, specialCode = na)
        if (nb in listOf("A", "R", "E")) return PairResult(a, b, specialWho = b, specialCode = nb)
        fun win(c: String?) = c != null && (c == "V" || c[0] == 'P')
        fun tw(c: String) = if (c == "V") fenceMax else c.drop(1).toInt()
        return when {
            win(na) -> PairResult(a, b, tw(na!!), nb?.toIntOrNull() ?: 0, winner = a)
            win(nb) -> PairResult(a, b, na?.toIntOrNull() ?: 0, tw(nb!!), winner = b)
            else -> null
        }
    }
}

package it.fence.pda

import it.fence.pda.core.Athlete
import it.fence.pda.core.UnitDto
import it.fence.pda.logic.*
import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private fun pool(n: Int, ris: List<List<String?>> = List(n) { List(7) { null } }) = UnitDto(
        id = "x", kind = "pool", title = "Girone 1", flags = 16, mf = 0, cat = 1, maxTouches = 5, fenceMax = 5,
        athletes = (0 until n).map { Athlete(pos = it + 1, numFis = 100 + it, cognome = "A$it", ris = ris[it]) }
    )

    @Test fun normalize() {
        assertEquals("P3", Rules.normalize("v3")); assertEquals("V", Rules.normalize("v")); assertEquals("A", Rules.normalize("a")); assertNull(Rules.normalize(" "))
    }

    @Test fun vittoriaPiena_V_e_perdenteNumero() {
        val c = Rules.toCell(PairResult(1, 2, 5, 3, winner = 1), 5, 5)
        assertEquals("V", c.ca); assertEquals("3", c.cb)
    }

    @Test fun vittoriaATempo_P() {
        val c = Rules.toCell(PairResult(1, 2, 3, 4, winner = 2), 5, 5)
        assertEquals("3", c.ca); assertEquals("P4", c.cb)
    }

    @Test fun limiteDiversoDaFence_usaP() {
        val c = Rules.toCell(PairResult(1, 2, 4, 1, winner = 1), 4, 5)
        assertEquals("P4", c.ca)
    }

    @Test fun validazione() {
        assertNotNull(Rules.validate(PairResult(1, 2, 3, 4, winner = 1), 5))
        assertNull(Rules.validate(PairResult(1, 2, 5, 4, winner = 1), 5))
        assertNotNull(Rules.validate(PairResult(1, 2, 2, 1), 5))
    }

    @Test fun lettura_da_db_per_numfis() {
        // atleta 100 batte 101 per 5-2: Ris2 di 100 = "V", Ris1 di 101 = "2"
        val u = pool(2, listOf(listOf(null, "V") + List(5) { null }, listOf("2") + List(6) { null }))
        val r = PoolLogic.fromUnit(u)[PoolLogic.key(100, 101)]!!
        assertEquals(100, r.winner); assertEquals(5, r.ta); assertEquals(2, r.tb)
    }

    @Test fun griglia_simmetrica_stesso_assalto() {
        val u = pool(2)
        val p = PairResult(100, 101, 5, 2, winner = 100)
        assertEquals("V", PoolLogic.cellText(p, 100, 5)); assertEquals("2", PoolLogic.cellText(p, 101, 5))
    }

    @Test fun speciale_vale_per_tutti_gli_assalti() {
        val u = pool(4)
        val m = PoolLogic.applySpecial(u, emptyMap(), 100, "A")
        assertEquals(3, m.size); assertTrue(m.values.all { it.specialWho == 100 })
        assertEquals(3, PoolLogic.missing(u, m).size)
    }

    @Test fun ordiniGirone_completi() {
        for (n in 3..7) {
            val o = PoolLogic.allPairs(n)
            assertEquals(n * (n - 1) / 2, o.size)
            assertEquals(o.size, o.map { PoolLogic.key(it.first, it.second) }.toSet().size)
        }
    }

    @Test fun squadre_nove_frazioni_e_tetto_progressivo() {
        assertEquals(9, TeamLogic.ORDER_A.size); assertEquals(9, TeamLogic.ORDER_B.size)
        val s = TeamLogic.withLeg(TeamState(), 0, 5, 3)
        assertEquals(5, TeamLogic.maxForLeg(s.legsA, 1))   // tetto 10 meno i 5 gia' fatti
        assertEquals(7, TeamLogic.maxForLeg(s.legsB, 1))
        assertEquals(5 to 3, TeamLogic.totals(s))
    }

    @Test fun squadre_pareggio_richiede_priorita() {
        var s = TeamState()
        for (i in 0 until 9) s = TeamLogic.withLeg(s, i, 2, 2)
        assertNull(TeamLogic.toPair(s).winner)
        assertEquals(2, TeamLogic.toPair(s.copy(priority = 2)).winner)
    }
}

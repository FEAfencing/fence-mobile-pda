using System.Data;
using Microsoft.Data.SqlClient;

namespace FencePdaBridge;

/// <summary>Accesso alle tabelle originali di Fence! (Pda, Mobiles, Gironi4Mobile, Assalti, Messaggi...). Nessuna modifica di schema.</summary>
public sealed class FenceRepo
{
    readonly string _cs;
    readonly Dictionary<string, (int gir, int ed, int sq)> _formula = new();
    readonly Dictionary<int, string> _cat = new();
    readonly object _cache = new();

    public FenceRepo(string connectionString) { _cs = connectionString; }

    public SqlConnection Open()
    {
        var c = new SqlConnection(_cs);
        c.Open();
        return c;
    }

    static int I(IDataRecord r, string n) { var o = r[n]; return o is DBNull ? 0 : Convert.ToInt32(o); }
    static string S(IDataRecord r, string n) { var o = r[n]; return o is DBNull ? "" : Convert.ToString(o)!.Trim(); }
    static string? SN(IDataRecord r, string n) { var o = r[n]; return o is DBNull ? null : Convert.ToString(o); }

    // ---------- diagnostica ----------
    public Dictionary<string, string> Diagnose()
    {
        var res = new Dictionary<string, string>();
        try
        {
            using var c = Open();
            foreach (var t in new[] { "Pda", "Mobiles", "Gironi4Mobile", "Assalti", "Messaggi", "Tesserati", "FormulaGara", "CategAtleti" })
            {
                try
                {
                    using var cmd = new SqlCommand($"SELECT COUNT(*) FROM {t}", c);
                    res[t] = Convert.ToString(cmd.ExecuteScalar()) + " righe";
                }
                catch (Exception ex) { res[t] = "ERRORE: " + ex.Message; }
            }
        }
        catch (Exception ex) { res["connessione"] = "ERRORE: " + ex.Message; }
        return res;
    }

    // ---------- registro PDA ----------
    public void EnsurePda(string mobile)
    {
        using var c = Open();
        using var cmd = new SqlCommand("IF NOT EXISTS (SELECT 1 FROM Pda WHERE Mobile = @m) INSERT INTO Pda (Mobile) VALUES (@m)", c);
        cmd.Parameters.AddWithValue("@m", mobile);
        cmd.ExecuteNonQuery();
    }

    public ArbiterDto? GetArbiter(string mobile)
    {
        using var c = Open();
        using var cmd = new SqlCommand("SELECT TOP 1 Arbitro, Cognome, Nome, Pedana FROM Mobiles WHERE Mobile = @m", c);
        cmd.Parameters.AddWithValue("@m", mobile);
        using var r = cmd.ExecuteReader();
        if (!r.Read()) return null;
        return new ArbiterDto(I(r, "Arbitro"), S(r, "Cognome"), S(r, "Nome"), I(r, "Pedana"));
    }

    // ---------- incontri ----------
    const string SelectRows = @"SELECT G.Flags, G.MaschioFemmina, G.Categoria, G.Girone, G.ED, G.Ordine, G.NumFis, G.Arbitro, G.Nome, G.Cognome,
        G.CodSoc, G.Sigla, G.Ris1, G.Ris2, G.Ris3, G.Ris4, G.Ris5, G.Ris6, G.Ris7, G.Eseguito, DATALENGTH(G.Firma) AS FirmaLen,
        G.NumFis1, G.NumFis2, G.NumFis3, G.NumFis4, G.Atl1, G.Atl2, G.Atl3, G.Atl4
        FROM Gironi4Mobile G ";

    static Row ReadRow(IDataRecord r)
    {
        var row = new Row
        {
            Flags = I(r, "Flags"), Mf = I(r, "MaschioFemmina"), Cat = I(r, "Categoria"), Girone = I(r, "Girone"), Ed = I(r, "ED"),
            Ordine = I(r, "Ordine"), NumFis = I(r, "NumFis"), Arbitro = I(r, "Arbitro"), Nome = S(r, "Nome"), Cognome = S(r, "Cognome"),
            CodSoc = I(r, "CodSoc"), Sigla = S(r, "Sigla"), Eseguito = I(r, "Eseguito") == 1, FirmaLen = I(r, "FirmaLen")
        };
        for (int k = 0; k < 7; k++) row.Ris[k] = SN(r, "Ris" + (k + 1));
        for (int k = 0; k < 4; k++) { row.TeamNum[k] = I(r, "NumFis" + (k + 1)); row.TeamAtl[k] = SN(r, "Atl" + (k + 1)); }
        return row;
    }

    /// <summary>Tutte le righe assegnate a un PDA (join con Mobiles come fa Fence!).</summary>
    public List<Unit> LoadUnits(string mobile)
    {
        var rows = new List<Row>();
        using (var c = Open())
        {
            using var cmd = new SqlCommand(SelectRows + @"INNER JOIN Mobiles M ON M.Arbitro = G.Arbitro WHERE M.Mobile = @m
                ORDER BY G.Flags, G.MaschioFemmina, G.Categoria, G.Girone, G.ED, G.Ordine", c);
            cmd.Parameters.AddWithValue("@m", mobile);
            using var r = cmd.ExecuteReader();
            while (r.Read()) rows.Add(ReadRow(r));
        }
        var units = Unit.Build(rows);
        using (var c = Open())
            foreach (var u in units) FillSchedule(c, u);
        return units;
    }

    void FillSchedule(SqlConnection c, Unit u)
    {
        try
        {
            // Pedana e orario stanno su Assalti (Fence li imposta con UpdGir4Arb / UpdED4Arb).
            int girone = u.Kind == "team-pool" ? u.GirKey - 1 : u.GirKey;
            using var cmd = new SqlCommand(@"SELECT TOP 1 Pedana, Data FROM Assalti WHERE Categoria=@c AND Flags=@f AND MaschioFemmina=@mf
                AND EliminDirect=@e AND Girone=@g AND (@o = 0 OR Ordine = @o) ORDER BY Ordine", c);
            cmd.Parameters.AddWithValue("@c", u.Cat); cmd.Parameters.AddWithValue("@f", u.Flags); cmd.Parameters.AddWithValue("@mf", u.Mf);
            cmd.Parameters.AddWithValue("@e", u.EdKey); cmd.Parameters.AddWithValue("@g", girone); cmd.Parameters.AddWithValue("@o", u.OrdKey);
            using var r = cmd.ExecuteReader();
            if (r.Read())
            {
                u.Pedana = I(r, "Pedana");
                u.Data = r["Data"] is DBNull ? null : Convert.ToDateTime(r["Data"]);
            }
        }
        catch { /* pedana/orario sono solo informativi */ }
    }

    /// <summary>Rilegge le righe di una unita' dentro una transazione (con UPDLOCK) e limitate all'arbitro del PDA.</summary>
    public List<Row> ReadUnitRows(SqlConnection c, SqlTransaction t, Unit u, int arbitro)
    {
        var rows = new List<Row>();
        using var cmd = new SqlCommand(SelectRows + @"WITH (UPDLOCK, ROWLOCK) WHERE G.Flags=@f AND G.MaschioFemmina=@mf AND G.Categoria=@c
            AND ABS(G.Girone)=@g AND ABS(G.ED)=@e AND (@o = 0 OR G.Ordine=@o) AND G.Arbitro=@a", c, t);
        cmd.Parameters.AddWithValue("@f", u.Flags); cmd.Parameters.AddWithValue("@mf", u.Mf); cmd.Parameters.AddWithValue("@c", u.Cat);
        cmd.Parameters.AddWithValue("@g", u.GirKey); cmd.Parameters.AddWithValue("@e", u.EdKey); cmd.Parameters.AddWithValue("@o", u.OrdKey);
        cmd.Parameters.AddWithValue("@a", arbitro);
        using var r = cmd.ExecuteReader();
        while (r.Read()) rows.Add(ReadRow(r));
        var tmp = Unit.Build(rows).FirstOrDefault(x => x.Id == u.Id);
        return tmp?.Rows ?? new List<Row>();
    }

    public void UpdateRow(SqlConnection c, SqlTransaction t, Row row, string?[] ris, bool eseguito)
    {
        using var cmd = new SqlCommand(@"UPDATE Gironi4Mobile SET Ris1=@r1, Ris2=@r2, Ris3=@r3, Ris4=@r4, Ris5=@r5, Ris6=@r6, Ris7=@r7, Eseguito=@e
            WHERE Flags=@f AND MaschioFemmina=@mf AND Categoria=@c AND Girone=@g AND ED=@ed AND Ordine=@o AND Arbitro=@a", c, t);
        for (int k = 0; k < 7; k++) cmd.Parameters.AddWithValue("@r" + (k + 1), (object?)ris[k] ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@e", eseguito ? 1 : 0);
        AddKey(cmd, row);
        if (cmd.ExecuteNonQuery() != 1) throw new InvalidOperationException("Riga Gironi4Mobile non aggiornata (modificata da Fence! nel frattempo?)");
    }

    static void AddKey(SqlCommand cmd, Row row)
    {
        cmd.Parameters.AddWithValue("@f", row.Flags); cmd.Parameters.AddWithValue("@mf", row.Mf); cmd.Parameters.AddWithValue("@c", row.Cat);
        cmd.Parameters.AddWithValue("@g", row.Girone); cmd.Parameters.AddWithValue("@ed", row.Ed); cmd.Parameters.AddWithValue("@o", row.Ordine);
        cmd.Parameters.AddWithValue("@a", row.Arbitro);
    }

    public void SaveSignature(Row row, byte[] jpeg)
    {
        using var c = Open();
        using var cmd = new SqlCommand(@"UPDATE Gironi4Mobile SET Firma=@fi WHERE Flags=@f AND MaschioFemmina=@mf AND Categoria=@c
            AND Girone=@g AND ED=@ed AND Ordine=@o AND Arbitro=@a", c);
        cmd.Parameters.Add(new SqlParameter("@fi", SqlDbType.VarBinary, -1) { Value = jpeg });
        AddKey(cmd, row);
        if (cmd.ExecuteNonQuery() != 1) throw new InvalidOperationException("Riga non trovata per la firma");
    }

    // ---------- import su Assalti (port di TrattaPunti) ----------
    public void ImportAssalti(SqlConnection c, SqlTransaction t, Unit u, List<Row> rows)
    {
        if (u.IsTeam) return; // gli incontri a squadre restano gestiti da Fence!
        if (u.Kind == "pool")
        {
            for (int i = 0; i < rows.Count; i++)
                for (int j = i + 1; j < rows.Count; j++)
                    ApplyAssalto(c, t, u, 0, u.GirKey, rows[i].NumFis, rows[j].NumFis,
                        rows[i].Ris[j], rows[j].Ris[i], ResultRules.FenceMaxPool);
        }
        else if (u.Kind == "ed" && rows.Count == 2)
        {
            ApplyAssalto(c, t, u, u.EdKey, 0, rows[0].NumFis, rows[1].NumFis, rows[0].Ris[0], rows[1].Ris[0], ResultRules.FenceMaxEd);
        }
    }

    void ApplyAssalto(SqlConnection c, SqlTransaction t, Unit u, int ed, int gir, int f1, int f2, string? ris1, string? ris2, int max)
    {
        int Find(int a, int b)
        {
            using var q = new SqlCommand(@"SELECT Ordine FROM Assalti WHERE Categoria=@c AND Flags=@f AND MaschioFemmina=@mf
                AND EliminDirect=@e AND Girone=@g AND NumFis1=@a AND NumFis2=@b", c, t);
            q.Parameters.AddWithValue("@c", u.Cat); q.Parameters.AddWithValue("@f", u.Flags); q.Parameters.AddWithValue("@mf", u.Mf);
            q.Parameters.AddWithValue("@e", ed); q.Parameters.AddWithValue("@g", gir); q.Parameters.AddWithValue("@a", a); q.Parameters.AddWithValue("@b", b);
            var o = q.ExecuteScalar();
            return o is null or DBNull ? 0 : Convert.ToInt32(o);
        }
        if (Find(f1, f2) < 1)
        {
            (f1, f2) = (f2, f1); (ris1, ris2) = (ris2, ris1);   // Fence! inverte atleti e risultati se l'ordine non coincide
            if (Find(f1, f2) < 1) return;                      // assalto non presente: lo segnala Fence!, non lo inventiamo
        }
        var (v1, v2, s1, s2) = ResultRules.ToAssalto(ris1, ris2, max);
        using var up = new SqlCommand(@"UPDATE Assalti SET Stoccate1=@s1, Stoccate2=@s2, Vittoria1=@v1, Vittoria2=@v2
            WHERE Categoria=@c AND Flags=@f AND MaschioFemmina=@mf AND EliminDirect=@e AND Girone=@g AND NumFis1=@a AND NumFis2=@b", c, t);
        up.Parameters.AddWithValue("@s1", s1); up.Parameters.AddWithValue("@s2", s2); up.Parameters.AddWithValue("@v1", v1); up.Parameters.AddWithValue("@v2", v2);
        up.Parameters.AddWithValue("@c", u.Cat); up.Parameters.AddWithValue("@f", u.Flags); up.Parameters.AddWithValue("@mf", u.Mf);
        up.Parameters.AddWithValue("@e", ed); up.Parameters.AddWithValue("@g", gir); up.Parameters.AddWithValue("@a", f1); up.Parameters.AddWithValue("@b", f2);
        up.ExecuteNonQuery();
    }

    // ---------- limiti stoccate e nomi categoria ----------
    public (int gir, int ed, int sq) Formula(int cat, int flags, int mf)
    {
        var key = $"{cat}.{flags}.{mf}";
        lock (_cache) if (_formula.TryGetValue(key, out var v)) return v;
        var res = (gir: ResultRules.FenceMaxPool, ed: ResultRules.FenceMaxEd, sq: ResultRules.FenceMaxTeam);
        try
        {
            using var c = Open();
            using var cmd = new SqlCommand("SELECT TOP 1 StoccateGironi, StoccateED, StoccateSquadre FROM FormulaGara WHERE CatAtleti=@c AND Flags=@f AND MaschioFemmina=@mf", c);
            cmd.Parameters.AddWithValue("@c", cat); cmd.Parameters.AddWithValue("@f", flags); cmd.Parameters.AddWithValue("@mf", mf);
            using var r = cmd.ExecuteReader();
            if (r.Read())
            {
                int g = I(r, "StoccateGironi"), e = I(r, "StoccateED"), s = I(r, "StoccateSquadre");
                res = (g > 0 ? g : res.gir, e > 0 ? e : res.ed, s > 0 ? s : res.sq);
            }
        }
        catch { /* si usano i limiti storici di Fence! */ }
        lock (_cache) _formula[key] = res;
        return res;
    }

    public string CategoryName(int cat, int mf)
    {
        lock (_cache) if (_cat.TryGetValue(cat * 10 + mf, out var v)) return v;
        string name = "Cat. " + cat;
        try
        {
            using var c = Open();
            using var cmd = new SqlCommand("SELECT TOP 1 Denominazione FROM CategAtleti WHERE CodCat=@c", c);
            cmd.Parameters.AddWithValue("@c", cat);
            if (cmd.ExecuteScalar() is string d && d.Length > 0)
            {
                int ix = d.IndexOf('/');
                name = ix < 0 ? d.Trim() : (mf == 0 ? d[..ix] : d[(ix + 1)..]).Trim();
            }
        }
        catch { }
        lock (_cache) _cat[cat * 10 + mf] = name;
        return name;
    }

    // ---------- messaggi (tabella Messaggi) ----------
    // Semantica ricavata da frmMessages: dal PDA al tavolo => OraInvio NULL, OraRx = ora di invio;
    // dal tavolo al PDA => OraInvio = ora invio, OraRx NULL finche' il PDA non conferma.
    public List<object> Messages(string mobile)
    {
        var list = new List<object>();
        using var c = Open();
        using var cmd = new SqlCommand(@"SELECT TOP 50 Prog, OraInvio, OraRx, Testo FROM Messaggi WHERE Mobile=@m AND OraInvio IS NOT NULL
            AND (OraRx IS NULL OR OraRx > OraInvio) ORDER BY Prog DESC", c);
        cmd.Parameters.AddWithValue("@m", mobile);
        using var r = cmd.ExecuteReader();
        while (r.Read())
            list.Add(new { prog = I(r, "Prog"), testo = S(r, "Testo"), oraInvio = Convert.ToDateTime(r["OraInvio"]).ToString("o"), letto = r["OraRx"] is not DBNull });
        return list;
    }

    public void SendMessage(string mobile, string testo)
    {
        using var c = Open();
        using var cmd = new SqlCommand("INSERT INTO Messaggi (Mobile, OraInvio, OraRx, Testo) VALUES (@m, NULL, GETDATE(), @t)", c);
        cmd.Parameters.AddWithValue("@m", mobile); cmd.Parameters.AddWithValue("@t", testo.Length > 500 ? testo[..500] : testo);
        cmd.ExecuteNonQuery();
    }

    public void AckMessage(string mobile, int prog)
    {
        using var c = Open();
        using var cmd = new SqlCommand("UPDATE Messaggi SET OraRx = GETDATE() WHERE Mobile=@m AND Prog=@p AND OraRx IS NULL", c);
        cmd.Parameters.AddWithValue("@m", mobile); cmd.Parameters.AddWithValue("@p", prog);
        cmd.ExecuteNonQuery();
    }

    public int PendingMessages(string mobile)
    {
        try
        {
            using var c = Open();
            using var cmd = new SqlCommand("SELECT COUNT(*) FROM Messaggi WHERE Mobile=@m AND OraInvio IS NOT NULL AND OraRx IS NULL", c);
            cmd.Parameters.AddWithValue("@m", mobile);
            return Convert.ToInt32(cmd.ExecuteScalar());
        }
        catch { return 0; }
    }
}

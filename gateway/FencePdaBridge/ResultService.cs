using System.Text.Json;
using Microsoft.Data.SqlClient;

namespace FencePdaBridge;

public sealed class ResultException : Exception
{
    public int Status { get; }
    public object? Body { get; }
    public ResultException(int status, string message, object? body = null) : base(message) { Status = status; Body = body; }
}

public sealed class ResultService
{
    static readonly object WriteGate = new();
    readonly FenceRepo _repo; readonly Store _store; readonly BridgeOptions _opt;
    public ResultService(FenceRepo repo, Store store, BridgeOptions opt) { _repo = repo; _store = store; _opt = opt; }

    public UnitDto ToDto(Unit u)
    {
        var f = _repo.Formula(u.Cat, u.Flags, u.Mf);
        int max = u.Kind switch { "pool" => f.gir, "ed" => f.ed, _ => f.sq };
        int fence = u.Kind switch { "pool" => ResultRules.FenceMaxPool, "ed" => ResultRules.FenceMaxEd, _ => ResultRules.FenceMaxTeam };
        var athletes = u.Rows.Select(r => new AthleteDto(r.Ordine, r.NumFis, r.Nome, r.Cognome, r.Sigla, r.CodSoc, r.Ris, r.FirmaLen > 0,
            Enumerable.Range(0, 4).Where(k => r.TeamNum[k] > 0 || !string.IsNullOrWhiteSpace(r.TeamAtl[k]))
                .Select(k => new MemberDto(r.TeamNum[k], (r.TeamAtl[k] ?? "").Trim())).ToList())).ToList();
        int gir = u.Kind == "pool" ? u.GirKey + 1 : u.GirKey;
        return new UnitDto(u.Id, u.Kind, u.Title, u.Flags, u.Mf, u.Cat, _repo.CategoryName(u.Cat, u.Mf), u.Weapon, gir, u.EdKey, u.OrdKey,
            u.Pedana, u.Data?.ToString("o"), max, fence, u.Status, u.Rev, athletes);
    }

    static int Key(Unit u, Row r, int idx) => u.IndividualKeys ? r.NumFis : idx + 1;

    /// <summary>Costruisce Ris1..Ris7 di ogni riga a partire dalle coppie inviate dal telefono, associando per NumFis (non per posizione).</summary>
    public static List<string?[]> BuildRis(Unit u, List<Row> rows, List<PairCell> cells, int max, bool final)
    {
        int n = rows.Count;
        if (u.Kind == "pool" && n > 7) throw new ResultException(422, "Girone con piu' di 7 atleti: il tracciato Gironi4Mobile (Ris1..Ris7) non lo supporta");
        if (u.Kind != "pool" && n != 2) throw new ResultException(409, "L'incontro non ha esattamente due lati in Gironi4Mobile");
        var keys = rows.Select((r, i) => Key(u, r, i)).ToList();
        foreach (var c in cells)
            if (!keys.Contains(c.A) || !keys.Contains(c.B) || c.A == c.B)
                throw new ResultException(409, "Gli atleti inviati non corrispondono a quelli del database (modificati da Fence!?)");

        string? Get(int a, int b)
        {
            foreach (var c in cells)
            {
                if (c.A == a && c.B == b) return ResultRules.Normalize(c.Ca);
                if (c.A == b && c.B == a) return ResultRules.Normalize(c.Cb);
            }
            return null;
        }

        var result = rows.Select(_ => new string?[7]).ToList();
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
            {
                var a = Get(keys[i], keys[j]); var b = Get(keys[j], keys[i]);
                var err = ResultRules.ValidatePair(a, b, max, final);
                if (err != null) throw new ResultException(422, $"{err} ({rows[i].Cognome} - {rows[j].Cognome})");
                // un codice speciale su un lato: l'avversario riceve "0" (Fence! legge solo il lato con A/R/E)
                if (ResultRules.IsSpecial(a) && b == null) b = "0";
                if (ResultRules.IsSpecial(b) && a == null) a = "0";
                if (u.Kind == "pool") { result[i][j] = a; result[j][i] = b; }
                else { result[i][0] = a; result[j][0] = b; }
            }
        return result;
    }

    public UnitDto Apply(DeviceRec dev, string mobile, ResultRequest req)
    {
        lock (WriteGate)
        {
            var arb = _repo.GetArbiter(mobile) ?? throw new ResultException(403, "PDA non associato a nessun arbitro in Fence! (PrepArbPda)");
            var unit = _repo.LoadUnits(mobile).FirstOrDefault(x => x.Id == req.UnitId)
                       ?? throw new ResultException(403, "Incontro non assegnato a questo PDA");
            var dto0 = ToDto(unit);
            int max = dto0.MaxTouches;

            using var c = _repo.Open();
            using var t = c.BeginTransaction();
            try
            {
                var rows = _repo.ReadUnitRows(c, t, unit, arb.NumFis);
                if (rows.Count == 0) throw new ResultException(409, "Incontro non piu' presente in Gironi4Mobile");
                unit.Rows = rows;
                var current = unit.Rev;

                if (!string.IsNullOrEmpty(req.ExpectedRev) && req.ExpectedRev != current && !req.Overwrite)
                    throw new ResultException(409, "Il risultato e' stato modificato da Fence! o da un altro dispositivo", ToDto(unit));

                bool alreadyFinal = rows.All(r => r.Eseguito);
                if (alreadyFinal && !req.Overwrite)
                    throw new ResultException(409, "Risultato gia' contabilizzato: serve una correzione autorizzata", ToDto(unit));
                if (req.Overwrite)
                {
                    if (string.IsNullOrWhiteSpace(_opt.SupervisorPin)) throw new ResultException(403, "Correzioni disabilitate: impostare SupervisorPin nel gateway");
                    if (req.SupervisorPin != _opt.SupervisorPin) throw new ResultException(403, "PIN responsabile errato");
                    if (string.IsNullOrWhiteSpace(req.Reason)) throw new ResultException(422, "Motivo della correzione obbligatorio");
                }

                var ris = BuildRis(unit, rows, req.Cells, max, req.Final);
                for (int i = 0; i < rows.Count; i++) _repo.UpdateRow(c, t, rows[i], ris[i], req.Final);
                for (int i = 0; i < rows.Count; i++) { rows[i].Ris = ris[i]; rows[i].Eseguito = req.Final; }

                if (req.Final && _opt.AutoImportAssalti) _repo.ImportAssalti(c, t, unit, rows);
                t.Commit();

                _store.Audit(dev.Id, req.Overwrite ? "result-correction" : (req.Final ? "result-final" : "result-partial"),
                    JsonSerializer.Serialize(new { req.UnitId, arbitro = arb.NumFis, req.Reason, req.Notes, req.Extra, before = current, after = unit.Rev }));
                return ToDto(unit);
            }
            catch { try { t.Rollback(); } catch { } throw; }
        }
    }

    public UnitDto SaveSignature(DeviceRec dev, string mobile, SignatureRequest req)
    {
        lock (WriteGate)
        {
            var arb = _repo.GetArbiter(mobile) ?? throw new ResultException(403, "PDA non associato");
            var unit = _repo.LoadUnits(mobile).FirstOrDefault(x => x.Id == req.UnitId) ?? throw new ResultException(403, "Incontro non assegnato a questo PDA");
            if (req.AthleteIndex < 0 || req.AthleteIndex >= unit.Rows.Count) throw new ResultException(422, "Indice atleta non valido");
            byte[] jpeg;
            try { jpeg = Convert.FromBase64String(req.JpegBase64); } catch { throw new ResultException(422, "Firma non valida"); }
            if (jpeg.Length < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8) throw new ResultException(422, "La firma deve essere un JPEG");
            if (jpeg.Length > 512 * 1024) throw new ResultException(422, "Firma troppo grande");
            _repo.SaveSignature(unit.Rows[req.AthleteIndex], jpeg);
            _store.Audit(dev.Id, "signature", $"{req.UnitId} atleta#{req.AthleteIndex} {jpeg.Length}B");
            return ToDto(_repo.LoadUnits(mobile).First(x => x.Id == req.UnitId));
        }
    }
}

using System.Security.Cryptography;
using System.Text;

namespace FencePdaBridge;

public sealed class BridgeOptions
{
    public int Port { get; set; } = 8765;
    public string SqlConnection { get; set; } =
        @"Data Source=(local)\FISDOTNET;Initial Catalog=Ges1Gara;Integrated Security=True;Encrypt=False;TrustServerCertificate=True";
    /// <summary>Se true il gateway applica anche TrattaPunti su Assalti (come il pulsante di Fence! in TxRx PDA).</summary>
    public bool AutoImportAssalti { get; set; }
    /// <summary>PIN del responsabile per le correzioni di risultati gia' trasmessi. Vuoto = correzioni disabilitate.</summary>
    public string SupervisorPin { get; set; } = "";
    public string PublicHost { get; set; } = "";
    public int PairCodeMinutes { get; set; } = 10;
}

/// <summary>Riga della tabella Gironi4Mobile cosi' come la usa Fence!.</summary>
public sealed class Row
{
    public int Flags, Mf, Cat, Girone, Ed, Ordine, NumFis, Arbitro, CodSoc;
    public string Nome = "", Cognome = "", Sigla = "";
    public string?[] Ris = new string?[7];
    public bool Eseguito;
    public int FirmaLen;
    public int[] TeamNum = new int[4];
    public string?[] TeamAtl = new string?[4];
    /// <summary>0 = primo lato (ED/Girone negativo), 1 = secondo lato.</summary>
    public int Side => (Ed < 0 || Girone < 0) ? 0 : 1;
}

/// <summary>Un girone (kind=pool) oppure un singolo assalto/incontro (ed, team-ed, team-pool).</summary>
public sealed class Unit
{
    public string Id = "", Kind = "";
    public int Flags, Mf, Cat, GirKey, EdKey, OrdKey;
    public int Pedana;
    public DateTime? Data;
    public List<Row> Rows = new();

    public bool IsTeam => Flags > 32;
    public bool IndividualKeys => Kind == "pool" || Kind == "ed";

    public string Weapon => Flags switch
    {
        8 or 64 => "Fioretto",
        16 or 128 => "Spada",
        32 or 256 => "Sciabola",
        _ => "Arma " + Flags
    };

    public string Title => Kind switch
    {
        "pool" => $"Girone {GirKey + 1}",
        "ed" => $"ED {EdKey} · assalto {OrdKey}",
        "team-pool" => $"Girone {GirKey} · incontro {OrdKey}",
        _ => $"ED {EdKey} · incontro {OrdKey}",
    };

    public string Status
    {
        get
        {
            if (Rows.Count > 0 && Rows.All(r => r.Eseguito)) return "done";
            if (Rows.Any(r => r.Ris.Any(x => x != null))) return "progress";
            return "todo";
        }
    }

    public string Rev
    {
        get
        {
            var sb = new StringBuilder();
            foreach (var r in Rows)
            {
                sb.Append(r.Ordine).Append('|').Append(r.NumFis).Append('|');
                foreach (var x in r.Ris) sb.Append(x ?? "~").Append(',');
                sb.Append(r.Eseguito ? 1 : 0).Append('|').Append(r.FirmaLen).Append(';');
            }
            return Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(sb.ToString())))[..12].ToLowerInvariant();
        }
    }

    public static List<Unit> Build(IEnumerable<Row> rows)
    {
        var map = new Dictionary<string, Unit>();
        foreach (var r in rows)
        {
            bool team = r.Flags > 32;
            string kind; int gk, ek, ok;
            if (r.Ed != 0) { kind = team ? "team-ed" : "ed"; gk = 0; ek = Math.Abs(r.Ed); ok = r.Ordine; }
            else if (team) { kind = "team-pool"; gk = Math.Abs(r.Girone); ek = 0; ok = r.Ordine; }
            else { kind = "pool"; gk = r.Girone; ek = 0; ok = 0; }
            var id = $"{r.Flags}.{r.Mf}.{r.Cat}.{gk}.{ek}.{ok}";
            if (!map.TryGetValue(id, out var u))
            {
                u = new Unit { Id = id, Kind = kind, Flags = r.Flags, Mf = r.Mf, Cat = r.Cat, GirKey = gk, EdKey = ek, OrdKey = ok };
                map[id] = u;
            }
            u.Rows.Add(r);
        }
        foreach (var u in map.Values)
            u.Rows = u.Kind == "pool"
                ? u.Rows.OrderBy(r => r.Ordine).ToList()
                : u.Rows.OrderBy(r => r.Side).ToList();
        return map.Values.OrderBy(u => u.Flags).ThenBy(u => u.Mf).ThenBy(u => u.Cat)
            .ThenBy(u => u.GirKey).ThenBy(u => u.EdKey).ThenBy(u => u.OrdKey).ToList();
    }
}

// ---------- DTO JSON (camelCase) ----------
public record MemberDto(int NumFis, string Nome);
public record AthleteDto(int Pos, int NumFis, string Nome, string Cognome, string Sigla, int CodSoc,
    string?[] Ris, bool HasFirma, List<MemberDto> Members);
public record UnitDto(string Id, string Kind, string Title, int Flags, int Mf, int Cat, string CatName, string Weapon,
    int Girone, int Ed, int Ordine, int Pedana, string? Data, int MaxTouches, int FenceMax,
    string Status, string Rev, List<AthleteDto> Athletes);
public record ArbiterDto(int NumFis, string Cognome, string Nome, int Pedana);

public record PairRequest(string Code, string DeviceId, string? Model);
public record PairCell(int A, int B, string? Ca, string? Cb);
public record ResultRequest(string TxId, string UnitId, bool Final, string? ExpectedRev, bool Overwrite,
    string? Reason, string? SupervisorPin, string? Notes, string? Extra, List<PairCell> Cells);
public record SignatureRequest(string TxId, string UnitId, int AthleteIndex, string JpegBase64);
public record HeartbeatRequest(string? AppVersion, int? Battery, int? Pending);
public record MessageRequest(string TxId, string Testo);
public record AckRequest(int Prog);

/// <summary>Risposta JSON gia' serializzata con codice di stato (usata per le risposte idempotenti).</summary>
public sealed class RawJson : Microsoft.AspNetCore.Http.IResult
{
    readonly int _status; readonly string _body;
    public RawJson(int status, string body) { _status = status; _body = body; }
    public Task ExecuteAsync(Microsoft.AspNetCore.Http.HttpContext ctx)
    {
        ctx.Response.StatusCode = _status;
        ctx.Response.ContentType = "application/json; charset=utf-8";
        return ctx.Response.WriteAsync(_body);
    }
}

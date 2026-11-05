using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;

namespace FencePdaBridge;

public sealed record DeviceRec(string Id, string Model, string PairedAt, string LastSeen, bool Revoked, string AppVersion, int Battery);

/// <summary>Database interno del gateway (SQLite). Non tocca mai le tabelle di Fence!.</summary>
public sealed class Store
{
    readonly string _cs;
    readonly object _gate = new();

    public Store(string path)
    {
        _cs = new SqliteConnectionStringBuilder { DataSource = path }.ToString();
        Exec(@"CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, token_hash TEXT NOT NULL, model TEXT, paired_at TEXT, last_seen TEXT,
                 revoked INTEGER NOT NULL DEFAULT 0, app_version TEXT, battery INTEGER);
               CREATE TABLE IF NOT EXISTS pair_codes(code TEXT PRIMARY KEY, expires TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0);
               CREATE TABLE IF NOT EXISTS tx(tx_id TEXT PRIMARY KEY, device_id TEXT, status INTEGER, body TEXT, at TEXT);
               CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, device_id TEXT, action TEXT, detail TEXT);");
    }

    static string Now() => DateTime.UtcNow.ToString("o");
    public static string Hash(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s)));

    void Exec(string sql, params (string, object?)[] p)
    {
        lock (_gate)
        {
            using var c = new SqliteConnection(_cs); c.Open();
            using var cmd = c.CreateCommand(); cmd.CommandText = sql;
            foreach (var (k, v) in p) cmd.Parameters.AddWithValue(k, v ?? DBNull.Value);
            cmd.ExecuteNonQuery();
        }
    }

    T Query<T>(string sql, Func<SqliteDataReader, T> map, params (string, object?)[] p)
    {
        lock (_gate)
        {
            using var c = new SqliteConnection(_cs); c.Open();
            using var cmd = c.CreateCommand(); cmd.CommandText = sql;
            foreach (var (k, v) in p) cmd.Parameters.AddWithValue(k, v ?? DBNull.Value);
            using var r = cmd.ExecuteReader();
            return map(r);
        }
    }

    public string CreatePairCode(int minutes)
    {
        const string alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var bytes = RandomNumberGenerator.GetBytes(10);
        var code = new string(bytes.Select(b => alphabet[b % alphabet.Length]).ToArray());
        Exec("INSERT INTO pair_codes(code,expires,used) VALUES(@c,@e,0)", ("@c", code), ("@e", DateTime.UtcNow.AddMinutes(minutes).ToString("o")));
        Exec("DELETE FROM pair_codes WHERE expires < @n", ("@n", DateTime.UtcNow.AddDays(-1).ToString("o")));
        return code;
    }

    /// <summary>Codice monouso con scadenza: lo consuma se valido.</summary>
    public bool ConsumePairCode(string code)
    {
        lock (_gate)
        {
            using var c = new SqliteConnection(_cs); c.Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = "UPDATE pair_codes SET used=1 WHERE code=@c AND used=0 AND expires > @n";
            cmd.Parameters.AddWithValue("@c", (code ?? "").Trim().ToUpperInvariant());
            cmd.Parameters.AddWithValue("@n", Now());
            return cmd.ExecuteNonQuery() == 1;
        }
    }

    public string RegisterDevice(string id, string? model)
    {
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        Exec(@"INSERT INTO devices(id,token_hash,model,paired_at,last_seen,revoked) VALUES(@i,@h,@m,@p,@p,0)
               ON CONFLICT(id) DO UPDATE SET token_hash=@h, model=@m, paired_at=@p, revoked=0",
            ("@i", id), ("@h", Hash(token)), ("@m", model ?? ""), ("@p", Now()));
        return token;
    }

    public DeviceRec? Authenticate(string id, string token)
    {
        var h = Hash(token);
        return Query<DeviceRec?>("SELECT id,model,paired_at,last_seen,revoked,app_version,battery,token_hash FROM devices WHERE id=@i", r =>
        {
            if (!r.Read()) return null;
            var stored = r.GetString(7);
            if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(stored), Encoding.ASCII.GetBytes(h))) return null;
            var rec = Map(r);
            return rec.Revoked ? null : rec;
        }, ("@i", id));
    }

    static DeviceRec Map(SqliteDataReader r) => new(r.GetString(0), r.IsDBNull(1) ? "" : r.GetString(1),
        r.IsDBNull(2) ? "" : r.GetString(2), r.IsDBNull(3) ? "" : r.GetString(3), r.GetInt32(4) == 1,
        r.IsDBNull(5) ? "" : r.GetString(5), r.IsDBNull(6) ? 0 : r.GetInt32(6));

    public List<DeviceRec> ListDevices() => Query("SELECT id,model,paired_at,last_seen,revoked,app_version,battery FROM devices ORDER BY paired_at DESC",
        r => { var l = new List<DeviceRec>(); while (r.Read()) l.Add(Map(r)); return l; });

    public void Touch(string id, string? ver, int? battery) =>
        Exec("UPDATE devices SET last_seen=@n, app_version=COALESCE(@v,app_version), battery=COALESCE(@b,battery) WHERE id=@i",
            ("@n", Now()), ("@v", ver), ("@b", battery), ("@i", id));

    public void Revoke(string id) => Exec("UPDATE devices SET revoked=1 WHERE id=@i", ("@i", id));

    public (int status, string body)? GetTx(string txId) =>
        Query("SELECT status,body FROM tx WHERE tx_id=@t", r => r.Read() ? ((int, string)?)(r.GetInt32(0), r.GetString(1)) : null, ("@t", txId));

    public void SaveTx(string txId, string deviceId, int status, string body) =>
        Exec("INSERT OR REPLACE INTO tx(tx_id,device_id,status,body,at) VALUES(@t,@d,@s,@b,@a)",
            ("@t", txId), ("@d", deviceId), ("@s", status), ("@b", body), ("@a", Now()));

    public void Audit(string? deviceId, string action, string detail) =>
        Exec("INSERT INTO audit(at,device_id,action,detail) VALUES(@a,@d,@x,@t)", ("@a", Now()), ("@d", deviceId), ("@x", action), ("@t", detail));

    public List<(string at, string dev, string action, string detail)> RecentAudit(int n) =>
        Query("SELECT at,COALESCE(device_id,''),action,COALESCE(detail,'') FROM audit ORDER BY id DESC LIMIT @n", r =>
        { var l = new List<(string, string, string, string)>(); while (r.Read()) l.Add((r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3))); return l; }, ("@n", n));
}

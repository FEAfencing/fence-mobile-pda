using System.Net;
using System.Text;
using System.Text.Json;
using FencePdaBridge;
using Microsoft.AspNetCore.Http.Json;
using QRCoder;

if (args.Contains("--install") || args.Contains("--uninstall")) return ServiceInstaller.Execute(args);

var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "FencePdaBridge");
Directory.CreateDirectory(dataDir);

var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = args, ContentRootPath = AppContext.BaseDirectory });
builder.Configuration.AddJsonFile(Path.Combine(dataDir, "bridge.json"), optional: true, reloadOnChange: false);
builder.Host.UseWindowsService(o => o.ServiceName = "FencePdaBridge");

var opt = builder.Configuration.GetSection("Bridge").Get<BridgeOptions>() ?? new BridgeOptions();
var cert = CertificateService.LoadOrCreate(dataDir);
var fingerprint = CertificateService.Fingerprint(cert);
int adminPort = opt.Port + 1;

builder.WebHost.ConfigureKestrel(k =>
{
    k.ListenAnyIP(opt.Port, l => l.UseHttps(cert));   // API per gli smartphone (HTTPS)
    k.ListenLocalhost(adminPort);                      // pannello operatore: solo questo computer
});
builder.Services.Configure<JsonOptions>(o => o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);

var store = new Store(Path.Combine(dataDir, "bridge.db"));
var repo = new FenceRepo(opt.SqlConnection);
var results = new ResultService(repo, store, opt);
builder.Services.AddSingleton(opt).AddSingleton(store).AddSingleton(repo).AddSingleton(results);

var app = builder.Build();
const string Version = "1.0.0";

// separazione delle porte: API solo su HTTPS, admin solo su loopback
app.Use(async (ctx, next) =>
{
    var path = ctx.Request.Path.Value ?? "";
    bool isAdminPort = ctx.Connection.LocalPort == adminPort;
    if ((path.StartsWith("/admin") && !isAdminPort) || (path.StartsWith("/api") && isAdminPort) || (path == "/" && !isAdminPort))
    { ctx.Response.StatusCode = 404; return; }
    await next();
});

IResult Fail(ResultException e) => Results.Json(new { ok = false, error = e.Message, unit = e.Body }, statusCode: e.Status);

// ---------------- pairing (senza token: serve il codice monouso del QR) ----------------
app.MapPost("/api/v1/pair", (PairRequest req) =>
{
    if (!System.Text.RegularExpressions.Regex.IsMatch(req.DeviceId ?? "", "^PDA-[0-9A-F]{6}$"))
        return Results.Json(new { ok = false, error = "Identificativo PDA non valido" }, statusCode: 422);
    if (!store.ConsumePairCode(req.Code))
        return Results.Json(new { ok = false, error = "Codice di associazione non valido, scaduto o gia' usato" }, statusCode: 403);
    try { repo.EnsurePda(req.DeviceId!); }
    catch (Exception ex) { return Results.Json(new { ok = false, error = "Database Fence!: " + ex.Message }, statusCode: 503); }
    var token = store.RegisterDevice(req.DeviceId!, req.Model);
    store.Audit(req.DeviceId, "pair", req.Model ?? "");
    return Results.Ok(new { ok = true, token, deviceId = req.DeviceId, mobile = req.DeviceId, fingerprint });
});

// ---------------- API autenticate ----------------
var secured = app.MapGroup("/api/v1").AddEndpointFilter(async (ctx, next) =>
{
    var http = ctx.HttpContext;
    var id = http.Request.Headers["X-Pda-Id"].ToString();
    var auth = http.Request.Headers.Authorization.ToString();
    var token = auth.StartsWith("Bearer ") ? auth[7..] : "";
    var dev = string.IsNullOrEmpty(id) || token.Length == 0 ? null : store.Authenticate(id, token);
    if (dev == null) return Results.Json(new { ok = false, error = "Dispositivo non autorizzato o revocato" }, statusCode: 401);
    http.Items["dev"] = dev;
    return await next(ctx);
});
static DeviceRec Dev(HttpContext c) => (DeviceRec)c.Items["dev"]!;

object DeviceInfo(DeviceRec d)
{
    ArbiterDto? arb = null; string? dbErr = null;
    try { arb = repo.GetArbiter(d.Id); } catch (Exception ex) { dbErr = ex.Message; }
    return new { deviceId = d.Id, mobile = d.Id, arbiter = arb, bridgeVersion = Version, serverTime = DateTime.UtcNow.ToString("o"), dbError = dbErr };
}

secured.MapGet("/device", (HttpContext c) => Results.Ok(DeviceInfo(Dev(c))));

secured.MapGet("/assignments", (HttpContext c) =>
{
    var d = Dev(c);
    try
    {
        var units = repo.LoadUnits(d.Id);
        return Results.Ok(new { arbiter = repo.GetArbiter(d.Id), units = units.Select(u => new { u.Id, u.Title, status = u.Status, rev = u.Rev }) });
    }
    catch (Exception ex) { return Results.Json(new { ok = false, error = ex.Message }, statusCode: 503); }
});

secured.MapGet("/bouts", (HttpContext c) =>
{
    try { return Results.Ok(repo.LoadUnits(Dev(c).Id).Select(results.ToDto)); }
    catch (Exception ex) { return Results.Json(new { ok = false, error = ex.Message }, statusCode: 503); }
});

secured.MapGet("/bouts/{id}", (HttpContext c, string id) =>
{
    var u = repo.LoadUnits(Dev(c).Id).FirstOrDefault(x => x.Id == id);
    return u == null ? Results.Json(new { ok = false, error = "Incontro non assegnato" }, statusCode: 404) : Results.Ok(results.ToDto(u));
});

// sincronizzazione incrementale: se la revisione coincide non si scarica nulla
secured.MapGet("/sync", (HttpContext c, string? rev) =>
{
    var d = Dev(c);
    store.Touch(d.Id, null, null);
    try
    {
        var units = repo.LoadUnits(d.Id);
        var arb = repo.GetArbiter(d.Id);
        int msgs = repo.PendingMessages(d.Id);
        var all = Convert.ToHexString(System.Security.Cryptography.SHA1.HashData(Encoding.UTF8.GetBytes(
            string.Join(";", units.Select(u => u.Id + u.Rev)) + "|" + (arb?.NumFis ?? 0) + "|" + msgs)))[..12].ToLowerInvariant();
        if (rev == all) return Results.Ok(new { changed = false, rev = all, serverTime = DateTime.UtcNow.ToString("o") });
        return Results.Ok(new { changed = true, rev = all, serverTime = DateTime.UtcNow.ToString("o"), arbiter = arb, pendingMessages = msgs, units = units.Select(results.ToDto) });
    }
    catch (Exception ex) { return Results.Json(new { ok = false, error = ex.Message }, statusCode: 503); }
});

// trasmissione risultato (idempotente per txId)
secured.MapPost("/results", (HttpContext c, ResultRequest req) =>
{
    var d = Dev(c);
    if (string.IsNullOrWhiteSpace(req.TxId)) return Results.Json(new { ok = false, error = "txId mancante" }, statusCode: 422);
    var prev = store.GetTx(req.TxId);
    if (prev != null) return new RawJson(prev.Value.status, prev.Value.body);
    int status; string body;
    try
    {
        var unit = results.Apply(d, d.Id, req);
        status = 200; body = JsonSerializer.Serialize(new { ok = true, unit }, new JsonSerializerOptions(JsonSerializerDefaults.Web));
    }
    catch (ResultException e)
    {
        status = e.Status; body = JsonSerializer.Serialize(new { ok = false, error = e.Message, unit = e.Body }, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        store.Audit(d.Id, "result-rejected", $"{req.UnitId}: {e.Message}");
    }
    catch (Exception ex)
    {
        store.Audit(d.Id, "result-error", $"{req.UnitId}: {ex.Message}");
        return Results.Json(new { ok = false, error = "Errore database: " + ex.Message }, statusCode: 503); // transitorio: il telefono riprova
    }
    if (status != 503) store.SaveTx(req.TxId, d.Id, status, body);
    return new RawJson(status, body);
});

secured.MapPost("/signatures", (HttpContext c, SignatureRequest req) =>
{
    var d = Dev(c);
    var prev = string.IsNullOrWhiteSpace(req.TxId) ? null : store.GetTx(req.TxId);
    if (prev != null) return new RawJson(prev.Value.status, prev.Value.body);
    try
    {
        var unit = results.SaveSignature(d, d.Id, req);
        var body = JsonSerializer.Serialize(new { ok = true, unit }, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        if (!string.IsNullOrWhiteSpace(req.TxId)) store.SaveTx(req.TxId, d.Id, 200, body);
        return new RawJson(200, body);
    }
    catch (ResultException e) { return Fail(e); }
    catch (Exception ex) { return Results.Json(new { ok = false, error = "Errore database: " + ex.Message }, statusCode: 503); }
});

secured.MapPost("/heartbeat", (HttpContext c, HeartbeatRequest hb) =>
{
    store.Touch(Dev(c).Id, hb.AppVersion, hb.Battery);
    return Results.Ok(new { ok = true, serverTime = DateTime.UtcNow.ToString("o") });
});

secured.MapPost("/unpair", (HttpContext c) =>
{
    var d = Dev(c);
    store.Revoke(d.Id);
    store.Audit(d.Id, "unpair", "richiesto dal dispositivo");
    return Results.Ok(new { ok = true });
});

secured.MapGet("/messages", (HttpContext c) =>
{
    try { return Results.Ok(repo.Messages(Dev(c).Id)); }
    catch (Exception ex) { return Results.Json(new { ok = false, error = ex.Message }, statusCode: 503); }
});
secured.MapPost("/messages", (HttpContext c, MessageRequest m) =>
{
    var d = Dev(c);
    if (string.IsNullOrWhiteSpace(m.Testo)) return Results.Json(new { ok = false, error = "Messaggio vuoto" }, statusCode: 422);
    if (!string.IsNullOrWhiteSpace(m.TxId) && store.GetTx(m.TxId) != null) return Results.Ok(new { ok = true, duplicate = true });
    try { repo.SendMessage(d.Id, m.Testo); }
    catch (Exception ex) { return Results.Json(new { ok = false, error = ex.Message }, statusCode: 503); }
    if (!string.IsNullOrWhiteSpace(m.TxId)) store.SaveTx(m.TxId, d.Id, 200, "{\"ok\":true}");
    store.Audit(d.Id, "message", m.Testo);
    return Results.Ok(new { ok = true });
});
secured.MapPost("/messages/ack", (HttpContext c, AckRequest a) =>
{
    try { repo.AckMessage(Dev(c).Id, a.Prog); return Results.Ok(new { ok = true }); }
    catch (Exception ex) { return Results.Json(new { ok = false, error = ex.Message }, statusCode: 503); }
});

// ---------------- pannello operatore (solo localhost) ----------------
app.MapGet("/", () => Results.Redirect("/admin"));
app.MapGet("/admin", () => Results.Content(AdminPage.Html, "text/html", Encoding.UTF8));
app.MapGet("/admin/api/status", () => Results.Ok(new
{
    version = Version, port = opt.Port, fingerprint, autoImport = opt.AutoImportAssalti, supervisorPinSet = !string.IsNullOrEmpty(opt.SupervisorPin),
    ips = CertificateService.LocalIPv4().Select(i => i.ToString()), db = repo.Diagnose()
}));
app.MapGet("/admin/api/devices", () => Results.Ok(store.ListDevices()));
app.MapPost("/admin/api/revoke/{id}", (string id) => { store.Revoke(id); store.Audit(id, "revoke", "operatore"); return Results.Ok(new { ok = true }); });
app.MapGet("/admin/api/audit", () => Results.Ok(store.RecentAudit(100).Select(a => new { a.at, a.dev, a.action, a.detail })));
app.MapGet("/admin/qr.png", (string? ip) =>
{
    var host = !string.IsNullOrWhiteSpace(opt.PublicHost) ? opt.PublicHost
        : !string.IsNullOrWhiteSpace(ip) ? ip : (CertificateService.LocalIPv4().FirstOrDefault()?.ToString() ?? "127.0.0.1");
    var code = store.CreatePairCode(opt.PairCodeMinutes);
    var payload = JsonSerializer.Serialize(new { u = $"https://{host}:{opt.Port}", fp = fingerprint, c = code });
    using var gen = new QRCodeGenerator();
    using var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
    var png = new PngByteQRCode(data).GetGraphic(8);
    return Results.File(png, "image/png");
});

app.Logger.LogInformation("FencePdaBridge {Version} — API https porta {Port}, pannello http://localhost:{Admin}/admin, impronta {Fp}", Version, opt.Port, adminPort, fingerprint);
app.Run();
return 0;

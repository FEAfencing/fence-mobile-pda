using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace FencePdaBridge;

public static class CertificateService
{
    const string Pwd = "fence-pda-bridge";

    public static X509Certificate2 LoadOrCreate(string dir)
    {
        var path = Path.Combine(dir, "bridge-cert.pfx");
        if (File.Exists(path))
        {
            try
            {
                var c = Open(path);
                if (c.NotAfter > DateTime.UtcNow.AddDays(30)) return c;
            }
            catch { /* rigenera */ }
        }
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=FencePdaBridge", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("localhost");
        try { san.AddDnsName(Environment.MachineName); } catch { }
        foreach (var ip in LocalIPv4()) san.AddIpAddress(ip);
        req.CertificateExtensions.Add(san.Build());
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
        using var self = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        File.WriteAllBytes(path, self.Export(X509ContentType.Pfx, Pwd));
        return Open(path);
    }

    static X509Certificate2 Open(string path)
    {
        try { return new X509Certificate2(path, Pwd, X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable); }
        catch { return new X509Certificate2(path, Pwd, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable); }
    }

    public static string Fingerprint(X509Certificate2 c) => Convert.ToHexString(SHA256.HashData(c.RawData)).ToLowerInvariant();

    public static List<System.Net.IPAddress> LocalIPv4()
    {
        var list = new List<System.Net.IPAddress>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                if (ua.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(ua.Address);
        }
        return list;
    }
}

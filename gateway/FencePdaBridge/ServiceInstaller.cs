using System.Diagnostics;

namespace FencePdaBridge;

/// <summary>FencePdaBridge.exe --install [--port N] / --uninstall: registra il servizio Windows e la regola firewall (serve admin).</summary>
public static class ServiceInstaller
{
    static int Run(string file, string args)
    {
        var p = Process.Start(new ProcessStartInfo(file, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true })!;
        var o = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
        p.WaitForExit();
        Console.WriteLine($"> {file} {args}\n{o.Trim()}");
        return p.ExitCode;
    }

    public static int Execute(string[] args)
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("Percorso eseguibile sconosciuto");
        int port = 8765;
        var ix = Array.IndexOf(args, "--port");
        if (ix >= 0 && ix + 1 < args.Length) int.TryParse(args[ix + 1], out port);

        if (args.Contains("--uninstall"))
        {
            Run("sc.exe", "stop FencePdaBridge");
            Run("sc.exe", "delete FencePdaBridge");
            Run("netsh", "advfirewall firewall delete rule name=\"Fence PDA Bridge\"");
            return 0;
        }
        Run("sc.exe", $"create FencePdaBridge binPath= \"\\\"{exe}\\\"\" start= auto DisplayName= \"Fence PDA Bridge\"");
        Run("sc.exe", "description FencePdaBridge \"Gateway tra gli smartphone Android (Fence! Mobile PDA) e il database Ges1Gara\"");
        Run("sc.exe", "failure FencePdaBridge reset= 86400 actions= restart/5000/restart/5000/restart/30000");
        Run("netsh", "advfirewall firewall delete rule name=\"Fence PDA Bridge\"");
        Run("netsh", $"advfirewall firewall add rule name=\"Fence PDA Bridge\" dir=in action=allow protocol=TCP localport={port} remoteip=localsubnet profile=any");
        Run("sc.exe", "start FencePdaBridge");
        Console.WriteLine($"Servizio installato. Pannello operatore: http://localhost:{port + 1}/admin");
        return 0;
    }
}

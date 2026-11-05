namespace FencePdaBridge;

/// <summary>
/// Regole sui risultati, derivate da Fence! (frmTxRx4Pda.TrattaPunti e frmAssGironiV.Congruente).
/// Notazione delle celle Ris*: "V" = vittoria al limite, "P&lt;n&gt;" = vittoria con n stoccate,
/// numero = stoccate di chi perde, "A"/"R"/"E" = codici speciali (Assente/Ritirato/Espulso).
/// </summary>
public static class ResultRules
{
    public const int FenceMaxPool = 5;
    public const int FenceMaxEd = 15;
    public const int FenceMaxTeam = 45;

    public static string? Normalize(string? c)
    {
        if (c == null) return null;
        c = c.Trim();
        if (c.Length == 0) return null;
        var u = c.ToUpperInvariant();
        if (u is "V" or "A" or "R" or "E") return u;
        if (u.Length > 1 && (u[0] == 'P' || u[0] == 'V') && u.Skip(1).All(char.IsDigit) && u.Length <= 4)
            return "P" + int.Parse(u[1..]);
        if (u.All(char.IsDigit) && u.Length <= 3) return int.Parse(u).ToString();
        return "?";
    }

    public static bool IsWinner(string? c) => c != null && (c == "V" || c[0] == 'P');
    public static bool IsSpecial(string? c) => c is "A" or "R" or "E";
    public static int WinnerTouches(string c, int max) => c == "V" ? max : int.Parse(c[1..]);
    public static int LoserTouches(string? c) => c != null && c.All(char.IsDigit) ? int.Parse(c) : 0;

    /// <summary>Converte il valore di una cella nella forma attesa da Fence! (V solo se uguale al limite storico di Fence).</summary>
    public static string ToFenceWinner(int touches, int localMax, int fenceMax)
        => touches == localMax && localMax == fenceMax ? "V" : "P" + touches;

    /// <summary>Controllo di congruenza di una coppia di celle. Restituisce un messaggio di errore oppure null.</summary>
    public static string? ValidatePair(string? a, string? b, int max, bool final)
    {
        a = Normalize(a); b = Normalize(b);
        if (a == "?" || b == "?") return "Valore non valido";
        if (IsSpecial(a) || IsSpecial(b)) return null;
        if (a == null && b == null) return final ? "Assalto non compilato" : null;
        bool wa = IsWinner(a), wb = IsWinner(b);
        if (wa && wb) return "Due vincitori nello stesso assalto";
        if (!wa && !wb) return final || (a != null && b != null) ? "Manca il vincitore" : null;
        var win = wa ? a! : b!;
        var los = wa ? b : a;
        if (final && los == null) return "Manca il punteggio di chi perde";
        int wt = WinnerTouches(win, max);
        if (wt > max) return "Stoccate oltre il limite";
        if (los != null && LoserTouches(los) > wt) return "Il perdente ha piu' stoccate del vincitore";
        return null;
    }

    /// <summary>Port di TrattaPunti: (Vittoria1, Vittoria2, Stoccate1, Stoccate2) per la tabella Assalti.</summary>
    public static (int v1, int v2, int s1, int s2) ToAssalto(string? ris1, string? ris2, int maxPunti)
    {
        ris1 = (ris1 ?? "").Trim(); ris2 = (ris2 ?? "").Trim();
        int v1 = 0, v2 = 0, s1 = 0, s2 = 0;
        static int ToInt(string s) => int.TryParse(s, out var n) ? n : 0;
        if (ris1.StartsWith('A') || ris1.StartsWith('R') || ris1.StartsWith('E'))
        {
            switch (ris1[0]) { case 'A': v1 = 2; v2 = 5; break; case 'R': v1 = 3; v2 = 6; break; case 'E': v1 = 4; v2 = 7; break; }
        }
        else if (ris2.StartsWith('A') || ris2.StartsWith('R') || ris2.StartsWith('E'))
        {
            switch (ris2[0]) { case 'A': v2 = 2; v1 = 5; break; case 'R': v2 = 3; v1 = 6; break; case 'E': v2 = 4; v1 = 7; break; }
        }
        else if (ris1 == "V" || ris1.StartsWith('P'))
        {
            s1 = ris1 == "V" ? maxPunti : ToInt(ris1[1..]); v1 = 1; s2 = ToInt(ris2); v2 = 0;
        }
        else if (ris2 == "V" || ris2.StartsWith('P'))
        {
            s2 = ris2 == "V" ? maxPunti : ToInt(ris2[1..]); v2 = 1; s1 = ToInt(ris1); v1 = 0;
        }
        return (v1, v2, s1, s2);
    }
}

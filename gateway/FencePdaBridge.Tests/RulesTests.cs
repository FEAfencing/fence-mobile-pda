using FencePdaBridge;
using Xunit;

public class RulesTests
{
    [Theory]
    [InlineData("v", "V")] [InlineData("p3", "P3")] [InlineData("v4", "P4")] [InlineData(" 3 ", "3")] [InlineData("a", "A")]
    [InlineData("", null)] [InlineData("x", "?")]
    public void Normalize(string input, string? expected) => Assert.Equal(expected, ResultRules.Normalize(input));

    [Fact] public void TrattaPunti_VittoriaPiena() => Assert.Equal((1, 0, 5, 3), ResultRules.ToAssalto("V", "3", 5));
    [Fact] public void TrattaPunti_VittoriaLatoDue() => Assert.Equal((0, 1, 2, 5), ResultRules.ToAssalto("2", "V", 5));
    [Fact] public void TrattaPunti_VittoriaConPunti() => Assert.Equal((1, 0, 4, 4), ResultRules.ToAssalto("P4", "4", 5));
    [Fact] public void TrattaPunti_Assente() => Assert.Equal((2, 5, 0, 0), ResultRules.ToAssalto("A", "0", 5));
    [Fact] public void TrattaPunti_AvversarioRitirato() => Assert.Equal((6, 3, 0, 0), ResultRules.ToAssalto("0", "R", 5));
    [Fact] public void TrattaPunti_Espulso() => Assert.Equal((4, 7, 0, 0), ResultRules.ToAssalto("E", "", 15));

    [Fact] public void Pair_Valida() => Assert.Null(ResultRules.ValidatePair("V", "3", 5, true));
    [Fact] public void Pair_DueVincitori() => Assert.NotNull(ResultRules.ValidatePair("V", "V", 5, true));
    [Fact] public void Pair_PerdenteOltreVincitore() => Assert.NotNull(ResultRules.ValidatePair("P3", "4", 5, true));
    [Fact] public void Pair_NonCompilataInFinale() => Assert.NotNull(ResultRules.ValidatePair(null, null, 5, true));
    [Fact] public void Pair_NonCompilataInParziale() => Assert.Null(ResultRules.ValidatePair(null, null, 5, false));
    [Fact] public void Pair_SpecialeAccettato() => Assert.Null(ResultRules.ValidatePair("A", "0", 5, true));

    [Fact]
    public void Pool_AssociazionePerNumFis_NonPerPosizione()
    {
        var u = new Unit { Kind = "pool", Id = "8.0.1.0.0.0" };
        var rows = new List<Row> { new() { NumFis = 111, Ordine = 1 }, new() { NumFis = 222, Ordine = 2 } };
        // il telefono invia la coppia con gli atleti in ordine inverso rispetto al DB
        var ris = ResultService.BuildRis(u, rows, new() { new PairCell(222, 111, "V", "2") }, 5, true);
        Assert.Equal("2", ris[0][1]);   // riga 111 contro 222
        Assert.Equal("V", ris[1][0]);   // riga 222 contro 111
    }

    [Fact]
    public void Ed_Ris1PerLato()
    {
        var u = new Unit { Kind = "ed", Id = "8.0.1.0.16.1" };
        var rows = new List<Row> { new() { NumFis = 1 }, new() { NumFis = 2 } };
        var ris = ResultService.BuildRis(u, rows, new() { new PairCell(1, 2, "P14", "14") }, 15, true);
        Assert.Equal("P14", ris[0][0]); Assert.Equal("14", ris[1][0]);
    }

    [Fact]
    public void Pool_CodiceSpecialeDaZeroAvversario()
    {
        var u = new Unit { Kind = "pool", Id = "x" };
        var rows = new List<Row> { new() { NumFis = 1 }, new() { NumFis = 2 } };
        var ris = ResultService.BuildRis(u, rows, new() { new PairCell(1, 2, "A", null) }, 5, true);
        Assert.Equal("A", ris[0][1]); Assert.Equal("0", ris[1][0]);
    }

    [Fact]
    public void AtletiDiversiDalDb_Conflitto()
    {
        var u = new Unit { Kind = "pool", Id = "x" };
        var rows = new List<Row> { new() { NumFis = 1 }, new() { NumFis = 2 } };
        Assert.Throws<ResultException>(() => ResultService.BuildRis(u, rows, new() { new PairCell(1, 99, "V", "0") }, 5, true));
    }

    [Fact]
    public void UnitBuild_RiconosceTipi()
    {
        var rows = new List<Row>
        {
            new() { Flags = 16, Girone = 0, Ed = 0, Ordine = 1, NumFis = 1 }, new() { Flags = 16, Girone = 0, Ed = 0, Ordine = 2, NumFis = 2 },
            new() { Flags = 16, Girone = 0, Ed = -8, Ordine = 3, NumFis = 3 }, new() { Flags = 16, Girone = 0, Ed = 8, Ordine = 3, NumFis = 4 },
            new() { Flags = 128, Girone = 0, Ed = -4, Ordine = 1 }, new() { Flags = 128, Girone = 0, Ed = 4, Ordine = 1 },
        };
        var units = Unit.Build(rows);
        Assert.Equal(3, units.Count);
        Assert.Contains(units, u => u.Kind == "pool" && u.Rows.Count == 2);
        Assert.Contains(units, u => u.Kind == "ed" && u.Rows[0].NumFis == 3);
        Assert.Contains(units, u => u.Kind == "team-ed");
    }
}

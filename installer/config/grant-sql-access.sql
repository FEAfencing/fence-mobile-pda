-- Eseguire UNA VOLTA con SQL Server Management Studio / sqlcmd sull'istanza di Fence! (es. (local)\FISDOTNET),
-- solo se il servizio gira con l'account "LocalSystem" e si usa l'autenticazione Windows.
-- In alternativa configurare un utente SQL nell'installer.
USE [master];
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'NT AUTHORITY\SYSTEM')
    CREATE LOGIN [NT AUTHORITY\SYSTEM] FROM WINDOWS;
GO
USE [Ges1Gara];
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'NT AUTHORITY\SYSTEM')
    CREATE USER [NT AUTHORITY\SYSTEM] FOR LOGIN [NT AUTHORITY\SYSTEM];
-- Il bridge legge/scrive solo queste tabelle di Fence!:
GRANT SELECT, INSERT, UPDATE ON dbo.Pda TO [NT AUTHORITY\SYSTEM];
GRANT SELECT ON dbo.Mobiles TO [NT AUTHORITY\SYSTEM];
GRANT SELECT, UPDATE ON dbo.Gironi4Mobile TO [NT AUTHORITY\SYSTEM];
GRANT SELECT, UPDATE ON dbo.Assalti TO [NT AUTHORITY\SYSTEM];      -- UPDATE solo se AutoImportAssalti = true
GRANT SELECT, INSERT, UPDATE ON dbo.Messaggi TO [NT AUTHORITY\SYSTEM];
GRANT SELECT ON dbo.FormulaGara TO [NT AUTHORITY\SYSTEM];
GRANT SELECT ON dbo.CategAtleti TO [NT AUTHORITY\SYSTEM];
GRANT SELECT ON dbo.Tesserati TO [NT AUTHORITY\SYSTEM];
GO

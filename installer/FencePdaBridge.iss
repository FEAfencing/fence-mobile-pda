; Inno Setup 6 — compilare con:  iscc /DAppVersion=1.0.0 installer\FencePdaBridge.iss
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6D1F4C1E-7A0B-4F2C-9A53-FE0C1D0A0001}
AppName=Fence! Mobile PDA Bridge
AppVersion={#AppVersion}
AppPublisher=Fence Mobile PDA
DefaultDirName={autopf}\FencePdaBridge
DefaultGroupName=Fence Mobile PDA
OutputDir=..\dist
OutputBaseFilename=FencePdaBridge-Setup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes
WizardStyle=modern
UninstallDisplayName=Fence! Mobile PDA Bridge

[Languages]
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[Files]
Source: "..\dist\FencePdaBridge.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "config\grant-sql-access.sql"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\docs\*.md"; DestDir: "{app}\docs"; Flags: ignoreversion

[Icons]
Name: "{group}\Pannello Fence PDA Bridge"; Filename: "{cmd}"; Parameters: "/c start """" http://localhost:{code:GetAdminPort}/admin"; IconFilename: "{app}\FencePdaBridge.exe"
Name: "{group}\Documentazione"; Filename: "{app}\docs"
Name: "{group}\Script permessi SQL"; Filename: "{app}\grant-sql-access.sql"

[Run]
Filename: "{sys}\sc.exe"; Parameters: "create FencePdaBridge binPath= ""{app}\FencePdaBridge.exe"" start= auto DisplayName= ""Fence PDA Bridge"""; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "description FencePdaBridge ""Gateway tra smartphone Android (Fence! Mobile PDA) e database Ges1Gara"""; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "failure FencePdaBridge reset= 86400 actions= restart/5000/restart/5000/restart/30000"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Fence PDA Bridge"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Fence PDA Bridge"" dir=in action=allow protocol=TCP localport={code:GetPort} remoteip=localsubnet profile=any"; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "start FencePdaBridge"; Flags: runhidden
Filename: "{cmd}"; Parameters: "/c start """" http://localhost:{code:GetAdminPort}/admin"; Description: "Apri il pannello operatore (QR di associazione)"; Flags: postinstall runhidden nowait skipifsilent

[UninstallRun]
Filename: "{sys}\sc.exe"; Parameters: "stop FencePdaBridge"; Flags: runhidden; RunOnceId: "StopSvc"
Filename: "{sys}\sc.exe"; Parameters: "delete FencePdaBridge"; Flags: runhidden; RunOnceId: "DelSvc"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Fence PDA Bridge"""; Flags: runhidden; RunOnceId: "DelFw"

[Code]
var
  SqlPage: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  SqlPage := CreateInputQueryPage(wpSelectDir, 'Database di Fence!', 'Connessione a SQL Server',
    'Valori standard di Fence!: istanza (local)\FISDOTNET, database Ges1Gara. Lasciare vuoto l''utente per usare l''autenticazione Windows del servizio (vedi grant-sql-access.sql).');
  SqlPage.Add('Server\istanza:', False);
  SqlPage.Add('Database:', False);
  SqlPage.Add('Utente SQL (opzionale):', False);
  SqlPage.Add('Password SQL:', True);
  SqlPage.Add('Porta HTTPS per gli smartphone:', False);
  SqlPage.Values[0] := '(local)\FISDOTNET';
  SqlPage.Values[1] := 'Ges1Gara';
  SqlPage.Values[4] := '8765';
end;

function GetPort(Param: String): String;
begin
  Result := Trim(SqlPage.Values[4]);
  if Result = '' then Result := '8765';
end;

function GetAdminPort(Param: String): String;
begin
  Result := IntToStr(StrToIntDef(GetPort(''), 8765) + 1);
end;

function JsonEsc(const S: String): String;
begin
  Result := S;
  StringChangeEx(Result, '\', '\\', True);
  StringChangeEx(Result, '"', '\"', True);
end;

procedure WriteConfig;
var
  Dir, Conn, Json: String;
begin
  Dir := ExpandConstant('{commonappdata}\FencePdaBridge');
  ForceDirectories(Dir);
  if FileExists(Dir + '\bridge.json') then Exit;  { aggiornamento: non sovrascrivere la configurazione esistente }
  Conn := 'Data Source=' + Trim(SqlPage.Values[0]) + ';Initial Catalog=' + Trim(SqlPage.Values[1]) + ';Encrypt=False;TrustServerCertificate=True;';
  if Trim(SqlPage.Values[2]) = '' then
    Conn := Conn + 'Integrated Security=True'
  else
    Conn := Conn + 'User ID=' + Trim(SqlPage.Values[2]) + ';Password=' + SqlPage.Values[3];
  Json := '{"Bridge":{"Port":' + GetPort('') + ',"SqlConnection":"' + JsonEsc(Conn) + '","AutoImportAssalti":false,"SupervisorPin":"","PublicHost":"","PairCodeMinutes":10}}';
  SaveStringToFile(Dir + '\bridge.json', Json, False);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  R: Integer;
begin
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop FencePdaBridge', '', SW_HIDE, ewWaitUntilTerminated, R);
  Exec(ExpandConstant('{sys}\sc.exe'), 'delete FencePdaBridge', '', SW_HIDE, ewWaitUntilTerminated, R);
  Sleep(1500);
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then WriteConfig;
end;

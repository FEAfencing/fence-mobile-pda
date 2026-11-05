# Installazione

## 1. Chiave di firma Android (una volta sola)
Gli APK devono essere firmati sempre con la **stessa** chiave, altrimenti Android rifiuta gli aggiornamenti.

```bash
keytool -genkeypair -v -keystore release.keystore -alias fencepda -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore      # (macOS: base64 -i release.keystore)
```
In GitHub → *Settings → Secrets and variables → Actions* creare:

| Secret | Valore |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | output di `base64` |
| `ANDROID_KEYSTORE_PASSWORD` | password del keystore |
| `ANDROID_KEY_ALIAS` | `fencepda` |
| `ANDROID_KEY_PASSWORD` | password della chiave |

**Conservare `release.keystore` in un posto sicuro**: se si perde, gli smartphone dovranno disinstallare l'app per installarne una firmata diversamente.

## 2. Pubblicare una Release
`git tag v1.0.0 && git push origin v1.0.0` — il workflow esegue i test, compila gateway, installer e APK e li allega alla Release.

## 3. Computer di gara (Windows 10/11 x64 o Server)
1. Eseguire `FencePdaBridge-Setup.exe` (come amministratore).
2. Inserire istanza SQL (`(local)\FISDOTNET`), database (`Ges1Gara`), porta (8765). Utente SQL vuoto = autenticazione Windows del servizio.
3. Se si usa l'autenticazione Windows, eseguire **una volta** `grant-sql-access.sql` (installato in `C:\Program Files\FencePdaBridge`) con SSMS/sqlcmd: concede a `NT AUTHORITY\SYSTEM` i soli permessi necessari.
4. Si apre il pannello operatore `http://localhost:8766/admin`: la sezione **Stato** mostra, per ogni tabella di Fence!, se è raggiungibile. Tutto verde = pronto.

Alternativa senza installer: `FencePdaBridge.exe --install [--port 8765]` da prompt amministratore (registra servizio e firewall); `--uninstall` per rimuoverli. La configurazione sta in `C:\ProgramData\FencePdaBridge\bridge.json`:

```json
{ "Bridge": { "Port": 8765, "SqlConnection": "...", "AutoImportAssalti": false, "SupervisorPin": "1234", "PublicHost": "", "PairCodeMinutes": 10 } }
```
- `SupervisorPin`: PIN per le **correzioni di risultati già trasmessi** (vuoto = correzioni disabilitate dal telefono).
- `AutoImportAssalti`: vedi `COMPATIBILITA_FENCE.md`.
Dopo ogni modifica: riavviare il servizio `FencePdaBridge`.

## 4. Smartphone
Scaricare dalla Release `FencePDA-Android-arm64.apk` (telefoni recenti) o `-universal.apk`, consentire l'installazione da fonte sconosciuta e installare. Android 7.0+.

# Fence! Mobile PDA

Gli smartphone Android sostituiscono i vecchi PDA di **Fence!**: ricevono le assegnazioni dell'arbitro, compilano gironi, eliminazione diretta e incontri a squadre, firmano e trasmettono i risultati. Fence! li vede come PDA normali: **nessuna modifica al programma originale**.

```
Smartphone (app Android) ──HTTPS+JSON, Wi-Fi gara──►  FencePdaBridge (Windows)  ──SQL──►  Ges1Gara (FisDotNet)  ◄── Fence! / PrepArbPda
```

| Cartella | Contenuto |
|---|---|
| `android/` | App Kotlin + Jetpack Compose + Room + WorkManager |
| `gateway/` | `FencePdaBridge` (.NET 8, servizio Windows, API REST) + test xUnit |
| `installer/` | Script Inno Setup e script SQL per i permessi |
| `docs/` | Installazione, associazione arbitri, rete, compatibilità, troubleshooting |
| `.github/workflows/release.yml` | Build e pubblicazione della Release (nessun Artifact) |

## Rilasciare
1. Creare i 4 *secrets* del repository (vedi `docs/INSTALLAZIONE.md`, sezione "Chiave di firma Android").
2. `git tag v1.0.0 && git push origin v1.0.0`
3. La Release conterrà: `FencePDA-Android-universal.apk`, `FencePDA-Android-arm64.apk`, `FencePdaBridge.exe`, `FencePdaBridge-Setup.exe`, `FencePDA-Documentazione.zip`.

## Stato del progetto — da leggere
Il codice è stato scritto a partire dall'analisi del decompilato di Fence! (tabelle `Pda`, `Mobiles`, `Gironi4Mobile`, `Messaggi`, `TrattaPunti`, `frmAssGironiV`, `frmIncontri`) **senza poter compilare né provare su un database reale**. La CI compila e lancia i test automatici, ma l'interoperabilità con una vera installazione di Fence! va collaudata prima di una gara ufficiale: vedi `docs/COMPATIBILITA_FENCE.md`.

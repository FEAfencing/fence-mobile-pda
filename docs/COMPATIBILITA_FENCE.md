# Compatibilità con Fence!

## Cosa fa il Bridge nel database (nessuna modifica di schema)
| Operazione | Tabella | Come |
|---|---|---|
| Registra il telefono | `Pda` | `INSERT (Mobile)` se assente |
| Trova l'arbitro | `Mobiles` | sola lettura, `WHERE Mobile = <FENCEPDA-…>` |
| Legge gli incontri | `Gironi4Mobile` ⨝ `Mobiles` | stessa query di `frmTxRx4Pda` |
| Scrive il risultato | `Gironi4Mobile` | `Ris1..Ris7`, `Eseguito` (0 parziale / 1 chiuso), chiave `Flags+MaschioFemmina+Categoria+Girone+ED+Ordine` |
| Firma | `Gironi4Mobile.Firma` | JPEG |
| Limiti stoccate | `FormulaGara` | `StoccateGironi/ED/Squadre`, solo lettura |
| Messaggi | `Messaggi` | stessa semantica di `frmMessages` |
| (opzionale) Aggiorna `Assalti` | `Assalti` | port di `TrattaPunti` |

Le tabelle interne (dispositivi, coda idempotente, registro eventi) stanno in `C:\ProgramData\FencePdaBridge\bridge.db`, non in Ges1Gara.

## Flusso in Fence!
Un girone/assalto chiuso dal telefono compare in **TxRx PDA → Completati** come con un PDA vero; l'operatore usa i pulsanti originali di Fence! per importarlo (`AggiornaGirone` / `AggiornaED`). È la modalità **predefinita** (`AutoImportAssalti: false`): è quella che Fence! già conosce e permette all'operatore di controllare.
Con `"AutoImportAssalti": true` il Bridge applica subito anche `TrattaPunti` su `Assalti` (individuali; le squadre restano a Fence!). L'operazione è identica a quella di Fence! e ripetibile.

## Convenzioni di scrittura (da `TrattaPunti`)
- Cella del vincitore: `V` se stoccate = limite storico di Fence! (5 gironi / 15 ED), altrimenti `P<n>`; cella di chi perde: numero di stoccate.
- `A`, `R`, `E`: scritti sul lato interessato; all'avversario viene scritto `0`.
- Il telefono invia le coppie per **NumFis**; il Bridge le colloca in `Ris1..7` secondo l'ordine del database (stesso effetto dell'inversione atleti di Fence!).

## Conflitti e correzioni
- Il telefono invia la revisione vista: se Fence! o un altro dispositivo ha cambiato la riga nel frattempo → **409**, nulla viene sovrascritto, l'app mostra il conflitto.
- Risultato già chiuso (`Eseguito = 1`): modifica solo con **PIN responsabile + motivo** (`SupervisorPin`), registrata nel registro eventi.
- Doppio invio: ogni trasmissione ha un `txId`; ripeterla restituisce la risposta già data.

## Limiti noti (leggere prima di una gara)
1. **Non collaudato su un Ges1Gara reale.** Il decompilato mostra le operazioni ma non vincoli, trigger o tipi di colonna esatti. Provare su una copia: scenari della tabella test nella specifica.
2. La tabella `Pda` potrebbe avere altre colonne obbligatorie oltre a `Mobile`: in tal caso `/api/v1/pair` risponde 503 con l'errore SQL.
3. I gironi sono limitati a **7 atleti** (`Ris1..Ris7`).
4. Squadre: in `Gironi4Mobile` esiste solo il punteggio finale (`Ris1`). Le 9 frazioni e le formazioni sono salvate nella bozza del telefono e nel registro eventi del Bridge, non in tabelle di Fence!. L'importazione delle squadre in Fence! resta manuale (`frmIncontri`). Struttura delle righe squadra dedotta da `ScriviRkSquadre`: da verificare.
5. Penalità/sanzioni: non esiste un campo dedicato in `Gironi4Mobile`; il testo inserito va nel registro eventi.
6. Pedana e orario sono letti da `Assalti` (`Pedana`, `Data`): se non presenti restano vuoti.
7. L'ordine suggerito degli assalti del girone (3–7 atleti) è la tabella standard FIE; non è letto da Fence!.
8. Il significato esatto di `Messaggi.OraInvio/OraRx` è dedotto da `frmMessages`.

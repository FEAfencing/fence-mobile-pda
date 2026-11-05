# Troubleshooting

| Sintomo | Causa probabile / rimedio |
|---|---|
| Pannello: tabella "ERRORE" | Permessi SQL: eseguire `grant-sql-access.sql` oppure usare un utente SQL nell'installer. Verificare istanza e nome database in `bridge.json`. |
| App: "Impronta del certificato diversa" | Il certificato del Bridge è stato rigenerato: rifare l'associazione con un nuovo QR. |
| App non raggiunge il Bridge | Stessa rete Wi-Fi? AP isolation attivo? Firewall (regola "Fence PDA Bridge")? IP del PC cambiato? Provare `https://IP:8765/api/v1/device` dal browser: deve rispondere 401 (= raggiungibile). |
| `/pair` risponde 503 | Errore SQL sulla tabella `Pda` (colonne obbligatorie / permessi): leggere il messaggio. |
| Il PDA non compare in PrepArbPda | Riaprire PrepArbPda; controllare che `Pda` contenga `PDA-…`. |
| Home: "Nessun arbitro associato" | Associare il PDA all'arbitro in PrepArbPda (tabella `Mobiles`). |
| Nessun incontro | Assegnare gironi/incontri all'arbitro in PrepArbPda: servono righe in `Gironi4Mobile`. |
| Conflitto 409 | Fence! o un altro dispositivo ha modificato il risultato. Scegliere "Scarta il mio invio" oppure correggere con PIN responsabile. |
| "Correzioni disabilitate" | Impostare `SupervisorPin` in `bridge.json` e riavviare il servizio. |
| QR scaduto/usato | Premere "Nuovo QR" nel pannello. |
| Registro eventi | Pannello operatore → *Registro operazioni* (chi ha trasmesso cosa e quando). |

# Associare smartphone e arbitri

Due passaggi distinti, come con i PDA fisici.

## A. Registrare lo smartphone come PDA (una volta per telefono)
1. Pannello operatore del Bridge (`http://localhost:8766/admin`) → scegliere l'indirizzo IP del computer visibile dai telefoni (quello della rete Wi-Fi di gara).
2. Sul telefono: app **Fence! PDA** → *Scansiona il QR del Bridge*.
3. Il Bridge verifica il codice (monouso, scade in 10 minuti), crea il token del dispositivo e inserisce l'identificativo `PDA-XXXXXX` nella tabella **`Pda`** di Fence!.

L'identificativo è un UUID generato alla prima apertura dell'app (non il MAC) e resta lo stesso nelle gare successive.

## B. Assegnare il PDA a un arbitro (come sempre, in Fence!)
1. Aprire **PrepArbPda**: i PDA `PDA-…` compaiono nell'elenco dei PDA disponibili.
2. Selezionare l'arbitro, selezionare il PDA, assegnare gironi/incontri/pedane e salvare. Fence! scrive le righe in `Mobiles` e `Gironi4Mobile`.
3. Entro pochi secondi la home dell'app mostra *Arbitro: Cognome Nome · FIS n* e gli incontri.

Regole: **un telefono = un PDA = un arbitro alla volta**; il telefono non può scegliere un altro arbitro. Per spostare un telefono su un altro arbitro, toglierlo prima dall'arbitro precedente in PrepArbPda.

## Revoca e smarrimento
Pannello operatore → *Dispositivi associati* → **Revoca**: da quel momento il token non vale più (l'app mostra "PDA revocato"). Per riusarlo basta un nuovo QR.

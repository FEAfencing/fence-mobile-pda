# Rete e sicurezza

- Il Bridge ascolta in **HTTPS** sulla porta configurata (8765) e, **solo su localhost**, il pannello operatore (porta+1, HTTP).
- Certificato autofirmato generato al primo avvio (`C:\ProgramData\FencePdaBridge\bridge-cert.pfx`, validità 10 anni). L'impronta SHA-256 è nel QR: l'app accetta **solo** quel certificato (pinning), quindi non servono autorità di certificazione né Internet.
- Ogni telefono ha un token individuale (salvato come hash); le credenziali SQL non lasciano mai il computer.
- Il QR è monouso e scade; l'accesso API richiede sempre token + identificativo PDA.
- Il firewall viene aperto solo per la sottorete locale (`remoteip=localsubnet`).
- Il Wi-Fi di gara deve consentire la comunicazione tra telefoni e computer (disattivare "isolamento client / AP isolation" sul router). Il PC dovrebbe avere IP fisso o riservato DHCP: se l'IP cambia, generare un nuovo QR.
- Funziona senza Internet. Se il Wi-Fi cade i risultati restano in coda sul telefono (database locale) e partono da soli al ritorno della rete, anche con l'app chiusa.
- Se il PC ha più schede di rete, scegliere nel pannello l'IP giusto prima di mostrare il QR (o impostare `PublicHost`).

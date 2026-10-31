# Umsatz-Abruf

Ruft per FinTS (PIN/TAN) regelmäßig die Umsätze eines Girokontos ab und speichert sie in einer
SQLite-Datenbank. Eine einzige Anwendung in einem Docker-Container, bedient über den Browser,
mit Caddy als HTTPS-Reverse-Proxy davor.

- **Läuft dauerhaft** und ruft im eingestellten Intervall ab (Standard: alle 6 Stunden).
- **Autorisierung im Browser:** Verlangt die Bank eine Freigabe (pushTAN-App, TAN, photoTAN,
  QR-TAN) oder eine Eingabe, wird sie in der Weboberfläche beantwortet – von überall, auch mit
  dem Handy.
- **Aussetzen statt Bedrängen:** Erfolgt eine verlangte Autorisierung nicht, werden die Abrufe
  ausgesetzt, bis sie im Browser erneuert wurde. Eine E-Mail an die Notfall-Adresse meldet das.
- **Buchungsübersicht** mit Zeitraum, Suche und Summen.
- **Export-Schnittstelle** für externe Anwendungen: alle Buchungen als CSV, JSON oder XML.
- **Nur lesend:** Das Programm führt keine Überweisungen oder sonstigen Aufträge aus, siehe
  [Nur Umsatzabfragen](#nur-umsatzabfragen).

Grundlage sind [HBCI4Java](https://github.com/hbci4j/hbci4java) (FinTS 3.0), Spring Boot 4.1, SQLite und
Java 25 (LTS, Support bis 2033).

## Inhalt

- [Schnellstart](#schnellstart)
- [Ablauf im Betrieb](#ablauf-im-betrieb)
  - [Intervall](#intervall)
  - [Autorisierung und Aussetzen](#autorisierung-und-aussetzen)
  - [Notfall-E-Mail](#notfall-e-mail)
- [Weboberfläche](#weboberfläche)
- [Export-Schnittstelle](#export-schnittstelle)
- [Caddy und Zugriff von außen](#caddy-und-zugriff-von-außen)
- [Sicherheit](#sicherheit)
- [Nur Umsatzabfragen](#nur-umsatzabfragen)
- [Konfiguration](#konfiguration)
- [Daten und Datenbank](#daten-und-datenbank)
- [Betrieb und Wartung](#betrieb-und-wartung)
- [Fehlerbehebung](#fehlerbehebung)
- [Entwicklung und Tests](#entwicklung-und-tests)

---

## Schnellstart

```bash
cp .env.example .env && chmod 600 .env
#   eintragen: Bankzugang (FINTS_*), FINTS_PIN,
#              WEB_DOMAIN, WEB_BENUTZER, WEB_PASSWORT, Notfall-E-Mail (MAIL_*)
mkdir -p daten              # vor dem ersten Start anlegen, sonst gehört es root
docker compose up -d --build
```

Danach: `https://<WEB_DOMAIN>/` öffnen und anmelden. Zum Ausprobieren auf dem eigenen Rechner
genügt `WEB_DOMAIN=localhost` (Caddy verwendet dann ein lokales Zertifikat, der Browser warnt
einmalig).

**Erster Abruf:** Er startet eine Minute nach dem Start, oder sofort mit „Jetzt abrufen“. Fast
immer verlangt die Bank beim ersten Mal eine Freigabe: Sie erscheint in der Weboberfläche, bei
pushTAN genügt die Bestätigung in der App.

**Passport-Datei:** Sie speichert verschlüsselt die Bankparameter und die System-ID, an der die
Bank dieses System wiedererkennt. Die Anwendung legt sie selbst an und verwaltet sie samt
Passwort intern, im Docker-Volume `umsatz-abruf_intern`. Darum musst du dich nicht kümmern. Geht
sie verloren, etwa weil das Volume gelöscht wurde, legt die Anwendung eine neue an, und die Bank
verlangt einmal eine Freigabe.

Docker Compose startet zwei Container: die Anwendung und Caddy. Alles, was die Anwendung
speichert, liegt im gemounteten Verzeichnis `daten/`.

---

## Ablauf im Betrieb

### Intervall

`ABRUF_INTERVALL` (Standard `6h`) legt den Abstand zwischen zwei Abrufen fest, z.B. `30m`, `2h`
oder `1d`. Das Minimum ist 15 Minuten, `aus` bedeutet nur Abrufe per Knopf. Gezählt wird ab dem
Beginn des letzten Abrufs, auch eines im Browser gestarteten. Der erste Abruf startet eine Minute
nach dem Start des Containers. Je Abruf werden die letzten `ABRUF_TAGE` Tage abgefragt
(Standard 7). Bereits gespeicherte Buchungen werden erkannt und nicht doppelt angelegt.

Scheitert ein Abruf aus anderen Gründen, z.B. weil Netz oder Bank nicht erreichbar sind,
versucht es die Anwendung einfach beim nächsten Intervall erneut.

Damit die automatischen Abrufe ohne Eingriff laufen, sollte **`FINTS_PIN` gesetzt** sein.
Fehlt die PIN, verlangt jeder Abruf die Eingabe im Browser. Bleibt sie aus, werden die Abrufe
ausgesetzt.

### Autorisierung und Aussetzen

Banken verlangen regelmäßig eine erneute Autorisierung: je nach Bank alle 90 oder 180 Tage,
außerdem nach dem Verlust der Passport-Datei und bei Umsätzen, die älter als 90 Tage sind.
Dann läuft es so:

1. Der Abruf fordert die Freigabe an. Sie erscheint in der Weboberfläche, und eine E-Mail geht
   an die Notfall-Adresse („Freigabe angefordert“).
2. Bestätigst du innerhalb von `WEB_WARTEZEIT` (Standard 10 Minuten) in der App bzw. gibst die
   TAN im Browser ein, läuft alles normal weiter.
3. Erfolgt die Autorisierung nicht, wird **nicht** einfach beim nächsten Intervall erneut
   gefragt. Stattdessen:
   - werden die automatischen Abrufe **ausgesetzt**,
   - geht eine E-Mail an die Notfall-Adresse („Autorisierung erforderlich – Abrufe ausgesetzt“),
   - zeigt die Weboberfläche ein rotes Banner „Autorisierung erforderlich“.
4. Mit **„Jetzt autorisieren“** im Browser startet ein Abruf, die Freigabe wird erneut angefordert.
   Ist sie erfolgreich, laufen die Abrufe wieder, und eine kurze E-Mail bestätigt das.

Ausgesetzt wird auch, wenn die Bank die PIN ablehnt. Ein erneuter Versuch mit derselben PIN
könnte den Zugang sperren.

Die Aussetzung wird in `daten/umsatz-abruf-status.properties` gespeichert und übersteht einen
Neustart des Containers. So entstehen keine ungewollten Freigabe-Anfragen auf dem Handy, solange
du nicht reagieren kannst.

Bei pushTAN fragt die Anwendung während der Wartezeit selbst regelmäßig bei der Bank nach, ob
freigegeben wurde. Im Browser ist dafür nichts einzugeben. „Freigegeben – jetzt prüfen“
verkürzt nur die Pause bis zur nächsten Nachfrage.

### Notfall-E-Mail

An `MAIL_NOTFALL` (mehrere Adressen mit Komma getrennt) gehen drei Arten von E-Mails:

| Betreff | Wann |
|---|---|
| Freigabe angefordert für Konto DE00 \*\*\*\* 0000 | Die Bank verlangt gerade eine Freigabe oder Eingabe |
| Autorisierung erforderlich – Abrufe ausgesetzt | Die Autorisierung ist nicht erfolgt, mit Anleitung und Link |
| Autorisierung erneuert – Abrufe laufen wieder | Nach erfolgreicher Autorisierung im Browser |

Einrichtung in `.env`:

```bash
MAIL_NOTFALL=ich@example.org
MAIL_HOST=smtp.example.org
MAIL_SICHERHEIT=starttls      # starttls (587), ssl (465) oder keine (25)
MAIL_BENUTZER=absender@example.org
MAIL_PASSWORT=...
```

Prüfen lässt sich die Einrichtung in der Weboberfläche, Karte **„E-Mail-Postfach“**:
- Sie zeigt Server, Port, Verschlüsselung, Anmeldename, Absender und Notfall-Adresse, aber nie
  das Passwort.
- Sie listet auf, was fehlt oder falsch ist, z.B. „MAIL_HOST (SMTP-Server) fehlt“. Eine
  unvollständige Einrichtung verhindert den Start nicht, es werden dann nur keine E-Mails
  verschickt.
- **„Test-E-Mail senden“** schickt eine Test-E-Mail über das konfigurierte Postfach, an die
  Notfall-Adresse oder an eine im Feld eingetragene Adresse. Ein Fehler, z.B. eine abgelehnte
  Anmeldung, erscheint direkt in der Oberfläche.
- **„Autorisierungs-Mail senden“** schickt die E-Mail „Autorisierung erforderlich“ an dieselben
  Empfänger. Sind die Abrufe ausgesetzt, geht sie als Erinnerung mit dem aktuellen Grund raus
  („Autorisierungs-Mail erneut senden“). Sonst geht sie als Muster mit „[Test]“ im Betreff, damit
  man sieht, wie sie aussieht.

Was in den E-Mails steht:
- Zeitpunkt, Konto nur maskiert, Grund und ein Link zur Weboberfläche (`WEB_ADRESSE`, Standard
  `https://<WEB_DOMAIN>`).
- PIN, TAN und TAN-Grafiken werden nie verschickt.
- Bei `starttls` wird ohne Verschlüsselung nicht gesendet.
- Bei Google, Microsoft 365 u.ä. ist meist ein App-Passwort nötig.

Mit eingerichteter Notfall-E-Mail gilt außerdem: Eine Freigabe-Anfrage in der Banking-App, zu
der keine E-Mail kam, stammt nicht von diesem System.

Ein nicht erreichbarer Mailserver verhindert keine Abrufe. Er wird nur protokolliert.

---

## Weboberfläche

- **Übersicht**
  - Offene Anfrage der Bank mit Eingabefeld (TAN, PIN, Auswahl) bzw. Hinweis zur Freigabe in der
    App. Bei photoTAN/QR-TAN wird die Grafik angezeigt.
  - Banner „Autorisierung erforderlich“ mit Knopf „Jetzt autorisieren“, wenn die Abrufe
    ausgesetzt sind.
  - Status: Konto, laufender Abruf, Intervall, nächster Abruf, Knopf „Jetzt abrufen“.
  - E-Mail-Postfach: Einrichtung, fehlende Angaben, Test-E-Mail und Autorisierungs-Mail an die
    Notfall-Adresse oder eine frei wählbare Adresse.
  - Export: Links zu CSV, JSON und XML aller Umsätze mit den vollständigen Adressen für externe
    Anwendungen.
  - Die letzten 20 Abrufe mit Ergebnis oder Grund des Scheiterns, Hinweise der Bank.
  - Die Seite aktualisiert sich selbst, sobald sich etwas ändert.
- **Buchungen** – neueste zuerst, mit Zeitraum, Suche (Name, Verwendungszweck, IBAN,
  Buchungstext) und den Summen der Einnahmen und Ausgaben aller Treffer. Darunter
  Export-Links (CSV, JSON, XML) für den gefilterten Zeitraum.

Es gibt genau einen Benutzer, `WEB_BENUTZER` und `WEB_PASSWORT` aus der Konfiguration.
Alle übrigen Einstellungen stehen in `.env` bzw. `fints.properties`.

---

## Export-Schnittstelle

Externe Anwendungen können die gespeicherten Buchungen des Kontos abrufen. Die Links stehen auch
in der Weboberfläche: auf der Übersicht (Karte „Export“, mit den vollständigen Adressen) und auf
der Seite „Buchungen“ (für den gefilterten Zeitraum).

```
GET /api/buchungen.json
GET /api/buchungen.csv
GET /api/buchungen.xml
GET /api/buchungen?format=json|csv|xml        (Standard: json)
```

| Parameter | Bedeutung |
|---|---|
| `tage=N` | Die letzten N Tage einschließlich heute. `tage=1` ist nur heute, `tage=3` sind heute, gestern und vorgestern. |
| `monat=JJJJ-MM` | Ein Kalendermonat, z.B. `monat=2026-10` |
| `jahr=JJJJ` | Ein Kalenderjahr, z.B. `jahr=2026` |
| `von`, `bis` | Fester Zeitraum, Buchungsdatum von/bis einschließlich, Format `JJJJ-MM-TT`. Einzeln möglich (`von=…` = ab diesem Tag). |
| `nachId` | Nur Buchungen mit größerer ID. Zum fortlaufenden Abholen: die höchste bisher erhaltene `id` merken und beim nächsten Mal mitgeben. Mit jeder Zeitangabe kombinierbar. |

Von den Zeitangaben (`tage`, `monat`, `jahr`, `von`/`bis`) ist jeweils nur eine Art erlaubt.
Ohne Zeitangabe kommen alle Buchungen. JSON und XML enthalten den aufgelösten Zeitraum (`von`,
`bis`).

Beispiele:

```bash
curl https://umsatz.example.org/api/buchungen.json?tage=3            # letzte 3 Tage
curl https://umsatz.example.org/api/buchungen.csv?monat=2026-10 -o oktober.csv
curl https://umsatz.example.org/api/buchungen.xml?jahr=2026
curl "https://umsatz.example.org/api/buchungen.json?von=2026-10-01&bis=2026-10-15"
curl https://umsatz.example.org/api/buchungen.json?nachId=1234       # nur neue Buchungen
```

Die Karte „Export“ in der Weboberfläche zeigt diese Beispiele mit dem für heute aufgelösten
Zeitraum und Links für CSV, JSON und XML. Dazu gibt es ein Formular für einen eigenen Zeitraum.

**Inhalt:**
- **Kontodaten:** IBAN, BIC, BLZ, Inhaber, Kontoart, Währung (in JSON und XML).
- **Je Buchung:** dieselben Felder wie in der Datenbank. Das sind `id`, `buchungsdatum`, `valuta`,
  `betrag`, `waehrung`, `saldo`, `ursprungsbetrag`, `gebuehren`, `storno`, `buchungstext`,
  `gvCode`, `verwendungszweck`, Gegenkonto (`gegenName`, `gegenIban`, `gegenBic` …),
  SEPA-Referenzen (`endToEndId`, `mandatsreferenz`, `purposeCode`), Bankreferenzen, `sepa`,
  `format` und `gespeichertAm`.
- **Reihenfolge:** Die Buchungen sind nach `id` aufsteigend sortiert. Neue Buchungen bekommen
  immer höhere IDs.

**Formate:**
- Beträge mit Dezimalpunkt (`-89.90`), Datum `JJJJ-MM-TT`, Zeitpunkte ISO 8601 in UTC
  (`2026-10-31T18:24:02Z`). Kodierung durchgehend UTF-8.
- **CSV:** Kopfzeile mit den Feldnamen, Semikolon als Trenner, Zeilenende CRLF, Felder mit `;`,
  `"` oder Zeilenumbruch in Anführungszeichen (RFC 4180). Die Werte stehen roh in der Datei.
  Öffnet man sie in Excel, können Verwendungszwecke, die mit `=` beginnen, als Formel gelten.
- **JSON:** `{ "erstellt", "konto": {…}, "anzahl", "buchungen": [ … ] }`.
- **XML:** `<umsaetze erstellt anzahl><konto>…</konto><buchungen><buchung id="…">…</buchung></buchungen></umsaetze>`.
  Leere Felder werden weggelassen.
- Der Header `X-Anzahl-Buchungen` enthält die Anzahl der Buchungen.

**Fehler:** 400 bei unbekanntem Format oder ungültigen bzw. mehreren Zeitangaben, 401 ohne bzw. mit falschen
Zugangsdaten (wenn geschützt), 404 wenn abgeschaltet, 409 bei `AUSGABE_ZIEL=csv` (der Export
liest aus der Datenbank), 429 nach zu vielen Fehlversuchen. Die Meldung steht als Klartext im
Text der Antwort.

### Schutz mit Basic Auth

Ohne weitere Angaben ist die Schnittstelle **ohne Anmeldung** erreichbar. Hinter Caddy mit
öffentlicher Domain gilt das für das ganze Internet. Die Anwendung warnt dann beim Start im Log.
Zum Schützen in `.env` beide Werte setzen:

```bash
EXPORT_BENUTZER=buchhaltung
EXPORT_PASSWORT=...          # mindestens 8 Zeichen oder {bcrypt}-Hash (in .env jedes $ als $$)
```

Danach verlangt `/api/**` HTTP Basic Auth:

```bash
curl -u buchhaltung:PASSWORT https://umsatz.example.org/api/buchungen.json
```

- **Nur für den Export:** Die Export-Zugangsdaten gelten nur für den Export. Mit ihnen kann man
  sich nicht an der Weboberfläche anmelden, und umgekehrt gilt der Web-Login nicht als Basic
  Auth für den Export.
- **Links in der Weboberfläche:** Wer in der Weboberfläche angemeldet ist, kann die Export-Links
  ohne weitere Abfrage nutzen.
- **Login-Sperre:** Fehlversuche zählen für die Sperre wie beim Web-Login. Nach 5 Fehlversuchen
  ist die Adresse 15 Minuten gesperrt und bekommt HTTP 429.
- **Nur über HTTPS:** Basic Auth überträgt das Passwort bei jedem Aufruf, nur Base64-kodiert,
  also nicht verschlüsselt. Deshalb nur über HTTPS (Caddy) nutzen.
- **Einen Hash erzeugen:** `docker compose run --rm umsatz-abruf passwort-hash` erzeugt ihn
  genauso wie für das Web-Passwort.

Unabhängig vom Schutz erlaubt die Schnittstelle nur lesende `GET`-Aufrufe. Mit
`EXPORT_AKTIV=false` lässt sie sich ganz abschalten.

---

## Caddy und Zugriff von außen

`docker-compose.yml` startet Caddy vor der Anwendung. Die Anwendung selbst ist nur im internen
Docker-Netz erreichbar, nach außen öffnet nur Caddy die Ports 80 und 443.

- **Öffentliche Domain:** `WEB_DOMAIN=umsatz.example.org`. Der DNS-Eintrag muss auf den Server
  zeigen, und die Ports 80/443 müssen aus dem Internet erreichbar sein. Caddy holt das
  Zertifikat automatisch bei Let's Encrypt und erneuert es.
- **Nur lokal / im VPN:** `WEB_DOMAIN=localhost` oder der Name des Servers im VPN. Ohne
  öffentliche Erreichbarkeit ist ein VPN (WireGuard, Tailscale) der sicherste Weg für den
  Zugriff von unterwegs.
- **Andere Ports**, falls 80/443 belegt sind: `HTTP_PORT` und `HTTPS_PORT` in `.env`.
- **Anpassungen** (z.B. zusätzliche Header, Basic-Auth als zweite Hürde) im `Caddyfile`.

Die Anwendung übernimmt die Client-Adresse und das Protokoll aus den Kopfzeilen von Caddy
(`SERVER_FORWARD_HEADERS_STRATEGY=native`). Das braucht die Login-Sperre je Adresse, und daraus
ergeben sich das Secure-Cookie und HSTS.

---

## Sicherheit

- **Login:** genau ein Benutzer. Das Passwort hat mindestens 8 Zeichen, besser ist ein
  bcrypt-Hash: `docker compose run --rm umsatz-abruf passwort-hash` fragt das Passwort ab und
  gibt die Zeile für `.env` aus, jedes `$` ist dort als `$$` geschrieben.
- **Login-Sperre:** nach 5 Fehlversuchen 15 Minuten je Adresse.
- **Sitzung:** läuft nach 30 Minuten ohne Aktivität ab. Das Cookie ist `HttpOnly`,
  `SameSite=Strict` und hinter Caddy `Secure`.
- **Schutz im Browser:** CSRF-Schutz für alle Aktionen, strikte Content-Security-Policy, kein
  Einbetten in fremde Seiten, keine Indexierung durch Suchmaschinen.
- **Zugangsdaten:** `.env` mit `chmod 600` schützen. Die Datei ist per `.gitignore` vom
  Versionieren ausgeschlossen. Umgebungsvariablen eines Containers sind per `docker inspect`
  für jeden mit Docker-Zugriff lesbar. Der Zugang zum Server ist damit gleichbedeutend mit dem
  Zugang zu den Kontodaten.
- **Ohne HTTPS nie ins Internet:** Die Weboberfläche nie ohne HTTPS freigeben. PIN und TAN
  gingen sonst im Klartext über das Netz.

---

## Nur Umsatzabfragen

HBCI4Java beherrscht den ganzen FinTS-Umfang, auch Überweisungen, Lastschriften und
Daueraufträge. Dieses Programm nutzt davon bewusst nur die Umsatzabfrage und ist auf drei
Ebenen dagegen abgesichert, Aufträge auszuführen:

1. **Im Programm:**
   - Alle Aufträge an die Bank entstehen in `NurLesend`. Dort sind nur die Umsatzabfragen
     `KUmsAll` und `KUmsAllCamt` erlaubt. Alles andere wird abgewiesen, bevor eine Nachricht an
     die Bank geht.
   - Meldet die Bank eine Empfängerüberprüfung (gibt es nur bei Zahlungen), bricht das Programm
     sofort ab.
   - Der Test `NurLesendTest` lässt den Build scheitern, sobald Code an `NurLesend` vorbei
     Aufträge erzeugt oder Auftragsklassen von HBCI4Java (z.B. `GVUebSEPA`) verwendet.
2. **Bei der Bank:** Jede Zahlung braucht eine eigene Freigabe, und die pushTAN-App zeigt dabei
   Empfänger und Betrag. Am wirksamsten ist ein **eigener Online-Banking-Zugang für dieses
   Programm mit reinen Leserechten oder einem Überweisungslimit von 0 €**, den viele Banken auf
   Nachfrage anbieten. Nur diese Ebene schützt auch dann, wenn jemand den Server samt PIN in die
   Hände bekommt.
3. **Bei der Freigabe:** Dieses Programm fordert nur Freigaben für Anmeldung und Umsatzabruf an.
   Zeigt die Banking-App einen Betrag oder Empfänger, die Freigabe ablehnen.

---

## Konfiguration

Jeder Wert steht in `.env` (für Docker Compose) oder in einer `fints.properties`. Die Namen der
Umgebungsvariablen sind die Schlüssel in Großbuchstaben, aus `.` wird `_`. Umgebungsvariablen
haben Vorrang. Ausführlich kommentiert: [`.env.example`](.env.example) und
[`fints.properties.example`](fints.properties.example). Eine `fints.properties` lässt sich über
die auskommentierten Zeilen in `docker-compose.yml` einbinden.

| Schlüssel | Umgebungsvariable | Standard | Bedeutung |
|---|---|---|---|
| `fints.blz` | `FINTS_BLZ` | – (Pflicht) | Bankleitzahl |
| `fints.benutzer` | `FINTS_BENUTZER` | – (Pflicht) | Anmeldename / Legitimations-ID |
| `fints.iban` | `FINTS_IBAN` | – (Pflicht) | Abzurufendes Konto |
| `fints.kundenid` | `FINTS_KUNDENID` | = Benutzer | Kundenkennung |
| `fints.pin` | `FINTS_PIN` | Eingabe im Browser | PIN – für automatische Abrufe setzen |
| `fints.url` | `FINTS_URL` | Bankenliste | FinTS-Serveradresse |
| `fints.produktid` | `FINTS_PRODUKTID` | Test-Registrierung | FinTS-Produktregistrierung |
| `fints.tanverfahren` | `FINTS_TANVERFAHREN` | Auswahl im Browser | Code des TAN-Verfahrens, z.B. `921` |
| `fints.tanmedium` | `FINTS_TANMEDIUM` | Auswahl im Browser | Name des TAN-Geräts |
| `intern.verzeichnis` | `INTERN_VERZEICHNIS` | im Container `/var/lib/umsatz-abruf` | Von der Anwendung verwaltet: Passport-Datei, Schlüssel, Status |
| `passport.passwort` | `PASSPORT_PASSWORT` | von der Anwendung erzeugt | Nur setzen, wenn ein eigenes Passwort gewünscht ist |
| `abruf.intervall` | `ABRUF_INTERVALL` | `6h` | Abstand der Abrufe (≥ 15m), `aus` = nur per Knopf |
| `abruf.tage` | `ABRUF_TAGE` | `7` | Anzahl vergangener Tage je Abruf |
| `abruf.von` / `abruf.bis` | `ABRUF_VON` / `ABRUF_BIS` | – / heute | Fester Zeitraum (JJJJ-MM-TT), nur zum Nachholen |
| `abruf.format` | `ABRUF_FORMAT` | `auto` | `auto`, `camt` oder `mt940` – möglichst nicht wechseln |
| `ausgabe.ziel` | `AUSGABE_ZIEL` | `datenbank` | `datenbank`, `csv` oder `beide` |
| `db.datei` | `DB_DATEI` | `umsaetze.db` | SQLite-Datei |
| `ausgabe.datei` | `AUSGABE_DATEI` | `umsaetze.csv` | CSV-Datei (bei `csv`/`beide`) |
| `web.benutzer` | `WEB_BENUTZER` | – (Pflicht) | Benutzer der Weboberfläche |
| `web.passwort` | `WEB_PASSWORT` | – (Pflicht) | Passwort (≥ 8 Zeichen) oder `{bcrypt}`-Hash |
| `web.adresse` | `WEB_ADRESSE` | `https://<WEB_DOMAIN>` | Adresse für Links in E-Mails |
| `web.wartezeit` | `WEB_WARTEZEIT` | `10` | Minuten bis zum Aussetzen ohne Autorisierung |
| `export.aktiv` | `EXPORT_AKTIV` | `true` | Export-Schnittstelle `/api/buchungen` |
| `export.benutzer` / `export.passwort` | `EXPORT_BENUTZER` / `EXPORT_PASSWORT` | – (offen!) | Basic Auth für den Export; ohne Angabe ohne Anmeldung erreichbar |
| `mail.notfall` | `MAIL_NOTFALL` | – (aus) | Notfall-Adresse(n) |
| `mail.host` | `MAIL_HOST` | – | SMTP-Server |
| `mail.sicherheit` | `MAIL_SICHERHEIT` | `starttls` | `starttls`, `ssl` oder `keine` |
| `mail.port` | `MAIL_PORT` | 587 / 465 / 25 | SMTP-Port |
| `mail.benutzer` | `MAIL_BENUTZER` | – | SMTP-Anmeldename |
| `mail.passwort` | `MAIL_PASSWORT` | – | SMTP-Passwort |
| `mail.absender` | `MAIL_ABSENDER` | = `mail.benutzer` | Absenderadresse |
| `log.level` | `LOG_LEVEL` | `2` | 0–5; ab 4 stehen Bankdaten im Log |
| – | `WEB_DOMAIN` | – (Pflicht für Caddy) | Domain für Caddy |
| – | `HTTP_PORT` / `HTTPS_PORT` | `80` / `443` | Ports von Caddy auf dem Host |
| – | `DATEN_VERZEICHNIS` | `./daten` | Datenverzeichnis auf dem Host |

Spring-Boot-Einstellungen lassen sich ebenfalls per Umgebungsvariable ändern, z.B.
`SERVER_SERVLET_SESSION_TIMEOUT=15m`.

---

## Daten und Datenbank

| Ort | Inhalt |
|---|---|
| Datenverzeichnis `daten/` (`DATEN_VERZEICHNIS`, im Container `/data`) | `umsaetze.db` (+ `-wal`, `-shm` im Betrieb): SQLite-Datenbank mit allen Buchungen |
| Docker-Volume `umsatz-abruf_intern` (im Container `/var/lib/umsatz-abruf`) | Von der Anwendung verwaltet: Passport-Datei, ihr Schlüssel, Betriebszustand (Aussetzung). Nicht anfassen. |

Nach außen gemountet ist nur die Datenbank. Das interne Volume übersteht Updates des Containers
(`docker compose up -d --build`). Gelöscht wird es nur mit `docker compose down -v`. Die Folge
wäre lediglich eine einmalige neue Freigabe bei der Bank.

Eine Passport-Datei früherer Versionen in `daten/` übernimmt die Anwendung beim Start. Kennt sie
deren Passwort nicht, weil es früher im Browser eingegeben wurde, legt sie die Datei unter
`/var/lib/umsatz-abruf/alt/` beiseite und erzeugt eine neue. Das kostet einmalig eine Freigabe
mit „Jetzt autorisieren“.

### Tabellen

Schema: [`src/main/resources/de/fehrenbacher/fints/schema.sql`](src/main/resources/de/fehrenbacher/fints/schema.sql).
Die Anwendung legt fehlende Tabellen beim Start selbst an.

- **`konto`** – das abgerufene Konto: IBAN, BIC, BLZ, Inhaber, Kontoart, Währung.
- **`abruf`** – ein Eintrag je erfolgreichem Abruf mit Zeitraum, Format, Anzahl gelieferter und
  neuer Buchungen.
- **`buchung`** – eine Zeile je gebuchter Kontobewegung:
  - Buchungsdatum, Valuta, Betrag, Währung, Saldo danach, Ursprungsbetrag, Gebühren, Storno
  - Buchungstext, Geschäftsvorfall-Code, Verwendungszweck
  - Gegenkonto (Name, IBAN, BIC, Kontonummer, BLZ, Gläubiger-ID)
  - SEPA-Referenzen (End-to-End-ID, Mandatsreferenz, Purpose Code), Bankreferenzen
  - Vorgemerkte Buchungen werden nicht gespeichert.

**Beträge stehen in ganzen Cent** (`betrag_cent`, `saldo_cent` …), damit Summen exakt sind:
12,34 EUR = `1234`. Datumswerte sind Text im Format `JJJJ-MM-TT`.

**Doppelte Buchungen erkennen:** Jede Buchung bekommt einen SHA-256-Schlüssel aus ihren
fachlichen Merkmalen (Datum, Betrag, Gegenkonto, Verwendungszweck …). Identische Buchungen,
z.B. zweimal derselbe Kaffee am selben Tag, werden durchnummeriert. Ein eindeutiger Index
verhindert Duplikate. Ein Wechsel von `ABRUF_FORMAT` kann trotzdem zu Duplikaten führen, weil
Banken die Texte in CAMT und MT940 unterschiedlich liefern.

### Auswerten

Die Datei lässt sich mit jedem SQLite-Werkzeug öffnen, z.B. `sqlite3`, DB Browser for SQLite oder
DBeaver. Am besten öffnest du sie nur lesend oder arbeitest mit einer Kopie (siehe Sicherung).
Beispiele, getestet mit dem Schema:

```sql
-- Letzte 20 Buchungen (Beträge in Euro)
SELECT buchungsdatum, betrag_cent / 100.0 AS betrag, waehrung, gegen_name, verwendungszweck
FROM buchung
ORDER BY buchungsdatum DESC, id DESC
LIMIT 20;

-- Einnahmen und Ausgaben je Monat
SELECT strftime('%Y-%m', buchungsdatum)                                   AS monat,
       SUM(CASE WHEN betrag_cent > 0 THEN betrag_cent ELSE 0 END) / 100.0 AS einnahmen,
       SUM(CASE WHEN betrag_cent < 0 THEN betrag_cent ELSE 0 END) / 100.0 AS ausgaben,
       SUM(betrag_cent) / 100.0                                           AS saldo_aenderung
FROM buchung
GROUP BY monat
ORDER BY monat;

-- Die zehn größten Ausgabenposten der letzten 12 Monate nach Empfänger
SELECT COALESCE(gegen_name, buchungstext) AS empfaenger,
       COUNT(*)                           AS anzahl,
       SUM(betrag_cent) / 100.0           AS summe
FROM buchung
WHERE betrag_cent < 0
  AND buchungsdatum >= date('now', '-1 year')
GROUP BY empfaenger
ORDER BY summe
LIMIT 10;

-- Kontostand am Ende jedes Buchungstags
SELECT buchungsdatum, saldo_cent / 100.0 AS saldo
FROM buchung
WHERE id IN (SELECT MAX(id) FROM buchung WHERE saldo_cent IS NOT NULL GROUP BY buchungsdatum)
ORDER BY buchungsdatum;

-- Die letzten Abrufe
SELECT a.zeitpunkt, k.iban, a.von, a.bis, a.format, a.anzahl_geliefert, a.anzahl_neu
FROM abruf a JOIN konto k ON k.id = a.konto_id
ORDER BY a.id DESC
LIMIT 10;
```

Die Suche in der Weboberfläche unterscheidet bei Umlauten zwischen Groß- und Kleinschreibung,
weil SQLite das bei `LIKE` nur für ASCII-Buchstaben ignoriert.

### Sicherung

Das Datenverzeichnis enthält sämtliche Kontobewegungen. Sichere es regelmäßig, verschlüssle die
Sicherung und bewahre sie außerhalb des Servers auf. Das interne Volume musst du nicht sichern:
Ohne es verlangt die Bank nur einmal eine neue Freigabe.

```bash
# Einfach und sicher: kurz anhalten, kopieren, weiterlaufen lassen
docker compose stop umsatz-abruf
tar czf umsatz-abruf-$(date +%F).tar.gz daten/
docker compose start umsatz-abruf

# Im laufenden Betrieb (mit installiertem sqlite3 auf dem Host): konsistente Kopie der Datenbank
sqlite3 daten/umsaetze.db ".backup 'umsaetze-$(date +%F).db'"
```

Im laufenden Betrieb nicht einfach nur `umsaetze.db` kopieren. Zur Datenbank gehören dann noch
die Dateien `-wal` und `-shm`.

**Wiederherstellen:** Anwendung stoppen, Dateien zurück nach `daten/` kopieren (Eigentümer
UID 1000), starten.

---

## Betrieb und Wartung

| Aufgabe | Befehl |
|---|---|
| Starten / nach Änderung der `.env` neu starten | `docker compose up -d` |
| Neu bauen (nach einem Update des Codes) | `docker compose up -d --build` |
| Status | `docker compose ps` |
| Log der Anwendung | `docker compose logs --tail 100 umsatz-abruf` |
| Stoppen | `docker compose stop` |
| Container entfernen (Daten bleiben) | `docker compose down` |
| Passport-Datei neu anlegen lassen (erzwingt neue Freigabe) | `docker compose down && docker volume rm umsatz-abruf_intern && docker compose up -d` |
| Caddy-Image aktualisieren | `docker compose pull caddy && docker compose up -d` |
| Passwort-Hash erzeugen | `docker compose run --rm umsatz-abruf passwort-hash` |

Beide Container starten nach einem Neustart des Rechners automatisch
(`restart: unless-stopped`). `docker compose down -v` löscht zusätzlich die Zertifikate von
Caddy und das interne Volume (neue Freigabe nötig), das Datenverzeichnis bleibt.

**Schemaänderungen:** Die Anwendung legt nur fehlende Tabellen an. Ändert eine neue Version das
Schema bestehender Tabellen, steht die nötige Anpassung in deren Änderungshinweisen.

**Umstieg von einer älteren Version mit CSV oder MariaDB:** Diese Daten werden nicht
automatisch übernommen. Die Datenbank füllt sich ab dem ersten Abruf. Ältere Umsätze lassen
sich einmalig mit `ABRUF_VON` nachholen, was meist eine Freigabe verlangt.

---

## Fehlerbehebung

| Symptom | Ursache und Lösung |
|---|---|
| Container startet nicht, Log: `Konfigurationsfehler: …` | Die Meldung nennt den fehlenden oder falschen Wert, in `.env` korrigieren, `docker compose up -d`. |
| Caddy: `WEB_DOMAIN in .env setzen` | `WEB_DOMAIN` fehlt in `.env`. |
| Browser zeigt 502 | Die Anwendung startet noch (einige Sekunden) oder läuft nicht: `docker compose logs umsatz-abruf`. |
| Kein Zertifikat für die Domain | DNS zeigt nicht auf den Server oder Ports 80/443 nicht erreichbar: `docker compose logs caddy`. |
| Banner „Autorisierung erforderlich“ | Gewollt: „Jetzt autorisieren“ wählen und in der App freigeben. Der Grund steht im Banner. |
| Jeder Abruf verlangt die PIN | `FINTS_PIN` in `.env` setzen. |
| „Die Bank hat die PIN abgelehnt“ | PIN prüfen und korrigieren, dann „Jetzt autorisieren“. Nicht mehrfach mit falscher PIN versuchen. |
| `Zu viele Fehlversuche` beim Login | Login-Sperre: 15 Minuten warten. |
| Keine E-Mails | Karte „E-Mail-Postfach“: Sie zeigt fehlende Angaben. „Test-E-Mail senden“ zeigt den Fehler des Mailservers. |
| `Permission denied` auf `/data` | `daten/` gehört nicht UID 1000: `sudo chown -R 1000:1000 daten`. |
| Buchungen doppelt | Nach einem Wechsel von `ABRUF_FORMAT`. |
| Warnung zur Test-Registrierung von HBCI4Java | Eigene Produktregistrierung in `FINTS_PRODUKTID` eintragen. |

---

## Entwicklung und Tests

Für den Docker-Betrieb ist lokal kein Java nötig. Gebaut wird im Container. Zum Entwickeln
ohne Docker braucht es ein **JDK 25**, z.B. `sudo apt install openjdk-25-jdk`.

```bash
./mvnw package                     # Bauen inkl. Tests (JDK 25), Ergebnis: target/umsatz-abruf.jar
# ohne lokales JDK 25, mit Docker:
docker run --rm -v "$PWD":/src -w /src -u "$(id -u):$(id -g)" -e HOME=/tmp/home \
  -v "$HOME/.m2":/tmp/home/.m2 maven:3.9.11-eclipse-temurin-25 mvn -B package
./mvnw spring-boot:run             # Direkt aus den Quellen, Konfiguration aus fints.properties
```

Die Tests laufen ohne Bank, Datenbankserver und Mailserver. SQLite läuft in einer temporären
Datei, die Bank wird simuliert.

| Klasse | Aufgabe |
|---|---|
| `UmsatzAbrufAnwendung` | Einstieg, Laden und Prüfen der Konfiguration, `passwort-hash` |
| `PassportVerwaltung` | Passport-Datei und ihr Passwort im internen Verzeichnis |
| `ZeitplanDienst` | Abruf im Intervall, pausiert bei Aussetzung |
| `AbrufDienst` | Ablauf eines Abrufs, Verlauf, Aussetzen bei fehlender Autorisierung |
| `Betriebszustand` | Gespeicherte Aussetzung (übersteht Neustarts) |
| `UmsatzAbruf`, `BankCallback` | Bankdialog mit HBCI4Java |
| `NurLesend` | Einzige Stelle für Aufträge an die Bank, erlaubt nur Umsatzabfragen |
| `WebInteraktion` | Eingaben und Freigaben, die die Bank verlangt, über den Browser |
| `UmsatzDatenbank`, `UmsatzDatei` | Speichern (SQLite, CSV) und Erkennen bekannter Buchungen |
| `Benachrichtigung` | E-Mails an die Notfall-Adresse |
| `WebController`, `WebSicherheit`, `LoginSperre`, `BuchungsAnsicht` | Weboberfläche |
| `ExportController`, `UmsatzExport` | Export-Schnittstelle (CSV, JSON, XML) |

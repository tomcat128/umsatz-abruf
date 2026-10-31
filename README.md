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
- **Nur lesend:** Das Programm führt keine Überweisungen oder sonstigen Aufträge aus, siehe
  [Nur Umsatzabfragen](#nur-umsatzabfragen).

Grundlage sind [HBCI4Java](https://github.com/hbci4j/hbci4java) (FinTS 3.0), Spring Boot 3.5 und SQLite.

## Inhalt

- [Schnellstart](#schnellstart)
- [Ablauf im Betrieb](#ablauf-im-betrieb)
  - [Intervall](#intervall)
  - [Autorisierung und Aussetzen](#autorisierung-und-aussetzen)
  - [Notfall-E-Mail](#notfall-e-mail)
- [Weboberfläche](#weboberfläche)
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
#   eintragen: Bankzugang (FINTS_*), FINTS_PIN, PASSPORT_PASSWORT,
#              WEB_DOMAIN, WEB_BENUTZER, WEB_PASSWORT, Notfall-E-Mail (MAIL_*)
mkdir -p daten              # vor dem ersten Start anlegen, sonst gehört es root
docker compose up -d --build
```

Danach: `https://<WEB_DOMAIN>/` öffnen und anmelden. Zum Ausprobieren auf dem eigenen Rechner
genügt `WEB_DOMAIN=localhost` (Caddy verwendet dann ein lokales Zertifikat, der Browser warnt
einmalig).

**Erster Abruf:** Er startet eine Minute nach dem Start, oder sofort mit „Jetzt abrufen“. Dabei
wird die Passport-Datei angelegt. Sie speichert verschlüsselt die Bankparameter und die
System-ID, an der die Bank dieses System wiedererkennt. Fast immer verlangt die Bank beim ersten
Mal eine Freigabe: Sie erscheint in der Weboberfläche, bei pushTAN genügt die Bestätigung in
der App.

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

Damit die automatischen Abrufe ohne Eingriff laufen, sollten **`FINTS_PIN` und
`PASSPORT_PASSWORT` gesetzt** sein. Fehlen sie, verlangt jeder Abruf die Eingabe im Browser.
Bleibt sie aus, werden die Abrufe ausgesetzt.

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

Prüfen lässt sich die Einrichtung mit dem Knopf **„Test-E-Mail senden“** in der Weboberfläche.

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
  - Status: Konto, laufender Abruf, Intervall, nächster Abruf, Notfall-Adresse.
  - Knöpfe „Jetzt abrufen“ und „Test-E-Mail senden“.
  - Die letzten 20 Abrufe mit Ergebnis oder Grund des Scheiterns, Hinweise der Bank.
  - Die Seite aktualisiert sich selbst, sobald sich etwas ändert.
- **Buchungen** – neueste zuerst, mit Zeitraum, Suche (Name, Verwendungszweck, IBAN,
  Buchungstext) und den Summen der Einnahmen und Ausgaben aller Treffer.

Es gibt genau einen Benutzer, `WEB_BENUTZER` und `WEB_PASSWORT` aus der Konfiguration.
Alle übrigen Einstellungen stehen in `.env` bzw. `fints.properties`.

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

- **Login:** genau ein Benutzer. Das Passwort hat mindestens 12 Zeichen, besser ist ein
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
| `passport.datei` | `PASSPORT_DATEI` | `umsatz-abruf.passport` | Passport-Datei |
| `passport.passwort` | `PASSPORT_PASSWORT` | Eingabe im Browser | Passwort der Passport-Datei – für automatische Abrufe setzen |
| `abruf.intervall` | `ABRUF_INTERVALL` | `6h` | Abstand der Abrufe (≥ 15m), `aus` = nur per Knopf |
| `abruf.tage` | `ABRUF_TAGE` | `7` | Anzahl vergangener Tage je Abruf |
| `abruf.von` / `abruf.bis` | `ABRUF_VON` / `ABRUF_BIS` | – / heute | Fester Zeitraum (JJJJ-MM-TT), nur zum Nachholen |
| `abruf.format` | `ABRUF_FORMAT` | `auto` | `auto`, `camt` oder `mt940` – möglichst nicht wechseln |
| `ausgabe.ziel` | `AUSGABE_ZIEL` | `datenbank` | `datenbank`, `csv` oder `beide` |
| `db.datei` | `DB_DATEI` | `umsaetze.db` | SQLite-Datei |
| `ausgabe.datei` | `AUSGABE_DATEI` | `umsaetze.csv` | CSV-Datei (bei `csv`/`beide`) |
| `web.benutzer` | `WEB_BENUTZER` | – (Pflicht) | Benutzer der Weboberfläche |
| `web.passwort` | `WEB_PASSWORT` | – (Pflicht) | Passwort (≥ 12 Zeichen) oder `{bcrypt}`-Hash |
| `web.adresse` | `WEB_ADRESSE` | `https://<WEB_DOMAIN>` | Adresse für Links in E-Mails |
| `web.wartezeit` | `WEB_WARTEZEIT` | `10` | Minuten bis zum Aussetzen ohne Autorisierung |
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

Alles liegt im Datenverzeichnis (`DATEN_VERZEICHNIS`, Standard `./daten`, im Container `/data`):

| Datei | Inhalt |
|---|---|
| `umsaetze.db` (+ `-wal`, `-shm` im Betrieb) | SQLite-Datenbank mit allen Buchungen |
| `umsatz-abruf.passport` | Verschlüsselte Bankparameter und System-ID – **nicht verlieren** |
| `umsatz-abruf-status.properties` | Ob die Abrufe ausgesetzt sind |

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

Das Datenverzeichnis enthält sämtliche Kontobewegungen und die Passport-Datei. Sichere es
regelmäßig, verschlüssle die Sicherung und bewahre sie außerhalb des Servers auf.

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
| Caddy-Image aktualisieren | `docker compose pull caddy && docker compose up -d` |
| Passwort-Hash erzeugen | `docker compose run --rm umsatz-abruf passwort-hash` |

Beide Container starten nach einem Neustart des Rechners automatisch
(`restart: unless-stopped`). `docker compose down -v` löscht zusätzlich die Zertifikate von
Caddy, das Datenverzeichnis bleibt.

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
| Jeder Abruf verlangt PIN oder Passwort | `FINTS_PIN` bzw. `PASSPORT_PASSWORT` in `.env` setzen. |
| „Die Bank hat die PIN abgelehnt“ | PIN prüfen und korrigieren, dann „Jetzt autorisieren“. Nicht mehrfach mit falscher PIN versuchen. |
| `Zu viele Fehlversuche` beim Login | Login-Sperre: 15 Minuten warten. |
| Keine E-Mails | „Test-E-Mail senden“ im Browser, die Fehlermeldung erscheint dort und im Log. |
| `Permission denied` auf `/data` | `daten/` gehört nicht UID 1000: `sudo chown -R 1000:1000 daten`. |
| Buchungen doppelt | Nach einem Wechsel von `ABRUF_FORMAT`. |
| Warnung zur Test-Registrierung von HBCI4Java | Eigene Produktregistrierung in `FINTS_PRODUKTID` eintragen. |

---

## Entwicklung und Tests

```bash
./mvnw package                     # Bauen inkl. Tests, Ergebnis: target/umsatz-abruf.jar
./mvnw spring-boot:run             # Direkt aus den Quellen, Konfiguration aus fints.properties
```

Die Tests laufen ohne Bank, Datenbankserver und Mailserver. SQLite läuft in einer temporären
Datei, die Bank wird simuliert.

| Klasse | Aufgabe |
|---|---|
| `UmsatzAbrufAnwendung` | Einstieg, Laden und Prüfen der Konfiguration, `passwort-hash` |
| `ZeitplanDienst` | Abruf im Intervall, pausiert bei Aussetzung |
| `AbrufDienst` | Ablauf eines Abrufs, Verlauf, Aussetzen bei fehlender Autorisierung |
| `Betriebszustand` | Gespeicherte Aussetzung (übersteht Neustarts) |
| `UmsatzAbruf`, `BankCallback` | Bankdialog mit HBCI4Java |
| `NurLesend` | Einzige Stelle für Aufträge an die Bank, erlaubt nur Umsatzabfragen |
| `WebInteraktion` | Eingaben und Freigaben, die die Bank verlangt, über den Browser |
| `UmsatzDatenbank`, `UmsatzDatei` | Speichern (SQLite, CSV) und Erkennen bekannter Buchungen |
| `Benachrichtigung` | E-Mails an die Notfall-Adresse |
| `WebController`, `WebSicherheit`, `LoginSperre`, `BuchungsAnsicht` | Weboberfläche |

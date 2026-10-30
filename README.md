# Umsatz-Abruf

Ruft per FinTS (PIN/TAN) die Umsätze eines Girokontos ab und sammelt sie in einer
CSV-Datei und/oder einer MariaDB-/MySQL-Datenbank. Bereits gespeicherte Buchungen werden
erkannt, jeder Lauf ergänzt nur neue.

Zwei Betriebsarten:

- **Server** – läuft dauerhaft, ruft nach Zeitplan ab und bietet eine Weboberfläche mit Login.
  Fordert die Bank eine Freigabe an (pushTAN-App, TAN, photoTAN, QR-TAN) oder fehlt eine
  Eingabe, lässt sie sich dort von überall beantworten – auch von unterwegs mit dem Handy.
  Eine zweite Seite zeigt die gespeicherten Buchungen mit Filter und Suche.
- **Konsole** – einmaliger Abruf mit Eingaben im Terminal.

Optional meldet das Programm per E-Mail, wenn es bei der Bank eine Freigabe anfordert.
Das Programm liest nur – es kann keine Überweisungen oder sonstigen Aufträge ausführen.

Grundlage sind [HBCI4Java](https://github.com/hbci4j/hbci4java) (FinTS 3.0) und Spring Boot 3.5.

## Inhalt

- [Schnellstart](#schnellstart)
- [Serverbetrieb und Weboberfläche](#serverbetrieb-und-weboberfläche)
  - [Einrichtung](#einrichtung)
  - [Zugriff von unterwegs](#zugriff-von-unterwegs)
  - [Ablauf einer Freigabe](#ablauf-einer-freigabe)
  - [Sicherheit der Weboberfläche](#sicherheit-der-weboberfläche)
- [Konsolenbetrieb](#konsolenbetrieb)
- [Konfiguration](#konfiguration)
- [E-Mail-Benachrichtigung](#e-mail-benachrichtigung)
- [Datenbank](#datenbank)
  - [Variante A: MariaDB-Container](#variante-a-mariadb-container)
  - [Variante B: Eigener Datenbankserver](#variante-b-eigener-datenbankserver)
  - [Datenmodell](#datenmodell)
  - [Erkennung doppelter Buchungen](#erkennung-doppelter-buchungen)
  - [Beispielabfragen](#beispielabfragen)
  - [Starten, Stoppen, Status](#starten-stoppen-status)
  - [Sicherung und Wiederherstellung](#sicherung-und-wiederherstellung)
  - [Passwörter](#passwörter)
  - [Updates](#updates)
  - [Lesezugriff für Auswertungen](#lesezugriff-für-auswertungen)
  - [Daten löschen](#daten-löschen)
  - [Sicherheit](#sicherheit)
  - [Fehlerbehebung](#fehlerbehebung)
- [Entwicklung und Tests](#entwicklung-und-tests)

---

## Schnellstart

### Server mit Docker (empfohlen)

```bash
cp .env.example .env && chmod 600 .env   # Bankzugang, WEB_BENUTZER, WEB_PASSWORT eintragen
mkdir -p daten                           # vor dem ersten Start anlegen, sonst gehört es root
docker compose up -d --build
```

Die Weboberfläche läuft dann unter <http://127.0.0.1:8080/>. Ergebnisse, Passport-Datei und
CSV liegen in `daten/`. Für den Zugriff von unterwegs siehe [unten](#zugriff-von-unterwegs).

Statt der `.env` kann auch eine `fints.properties` eingebunden werden:
`docker compose -f docker-compose.properties.yml up -d --build`.

### Lokal (Java 17)

```bash
./mvnw package
cp fints.properties.example fints.properties && chmod 600 fints.properties   # Werte eintragen
java -jar target/umsatz-abruf.jar server     # Serverbetrieb
java -jar target/umsatz-abruf.jar            # einmaliger Abruf im Terminal
```

### Erster Lauf

Beim ersten Abruf wird eine Passport-Datei angelegt. Sie speichert verschlüsselt die
Bankparameter und die System-ID, an der die Bank dieses System wiedererkennt. Danach folgen
PIN, Auswahl des TAN-Verfahrens und die erste Freigabe. Im Serverbetrieb erscheinen alle diese
Abfragen in der Weboberfläche – den ersten Abruf daher dort mit „Jetzt abrufen“ starten.

Die Passport-Datei muss erhalten bleiben. Geht sie verloren, verlangt die Bank eine neue
Freigabe, weil sie das System als neues Gerät ansieht. Auch bei erhaltener Passport-Datei
fordern Banken regelmäßig (je nach Bank alle 90 oder 180 Tage) sowie für Umsätze älter als
90 Tage eine erneute Freigabe.

---

## Serverbetrieb und Weboberfläche

### Einrichtung

Pflicht für den Serverbetrieb sind der Bankzugang und **ein** Benutzer für die Weboberfläche:

```bash
# .env
WEB_BENUTZER=ich
WEB_PASSWORT=...          # mindestens 12 Zeichen – besser als Hash, siehe unten
ABRUF_ZEITPLAN=           # leer = täglich 6 Uhr, "-" = nur per Knopf
```

**Passwort als Hash** – dann steht es nicht im Klartext in der Konfiguration:

```bash
java -jar target/umsatz-abruf.jar passwort-hash
docker compose run --rm umsatz-abruf passwort-hash
```

Der Befehl fragt das Passwort doppelt ab und gibt die Zeile für `fints.properties`
(`web.passwort={bcrypt}$2a$10$...`) und für `.env` aus. In `.env` muss jedes `$` als `$$`
geschrieben werden, der Befehl gibt diese Form gleich mit aus.

**Damit automatische Abrufe ohne Eingabe durchlaufen**, sollten `FINTS_PIN` und
`PASSPORT_PASSWORT` gesetzt sein. Fehlen sie, wartet jeder Lauf in der Weboberfläche auf die
Eingabe (mit E-Mail-Benachrichtigung, falls konfiguriert). Eine Freigabe in der App verlangt die
Bank trotzdem regelmäßig, siehe [Ablauf einer Freigabe](#ablauf-einer-freigabe).

**Zeitplan** (`abruf.zeitplan`): Spring-Cron mit sechs Feldern – Sekunde, Minute, Stunde, Tag,
Monat, Wochentag. Beispiele: `0 0 6 * * *` (täglich 6 Uhr, Standard), `0 30 7,19 * * *`
(7:30 und 19:30), `0 0 6 * * MON-FRI` (werktags), `-` (kein automatischer Abruf). Läuft bereits
ein Abruf, wird der geplante übersprungen.

**Seiten der Weboberfläche:**

- **Übersicht** – offene Anfrage der Bank mit Eingabefeld, Status, nächster geplanter Abruf,
  Knopf „Jetzt abrufen“, die letzten 20 Abrufe mit Ergebnis oder Fehler, Hinweise der Bank.
  Die Seite aktualisiert sich selbst, sobald sich etwas ändert.
- **Buchungen** – gespeicherte Buchungen, neueste zuerst, mit Zeitraum, Suche
  (Name, Verwendungszweck, IBAN, Buchungstext) und Summen der Einnahmen und Ausgaben. Quelle ist
  die Datenbank, wenn sie Speicherziel ist, sonst die CSV-Datei.

Konsole und Server dürfen nicht gleichzeitig laufen, beide nutzen dieselbe Passport-Datei.
Für einen Abruf zwischendurch den Knopf „Jetzt abrufen“ verwenden.

### Zugriff von unterwegs

Der Container gibt die Weboberfläche nur auf `127.0.0.1:8080` frei. Für den Zugriff von
außen gibt es zwei Wege:

**1. VPN (am sichersten).** Mit WireGuard, Tailscale o.ä. ist der Server von unterwegs wie im
Heimnetz erreichbar, die Weboberfläche bleibt für das Internet unsichtbar. Dazu `WEB_PORT` bzw.
die Portfreigabe in `docker-compose.yml` auf die VPN-Adresse des Servers legen.

**2. HTTPS mit eigener Domain.** `docker-compose.https.yml` stellt Caddy als Reverse-Proxy davor,
der das Zertifikat automatisch bei Let's Encrypt holt und erneuert.

```bash
# .env
WEB_DOMAIN=umsatz.example.org
COMPOSE_FILE=docker-compose.yml:docker-compose.https.yml
# (mit Datenbank: docker-compose.yml:docker-compose.https.yml:docker-compose.mariadb.yml)

docker compose up -d
```

Voraussetzungen: Der DNS-Eintrag der Domain zeigt auf den Server, und die Ports 80 und 443 sind
aus dem Internet erreichbar. Das Overlay setzt `WEB_ADRESSE=https://<WEB_DOMAIN>` (Link in den
E-Mails) und `SERVER_FORWARD_HEADERS_STRATEGY=native`. Damit übernimmt die Anwendung die echte
Client-Adresse für die Login-Sperre und erkennt HTTPS für Secure-Cookie und HSTS.

Die Weboberfläche nie ohne HTTPS ins Internet stellen: PIN, TAN und Passwort gingen sonst im
Klartext über das Netz.

### Ablauf einer Freigabe

1. Der Zeitplan oder der Knopf „Jetzt abrufen“ startet einen Abruf.
2. Verlangt die Bank eine Freigabe, erscheint sie oben in der Übersicht. Ist die
   [E-Mail-Benachrichtigung](#e-mail-benachrichtigung) eingerichtet, kommt zusätzlich eine
   E-Mail mit Link zur Weboberfläche.
3. Je nach TAN-Verfahren:
   - **pushTAN / Freigabe in der App (decoupled):** Einfach in der Banking-App bestätigen. Das
     Programm fragt selbst regelmäßig bei der Bank nach und erkennt die Freigabe automatisch.
     „Freigegeben – jetzt prüfen“ verkürzt die Wartezeit bis zur nächsten Nachfrage.
   - **TAN, photoTAN, QR-TAN:** TAN in der Weboberfläche eingeben. Die photoTAN- bzw.
     QR-Grafik wird dort angezeigt.
4. Ohne Antwort innerhalb von `web.wartezeit` (Standard 10 Minuten) bricht der Abruf ab, der
   Verlauf zeigt den Grund. Der nächste geplante Abruf versucht es erneut. Banken verwerfen
   offene TAN-Anfragen meist nach einigen Minuten, eine viel längere Wartezeit bringt daher wenig.
5. „Abbrechen“ beendet den Abruf sofort.

Auf dieselbe Weise fragt die Weboberfläche fehlende PIN, Passport-Passwort, TAN-Verfahren und
TAN-Medium ab.

Die Pause zwischen zwei Nachfragen bei der App-Freigabe beträgt mindestens 5 Sekunden bzw. die
von der Bank verlangte Zeit. Begrenzt die Bank die Zahl der Nachfragen, wird die Pause so
verlängert, dass die Nachfragen für die ganze Wartezeit reichen.

### Sicherheit der Weboberfläche

- **Benutzer:** Genau ein Benutzer aus der Konfiguration. Das Passwort hat mindestens 12 Zeichen,
  besser ist ein bcrypt-Hash.
- **Login-Sperre:** Nach 5 falschen Anmeldungen ist die Adresse für 15 Minuten gesperrt.
- **Sitzung:** läuft nach 30 Minuten ohne Aktivität ab. Das Cookie ist `HttpOnly` und
  `SameSite=Strict`, hinter HTTPS auch `Secure`.
- **Schutz im Browser:** CSRF-Schutz für alle Aktionen, strikte Content-Security-Policy (nur
  eigene Skripte und Styles), kein Einbetten in fremde Seiten, keine Indexierung durch
  Suchmaschinen.
- **Bankzugriff nur lesend:** Selbst mit gültigem Login lassen sich nur Umsätze abrufen und
  ansehen.
- **Fremde Freigaben erkennen:** Mit eingerichteter E-Mail-Benachrichtigung gilt: Eine
  Freigabe-Anfrage in der Banking-App, zu der keine E-Mail kam, stammt nicht von diesem System.

---

## Konsolenbetrieb

Einmaliger Abruf, alle Eingaben im Terminal, danach ist das Programm beendet. Exit-Code 0 =
erfolgreich, 1 = Abruf fehlgeschlagen, 2 = Konfigurationsfehler.

```bash
java -jar target/umsatz-abruf.jar [abruf] [konfigurationsdatei] [--schluessel=wert ...]
java -jar target/umsatz-abruf.jar abruf --abruf.tage=30      # einzelne Werte überschreiben
docker compose run --rm umsatz-abruf abruf                   # im Container (Server vorher stoppen)
```

Weitere Befehle: `server`, `mail-test` (Test-E-Mail senden), `passwort-hash`.

---

## Konfiguration

Jeder Wert kann in `fints.properties`, als Umgebungsvariable oder als `--schluessel=wert` auf der
Befehlszeile gesetzt werden. Vorrang: Befehlszeile vor Umgebungsvariable vor Datei. Name der
Variablen: Großbuchstaben, `.` wird `_`. Ausführlich kommentiert:
[`fints.properties.example`](fints.properties.example) und [`.env.example`](.env.example).

| Schlüssel | Umgebungsvariable | Standard | Bedeutung |
|---|---|---|---|
| `fints.blz` | `FINTS_BLZ` | – (Pflicht) | Bankleitzahl |
| `fints.benutzer` | `FINTS_BENUTZER` | – (Pflicht) | Anmeldename / Legitimations-ID |
| `fints.iban` | `FINTS_IBAN` | – (Pflicht) | Abzurufendes Konto |
| `fints.kundenid` | `FINTS_KUNDENID` | = Benutzer | Kundenkennung |
| `fints.pin` | `FINTS_PIN` | Abfrage | PIN |
| `fints.url` | `FINTS_URL` | Bankenliste | FinTS-Serveradresse |
| `fints.produktid` | `FINTS_PRODUKTID` | Test-Registrierung | FinTS-Produktregistrierung |
| `fints.tanverfahren` | `FINTS_TANVERFAHREN` | Auswahl | Code des TAN-Verfahrens, z.B. `921` |
| `fints.tanmedium` | `FINTS_TANMEDIUM` | Auswahl | Name des TAN-Geräts |
| `passport.datei` | `PASSPORT_DATEI` | `umsatz-abruf.passport` | Passport-Datei |
| `passport.passwort` | `PASSPORT_PASSWORT` | Abfrage | Passwort der Passport-Datei |
| `abruf.tage` | `ABRUF_TAGE` | `7` | Anzahl vergangener Tage |
| `abruf.von` / `abruf.bis` | `ABRUF_VON` / `ABRUF_BIS` | – / heute | Fester Zeitraum (JJJJ-MM-TT) |
| `abruf.format` | `ABRUF_FORMAT` | `auto` | `auto`, `camt` oder `mt940` |
| `abruf.zeitplan` | `ABRUF_ZEITPLAN` | `0 0 6 * * *` | Zeitplan im Serverbetrieb, `-` = aus |
| `web.benutzer` | `WEB_BENUTZER` | – (Pflicht für Server) | Benutzer der Weboberfläche |
| `web.passwort` | `WEB_PASSWORT` | – (Pflicht für Server) | Passwort (≥ 12 Zeichen) oder `{bcrypt}`-Hash |
| `web.adresse` | `WEB_ADRESSE` | – | Öffentliche Adresse, wird in E-Mails verlinkt |
| `web.wartezeit` | `WEB_WARTEZEIT` | `10` | Minuten bis zum Abbruch ohne Eingabe/Freigabe |
| `ausgabe.ziel` | `AUSGABE_ZIEL` | `csv` | `csv`, `datenbank` oder `beide` |
| `ausgabe.datei` | `AUSGABE_DATEI` | `umsaetze.csv` | CSV-Datei |
| `db.url` | `DB_URL` | – | JDBC-URL, z.B. `jdbc:mariadb://host:3306/umsaetze` |
| `db.benutzer` | `DB_BENUTZER` | – | Datenbankbenutzer |
| `db.passwort` | `DB_PASSWORT` | – | Datenbankpasswort |
| `mail.empfaenger` | `MAIL_EMPFAENGER` | – (aus) | Empfänger der Benachrichtigung |
| `mail.host` | `MAIL_HOST` | – | SMTP-Server |
| `mail.sicherheit` | `MAIL_SICHERHEIT` | `starttls` | `starttls`, `ssl` oder `keine` |
| `mail.port` | `MAIL_PORT` | 587 / 465 / 25 | SMTP-Port |
| `mail.benutzer` | `MAIL_BENUTZER` | – | SMTP-Anmeldename |
| `mail.passwort` | `MAIL_PASSWORT` | – | SMTP-Passwort |
| `mail.absender` | `MAIL_ABSENDER` | = `mail.benutzer` | Absenderadresse |
| `log.level` | `LOG_LEVEL` | `2` | 0–5; ab 4 stehen Bankdaten im Log |

Einstellungen von Spring Boot selbst (Port, Sitzungsdauer …) lassen sich ebenso per
Umgebungsvariable ändern, z.B. `SERVER_PORT=8081` oder `SERVER_SERVLET_SESSION_TIMEOUT=15m`.

---

## E-Mail-Benachrichtigung

Fordert die Bank während des Abrufs eine Freigabe an – Bestätigung in der pushTAN-App,
TAN-Eingabe, photoTAN oder QR-TAN –, sendet das Programm vorher eine E-Mail. Damit lässt sich
eine Push-Nachricht der Bank-App eindeutig diesem System zuordnen. Kommt eine Freigabe-Anfrage
**ohne** passende E-Mail, stammt sie nicht von hier: nicht freigeben, Bank informieren.
Im Serverbetrieb wird zusätzlich gemeldet, wenn PIN, Passport-Passwort oder eine Auswahl in
der Weboberfläche erwartet werden.

Aktiv, sobald `mail.empfaenger` gesetzt ist. Beispiel `.env`:

```bash
MAIL_EMPFAENGER=ich@example.org
MAIL_HOST=smtp.example.org
MAIL_SICHERHEIT=starttls
MAIL_BENUTZER=absender@example.org
MAIL_PASSWORT=...
```

Test ohne Bankkontakt:

```bash
java -jar target/umsatz-abruf.jar mail-test
docker compose run --rm umsatz-abruf mail-test
docker compose -f docker-compose.properties.yml run --rm umsatz-abruf mail-test /config/fints.properties
```

Inhalt der E-Mail:
- Zeitpunkt, Rechner (im Container `umsatz-abruf`), BLZ
- Konto nur maskiert, z.B. `DE89 **** 3000`
- Zeitraum, Art der Freigabe und Hinweistext der Bank
- Link zur Weboberfläche, wenn `web.adresse` gesetzt ist

PIN, TAN und TAN-Grafiken werden nie verschickt.

Verhalten:

- Der Mailserver wird **vor** dem Kontakt zur Bank geprüft (Verbindung und Anmeldung). Ist er
  nicht erreichbar, bricht der Abruf ab, bevor eine Freigabe ausgelöst wird.
- Scheitert der Versand später während des Abrufs, erscheint nur eine Warnung; der Abruf läuft weiter.
- Bei `starttls` wird ohne erfolgreiche Verschlüsselung nicht gesendet, das Passwort geht nie
  im Klartext über das Netz. `keine` nur für einen Mailserver im eigenen Netz verwenden.
- Bei Google, Microsoft 365 u.ä. ist meist ein App-Passwort nötig.

---

## Datenbank

Statt oder zusätzlich zur CSV-Datei können die Buchungen in einer Datenbank gespeichert werden.

- **Unterstützt:** MariaDB ab 10.5, MySQL ab 8.0 (getestet mit MariaDB 11.4 und MySQL 8.4).
- **Treiber:** MariaDB Connector/J, im Programm enthalten. `jdbc:mysql://`-URLs werden akzeptiert.
- **Schema:** [`src/main/resources/de/fehrenbacher/fints/schema.sql`](src/main/resources/de/fehrenbacher/fints/schema.sql).
  Das Programm legt fehlende Tabellen beim Start selbst an.
- **Ablauf:** Die Datenbankverbindung wird **vor** dem Kontakt zur Bank geprüft. Ein Fehler kostet
  also keine TAN-Freigabe. Jeder Abruf wird in einer Transaktion gespeichert: ganz oder gar nicht.

### Variante A: MariaDB-Container

`docker-compose.mariadb.yml` ergänzt eine der beiden Compose-Dateien um einen MariaDB-11.4-Container.

**Einrichtung**

1. In `.env` ein Passwort für den Datenbankbenutzer setzen:
   ```bash
   DB_PASSWORT=ein-langes-zufaelliges-passwort
   ```
   Optional: `DB_NAME` (Standard `umsaetze`), `DB_BENUTZER` (Standard `umsatz`),
   `DB_HOST_PORT` (Standard `3307`), `AUSGABE_ZIEL` (Standard mit diesem Overlay: `datenbank`;
   `beide` schreibt zusätzlich die CSV).
2. Damit nicht bei jedem Befehl beide Dateien angegeben werden müssen, in `.env` ergänzen:
   ```bash
   COMPOSE_FILE=docker-compose.yml:docker-compose.mariadb.yml
   # bzw. mit fints.properties:
   # COMPOSE_FILE=docker-compose.properties.yml:docker-compose.mariadb.yml
   ```
   **Alle folgenden `docker compose`-Befehle setzen das voraus.** Ohne `COMPOSE_FILE` jeweils
   `-f docker-compose.yml -f docker-compose.mariadb.yml` angeben.
3. Starten. MariaDB startet mit, das Programm wartet, bis sie bereit ist:
   ```bash
   docker compose up -d
   ```

Beim allerersten Start legt der Container Datenbank und Benutzer an. Dabei wird auch ein
zufälliges root-Passwort erzeugt und **nur einmal** ins Log geschrieben – bei Bedarf sofort notieren:

```bash
docker compose logs mariadb | grep "GENERATED ROOT PASSWORD"
```

Für den normalen Betrieb ist root nicht nötig: Der Benutzer `umsatz` hat alle Rechte auf seiner Datenbank.

**Speicherort:** Docker-Volume `umsatz-abruf_mariadb-daten`. Der Projektname ist in den
Compose-Dateien fest auf `umsatz-abruf` gesetzt, Umbenennen oder Verschieben des Ordners ändert
daran nichts.

**Zugriff vom Host** (nur über `127.0.0.1`, nicht aus dem Netz):

```bash
mariadb -h 127.0.0.1 -P 3307 -u umsatz -p umsaetze          # MariaDB-Client auf dem Host
docker compose exec mariadb mariadb -u umsatz -p umsaetze  # ohne lokalen Client
```

Grafische Werkzeuge (DBeaver, HeidiSQL, DataGrip …): Host `127.0.0.1`, Port `3307`.

### Variante B: Eigener Datenbankserver

Datenbank und Benutzer einmalig als Administrator anlegen:

```sql
CREATE DATABASE umsaetze CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'umsatz'@'%' IDENTIFIED BY 'ein-langes-zufaelliges-passwort';
GRANT SELECT, INSERT, UPDATE, CREATE, INDEX, REFERENCES ON umsaetze.* TO 'umsatz'@'%';
```

Diese Rechte genügen für den Betrieb einschließlich des automatischen Anlegens der Tabellen.
Wer die Tabellen selbst anlegt (`mariadb -u admin -p umsaetze < schema.sql`), kommt im Betrieb mit
`SELECT, INSERT, UPDATE` aus. `'%'` nach Möglichkeit auf die Adresse des abrufenden Rechners einschränken.

Konfiguration (`fints.properties` oder `.env`):

```properties
ausgabe.ziel=datenbank
db.url=jdbc:mariadb://datenbank.example:3306/umsaetze
db.benutzer=umsatz
db.passwort=...
```

**MySQL 8** ohne SSL-Verbindung braucht den Zusatz `?allowPublicKeyRetrieval=true` an der URL.

**Datenbank auf dem Docker-Host, Programm im Container:** `localhost` zeigt im Container auf den
Container selbst. Stattdessen in `docker-compose.yml` beim Dienst `umsatz-abruf` ergänzen

```yaml
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

und `DB_URL=jdbc:mariadb://host.docker.internal:3306/umsaetze` setzen. Der Datenbankserver muss dafür
auch auf der Docker-Bridge lauschen (`bind-address`) und der Benutzer von dort (`172.%`) zugelassen sein.

### Datenmodell

```
konto 1───n abruf 1───n buchung
  └──────────────────n─────┘
```

**`konto`** – die eigenen, abgerufenen Konten (eindeutig über die IBAN). IBAN, BIC, BLZ,
Kontonummer, Inhaber, Kontoart, Währung. Wird bei jedem Abruf mit den Angaben der Bank aktualisiert.

**`abruf`** – ein Eintrag pro Lauf: Zeitpunkt, Zeitraum (`von`, `bis`), Format (CAMT/MT940),
`anzahl_geliefert` (von der Bank) und `anzahl_neu` (davon gespeichert). Läufe ohne neue Buchungen
werden ebenfalls protokolliert.

**`buchung`** – eine Zeile je gebuchter Kontobewegung. Vorgemerkte Buchungen werden nicht
gespeichert, weil sie sich bis zur Buchung noch ändern können.

| Gruppe | Spalten |
|---|---|
| Zuordnung | `konto_id`, `abruf_id` (Abruf, mit dem die Buchung gespeichert wurde), `gespeichert_am` |
| Erkennung | `schluessel`, `vorkommen` (siehe unten) |
| Datum und Betrag | `buchungsdatum`, `valuta`, `betrag` (negativ = Belastung), `waehrung`, `saldo` (Kontostand danach laut Bank) |
| Weitere Beträge | `ursprungsbetrag`, `ursprungswaehrung` (z.B. Fremdwährung), `gebuehren`, `gebuehren_waehrung` |
| Art | `buchungstext` (z.B. KARTENZAHLUNG), `gv_code` (Geschäftsvorfall-Code, 999 = unstrukturiert), `textschluessel_zusatz`, `primanota`, `storno` |
| Text | `verwendungszweck`, `zusatzinfo` (Rohdaten bei unstrukturierten Buchungen) |
| Gegenkonto | `gegen_name`, `gegen_iban`, `gegen_bic`, `gegen_kontonummer`, `gegen_blz`, `glaeubiger_id` |
| SEPA-Referenzen | `end_to_end_id`, `mandatsreferenz`, `purpose_code` (z.B. SALA = Gehalt) |
| Sonstige Referenzen | `kundenreferenz`, `bankreferenz`, `bank_buchungs_id` (nur CAMT) |
| Herkunft | `sepa`, `format` (CAMT/MT940) |

Indizes: eindeutig `(konto_id, schluessel, vorkommen)`, außerdem `(konto_id, buchungsdatum)` und `gegen_iban`.

Liefert die Bank einen Wert, der länger als die Spalte ist, wird er gekürzt, statt den Abruf
scheitern zu lassen (betrifft nur ungewöhnliche Daten; Verwendungszweck und Zusatzinfo sind `TEXT`).

### Erkennung doppelter Buchungen

Da sich die Abrufzeiträume überlappen, liefert die Bank viele Buchungen mehrfach. Gespeichert
wird nur, was noch fehlt:

- `schluessel` ist ein SHA-256 über die fachlichen Merkmale: Buchungsdatum, Valuta, Betrag,
  Währung, Buchungstext, Gegenkonto, Verwendungszweck, End-to-End-ID, Mandatsreferenz, Storno.
  Das sind dieselben Merkmale wie in der CSV-Datei. Der Saldo gehört nicht dazu.
- Echte identische Buchungen (zweimal derselbe Kaffee am selben Tag) zählt `vorkommen` durch
  (1, 2, …). Liefert die Bank eine Buchung öfter, als sie gespeichert ist, wird nur der Rest ergänzt.
- Der eindeutige Index verhindert Duplikate auch dann, wenn zwei Abrufe gleichzeitig laufen. Der
  zweite bricht dann mit einem Fehler ab und speichert nichts; beim nächsten Lauf wird nachgeholt.

Grenzen:

- **Format nicht wechseln.** CAMT und MT940 liefern Texte teils unterschiedlich – nach einem Wechsel
  von `abruf.format` können Buchungen doppelt erscheinen. Kandidaten findet Abfrage 7 unten.
- **Lücken werden nicht gefüllt.** Bei `abruf.tage=7` mindestens einmal pro Woche abrufen. Ältere
  Umsätze einmalig mit `abruf.von` nachholen (meist mit TAN).
- Die Daten einer bestehenden CSV-Datei werden nicht in die Datenbank übernommen.

### Beispielabfragen

Alle Abfragen getestet mit MariaDB 11.4.

```sql
-- 1. Letzte 20 Buchungen
SELECT buchungsdatum, betrag, waehrung, buchungstext, gegen_name, verwendungszweck
FROM buchung
ORDER BY buchungsdatum DESC, id DESC
LIMIT 20;

-- 2. Einnahmen und Ausgaben je Monat
SELECT DATE_FORMAT(buchungsdatum, '%Y-%m')              AS monat,
       SUM(CASE WHEN betrag > 0 THEN betrag ELSE 0 END) AS einnahmen,
       SUM(CASE WHEN betrag < 0 THEN betrag ELSE 0 END) AS ausgaben,
       SUM(betrag)                                      AS saldo_aenderung
FROM buchung
GROUP BY monat
ORDER BY monat;

-- 3. Die zehn größten Ausgabenposten der letzten 12 Monate nach Empfänger
SELECT COALESCE(gegen_name, buchungstext) AS empfaenger,
       COUNT(*)                           AS anzahl,
       SUM(betrag)                        AS summe
FROM buchung
WHERE betrag < 0
  AND buchungsdatum >= CURDATE() - INTERVAL 1 YEAR
GROUP BY empfaenger
ORDER BY summe
LIMIT 10;

-- 4. Volltextsuche in Verwendungszweck und Name
SELECT buchungsdatum, betrag, gegen_name, verwendungszweck
FROM buchung
WHERE verwendungszweck LIKE '%Gehalt%' OR gegen_name LIKE '%Gehalt%'
ORDER BY buchungsdatum;

-- 5. Kontostand am Ende jedes Buchungstags
SELECT b.buchungsdatum, b.saldo
FROM buchung b
JOIN (SELECT buchungsdatum, MAX(id) AS id FROM buchung WHERE saldo IS NOT NULL GROUP BY buchungsdatum) l
  ON l.id = b.id
ORDER BY b.buchungsdatum;

-- 6. Die letzten Abrufe
SELECT a.zeitpunkt, k.iban, a.von, a.bis, a.format, a.anzahl_geliefert, a.anzahl_neu
FROM abruf a JOIN konto k ON k.id = a.konto_id
ORDER BY a.id DESC
LIMIT 10;

-- 7. Mögliche Duplikate nach einem Formatwechsel (gleicher Tag und Betrag, anderes Format)
SELECT b1.id, b2.id AS moegliches_duplikat, b1.buchungsdatum, b1.betrag, b1.format, b2.format AS format2
FROM buchung b1
JOIN buchung b2
  ON  b2.konto_id = b1.konto_id
  AND b2.buchungsdatum = b1.buchungsdatum
  AND b2.betrag = b1.betrag
  AND b2.id > b1.id
  AND b2.format <> b1.format;

-- 8. Größe der Tabellen
SELECT table_name, table_rows, ROUND((data_length + index_length) / 1024 / 1024, 2) AS mb
FROM information_schema.tables
WHERE table_schema = DATABASE();
```

Bei mehreren Konten in einer Datenbank zusätzlich nach Konto filtern, z.B.
`WHERE konto_id = (SELECT id FROM konto WHERE iban = 'DE…')`.

### Starten, Stoppen, Status

| Aufgabe | Befehl |
|---|---|
| Server und Datenbank starten | `docker compose up -d` |
| Nur die Datenbank starten | `docker compose up -d mariadb` |
| Server und Datenbank neu bauen und starten | `docker compose up -d --build` |
| Status | `docker compose ps` |
| Log der Datenbank / des Servers | `docker compose logs --tail 50 mariadb` / `… umsatz-abruf` |
| Stoppen (Daten bleiben) | `docker compose stop` |
| Container entfernen (Daten bleiben) | `docker compose down` |
| **Alles inkl. Daten löschen** | `docker compose down -v` |

Server und Datenbank starten nach einem Neustart des Rechners automatisch
(`restart: unless-stopped`), solange sie nicht ausdrücklich gestoppt wurden.

### Sicherung und Wiederherstellung

Das Docker-Volume ist keine Sicherung. Regelmäßig einen Dump anlegen und außerhalb des Rechners
aufbewahren – er enthält sämtliche Kontobewegungen, also verschlüsselt ablegen.

**Sicherung** (Container läuft; konsistent dank `--single-transaction`, ohne Unterbrechung):

```bash
docker compose exec -T mariadb sh -c \
  'mariadb-dump --single-transaction -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"' \
  > umsaetze-$(date +%F).sql
```

Das Passwort wird dabei aus den Umgebungsvariablen des Containers gelesen und erscheint
nicht in der Befehlszeile des Hosts. Für eine tägliche Sicherung per cron (Pfad anpassen; `%`
muss in der crontab maskiert werden):

```cron
30 2 * * * cd /pfad/zu/umsatz-abruf && docker compose exec -T mariadb sh -c 'mariadb-dump --single-transaction -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"' | gzip > sicherung/umsaetze-$(date +\%F).sql.gz
```

**Wiederherstellung** (überschreibt die Tabellen in der Datenbank):

```bash
docker compose up -d mariadb
docker compose exec -T mariadb sh -c \
  'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"' \
  < umsaetze-2026-10-30.sql
```

Hinweise:

- Ein Dump von MariaDB 11.4 beginnt mit einer Zeile `/*M!999999\- enable the sandbox mode */`, die
  MySQL und ältere MariaDB-Versionen nicht verstehen. Zum Umzug dorthin diese erste Zeile entfernen.
- Auf einem eigenen Server (Variante B) braucht die Wiederherstellung mehr Rechte als der Betrieb
  (u.a. `DROP`, `ALTER`, `LOCK TABLES`) – als Administrator einspielen.
- Die CSV-Datei und vor allem die **Passport-Datei** (`daten/umsatz-abruf.passport`) gehören mit in
  die Sicherung.

### Passwörter

**Passwort des Datenbankbenutzers ändern** (MariaDB-Container): `DB_PASSWORT` in `.env` wirkt nur
beim allerersten Start. Eine spätere Änderung dort ändert das Passwort in der Datenbank **nicht**
und führt zu „Access denied“. Richtige Reihenfolge:

```bash
docker compose exec mariadb mariadb -u umsatz -p
#   SET PASSWORD = PASSWORD('neues-passwort');
#   exit
# danach DB_PASSWORT in .env auf das neue Passwort setzen und den Container neu erzeugen,
# damit auch die Sicherungsbefehle das neue Passwort verwenden (Daten bleiben erhalten):
docker compose up -d mariadb
```

**root-Passwort vergessen:** Im Normalbetrieb nicht nötig. Falls doch: Sicherung anlegen, Container
mit `--skip-grant-tables` starten oder Volume neu aufsetzen und Sicherung einspielen.

### Updates

- **Fehlerbehebungen innerhalb von MariaDB 11.4** (empfohlen, z.B. monatlich):
  ```bash
  docker compose pull mariadb && docker compose up -d mariadb
  ```
- **Neue Hauptversion** (z.B. 11.4 → 11.8): vorher sichern, in `docker-compose.mariadb.yml` das Image
  ändern und einmalig `MARIADB_AUTO_UPGRADE: "1"` bei `mariadb.environment` ergänzen, damit die
  Systemtabellen angepasst werden.
- **Programm aktualisieren:** `docker compose up -d --build` bzw. `./mvnw package`. Das Programm legt nur
  **fehlende** Tabellen an (`CREATE TABLE IF NOT EXISTS`). Ändert eine neue Version das Schema
  bestehender Tabellen, steht die nötige Anpassung (`ALTER TABLE …`) in deren Änderungshinweisen
  und muss einmalig von Hand eingespielt werden.

### Lesezugriff für Auswertungen

Die Weboberfläche zeigt die Buchungen bereits an. Für Tabellenkalkulation, Grafana o.ä. einen eigenen Benutzer nur mit Leserechten anlegen (als root):

```sql
CREATE USER 'auswertung'@'%' IDENTIFIED BY '...';
GRANT SELECT ON umsaetze.* TO 'auswertung'@'%';
```

### Daten löschen

Ein Konto mit allen Buchungen und Abrufen entfernen (Reihenfolge wegen der Fremdschlüssel):

```sql
SET @konto = (SELECT id FROM konto WHERE iban = 'DE…');
DELETE FROM buchung WHERE konto_id = @konto;
DELETE FROM abruf   WHERE konto_id = @konto;
DELETE FROM konto   WHERE id = @konto;
```

Einzelne Buchungen löschen ist möglich; liegen sie noch im Abrufzeitraum, werden sie beim
nächsten Lauf wieder ergänzt.

### Sicherheit

- Die Datenbank enthält die vollständige Kontohistorie. Port 3307 ist nur auf `127.0.0.1` freigegeben;
  nicht auf `0.0.0.0` ändern, ohne zusätzlich TLS und eine Firewall einzurichten.
- `.env` und `fints.properties` mit `chmod 600` schützen; beide sind per `.gitignore` vom
  Versionieren ausgeschlossen.
- Passwörter in Umgebungsvariablen sind für jeden mit Docker-Zugriff per `docker inspect`
  lesbar. PIN und Passport-Passwort daher besser leer lassen und beim Start eingeben.
- Sicherungen verschlüsselt und getrennt vom Rechner aufbewahren.
- Auswertungen mit einem Nur-Lese-Benutzer durchführen (siehe oben).

### Fehlerbehebung

| Meldung / Symptom | Ursache und Lösung |
|---|---|
| `Verbindung zur Datenbank … fehlgeschlagen` / `Access denied` | Passwort falsch – häufig, weil `DB_PASSWORT` in `.env` nach dem ersten Start geändert wurde. Siehe [Passwörter](#passwörter). |
| `Connection refused` / `Socket fail to connect` | Datenbank läuft nicht oder falscher Host/Port. `docker compose ps`, bei eigenem Server Firewall und `bind-address` prüfen. |
| `Unknown database` | Datenbank fehlt (Variante B): `CREATE DATABASE` ausführen. |
| `Public Key Retrieval is not allowed` | MySQL 8 ohne SSL: `?allowPublicKeyRetrieval=true` an die URL anhängen. |
| `CREATE command denied` / `REFERENCES command denied` | Benutzer hat zu wenig Rechte, siehe [Variante B](#variante-b-eigener-datenbankserver). |
| `Duplicate entry … for key 'uk_buchung_schluessel'` | Zwei Abrufe liefen gleichzeitig. Nichts zu tun; der nächste Lauf holt nach. |
| `DB_PASSWORT in .env setzen` beim Start von Compose | `DB_PASSWORT` fehlt in `.env`. |
| `port is already allocated` beim Start von MariaDB | Port 3307 ist belegt: `DB_HOST_PORT` in `.env` ändern. |
| Programm meldet `0 neue Buchung(en)`, obwohl welche fehlen | Sie liegen außerhalb des Abrufzeitraums: einmalig `ABRUF_VON` setzen. |
| Buchungen doppelt | Nach Formatwechsel CAMT/MT940, siehe Abfrage 7. |

---

## Entwicklung und Tests

```bash
./mvnw package                     # Bauen inkl. Tests, Ergebnis: target/umsatz-abruf.jar
./mvnw spring-boot:run -Dspring-boot.run.arguments=server   # Server direkt aus den Quellen
```

Aufbau (Paket `de.fehrenbacher.fints`):

| Klasse | Aufgabe |
|---|---|
| `UmsatzAbrufAnwendung` | Einstieg, Befehle, Laden und Prüfen der Konfiguration |
| `AbrufDienst` | Ablauf eines Abrufs, Verlauf, höchstens ein Abruf gleichzeitig |
| `UmsatzAbruf`, `BankCallback` | Bankdialog mit HBCI4Java |
| `Interaktion` | Eingaben während des Bankdialogs: `KonsolenInteraktion` bzw. `WebInteraktion` |
| `UmsatzDatei`, `UmsatzDatenbank` | Speichern (CSV, MariaDB/MySQL) und Erkennen bekannter Buchungen |
| `ZeitplanDienst` | Abruf nach Zeitplan |
| `WebController`, `WebSicherheit`, `LoginSperre`, `BuchungsAnsicht` | Weboberfläche |
| `Benachrichtigung` | E-Mail bei Freigabe-Anforderungen |

Der Integrationstest der Datenbank läuft nur, wenn `UMSATZ_TEST_DB_URL` gesetzt ist. Er löscht
nichts, legt aber ein Testkonto (IBAN beginnt mit `XX00TEST`) mit Buchungen an – daher eine eigene
Testdatenbank verwenden, z.B. einen Wegwerf-Container:

```bash
docker run -d --rm --name umsatz-test-db -p 127.0.0.1:3317:3306 \
  -e MARIADB_DATABASE=test -e MARIADB_USER=test -e MARIADB_PASSWORD=test \
  -e MARIADB_RANDOM_ROOT_PASSWORD=1 mariadb:11.4
UMSATZ_TEST_DB_URL="jdbc:mariadb://127.0.0.1:3317/test?user=test&password=test" ./mvnw test
docker stop umsatz-test-db
```

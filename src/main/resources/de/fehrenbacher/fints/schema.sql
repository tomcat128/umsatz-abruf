-- Datenbankschema für den Umsatz-Abruf (SQLite)
--
-- Das Programm legt die Tabellen beim Start selbst an, sofern sie fehlen.
-- Beträge stehen in ganzen Cent (INTEGER), damit Summen exakt sind: 12,34 EUR = 1234.
-- Datumswerte stehen als Text im Format JJJJ-MM-TT, Zeitpunkte als JJJJ-MM-TT HH:MM:SS (UTC).
-- Hinweis: Diese Datei wird vom Programm an jedem Semikolon getrennt ausgeführt,
-- Kommentare dürfen daher kein Semikolon enthalten.

-- Abgerufene Konten (eigene Konten)
CREATE TABLE IF NOT EXISTS konto (
  id                INTEGER PRIMARY KEY,
  iban              TEXT    NOT NULL UNIQUE,
  bic               TEXT,
  blz               TEXT,
  kontonummer       TEXT,
  inhaber           TEXT,
  kontoart          TEXT,                       -- Bezeichnung der Bank, z.B. Girokonto
  waehrung          TEXT,
  angelegt_am       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  aktualisiert_am   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Protokoll der Abrufe
CREATE TABLE IF NOT EXISTS abruf (
  id                INTEGER PRIMARY KEY,
  konto_id          INTEGER NOT NULL REFERENCES konto (id),
  zeitpunkt         TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  von               TEXT    NOT NULL,
  bis               TEXT    NOT NULL,
  format            TEXT    NOT NULL,           -- CAMT oder MT940
  anzahl_geliefert  INTEGER NOT NULL,
  anzahl_neu        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_abruf_konto_zeitpunkt ON abruf (konto_id, zeitpunkt);

-- Gebuchte Umsätze (vorgemerkte Buchungen werden nicht gespeichert)
CREATE TABLE IF NOT EXISTS buchung (
  id                      INTEGER PRIMARY KEY,
  konto_id                INTEGER NOT NULL REFERENCES konto (id),
  abruf_id                INTEGER NOT NULL REFERENCES abruf (id),  -- Abruf, mit dem die Buchung gespeichert wurde

  -- Erkennung bereits gespeicherter Buchungen: SHA-256 über die fachlichen Merkmale
  -- (wie die CSV-Zeile). Identische Buchungen werden über "vorkommen" durchgezählt.
  schluessel              TEXT    NOT NULL,
  vorkommen               INTEGER NOT NULL DEFAULT 1,

  buchungsdatum           TEXT,
  valuta                  TEXT,
  betrag_cent             INTEGER NOT NULL,     -- negativ = Belastung
  waehrung                TEXT    NOT NULL,
  saldo_cent              INTEGER,              -- Kontostand nach der Buchung laut Bank
  ursprungsbetrag_cent    INTEGER,              -- z.B. bei Fremdwährung
  ursprungswaehrung       TEXT,
  gebuehren_cent          INTEGER,
  gebuehren_waehrung      TEXT,
  storno                  INTEGER NOT NULL DEFAULT 0,

  buchungstext            TEXT,                 -- z.B. KARTENZAHLUNG, GUTSCHRIFT
  gv_code                 TEXT,                 -- Geschäftsvorfall-Code, 999 = unstrukturiert
  textschluessel_zusatz   TEXT,
  primanota               TEXT,
  verwendungszweck        TEXT,
  zusatzinfo              TEXT,                 -- Rohdaten bei unstrukturierten Buchungen

  gegen_name              TEXT,
  gegen_iban              TEXT,
  gegen_bic               TEXT,
  gegen_kontonummer       TEXT,
  gegen_blz               TEXT,
  glaeubiger_id           TEXT,                 -- SEPA-Gläubiger-ID bei Lastschriften

  end_to_end_id           TEXT,
  mandatsreferenz         TEXT,
  purpose_code            TEXT,                 -- SEPA Purpose Code, z.B. SALA
  kundenreferenz          TEXT,
  bankreferenz            TEXT,
  bank_buchungs_id        TEXT,                 -- Eindeutige Referenz der Bank (nur CAMT)

  sepa                    INTEGER NOT NULL DEFAULT 0,
  format                  TEXT    NOT NULL,     -- CAMT oder MT940
  gespeichert_am          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,

  UNIQUE (konto_id, schluessel, vorkommen)
);
CREATE INDEX IF NOT EXISTS ix_buchung_datum ON buchung (konto_id, buchungsdatum);
CREATE INDEX IF NOT EXISTS ix_buchung_gegen_iban ON buchung (gegen_iban);

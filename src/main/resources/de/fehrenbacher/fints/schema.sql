-- Datenbankschema für den Umsatz-Abruf (MariaDB ab 10.5, MySQL ab 8.0)
--
-- Das Programm legt die Tabellen beim Start selbst an, sofern sie fehlen.
-- Zum manuellen Anlegen: mariadb -u <benutzer> -p <datenbank> < schema.sql
-- Hinweis: Diese Datei wird vom Programm an jedem Semikolon getrennt ausgeführt,
-- Kommentare dürfen daher kein Semikolon enthalten.

-- Abgerufene Konten (eigene Konten)
CREATE TABLE IF NOT EXISTS konto (
  id                INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  iban              VARCHAR(34)   NOT NULL,
  bic               VARCHAR(11)   NULL,
  blz               VARCHAR(30)   NULL,
  kontonummer       VARCHAR(30)   NULL,
  inhaber           VARCHAR(255)  NULL,
  kontoart          VARCHAR(255)  NULL     COMMENT 'Bezeichnung der Bank, z.B. Girokonto',
  waehrung          VARCHAR(3)    NULL,
  angelegt_am       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  aktualisiert_am   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_konto_iban (iban)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Protokoll der Abrufe
CREATE TABLE IF NOT EXISTS abruf (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  konto_id          INT UNSIGNED  NOT NULL,
  zeitpunkt         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  von               DATE          NOT NULL,
  bis               DATE          NOT NULL,
  format            VARCHAR(5)    NOT NULL COMMENT 'CAMT oder MT940',
  anzahl_geliefert  INT UNSIGNED  NOT NULL,
  anzahl_neu        INT UNSIGNED  NOT NULL,
  PRIMARY KEY (id),
  KEY ix_abruf_konto_zeitpunkt (konto_id, zeitpunkt),
  CONSTRAINT fk_abruf_konto FOREIGN KEY (konto_id) REFERENCES konto (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Gebuchte Umsätze (vorgemerkte Buchungen werden nicht gespeichert)
CREATE TABLE IF NOT EXISTS buchung (
  id                      BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  konto_id                INT UNSIGNED     NOT NULL,
  abruf_id                BIGINT UNSIGNED  NOT NULL COMMENT 'Abruf, mit dem die Buchung gespeichert wurde',

  -- Erkennung bereits gespeicherter Buchungen: SHA-256 über die fachlichen Merkmale
  -- (wie die CSV-Zeile). Identische Buchungen werden über "vorkommen" durchgezählt.
  schluessel              CHAR(64)         NOT NULL,
  vorkommen               SMALLINT UNSIGNED NOT NULL DEFAULT 1,

  buchungsdatum           DATE             NULL,
  valuta                  DATE             NULL,
  betrag                  DECIMAL(15,2)    NOT NULL COMMENT 'negativ = Belastung',
  waehrung                VARCHAR(3)       NOT NULL,
  saldo                   DECIMAL(15,2)    NULL     COMMENT 'Kontostand nach der Buchung laut Bank',
  ursprungsbetrag         DECIMAL(15,2)    NULL     COMMENT 'z.B. bei Fremdwährung',
  ursprungswaehrung       VARCHAR(3)       NULL,
  gebuehren               DECIMAL(15,2)    NULL,
  gebuehren_waehrung      VARCHAR(3)       NULL,
  storno                  BOOLEAN          NOT NULL DEFAULT FALSE,

  buchungstext            VARCHAR(255)     NULL     COMMENT 'z.B. KARTENZAHLUNG, GUTSCHRIFT',
  gv_code                 VARCHAR(3)       NULL     COMMENT 'Geschäftsvorfall-Code, 999 = unstrukturiert',
  textschluessel_zusatz   VARCHAR(10)      NULL,
  primanota               VARCHAR(20)      NULL,
  verwendungszweck        TEXT             NULL,
  zusatzinfo              TEXT             NULL     COMMENT 'Rohdaten bei unstrukturierten Buchungen',

  gegen_name              VARCHAR(255)     NULL,
  gegen_iban              VARCHAR(34)      NULL,
  gegen_bic               VARCHAR(11)      NULL,
  gegen_kontonummer       VARCHAR(30)      NULL,
  gegen_blz               VARCHAR(30)      NULL,
  glaeubiger_id           VARCHAR(35)      NULL     COMMENT 'SEPA-Gläubiger-ID bei Lastschriften',

  end_to_end_id           VARCHAR(255)     NULL,
  mandatsreferenz         VARCHAR(255)     NULL,
  purpose_code            VARCHAR(4)       NULL     COMMENT 'SEPA Purpose Code, z.B. SALA',
  kundenreferenz          VARCHAR(255)     NULL,
  bankreferenz            VARCHAR(255)     NULL,
  bank_buchungs_id        VARCHAR(255)     NULL     COMMENT 'Eindeutige Referenz der Bank (nur CAMT)',

  sepa                    BOOLEAN          NOT NULL DEFAULT FALSE,
  format                  VARCHAR(5)       NOT NULL COMMENT 'CAMT oder MT940',
  gespeichert_am          TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE KEY uk_buchung_schluessel (konto_id, schluessel, vorkommen),
  KEY ix_buchung_datum (konto_id, buchungsdatum),
  KEY ix_buchung_gegen_iban (gegen_iban),
  CONSTRAINT fk_buchung_konto FOREIGN KEY (konto_id) REFERENCES konto (id),
  CONSTRAINT fk_buchung_abruf FOREIGN KEY (abruf_id) REFERENCES abruf (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

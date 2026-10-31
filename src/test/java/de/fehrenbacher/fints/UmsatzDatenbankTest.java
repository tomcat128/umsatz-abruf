package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Saldo;
import org.kapott.hbci.structures.Value;

class UmsatzDatenbankTest
{
  private static final String IBAN = "DE02120300000000202051";

  @TempDir
  Path verzeichnis;

  private static UmsLine buchung(int tag, String betrag, String zweck)
  {
    UmsLine b = new UmsLine();
    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.OCTOBER, tag, 0, 0, 0);
    b.bdate = c.getTime();
    b.valuta = c.getTime();
    b.value = new Value(betrag, "EUR");
    b.text = "KARTENZAHLUNG";
    b.usage.add(zweck);
    return b;
  }

  private static Konto konto()
  {
    Konto konto = new Konto();
    konto.iban = "DE02 1203 0000 0000 2020 51";
    konto.blz = "12030000";
    konto.name = "Max Mustermann";
    konto.type = "Girokonto";
    konto.curr = "EUR";
    return konto;
  }

  @Test
  void schluesselIstStabilUndFachlich()
  {
    UmsLine a = buchung(27, "-3.50", "Kaffee");
    UmsLine b = buchung(27, "-3.50", "Kaffee");
    b.saldo = new Saldo();
    b.saldo.value = new Value("100.00", "EUR");

    // Der Saldo gehört nicht zum Schlüssel
    assertEquals(UmsatzDatenbank.schluessel(a), UmsatzDatenbank.schluessel(b));
    assertEquals(64, UmsatzDatenbank.schluessel(a).length());
    assertNotEquals(UmsatzDatenbank.schluessel(a), UmsatzDatenbank.schluessel(buchung(27, "-3.60", "Kaffee")));
  }

  @Test
  void centExakt()
  {
    assertEquals(-350L, UmsatzDatenbank.cent(new BigDecimal("-3.50")));
    assertEquals(250000L, UmsatzDatenbank.cent(new BigDecimal("2500")));
    assertEquals(1L, UmsatzDatenbank.cent(new BigDecimal("0.005")));
    assertNull(UmsatzDatenbank.cent(null));
    assertNull(UmsatzDatenbank.leerAlsNull("  "));
  }

  @Test
  void speichernUndSuchen() throws Exception
  {
    Path datei = verzeichnis.resolve("unterordner/umsaetze.db");

    UmsLine kaffee1 = buchung(27, "-3.50", "Kaffee");
    UmsLine kaffee2 = buchung(27, "-3.50", "Kaffee");
    UmsLine gehalt = buchung(28, "2500.00", "Gehalt Oktober");
    gehalt.isSepa = true;
    gehalt.gvcode = "153";
    gehalt.purposecode = "SALA";
    gehalt.endToEndId = "E2E-4711";
    gehalt.saldo = new Saldo();
    gehalt.saldo.value = new Value("3100.00", "EUR");
    gehalt.other = new Konto();
    gehalt.other.name = "Arbeitgeber GmbH";
    gehalt.other.iban = "DE89 3704 0044 0532 0130 00";
    gehalt.other.bic = "COBADEFFXXX";
    LocalDate von = LocalDate.of(2026, 10, 22);
    LocalDate bis = LocalDate.of(2026, 10, 29);

    try (UmsatzDatenbank db = UmsatzDatenbank.oeffnen(datei))
    {
      assertEquals(1, db.speichere(konto(), von, bis, "CAMT", List.of(kaffee1)).size());
      // Zweiter Abruf überlappt: eine weitere identische Kartenzahlung und das Gehalt sind neu
      assertEquals(List.of(kaffee2, gehalt), db.speichere(konto(), von, bis, "CAMT", List.of(kaffee1, kaffee2, gehalt)));
      assertEquals(0, db.speichere(konto(), von, bis, "CAMT", List.of(kaffee1, kaffee2, gehalt)).size());

      // Suche für die Weboberfläche: neueste zuerst, Summen über alle Treffer
      BuchungsAnsicht.Seite alle = db.suche(IBAN, new BuchungsAnsicht.Filter(null, null, null, 1));
      assertEquals(3, alle.gesamt());
      assertEquals("Arbeitgeber GmbH", alle.zeilen().get(0).gegenName());
      assertEquals(new BigDecimal("2500.00"), alle.zeilen().get(0).betrag());
      assertEquals(LocalDate.of(2026, 10, 28), alle.zeilen().get(0).buchungsdatum());
      assertEquals(new BigDecimal("2500.00"), alle.einnahmen());
      assertEquals(new BigDecimal("-7.00"), alle.ausgaben());

      assertEquals(1, db.suche(IBAN, new BuchungsAnsicht.Filter(null, null, "gehalt", 1)).gesamt());
      assertEquals(1, db.suche(IBAN, new BuchungsAnsicht.Filter(null, null, "DE8937", 1)).gesamt());
      assertEquals(2, db.suche(IBAN, new BuchungsAnsicht.Filter(null, LocalDate.of(2026, 10, 27), null, 1)).gesamt());
      // Platzhalter werden wörtlich genommen
      assertEquals(0, db.suche(IBAN, new BuchungsAnsicht.Filter(null, null, "%", 1)).gesamt());
      assertEquals(0, db.suche("DE00999999990000000000", new BuchungsAnsicht.Filter(null, null, null, 1)).gesamt());
    }

    // Erneutes Öffnen legt nichts doppelt an
    UmsatzDatenbank.oeffnen(datei).close();

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + datei); Statement st = c.createStatement())
    {
      try (ResultSet rs = st.executeQuery("SELECT b.buchungsdatum, b.gegen_iban, b.purpose_code, b.saldo_cent,"
          + " b.sepa, k.iban, k.kontoart FROM buchung b JOIN konto k ON k.id = b.konto_id WHERE b.end_to_end_id = 'E2E-4711'"))
      {
        assertTrue(rs.next());
        assertEquals("2026-10-28", rs.getString(1));
        assertEquals("DE89370400440532013000", rs.getString(2));
        assertEquals("SALA", rs.getString(3));
        assertEquals(310000L, rs.getLong(4));
        assertEquals(1, rs.getInt(5));
        assertEquals(IBAN, rs.getString(6));
        assertEquals("Girokonto", rs.getString(7));
      }
      try (ResultSet rs = st.executeQuery("SELECT anzahl_geliefert, anzahl_neu FROM abruf ORDER BY id"))
      {
        int[][] erwartet = { { 1, 1 }, { 3, 2 }, { 3, 0 } };
        for (int[] e : erwartet)
        {
          assertTrue(rs.next());
          assertEquals(e[0], rs.getInt(1));
          assertEquals(e[1], rs.getInt(2));
        }
      }
      try (ResultSet rs = st.executeQuery("PRAGMA journal_mode"))
      {
        rs.next();
        assertEquals("wal", rs.getString(1));
      }
    }
  }

  @Test
  void kontoAngabenWerdenAktualisiert() throws Exception
  {
    Path datei = verzeichnis.resolve("umsaetze.db");
    LocalDate tag = LocalDate.of(2026, 10, 1);
    try (UmsatzDatenbank db = UmsatzDatenbank.oeffnen(datei))
    {
      db.speichere(konto(), tag, tag, "MT940", List.of());
      Konto geaendert = konto();
      geaendert.type = "Gemeinschaftskonto";
      db.speichere(geaendert, tag, tag, "MT940", List.of());
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + datei); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*), MAX(kontoart) FROM konto"))
    {
      rs.next();
      assertEquals(1, rs.getInt(1));
      assertEquals("Gemeinschaftskonto", rs.getString(2));
    }
  }
}

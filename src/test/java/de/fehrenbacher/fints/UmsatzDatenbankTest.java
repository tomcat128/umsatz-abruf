package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Saldo;
import org.kapott.hbci.structures.Value;

class UmsatzDatenbankTest
{
  /**
   * JDBC-URL einer Testdatenbank, z.B. jdbc:mariadb://localhost:3307/test?user=..&password=..
   * Der Test löscht nichts, legt aber ein Testkonto mit zufälliger IBAN samt Buchungen an.
   */
  private static final String ENV_TEST_DB = "UMSATZ_TEST_DB_URL";

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
  void kurz()
  {
    assertNull(UmsatzDatenbank.kurz("  ", 5));
    assertEquals("abc", UmsatzDatenbank.kurz(" abc ", 5));
    assertEquals("abcde", UmsatzDatenbank.kurz("abcdefg", 5));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = ENV_TEST_DB, matches = "jdbc:.+")
  void speichernGegenEchteDatenbank() throws Exception
  {
    String url = System.getenv(ENV_TEST_DB);
    // Eigenes Konto je Lauf, damit vorhandene Daten weder stören noch verändert werden
    String iban = String.format("XX00TEST%016d", ThreadLocalRandom.current().nextLong(1L, 10_000_000_000_000_000L));

    Konto konto = new Konto();
    konto.iban = iban;
    konto.blz = "12030000";
    konto.name = "Max Mustermann";
    konto.type = "Girokonto";
    konto.curr = "EUR";

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
    gehalt.other.iban = "DE89370400440532013000";
    gehalt.other.bic = "COBADEFFXXX";
    LocalDate von = LocalDate.of(2026, 10, 22);
    LocalDate bis = LocalDate.of(2026, 10, 29);

    try (UmsatzDatenbank db = UmsatzDatenbank.verbinden(url, null, null))
    {
      assertEquals(1, db.speichere(konto, von, bis, "CAMT", List.of(kaffee1)).size());
      // Zweiter Abruf überlappt: eine weitere identische Kartenzahlung und das Gehalt sind neu
      assertEquals(List.of(kaffee2, gehalt), db.speichere(konto, von, bis, "CAMT", List.of(kaffee1, kaffee2, gehalt)));
      assertEquals(0, db.speichere(konto, von, bis, "CAMT", List.of(kaffee1, kaffee2, gehalt)).size());
    }

    // Schema anlegen ist wiederholbar
    UmsatzDatenbank.verbinden(url, null, null).close();

    try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement())
    {
      String kontoId = "(SELECT id FROM konto WHERE iban = '" + iban + "')";
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(betrag) FROM buchung WHERE konto_id = " + kontoId))
      {
        rs.next();
        assertEquals(3, rs.getInt(1));
        assertEquals(0, new BigDecimal("2493.00").compareTo(rs.getBigDecimal(2)));
      }
      try (ResultSet rs = st.executeQuery("SELECT b.buchungsdatum, b.gegen_name, b.gegen_iban, b.purpose_code, b.saldo,"
          + " b.sepa, k.iban FROM buchung b JOIN konto k ON k.id = b.konto_id"
          + " WHERE b.end_to_end_id = 'E2E-4711' AND k.iban = '" + iban + "'"))
      {
        rs.next();
        assertEquals(LocalDate.of(2026, 10, 28), rs.getObject(1, LocalDate.class));
        assertEquals("Arbeitgeber GmbH", rs.getString(2));
        assertEquals("DE89370400440532013000", rs.getString(3));
        assertEquals("SALA", rs.getString(4));
        assertEquals(0, new BigDecimal("3100.00").compareTo(rs.getBigDecimal(5)));
        assertEquals(true, rs.getBoolean(6));
        assertEquals(iban, rs.getString(7));
      }
      try (ResultSet rs = st.executeQuery("SELECT anzahl_geliefert, anzahl_neu FROM abruf WHERE konto_id = "
          + kontoId + " ORDER BY id"))
      {
        int[][] erwartet = { { 1, 1 }, { 3, 2 }, { 3, 0 } };
        for (int[] e : erwartet)
        {
          rs.next();
          assertEquals(e[0], rs.getInt(1));
          assertEquals(e[1], rs.getInt(2));
        }
      }
    }
  }
}

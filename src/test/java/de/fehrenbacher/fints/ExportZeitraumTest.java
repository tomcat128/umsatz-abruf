package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExportZeitraumTest
{
  private static final LocalDate HEUTE = LocalDate.of(2026, 10, 31);

  private static ExportZeitraum.Zeitraum z(String von, String bis, String tage, String monat, String jahr)
  {
    return ExportZeitraum.bestimme(von, bis, tage, monat, jahr, HEUTE);
  }

  @Test
  void letzteTageEinschliesslichHeute()
  {
    assertEquals(new ExportZeitraum.Zeitraum(LocalDate.of(2026, 10, 29), HEUTE), z(null, null, "3", null, null));
    assertEquals(new ExportZeitraum.Zeitraum(HEUTE, HEUTE), z(null, null, "1", null, null));
    assertEquals("29.10.2026 – 31.10.2026", z(null, null, "3", null, null).text());
  }

  @Test
  void monatUndJahr()
  {
    assertEquals(new ExportZeitraum.Zeitraum(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)),
        z(null, null, null, "2028-02", null), "Schaltjahr");
    assertEquals(new ExportZeitraum.Zeitraum(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        z(null, null, null, null, "2026"));
  }

  @Test
  void festerZeitraumUndOhneAngabe()
  {
    assertEquals(new ExportZeitraum.Zeitraum(LocalDate.of(2026, 10, 1), null), z("2026-10-01", null, null, null, null));
    assertEquals("ab 01.10.2026", z("2026-10-01", "", "", "", "").text(), "leere Formularfelder zählen nicht");
    ExportZeitraum.Zeitraum alle = z(null, null, null, null, null);
    assertNull(alle.von());
    assertEquals("alle Buchungen", alle.text());
  }

  @Test
  void fehler()
  {
    assertThrows(IllegalArgumentException.class, () -> z(null, null, "3", "2026-10", null));
    assertThrows(IllegalArgumentException.class, () -> z("2026-10-01", null, "3", null, null));
    assertThrows(IllegalArgumentException.class, () -> z(null, null, "0", null, null));
    assertThrows(IllegalArgumentException.class, () -> z(null, null, "drei", null, null));
    assertThrows(IllegalArgumentException.class, () -> z(null, null, null, "10/2026", null));
    assertThrows(IllegalArgumentException.class, () -> z(null, null, null, null, "26"));
    assertThrows(IllegalArgumentException.class, () -> z("2026-10-31", "2026-10-01", null, null, null));
    Exception e = assertThrows(IllegalArgumentException.class, () -> z("31.10.2026", null, null, null, null));
    assertTrue(e.getMessage().contains("JJJJ-MM-TT"), e.getMessage());
  }

  @Test
  void beispieleSindGueltigUndAufgeloest()
  {
    List<ExportZeitraum.Beispiel> b = ExportZeitraum.beispiele(HEUTE);
    assertEquals("?tage=3", b.get(0).abfrage());
    assertEquals("29.10.2026 – 31.10.2026", b.get(0).zeitraum());
    assertTrue(b.stream().anyMatch(x -> x.abfrage().equals("?monat=2026-09") && x.zeitraum().equals("01.09.2026 – 30.09.2026")));
    assertTrue(b.stream().anyMatch(x -> x.abfrage().equals("?jahr=2026")));
  }
}

package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WochenplanTest
{
  // 2. November 2026 ist ein Montag
  private static final LocalDateTime MONTAG_9_UHR = LocalDateTime.of(2026, 11, 2, 9, 0);

  @Test
  void standardIstTaeglichUmZwoelf()
  {
    assertEquals("täglich um 12:00 Uhr", Wochenplan.STANDARD.beschreibung());
    assertEquals(MONTAG_9_UHR.withHour(12), Wochenplan.STANDARD.naechsterNach(MONTAG_9_UHR));
    assertEquals(MONTAG_9_UHR.withHour(12).plusDays(1), Wochenplan.STANDARD.naechsterNach(MONTAG_9_UHR.withHour(12)),
        "echt danach");
  }

  @Test
  void jeTagEigeneZeiten()
  {
    Wochenplan plan = Wochenplan.aus(Map.of(DayOfWeek.MONDAY, "18:30, 8:00", DayOfWeek.SATURDAY, "10:00"));
    assertEquals("08:00, 18:30", plan.text(DayOfWeek.MONDAY), "sortiert und einheitlich formatiert");
    assertEquals("", plan.text(DayOfWeek.TUESDAY));
    assertEquals("Mo 08:00, 18:30 · Sa 10:00", plan.beschreibung());

    assertEquals(MONTAG_9_UHR.withHour(18).withMinute(30), plan.naechsterNach(MONTAG_9_UHR));
    assertEquals(LocalDateTime.of(2026, 11, 7, 10, 0), plan.naechsterNach(MONTAG_9_UHR.withHour(19)), "Tage ohne Zeit entfallen");
    assertEquals(LocalDateTime.of(2026, 11, 9, 8, 0), plan.naechsterNach(LocalDateTime.of(2026, 11, 7, 10, 0)), "über das Wochenende");
  }

  @Test
  void einzigeZeitDerWocheKommtEineWocheSpaeter()
  {
    Wochenplan plan = Wochenplan.aus(Map.of(DayOfWeek.MONDAY, "12:00"));
    assertEquals(LocalDateTime.of(2026, 11, 9, 12, 0), plan.naechsterNach(MONTAG_9_UHR.withHour(12)));
  }

  @Test
  void leererPlan()
  {
    assertTrue(Wochenplan.LEER.leer());
    assertFalse(Wochenplan.STANDARD.leer());
    assertNull(Wochenplan.LEER.naechsterNach(MONTAG_9_UHR));
    assertEquals("kein automatischer Abruf", Wochenplan.LEER.beschreibung());
  }

  @Test
  void ungueltigeUhrzeiten()
  {
    for (String falsch : new String[] { "25:00", "12:60", "12", "mittags", "12.00" })
    {
      Exception e = assertThrows(IllegalArgumentException.class, () -> Wochenplan.aus(Map.of(DayOfWeek.TUESDAY, falsch)));
      assertTrue(e.getMessage().startsWith("Dienstag:"), e.getMessage());
      assertTrue(e.getMessage().contains(falsch), e.getMessage());
    }
  }

  @Test
  void mindestabstandAuchUeberMitternachtUndWochenende()
  {
    Wochenplan.aus(Map.of(DayOfWeek.MONDAY, "12:00, 12:15"));

    Exception e = assertThrows(IllegalArgumentException.class, () -> Wochenplan.aus(Map.of(DayOfWeek.MONDAY, "12:00, 12:14")));
    assertTrue(e.getMessage().contains("Montag 12:00 und Montag 12:14"), e.getMessage());

    e = assertThrows(IllegalArgumentException.class,
        () -> Wochenplan.aus(Map.of(DayOfWeek.MONDAY, "23:55", DayOfWeek.TUESDAY, "00:05")));
    assertTrue(e.getMessage().contains("Montag 23:55 und Dienstag 00:05"), e.getMessage());

    e = assertThrows(IllegalArgumentException.class,
        () -> Wochenplan.aus(Map.of(DayOfWeek.SUNDAY, "23:55", DayOfWeek.MONDAY, "00:05")));
    assertTrue(e.getMessage().contains("Sonntag 23:55 und Montag 00:05"), e.getMessage());
  }
}

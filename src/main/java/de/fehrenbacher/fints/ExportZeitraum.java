package de.fehrenbacher.fints;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zeitraum für den Export aus den Abfrageparametern. Möglich ist genau eine der Angaben:
 * <ul>
 * <li>{@code von} und/oder {@code bis} (JJJJ-MM-TT) - fester Zeitraum, beide Tage einschließlich</li>
 * <li>{@code tage=N} - die letzten N Tage einschließlich heute (tage=1 = nur heute)</li>
 * <li>{@code monat=JJJJ-MM} - ein Kalendermonat</li>
 * <li>{@code jahr=JJJJ} - ein Kalenderjahr</li>
 * </ul>
 * Ohne Angabe: alle Buchungen.
 */
final class ExportZeitraum
{
  /** Höchstwert für tage - zehn Jahre. */
  static final int MAX_TAGE = 3660;

  private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  /**
   * @param von {@code null} = ohne Untergrenze.
   * @param bis {@code null} = ohne Obergrenze.
   */
  record Zeitraum(LocalDate von, LocalDate bis)
  {
    /** z.B. "01.10.2026 – 31.10.2026", "ab 01.10.2026" oder "alle Buchungen". */
    String text()
    {
      if (von == null && bis == null)
        return "alle Buchungen";
      if (von == null)
        return "bis " + DATUM.format(bis);
      if (bis == null)
        return "ab " + DATUM.format(von);
      return von.equals(bis) ? DATUM.format(von) : DATUM.format(von) + " – " + DATUM.format(bis);
    }
  }

  /**
   * Ein Beispiel für die Weboberfläche.
   * @param abfrage Abfrageteil ohne Format, z.B. "?tage=3" oder "" für alle.
   */
  record Beispiel(String beschreibung, String abfrage, String zeitraum) {}

  private ExportZeitraum()
  {
  }

  /**
   * @param heute Bezugstag für tage (im Betrieb: heute in der eingestellten Zeitzone).
   * @throws IllegalArgumentException bei ungültigen oder widersprüchlichen Angaben - mit einer
   *         Meldung für den Aufrufer.
   */
  static Zeitraum bestimme(String von, String bis, String tage, String monat, String jahr, LocalDate heute)
  {
    int angaben = (leer(von) && leer(bis) ? 0 : 1) + (leer(tage) ? 0 : 1) + (leer(monat) ? 0 : 1)
        + (leer(jahr) ? 0 : 1);
    if (angaben > 1)
      throw new IllegalArgumentException("Bitte nur eine Zeitangabe verwenden: von/bis, tage, monat oder jahr.");

    if (!leer(tage))
    {
      int n;
      try
      {
        n = Integer.parseInt(tage.trim());
      }
      catch (NumberFormatException e)
      {
        n = -1;
      }
      if (n < 1 || n > MAX_TAGE)
        throw new IllegalArgumentException("tage muss eine ganze Zahl von 1 bis " + MAX_TAGE + " sein: " + tage);
      return new Zeitraum(heute.minusDays(n - 1L), heute);
    }
    if (!leer(monat))
    {
      try
      {
        YearMonth m = YearMonth.parse(monat.trim());
        return new Zeitraum(m.atDay(1), m.atEndOfMonth());
      }
      catch (DateTimeParseException e)
      {
        throw new IllegalArgumentException("monat muss im Format JJJJ-MM angegeben werden: " + monat);
      }
    }
    if (!leer(jahr))
    {
      try
      {
        int j = Integer.parseInt(jahr.trim());
        if (j < 1900 || j > 9999)
          throw new NumberFormatException();
        return new Zeitraum(LocalDate.of(j, 1, 1), LocalDate.of(j, 12, 31));
      }
      catch (NumberFormatException e)
      {
        throw new IllegalArgumentException("jahr muss eine vierstellige Jahreszahl sein: " + jahr);
      }
    }

    LocalDate v = datum("von", von);
    LocalDate b = datum("bis", bis);
    if (v != null && b != null && v.isAfter(b))
      throw new IllegalArgumentException("von liegt nach bis.");
    return new Zeitraum(v, b);
  }

  /** @return Beispiele mit dem für heute aufgelösten Zeitraum. */
  static List<Beispiel> beispiele(LocalDate heute)
  {
    YearMonth monat = YearMonth.from(heute);
    YearMonth vormonat = monat.minusMonths(1);
    LocalDate von = vormonat.atDay(1);
    LocalDate bis = vormonat.atDay(15);
    List<Beispiel> b = new ArrayList<>();
    b.add(beispiel("Letzte 3 Tage (heute, gestern, vorgestern)", "?tage=3", heute));
    b.add(beispiel("Letzte 7 Tage", "?tage=7", heute));
    b.add(beispiel("Letzte 30 Tage", "?tage=30", heute));
    b.add(beispiel("Dieser Monat", "?monat=" + monat, heute));
    b.add(beispiel("Vormonat", "?monat=" + vormonat, heute));
    b.add(beispiel("Dieses Jahr", "?jahr=" + heute.getYear(), heute));
    b.add(beispiel("Fester Zeitraum", "?von=" + von + "&bis=" + bis, heute));
    b.add(beispiel("Ab einem Tag bis heute", "?von=" + von, heute));
    b.add(new Beispiel("Alle Buchungen", "", "alle Buchungen"));
    b.add(new Beispiel("Nur neue Buchungen seit der letzten Abholung (höchste bekannte ID)", "?nachId=1234",
        "Buchungen mit ID > 1234"));
    return b;
  }

  private static Beispiel beispiel(String beschreibung, String abfrage, LocalDate heute)
  {
    String q = abfrage.substring(1);
    String von = null, bis = null, tage = null, monat = null, jahr = null;
    for (String teil : q.split("&"))
    {
      String[] kv = teil.split("=", 2);
      switch (kv[0])
      {
        case "von" -> von = kv[1];
        case "bis" -> bis = kv[1];
        case "tage" -> tage = kv[1];
        case "monat" -> monat = kv[1];
        case "jahr" -> jahr = kv[1];
        default -> throw new IllegalArgumentException(kv[0]);
      }
    }
    return new Beispiel(beschreibung, abfrage, bestimme(von, bis, tage, monat, jahr, heute).text());
  }

  private static LocalDate datum(String name, String wert)
  {
    if (leer(wert))
      return null;
    try
    {
      return LocalDate.parse(wert.trim());
    }
    catch (DateTimeParseException e)
    {
      throw new IllegalArgumentException(name + " muss im Format JJJJ-MM-TT angegeben werden: " + wert);
    }
  }

  private static boolean leer(String s)
  {
    return s == null || s.isBlank();
  }
}

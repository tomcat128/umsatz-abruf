package de.fehrenbacher.fints;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Zu welchen Uhrzeiten an welchem Wochentag automatisch abgerufen wird. Ein Tag ohne Uhrzeit hat
 * keinen Abruf, ein Plan ganz ohne Uhrzeiten bedeutet "nur per Knopf".
 */
final class Wochenplan
{
  /** Kürzester erlaubter Abstand - Banken sehen häufige automatische Abrufe nicht gern. */
  static final Duration MIN_ABSTAND = Duration.ofMinutes(15);

  private static final DateTimeFormatter EINGABE = DateTimeFormatter.ofPattern("H:mm");
  private static final DateTimeFormatter AUSGABE = DateTimeFormatter.ofPattern("HH:mm");
  private static final int MINUTEN_JE_WOCHE = 7 * 24 * 60;
  private static final String[] NAMEN = { "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag" };

  /** Gilt, solange in der Weboberfläche noch kein Plan gespeichert wurde. Nach den Formatierern anlegen. */
  static final Wochenplan STANDARD = jedenTag("12:00");

  static final Wochenplan LEER = jedenTag("");

  private final Map<DayOfWeek,List<LocalTime>> zeiten;

  private Wochenplan(Map<DayOfWeek,List<LocalTime>> zeiten)
  {
    this.zeiten = zeiten;
  }

  /** @return der Plan mit denselben Uhrzeiten an jedem Wochentag. */
  static Wochenplan jedenTag(String uhrzeiten)
  {
    Map<DayOfWeek,String> eingabe = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek tag : DayOfWeek.values())
      eingabe.put(tag, uhrzeiten);
    return aus(eingabe);
  }

  /**
   * @param eingabe je Wochentag die Uhrzeiten als Text, z.B. "08:00, 18:30"; fehlend oder leer =
   *                kein Abruf an diesem Tag.
   * @throws IllegalArgumentException bei einer ungültigen Uhrzeit oder zu kurzem Abstand; die
   *                                  Meldung ist für die Anzeige gedacht.
   */
  static Wochenplan aus(Map<DayOfWeek,String> eingabe)
  {
    Map<DayOfWeek,List<LocalTime>> zeiten = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek tag : DayOfWeek.values())
      zeiten.put(tag, uhrzeiten(tag, eingabe.get(tag)));
    pruefeAbstand(zeiten);
    return new Wochenplan(zeiten);
  }

  static String name(DayOfWeek tag)
  {
    return NAMEN[tag.ordinal()];
  }

  /** @return die Uhrzeiten des Tages als Text, z.B. "08:00, 18:30"; leer = kein Abruf. */
  String text(DayOfWeek tag)
  {
    return zeiten.get(tag).stream().map(AUSGABE::format).collect(Collectors.joining(", "));
  }

  boolean leer()
  {
    return zeiten.values().stream().allMatch(List::isEmpty);
  }

  /** @return die erste geplante Zeit echt nach {@code zeitpunkt} oder {@code null} bei leerem Plan. */
  LocalDateTime naechsterNach(LocalDateTime zeitpunkt)
  {
    for (int i = 0; i <= 7; i++)
    {
      LocalDate tag = zeitpunkt.toLocalDate().plusDays(i);
      for (LocalTime zeit : zeiten.get(tag.getDayOfWeek()))
      {
        LocalDateTime kandidat = tag.atTime(zeit);
        if (kandidat.isAfter(zeitpunkt))
          return kandidat;
      }
    }
    return null;
  }

  /** @return z.B. "täglich um 12:00 Uhr" oder "Mo 08:00, 18:30 · Sa 10:00". */
  String beschreibung()
  {
    if (leer())
      return "kein automatischer Abruf";
    String montag = text(DayOfWeek.MONDAY);
    boolean jedenTagGleich = zeiten.keySet().stream().allMatch(t -> text(t).equals(montag));
    if (jedenTagGleich)
      return "täglich um " + montag + " Uhr";
    List<String> tage = new ArrayList<>();
    for (DayOfWeek tag : DayOfWeek.values())
    {
      if (!zeiten.get(tag).isEmpty())
        tage.add(name(tag).substring(0, 2) + " " + text(tag));
    }
    return String.join(" · ", tage);
  }

  private static List<LocalTime> uhrzeiten(DayOfWeek tag, String eingabe)
  {
    TreeSet<LocalTime> zeiten = new TreeSet<>();
    if (eingabe == null || eingabe.isBlank())
      return List.of();
    for (String teil : eingabe.trim().split("[,;\\s]+"))
    {
      try
      {
        zeiten.add(LocalTime.parse(teil, EINGABE));
      }
      catch (DateTimeParseException e)
      {
        throw new IllegalArgumentException(name(tag) + ": \"" + teil + "\" ist keine gültige Uhrzeit (Format 12:00).");
      }
    }
    return List.copyOf(zeiten);
  }

  /** Prüft alle aufeinanderfolgenden Abrufe, auch über Mitternacht und das Wochenende hinweg. */
  private static void pruefeAbstand(Map<DayOfWeek,List<LocalTime>> zeiten)
  {
    List<Integer> minuten = new ArrayList<>();
    for (DayOfWeek tag : DayOfWeek.values())
    {
      for (LocalTime zeit : zeiten.get(tag))
        minuten.add(tag.ordinal() * 24 * 60 + zeit.getHour() * 60 + zeit.getMinute());
    }
    for (int i = 0; i < minuten.size() && minuten.size() > 1; i++)
    {
      int naechste = i + 1 < minuten.size() ? minuten.get(i + 1) : minuten.get(0) + MINUTEN_JE_WOCHE;
      if (naechste - minuten.get(i) < MIN_ABSTAND.toMinutes())
        throw new IllegalArgumentException("Zwischen zwei Abrufen müssen mindestens " + MIN_ABSTAND.toMinutes()
            + " Minuten liegen (" + zeitpunkt(minuten.get(i)) + " und " + zeitpunkt(naechste % MINUTEN_JE_WOCHE) + ").");
    }
  }

  private static String zeitpunkt(int minuteDerWoche)
  {
    DayOfWeek tag = DayOfWeek.of(minuteDerWoche / (24 * 60) + 1);
    return name(tag) + " " + AUSGABE.format(LocalTime.ofSecondOfDay(minuteDerWoche % (24 * 60) * 60L));
  }
}

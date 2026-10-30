package de.fehrenbacher.fints;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Liest die gespeicherten Buchungen für die Weboberfläche - aus der Datenbank, wenn sie
 * Speicherziel ist, sonst aus der CSV-Datei. Neueste Buchungen zuerst.
 */
@Component
class BuchungsAnsicht
{
  static final int PRO_SEITE = 50;

  private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * @param von {@code null} = ohne Untergrenze.
   * @param suche Text in Buchungstext, Gegenkonto oder Verwendungszweck; {@code null} = alle.
   * @param seite ab 1.
   */
  record Filter(LocalDate von, LocalDate bis, String suche, int seite) {}

  record Zeile(LocalDate buchungsdatum, LocalDate valuta, BigDecimal betrag, String waehrung, String buchungstext,
      String gegenName, String gegenKonto, String verwendungszweck, boolean storno) {}

  /**
   * Eine Seite des Ergebnisses. Einnahmen und Ausgaben beziehen sich auf alle Treffer, nicht nur
   * auf die angezeigte Seite.
   */
  record Seite(List<Zeile> zeilen, int gesamt, int seite, int seiten, BigDecimal einnahmen, BigDecimal ausgaben,
      String quelle) {}

  private final Konfiguration konfig;

  BuchungsAnsicht(Konfiguration konfig)
  {
    this.konfig = konfig;
  }

  Seite suche(Filter filter) throws IOException, SQLException
  {
    if (konfig.ausgabeZiel().datenbank())
    {
      try (UmsatzDatenbank db = UmsatzDatenbank.verbinden(konfig.dbUrl(), konfig.dbBenutzer(), konfig.dbPasswort()))
      {
        return db.suche(konfig.iban(), filter);
      }
    }
    return ausCsv(filter);
  }

  private Seite ausCsv(Filter filter) throws IOException
  {
    String quelle = "CSV-Datei " + konfig.ausgabeDatei().toAbsolutePath();
    List<Zeile> alle = new ArrayList<>();
    if (Files.exists(konfig.ausgabeDatei()))
    {
      String inhalt = Files.readString(konfig.ausgabeDatei(), StandardCharsets.UTF_8);
      if (!inhalt.isEmpty() && inhalt.charAt(0) == '﻿')
        inhalt = inhalt.substring(1);
      List<List<String>> zeilen = UmsatzDatei.parse(inhalt);
      for (List<String> z : zeilen.subList(Math.min(1, zeilen.size()), zeilen.size()))
      {
        Zeile zeile = zeile(z);
        if (zeile != null && passt(zeile, filter))
          alle.add(zeile);
      }
    }

    // Neueste zuerst: Die Datei ist in Abrufreihenfolge, daher erst umdrehen, dann stabil sortieren
    Collections.reverse(alle);
    alle.sort(Comparator.comparing(Zeile::buchungsdatum, Comparator.nullsLast(Comparator.reverseOrder())));

    BigDecimal einnahmen = BigDecimal.ZERO;
    BigDecimal ausgaben = BigDecimal.ZERO;
    for (Zeile z : alle)
    {
      if (z.betrag().signum() > 0)
        einnahmen = einnahmen.add(z.betrag());
      else
        ausgaben = ausgaben.add(z.betrag());
    }
    int seiten = Math.max(1, (alle.size() + PRO_SEITE - 1) / PRO_SEITE);
    int seite = Math.min(Math.max(1, filter.seite()), seiten);
    int start = (seite - 1) * PRO_SEITE;
    List<Zeile> ausschnitt = alle.subList(Math.min(start, alle.size()), Math.min(start + PRO_SEITE, alle.size()));
    return new Seite(List.copyOf(ausschnitt), alle.size(), seite, seiten, einnahmen, ausgaben, quelle);
  }

  /** @return die Zeile oder {@code null}, wenn sie nicht lesbar ist (z.B. von Hand bearbeitet). */
  private static Zeile zeile(List<String> z)
  {
    if (z.size() < UmsatzDatei.SPALTEN.size())
      return null;
    try
    {
      return new Zeile(datum(z.get(0)), datum(z.get(1)), new BigDecimal(z.get(2).replace(".", "").replace(',', '.')),
          text(z.get(3)), text(z.get(4)), text(z.get(5)), text(z.get(6)), text(z.get(8)), "ja".equals(z.get(11)));
    }
    catch (NumberFormatException | DateTimeParseException e)
    {
      return null;
    }
  }

  static boolean passt(Zeile z, Filter f)
  {
    if (f.von() != null && (z.buchungsdatum() == null || z.buchungsdatum().isBefore(f.von())))
      return false;
    if (f.bis() != null && (z.buchungsdatum() == null || z.buchungsdatum().isAfter(f.bis())))
      return false;
    if (f.suche() == null || f.suche().isBlank())
      return true;
    String s = f.suche().trim().toLowerCase(Locale.GERMANY);
    for (String feld : new String[] { z.buchungstext(), z.gegenName(), z.gegenKonto(), z.verwendungszweck() })
    {
      if (feld != null && feld.toLowerCase(Locale.GERMANY).contains(s))
        return true;
    }
    return false;
  }

  private static LocalDate datum(String s)
  {
    return s == null || s.isBlank() ? null : LocalDate.parse(s, DATUM);
  }

  /** Macht das ' wieder rückgängig, mit dem {@link UmsatzDatei#text} Formeln entschärft. */
  private static String text(String s)
  {
    if (s == null || s.isEmpty())
      return null;
    if (s.length() > 1 && s.charAt(0) == '\'' && "=+-@".indexOf(s.charAt(1)) >= 0)
      return s.substring(1);
    return s;
  }
}

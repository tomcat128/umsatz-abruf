package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;

class UmsatzDateiTest
{
  @TempDir
  Path verzeichnis;

  private static UmsLine buchung(int tag, String betrag, String zweck)
  {
    UmsLine b = new UmsLine();
    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.OCTOBER, tag);
    b.bdate = c.getTime();
    b.valuta = c.getTime();
    b.value = new Value(betrag, "EUR");
    b.text = "KARTENZAHLUNG";
    b.usage.add(zweck);
    return b;
  }

  @Test
  void neueDateiMitKopfzeile() throws Exception
  {
    Path datei = verzeichnis.resolve("umsaetze.csv");
    UmsLine b = buchung(27, "-1234.5", "REWE SAGT DANKE");
    b.other = new Konto();
    b.other.name = "REWE Markt";
    b.other.iban = "DE02120300000000202051";

    List<UmsLine> neu = new UmsatzDatei(datei).ergaenze(List.of(b));

    assertEquals(1, neu.size());
    String inhalt = Files.readString(datei, StandardCharsets.UTF_8);
    assertTrue(inhalt.startsWith("﻿Buchungsdatum;Valuta;Betrag;"), inhalt);
    assertTrue(inhalt.contains("27.10.2026;27.10.2026;-1234,50;EUR;KARTENZAHLUNG;REWE Markt;DE02120300000000202051;;REWE SAGT DANKE;;;\r\n"), inhalt);
  }

  @Test
  void ueberlappenderAbrufErgaenztNurNeue() throws Exception
  {
    UmsatzDatei datei = new UmsatzDatei(verzeichnis.resolve("umsaetze.csv"));
    UmsLine a = buchung(20, "-10.00", "Erste");
    UmsLine b = buchung(21, "-20.00", "Zweite");
    UmsLine c = buchung(22, "-30.00", "Dritte");

    assertEquals(2, datei.ergaenze(List.of(a, b)).size());
    assertEquals(List.of(c), datei.ergaenze(List.of(buchung(20, "-10.00", "Erste"), buchung(21, "-20.00", "Zweite"), c)));
    assertEquals(0, datei.ergaenze(List.of(a, b, c)).size());

    List<List<String>> zeilen = UmsatzDatei.parse(Files.readString(verzeichnis.resolve("umsaetze.csv")).substring(1));
    assertEquals(4, zeilen.size());
  }

  @Test
  void identischeBuchungenWerdenGezaehlt() throws Exception
  {
    UmsatzDatei datei = new UmsatzDatei(verzeichnis.resolve("umsaetze.csv"));

    assertEquals(1, datei.ergaenze(List.of(buchung(27, "-3.50", "Kaffee"))).size());
    // Später liefert die Bank für denselben Tag zwei gleiche Buchungen - eine ist neu
    assertEquals(1, datei.ergaenze(List.of(buchung(27, "-3.50", "Kaffee"), buchung(27, "-3.50", "Kaffee"))).size());
    assertEquals(0, datei.ergaenze(List.of(buchung(27, "-3.50", "Kaffee"), buchung(27, "-3.50", "Kaffee"))).size());
  }

  @Test
  void sonderzeichenUeberlebenDenRundlauf() throws Exception
  {
    UmsatzDatei datei = new UmsatzDatei(verzeichnis.resolve("umsaetze.csv"));
    UmsLine b = buchung(27, "5.00", "Rechnung \"4711\"; Kunde 08/15");

    assertEquals(1, datei.ergaenze(List.of(b)).size());
    assertEquals(0, datei.ergaenze(List.of(b)).size());
    assertTrue(Files.readString(verzeichnis.resolve("umsaetze.csv")).contains("\"Rechnung \"\"4711\"\"; Kunde 08/15\""));
  }

  @Test
  void dateiOhneAbschliessendenZeilenumbruch() throws Exception
  {
    Path pfad = verzeichnis.resolve("umsaetze.csv");
    UmsatzDatei datei = new UmsatzDatei(pfad);
    datei.ergaenze(List.of(buchung(20, "-10.00", "Erste")));
    String inhalt = Files.readString(pfad);
    Files.writeString(pfad, inhalt.substring(0, inhalt.length() - 2));

    assertEquals(1, datei.ergaenze(List.of(buchung(20, "-10.00", "Erste"), buchung(21, "-20.00", "Zweite"))).size());
    assertEquals(3, UmsatzDatei.parse(Files.readString(pfad).substring(1)).size());
  }

  @Test
  void fremdeDateiWirdNichtVeraendert() throws Exception
  {
    Path pfad = verzeichnis.resolve("andere.csv");
    Files.writeString(pfad, "a;b;c\r\n1;2;3\r\n");

    assertThrows(IllegalStateException.class, () -> new UmsatzDatei(pfad).ergaenze(List.of(buchung(20, "1.00", "x"))));
    assertEquals("a;b;c\r\n1;2;3\r\n", Files.readString(pfad));
  }

  @Test
  void sepaVerwendungszweckOhneTrenner()
  {
    UmsLine b = new UmsLine();
    b.isSepa = true;
    b.usage.add("Rechnung 4711 vom 01.10.2026 Kund");
    b.usage.add("ennummer 0815");
    assertEquals("Rechnung 4711 vom 01.10.2026 Kundennummer 0815", UmsatzDatei.verwendungszweck(b));
  }

  @Test
  void klassischerVerwendungszweckMitLeerzeichen()
  {
    UmsLine b = new UmsLine();
    b.usage.add("MIETE OKTOBER ");
    b.usage.add(" WOHNUNG 3");
    assertEquals("MIETE OKTOBER WOHNUNG 3", UmsatzDatei.verwendungszweck(b));
  }

  @Test
  void unstrukturierteBuchung()
  {
    UmsLine b = new UmsLine();
    b.gvcode = "999";
    b.additional = "Rohdaten";
    assertEquals("Rohdaten", UmsatzDatei.verwendungszweck(b));
  }

  @Test
  void formelnWerdenEntschaerft()
  {
    assertEquals("'=HYPERLINK(\"x\")", UmsatzDatei.text("=HYPERLINK(\"x\")"));
    assertEquals("'-Gutschrift", UmsatzDatei.text("-Gutschrift"));
    assertEquals("Zeile 1 Zeile 2", UmsatzDatei.text("Zeile 1\r\n  Zeile 2"));
  }
}

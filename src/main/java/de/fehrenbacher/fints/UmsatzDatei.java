package de.fehrenbacher.fints;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;

/**
 * Sammelt die Buchungen in einer CSV-Datei (UTF-8 mit BOM, Semikolon, deutsches
 * Zahlenformat - lässt sich so direkt in Excel/LibreOffice öffnen).
 *
 * Da sich die Abrufzeiträume überlappen, wird jede Buchung nur ergänzt, wenn sie noch
 * nicht in der Datei steht. Als Schlüssel dient die komplette CSV-Zeile. Identische
 * Buchungen (z.B. zwei gleiche Kartenzahlungen am selben Tag) werden gezählt: Liefert
 * die Bank eine Zeile öfter als sie in der Datei steht, werden nur die fehlenden ergänzt.
 */
final class UmsatzDatei
{
  static final List<String> SPALTEN = List.of(
      "Buchungsdatum", "Valuta", "Betrag", "Währung", "Buchungstext",
      "Gegenkonto Name", "Gegenkonto IBAN/Kontonummer", "Gegenkonto BIC/BLZ",
      "Verwendungszweck", "End-to-End-ID", "Mandatsreferenz", "Storno");

  private static final char TRENNER = ';';
  private static final String ZEILENENDE = "\r\n";
  private static final char BOM = '﻿';

  private final Path datei;

  UmsatzDatei(Path datei)
  {
    this.datei = datei;
  }

  /**
   * Hängt die Buchungen an, die noch nicht in der Datei stehen. Legt die Datei bei Bedarf an.
   * @param buchungen die von der Bank gelieferten Buchungen.
   * @return die neu ergänzten Buchungen in der gelieferten Reihenfolge.
   * @throws IOException wenn die Datei nicht gelesen oder geschrieben werden kann.
   */
  List<UmsLine> ergaenze(List<UmsLine> buchungen) throws IOException
  {
    String inhalt = Files.exists(datei) ? Files.readString(datei, StandardCharsets.UTF_8) : "";
    if (!inhalt.isEmpty() && inhalt.charAt(0) == BOM)
      inhalt = inhalt.substring(1);

    Map<List<String>,Integer> vorhanden = new HashMap<>();
    List<List<String>> zeilen = parse(inhalt);
    if (!zeilen.isEmpty())
    {
      if (!zeilen.get(0).equals(SPALTEN))
        throw new IllegalStateException("Die Datei " + datei + " hat andere Spalten als erwartet."
            + " Bitte eine andere Datei konfigurieren (ausgabe.datei).");
      for (List<String> z : zeilen.subList(1, zeilen.size()))
        vorhanden.merge(z, 1, Integer::sum);
    }

    List<UmsLine> neu = new ArrayList<>();
    StringBuilder anhang = new StringBuilder();
    for (UmsLine b : buchungen)
    {
      List<String> z = zeile(b);
      int rest = vorhanden.getOrDefault(z, 0);
      if (rest > 0)
      {
        vorhanden.put(z, rest - 1);
        continue;
      }
      neu.add(b);
      schreibe(anhang, z);
    }

    if (zeilen.isEmpty())
    {
      StringBuilder kopf = new StringBuilder().append(BOM);
      schreibe(kopf, SPALTEN);
      anhang.insert(0, kopf);
    }
    else if (!inhalt.endsWith("\n"))
    {
      anhang.insert(0, ZEILENENDE);
    }

    if (zeilen.isEmpty() || !neu.isEmpty())
    {
      // Neue oder leere Datei komplett schreiben, sonst nur anhängen
      StandardOpenOption modus = zeilen.isEmpty() ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND;
      try (Writer w = Files.newBufferedWriter(datei, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.WRITE, modus))
      {
        w.write(anhang.toString());
      }
    }
    return neu;
  }

  static List<String> zeile(UmsLine b)
  {
    Konto k = b.other;
    List<String> z = new ArrayList<>(SPALTEN.size());
    z.add(datum(b.bdate));
    z.add(datum(b.valuta));
    z.add(betrag(b.value));
    z.add(b.value != null ? text(b.value.getCurr()) : "");
    z.add(text(b.text));
    z.add(k != null ? text(verbinde(k.name, k.name2)) : "");
    z.add(k != null ? text(k.iban != null && !k.iban.isBlank() ? k.iban : k.number) : "");
    z.add(k != null ? text(k.bic != null && !k.bic.isBlank() ? k.bic : k.blz) : "");
    z.add(text(verwendungszweck(b)));
    z.add(text(b.endToEndId));
    z.add(text(b.mandateId));
    z.add(b.isStorno ? "ja" : "");
    return z;
  }

  /**
   * Fügt die Verwendungszweck-Zeilen zusammen. Bei SEPA-Buchungen wurde der Text
   * von der Bank auf Zeilen fester Länge umgebrochen, auch mitten im Wort - daher
   * ohne Trennzeichen. Bei Nicht-SEPA-Buchungen sind es eigenständige Zeilen.
   * Ist die Buchung nicht strukturiert (GV-Code 999), stehen die Daten roh in "additional".
   */
  static String verwendungszweck(UmsLine b)
  {
    if (b.usage == null || b.usage.isEmpty())
      return b.additional;

    String trenner = b.isSepa ? "" : " ";
    StringBuilder sb = new StringBuilder();
    for (String z : b.usage)
    {
      if (z == null || z.isBlank())
        continue;
      if (sb.length() > 0)
        sb.append(trenner);
      sb.append(b.isSepa ? z : z.trim());
    }
    return sb.toString().trim();
  }

  /**
   * Bereinigt einen Textwert. Zeilenumbrüche werden zu Leerzeichen. Werte, die mit
   * =, +, - oder @ beginnen, bekommen ein ' vorangestellt, damit Excel einen vom
   * Auftraggeber gewählten Verwendungszweck nicht als Formel ausführt.
   */
  static String text(String s)
  {
    if (s == null)
      return "";
    String t = s.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
    if (!t.isEmpty() && "=+-@".indexOf(t.charAt(0)) >= 0)
      t = "'" + t;
    return t;
  }

  private static String betrag(Value v)
  {
    if (v == null)
      return "";
    return String.format(Locale.GERMANY, "%.2f", v.getBigDecimalValue());
  }

  private static String datum(Date d)
  {
    return d == null ? "" : new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(d);
  }

  private static String verbinde(String a, String b)
  {
    String x = a == null ? "" : a.trim();
    String y = b == null ? "" : b.trim();
    return x.isEmpty() || y.isEmpty() ? x + y : x + " " + y;
  }

  private static void schreibe(StringBuilder sb, List<String> felder)
  {
    for (int i = 0; i < felder.size(); i++)
    {
      if (i > 0)
        sb.append(TRENNER);
      String f = felder.get(i);
      if (f.indexOf(TRENNER) >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0)
        sb.append('"').append(f.replace("\"", "\"\"")).append('"');
      else
        sb.append(f);
    }
    sb.append(ZEILENENDE);
  }

  /**
   * Liest CSV nach RFC 4180 (Felder in Anführungszeichen dürfen Trenner, "" und
   * Zeilenumbrüche enthalten). Leere Zeilen werden übersprungen.
   */
  static List<List<String>> parse(String inhalt)
  {
    List<List<String>> zeilen = new ArrayList<>();
    List<String> zeile = new ArrayList<>();
    StringBuilder feld = new StringBuilder();
    boolean inQuotes = false;
    boolean zeileBegonnen = false;

    for (int i = 0; i < inhalt.length(); i++)
    {
      char c = inhalt.charAt(i);
      if (inQuotes)
      {
        if (c != '"')
          feld.append(c);
        else if (i + 1 < inhalt.length() && inhalt.charAt(i + 1) == '"')
          feld.append(inhalt.charAt(++i));
        else
          inQuotes = false;
        continue;
      }

      switch (c)
      {
        case '"':
          inQuotes = true;
          zeileBegonnen = true;
          break;
        case TRENNER:
          zeile.add(feld.toString());
          feld.setLength(0);
          zeileBegonnen = true;
          break;
        case '\r':
          break;
        case '\n':
          if (zeileBegonnen || feld.length() > 0)
          {
            zeile.add(feld.toString());
            zeilen.add(zeile);
          }
          zeile = new ArrayList<>();
          feld.setLength(0);
          zeileBegonnen = false;
          break;
        default:
          feld.append(c);
          zeileBegonnen = true;
      }
    }
    if (zeileBegonnen || feld.length() > 0)
    {
      zeile.add(feld.toString());
      zeilen.add(zeile);
    }
    return zeilen;
  }
}

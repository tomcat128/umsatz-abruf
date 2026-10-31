package de.fehrenbacher.fints;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Export der gespeicherten Buchungen für externe Anwendungen als CSV, JSON oder XML.
 *
 * Alle Formate enthalten dieselben Felder. Beträge mit Dezimalpunkt (z.B. -3.50), Datumswerte
 * im ISO-Format (JJJJ-MM-TT), Zeitpunkte in UTC (ISO 8601). Die Buchungen sind nach ihrer ID
 * aufsteigend sortiert - neue Buchungen bekommen immer höhere IDs, so dass sich mit nachId
 * fortlaufend nur die neuen abholen lassen.
 */
final class UmsatzExport
{
  enum Format
  {
    CSV("text/csv"), JSON("application/json"), XML("application/xml");

    final String mimeTyp;

    Format(String mimeTyp)
    {
      this.mimeTyp = mimeTyp;
    }

    static Format von(String s)
    {
      try
      {
        return valueOf(s.toUpperCase(Locale.ROOT));
      }
      catch (IllegalArgumentException e)
      {
        return null;
      }
    }
  }

  record Konto(String iban, String bic, String blz, String kontonummer, String inhaber, String kontoart,
      String waehrung) {}

  record Buchung(long id, LocalDate buchungsdatum, LocalDate valuta, BigDecimal betrag, String waehrung,
      BigDecimal saldo, BigDecimal ursprungsbetrag, String ursprungswaehrung, BigDecimal gebuehren,
      String gebuehrenWaehrung, boolean storno, String buchungstext, String gvCode, String textschluesselZusatz,
      String primanota, String verwendungszweck, String zusatzinfo, String gegenName, String gegenIban,
      String gegenBic, String gegenKontonummer, String gegenBlz, String glaeubigerId, String endToEndId,
      String mandatsreferenz, String purposeCode, String kundenreferenz, String bankreferenz,
      String bankBuchungsId, boolean sepa, String format, Instant gespeichertAm)
  {
    /** @return die Felder in fester Reihenfolge (Spalten der CSV, Elemente im XML). */
    Map<String,Object> felder()
    {
      Map<String,Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("buchungsdatum", buchungsdatum);
      m.put("valuta", valuta);
      m.put("betrag", betrag);
      m.put("waehrung", waehrung);
      m.put("saldo", saldo);
      m.put("ursprungsbetrag", ursprungsbetrag);
      m.put("ursprungswaehrung", ursprungswaehrung);
      m.put("gebuehren", gebuehren);
      m.put("gebuehrenWaehrung", gebuehrenWaehrung);
      m.put("storno", storno);
      m.put("buchungstext", buchungstext);
      m.put("gvCode", gvCode);
      m.put("textschluesselZusatz", textschluesselZusatz);
      m.put("primanota", primanota);
      m.put("verwendungszweck", verwendungszweck);
      m.put("zusatzinfo", zusatzinfo);
      m.put("gegenName", gegenName);
      m.put("gegenIban", gegenIban);
      m.put("gegenBic", gegenBic);
      m.put("gegenKontonummer", gegenKontonummer);
      m.put("gegenBlz", gegenBlz);
      m.put("glaeubigerId", glaeubigerId);
      m.put("endToEndId", endToEndId);
      m.put("mandatsreferenz", mandatsreferenz);
      m.put("purposeCode", purposeCode);
      m.put("kundenreferenz", kundenreferenz);
      m.put("bankreferenz", bankreferenz);
      m.put("bankBuchungsId", bankBuchungsId);
      m.put("sepa", sepa);
      m.put("format", format);
      m.put("gespeichertAm", gespeichertAm);
      return m;
    }
  }

  /**
   * Der Export als Ganzes.
   * @param konto {@code null}, wenn für das Konto noch nichts gespeichert wurde.
   */
  record Daten(Instant erstellt, Konto konto, int anzahl, List<Buchung> buchungen) {}

  /** Spaltennamen der CSV - dieselben wie die Felder in JSON und XML. */
  static final List<String> SPALTEN = List.copyOf(new Buchung(0, null, null, null, null, null, null, null, null,
      null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
      null, null, false, null, null).felder().keySet());

  private UmsatzExport()
  {
  }

  static void schreibe(Daten daten, Format format, OutputStream out, ObjectMapper json) throws IOException
  {
    switch (format)
    {
      case CSV -> csv(daten, out);
      case JSON -> json.writerWithDefaultPrettyPrinter().writeValue(out, daten);
      case XML -> xml(daten, out);
    }
  }

  /**
   * CSV nach RFC 4180: UTF-8, Semikolon als Trenner (Verwendungszwecke enthalten oft Kommas),
   * Kopfzeile mit den Feldnamen, Zeilenende CRLF.
   */
  static void csv(Daten daten, OutputStream out) throws IOException
  {
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write(String.join(";", SPALTEN));
    w.write("\r\n");
    for (Buchung b : daten.buchungen())
    {
      List<String> zeile = new ArrayList<>();
      for (Object wert : b.felder().values())
        zeile.add(csvFeld(text(wert)));
      w.write(String.join(";", zeile));
      w.write("\r\n");
    }
    w.flush();
  }

  static void xml(Daten daten, OutputStream out) throws IOException
  {
    try
    {
      XMLStreamWriter x = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
      x.writeStartDocument("UTF-8", "1.0");
      x.writeStartElement("umsaetze");
      x.writeAttribute("erstellt", daten.erstellt().toString());
      x.writeAttribute("anzahl", Integer.toString(daten.anzahl()));
      if (daten.konto() != null)
      {
        Konto k = daten.konto();
        x.writeStartElement("konto");
        element(x, "iban", k.iban());
        element(x, "bic", k.bic());
        element(x, "blz", k.blz());
        element(x, "kontonummer", k.kontonummer());
        element(x, "inhaber", k.inhaber());
        element(x, "kontoart", k.kontoart());
        element(x, "waehrung", k.waehrung());
        x.writeEndElement();
      }
      x.writeStartElement("buchungen");
      for (Buchung b : daten.buchungen())
      {
        x.writeStartElement("buchung");
        x.writeAttribute("id", Long.toString(b.id()));
        for (Map.Entry<String,Object> f : b.felder().entrySet())
        {
          if (!f.getKey().equals("id"))
            element(x, f.getKey(), text(f.getValue()));
        }
        x.writeEndElement();
      }
      x.writeEndElement();
      x.writeEndElement();
      x.writeEndDocument();
      x.flush();
    }
    catch (XMLStreamException e)
    {
      throw new IOException("XML konnte nicht geschrieben werden", e);
    }
  }

  /** Leere Werte werden weggelassen. */
  private static void element(XMLStreamWriter x, String name, String wert) throws XMLStreamException
  {
    if (wert == null || wert.isEmpty())
      return;
    x.writeStartElement(name);
    x.writeCharacters(xmlZeichen(wert));
    x.writeEndElement();
  }

  /** Entfernt Steuerzeichen, die in XML 1.0 nicht erlaubt sind. */
  static String xmlZeichen(String s)
  {
    StringBuilder sb = new StringBuilder(s.length());
    s.codePoints().filter(c -> c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
        || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF)).forEach(sb::appendCodePoint);
    return sb.toString();
  }

  private static String text(Object wert)
  {
    if (wert == null)
      return "";
    if (wert instanceof BigDecimal b)
      return b.toPlainString();
    return wert.toString();
  }

  private static String csvFeld(String f)
  {
    if (f.indexOf(';') >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0)
      return '"' + f.replace("\"", "\"\"") + '"';
    return f;
  }
}

package de.fehrenbacher.fints;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;

/**
 * Formatiert die abgerufenen Buchungen für die Konsole.
 */
final class UmsatzAusgabe
{
  private static final Locale DE = Locale.GERMANY;

  private final PrintStream out;

  UmsatzAusgabe(PrintStream out)
  {
    this.out = out;
  }

  void drucke(String iban, List<UmsLine> buchungen)
  {
    out.println();
    out.println("Neue Umsätze für " + iban + ": " + buchungen.size() + " Buchung(en)");
    out.println("=".repeat(78));

    for (UmsLine b : buchungen)
    {
      out.printf("%s  (Valuta %s)%s%n", datum(b.bdate), datum(b.valuta), rechts(betrag(b.value), 48));

      zeile("Buchungstext", b.text);
      zeile("Gegenkonto", gegenkonto(b.other));
      zeile("Verwendungszweck", verwendungszweck(b));
      if (b.isStorno)
        zeile("Hinweis", "Storno");
      out.println("-".repeat(78));
    }

    if (!buchungen.isEmpty())
    {
      UmsLine letzte = buchungen.get(buchungen.size() - 1);
      if (letzte.saldo != null && letzte.saldo.value != null)
        out.println("Saldo nach letzter Buchung: " + betrag(letzte.saldo.value));
    }
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

  static String gegenkonto(Konto k)
  {
    if (k == null)
      return null;

    StringBuilder sb = new StringBuilder();
    anhaengen(sb, k.name);
    anhaengen(sb, k.name2);
    if (k.iban != null && !k.iban.isBlank())
      anhaengen(sb, k.iban);
    else if (k.number != null && !k.number.isBlank())
      anhaengen(sb, "Kto " + k.number + (k.blz != null ? " BLZ " + k.blz : ""));
    return sb.length() == 0 ? null : sb.toString();
  }

  static String betrag(Value v)
  {
    if (v == null)
      return "";
    BigDecimal b = v.getBigDecimalValue();
    return String.format(DE, "%,.2f %s", b, v.getCurr());
  }

  private void zeile(String bezeichnung, String wert)
  {
    if (wert == null || wert.isBlank())
      return;
    out.printf("  %-18s %s%n", bezeichnung + ":", wert.trim());
  }

  private static void anhaengen(StringBuilder sb, String s)
  {
    if (s == null || s.isBlank())
      return;
    if (sb.length() > 0)
      sb.append(", ");
    sb.append(s.trim());
  }

  private static String datum(Date d)
  {
    return d == null ? "          " : new SimpleDateFormat("dd.MM.yyyy", DE).format(d);
  }

  private static String rechts(String s, int breite)
  {
    return s.length() >= breite ? " " + s : " ".repeat(breite - s.length()) + s;
  }
}

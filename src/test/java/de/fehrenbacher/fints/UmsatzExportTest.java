package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class UmsatzExportTest
{
  private static UmsatzExport.Buchung buchung(String zweck)
  {
    return new UmsatzExport.Buchung(7, LocalDate.of(2026, 10, 31), null, new BigDecimal("-12.30"), "EUR", null, null,
        null, null, null, false, "SEPA", null, null, null, zweck, null, "Händler; GmbH", null, null, null, null, null,
        null, null, null, null, null, null, true, "CAMT", Instant.parse("2026-10-31T10:00:00Z"));
  }

  @Test
  void csvMaskiertTrennerUndAnfuehrungszeichen() throws Exception
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    UmsatzExport.csv(new UmsatzExport.Daten(Instant.now(), null, null, null, 1, List.of(buchung("Rechnung \"4711\""))), out);
    String[] zeilen = out.toString(StandardCharsets.UTF_8).split("\r\n");
    assertEquals(String.join(";", UmsatzExport.SPALTEN), zeilen[0]);
    assertTrue(zeilen[1].startsWith("7;2026-10-31;;-12.30;EUR;"), zeilen[1]);
    assertTrue(zeilen[1].contains(";\"Rechnung \"\"4711\"\"\";"), zeilen[1]);
    assertTrue(zeilen[1].contains(";\"Händler; GmbH\";"), zeilen[1]);
    assertTrue(zeilen[1].endsWith(";true;CAMT;2026-10-31T10:00:00Z"), zeilen[1]);
  }

  @Test
  void xmlEntferntUnerlaubteSteuerzeichen() throws Exception
  {
    assertEquals("ab", UmsatzExport.xmlZeichen("a\u0001b"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    UmsatzExport.xml(new UmsatzExport.Daten(Instant.now(), LocalDate.of(2026, 10, 1), null, null, 1, List.of(buchung("Zeile\u0007 mit & und <"))), out);
    String xml = out.toString(StandardCharsets.UTF_8);
    assertTrue(xml.contains("<verwendungszweck>Zeile mit &amp; und &lt;</verwendungszweck>"), xml);
    assertTrue(xml.contains("<buchung id=\"7\">"), xml);
    assertTrue(xml.contains("von=\"2026-10-01\""), xml);
    assertTrue(!xml.contains("bis=\""), "fehlende Grenze wird weggelassen");
  }
}

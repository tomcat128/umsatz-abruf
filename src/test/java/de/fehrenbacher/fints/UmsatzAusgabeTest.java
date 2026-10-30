package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;

class UmsatzAusgabeTest
{
  @Test
  void sepaVerwendungszweckOhneTrenner()
  {
    UmsLine b = new UmsLine();
    b.isSepa = true;
    b.usage.add("Rechnung 4711 vom 01.10.2026 Kund");
    b.usage.add("ennummer 0815");

    assertEquals("Rechnung 4711 vom 01.10.2026 Kundennummer 0815", UmsatzAusgabe.verwendungszweck(b));
  }

  @Test
  void klassischerVerwendungszweckMitLeerzeichen()
  {
    UmsLine b = new UmsLine();
    b.usage.add("MIETE OKTOBER ");
    b.usage.add(" WOHNUNG 3");

    assertEquals("MIETE OKTOBER WOHNUNG 3", UmsatzAusgabe.verwendungszweck(b));
  }

  @Test
  void unstrukturierteBuchung()
  {
    UmsLine b = new UmsLine();
    b.gvcode = "999";
    b.additional = "Rohdaten";

    assertEquals("Rohdaten", UmsatzAusgabe.verwendungszweck(b));
  }

  @Test
  void gegenkonto()
  {
    Konto k = new Konto();
    k.name = "Max Mustermann";
    k.iban = "DE02120300000000202051";

    assertEquals("Max Mustermann, DE02120300000000202051", UmsatzAusgabe.gegenkonto(k));
    assertNull(UmsatzAusgabe.gegenkonto(new Konto()));
  }

  @Test
  void druckeBuchung()
  {
    UmsLine b = new UmsLine();
    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.OCTOBER, 27);
    b.bdate = c.getTime();
    b.valuta = c.getTime();
    b.value = new Value("-45.90", "EUR");
    b.text = "KARTENZAHLUNG";
    b.usage.add("REWE SAGT DANKE");

    ByteArrayOutputStream puffer = new ByteArrayOutputStream();
    new UmsatzAusgabe(new PrintStream(puffer, true, StandardCharsets.UTF_8)).drucke("DE00", List.of(b));
    String text = puffer.toString(StandardCharsets.UTF_8);

    assertTrue(text.contains("27.10.2026"), text);
    assertTrue(text.contains("-45,90 EUR"), text);
    assertTrue(text.contains("Buchungstext:      KARTENZAHLUNG"), text);
    assertTrue(text.contains("Verwendungszweck:  REWE SAGT DANKE"), text);
  }
}

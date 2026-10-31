package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class BenachrichtigungTest
{
  private static Konfiguration konfig(String empfaenger)
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "50050201");
    p.setProperty("fints.benutzer", "Anmeldename");
    p.setProperty("fints.iban", "DE89370400440532013000");
    p.setProperty("abruf.bis", "2026-10-30");
    if (empfaenger != null)
    {
      p.setProperty("mail.empfaenger", empfaenger);
      p.setProperty("mail.host", "localhost");
      p.setProperty("mail.absender", "umsatz@example.org");
    }
    return Konfiguration.aus(p, Map.of());
  }

  @Test
  void ohneEmpfaengerKeineBenachrichtigung()
  {
    assertNull(Benachrichtigung.aus(konfig(null)));
    assertNull(Benachrichtigung.fuerTest(konfig(null)));
  }

  @Test
  void unvollstaendigeEinrichtungSendetNicht()
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "50050201");
    p.setProperty("fints.benutzer", "Anmeldename");
    p.setProperty("fints.iban", "DE89370400440532013000");
    p.setProperty("mail.notfall", "ich@example.org");
    Konfiguration k = Konfiguration.aus(p, Map.of());
    assertNull(Benachrichtigung.aus(k), "ohne Server keine Benachrichtigung");
    assertNull(Benachrichtigung.fuerTest(k));
  }

  @Test
  void ibanWirdMaskiert()
  {
    assertEquals("DE89 **** 3000", Benachrichtigung.maskiereIban("DE89370400440532013000"));
    assertEquals("****", Benachrichtigung.maskiereIban("DE89"));
  }

  @Test
  void text()
  {
    Konfiguration k = konfig("ich@example.org");
    String text = Benachrichtigung.aus(k).text("Freigabe in der App (z.B. pushTAN)",
        "Bitte bestätigen Sie den Vorgang in Ihrer S-pushTAN-App.", k.zeitraum(),
        ZonedDateTime.of(2026, 10, 30, 7, 15, 3, 0, ZoneId.of("Europe/Berlin")));

    assertTrue(text.contains("Zeitpunkt:     30.10.2026 07:15:03"), text);
    assertTrue(text.contains("Konto:         DE89 **** 3000"), text);
    assertTrue(text.contains("BLZ:           50050201"), text);
    assertTrue(text.contains("Zeitraum:      2026-10-23 bis 2026-10-30"), text);
    assertTrue(text.contains("Art:           Freigabe in der App (z.B. pushTAN)"), text);
    assertTrue(text.contains("S-pushTAN-App"), text);
    assertFalse(text.contains("DE89370400440532013000"), text);
    assertFalse(text.contains("Weboberfläche"), text);
  }

  @Test
  void ausgesetztText()
  {
    Konfiguration k = konfig("notfall@example.org");
    String text = Benachrichtigung.aus(k).ausgesetztText("Keine Freigabe in der App innerhalb von 10 Minuten.",
        ZonedDateTime.of(2026, 10, 31, 3, 0, 0, 0, ZoneId.of("Europe/Berlin")));
    assertTrue(text.contains("ausgesetzt"), text);
    assertTrue(text.contains("Grund:         Keine Freigabe in der App innerhalb von 10 Minuten."), text);
    assertTrue(text.contains("\"Jetzt autorisieren\""), text);
    assertFalse(text.contains("DE89370400440532013000"), text);
  }

  @Test
  void textMitLinkZurWeboberflaeche()
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "50050201");
    p.setProperty("fints.benutzer", "Anmeldename");
    p.setProperty("fints.iban", "DE89370400440532013000");
    p.setProperty("mail.empfaenger", "ich@example.org");
    p.setProperty("mail.host", "localhost");
    p.setProperty("mail.absender", "umsatz@example.org");
    p.setProperty("web.adresse", "https://umsatz.example.org/");
    Konfiguration k = Konfiguration.aus(p, Map.of());

    String text = Benachrichtigung.aus(k).text("TAN-Eingabe", null, k.zeitraum(), ZonedDateTime.now());
    assertTrue(text.contains("Eingaben und Status in der Weboberfläche: https://umsatz.example.org/"), text);
  }
}

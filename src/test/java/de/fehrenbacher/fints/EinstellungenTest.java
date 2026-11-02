package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EinstellungenTest
{
  @TempDir
  Path verzeichnis;

  private Konfiguration konfig(String... mail)
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "12345678");
    p.setProperty("fints.benutzer", "bank");
    p.setProperty("fints.iban", "DE00123456780000000000");
    p.setProperty("intern.verzeichnis", verzeichnis.resolve("intern").toString());
    p.setProperty("db.datei", verzeichnis.resolve("daten/umsaetze.db").toString());
    for (int i = 0; i + 1 < mail.length; i += 2)
      p.setProperty(mail[i], mail[i + 1]);
    return Konfiguration.aus(p, Map.of());
  }

  @Test
  void dateiLiegtNebenDerDatenbankNichtImInternenVolume()
  {
    assertEquals(verzeichnis.resolve("daten/einstellungen.properties"), konfig().einstellungenDatei());
  }

  @Test
  void notfallkontakteAusDerEnvSindDerStandard()
  {
    Einstellungen e = new Einstellungen(konfig("mail.notfall", "env@example.org"));
    assertEquals(List.of(), e.eigeneKontakte());
    assertEquals(List.of("env@example.org"), e.empfaenger());
  }

  @Test
  void eigeneKontakteErsetzenDenStandardUndUeberstehenNeustart()
  {
    Konfiguration k = konfig("mail.notfall", "env@example.org");
    new Einstellungen(k).speichereKontakte(" eins@example.org\nzwei@example.org, drei@example.org;eins@example.org ");

    Einstellungen neu = new Einstellungen(k);
    List<String> erwartet = List.of("eins@example.org", "zwei@example.org", "drei@example.org");
    assertEquals(erwartet, neu.eigeneKontakte());
    assertEquals(erwartet, neu.empfaenger());

    neu.speichereKontakte("  ");
    assertEquals(List.of("env@example.org"), new Einstellungen(k).empfaenger(), "leer = wieder der Standard");
  }

  @Test
  void ungueltigeKontakteWerdenAbgewiesenUndNichtGespeichert()
  {
    Einstellungen e = new Einstellungen(konfig());
    e.speichereKontakte("gut@example.org");

    for (String falsch : new String[] { "ohne-at", "a@b@c", "<x>@example.org" })
    {
      Exception f = assertThrows(IllegalArgumentException.class, () -> e.speichereKontakte("neu@example.org, " + falsch));
      assertTrue(f.getMessage().contains(falsch), f.getMessage());
    }
    assertEquals(List.of("gut@example.org"), e.eigeneKontakte());

    String zuViele = String.join(",", IntStream.rangeClosed(0, Einstellungen.MAX_KONTAKTE)
        .mapToObj(i -> "k" + i + "@example.org").toList());
    assertThrows(IllegalArgumentException.class, () -> e.speichereKontakte(zuViele));
  }

  @Test
  void eigeneKontakteHebenFehlendesMailNotfallAuf()
  {
    Konfiguration k = konfig("mail.host", "localhost", "mail.absender", "umsatz@example.org");
    Einstellungen e = new Einstellungen(k);
    assertTrue(e.mailProbleme().get(0).contains("MAIL_NOTFALL"));
    assertNull(Benachrichtigung.aus(k, e), "ohne Empfänger keine Benachrichtigung");

    e.speichereKontakte("eigen@example.org");
    assertEquals(List.of(), e.mailProbleme());
    assertNotNull(Benachrichtigung.aus(k, e));
  }

  @Test
  void eigeneKontakteOhnePostfachSendenNicht()
  {
    Konfiguration k = konfig();
    Einstellungen e = new Einstellungen(k);
    e.speichereKontakte("eigen@example.org");
    assertNull(Benachrichtigung.aus(k, e));
  }

  @Test
  void gemerkteAdresseErscheintAlsLinkInDenEmails()
  {
    Konfiguration k = konfig("mail.notfall", "ich@example.org", "mail.host", "localhost", "mail.absender", "umsatz@example.org");
    Einstellungen e = new Einstellungen(k);
    assertNull(e.webAdresse());

    e.merkeAdresse("https://umsatz.example.org");
    ZonedDateTime zeit = ZonedDateTime.of(2026, 10, 31, 3, 0, 0, 0, ZoneId.of("Europe/Berlin"));
    String text = Benachrichtigung.aus(k, e).ausgesetztText("Grund", zeit);
    assertTrue(text.contains("1. In der Weboberfläche anmelden: https://umsatz.example.org/\n"), text);

    String test = Benachrichtigung.fuerTest(k, e).autorisierungsMuster(zeit);
    assertTrue(test.contains("1. In der Weboberfläche anmelden: https://umsatz.example.org/\n"), test);

    // Für automatische E-Mails muss sie einen Neustart überstehen, auch neben den übrigen Einstellungen
    e.speichereKontakte("eigen@example.org");
    Einstellungen neu = new Einstellungen(k);
    assertEquals("https://umsatz.example.org", neu.webAdresse());
    assertEquals(List.of("eigen@example.org"), neu.eigeneKontakte());
    assertFalse(neu.zeitplanGespeichert(), "die Adresse gilt nicht als gespeicherter Plan");
  }

  @Test
  void konfigurierteAdresseHatVorrang()
  {
    Einstellungen e = new Einstellungen(konfig("web.adresse", "https://fest.example.org/"));
    e.merkeAdresse("https://anders.example.org");
    assertEquals("https://fest.example.org", e.webAdresse());
  }

  @Test
  void unbrauchbareGemerkteAdresseWirdIgnoriert() throws Exception
  {
    Konfiguration k = konfig();
    Files.createDirectories(k.einstellungenDatei().getParent());
    Files.writeString(k.einstellungenDatei(), "web.adresse=javascript:alert(1)\n");
    assertNull(new Einstellungen(k).webAdresse());
  }

  @Test
  void zeitplanOhneSpeicherungIstStandard()
  {
    Einstellungen e = new Einstellungen(konfig());
    assertFalse(e.zeitplanGespeichert());
    assertEquals("täglich um 12:00 Uhr", e.zeitplan().beschreibung());
    assertNull(e.zeitplanGeaendert());
  }

  @Test
  void zeitplanUeberstehtNeustart()
  {
    Konfiguration k = konfig();
    Einstellungen e = new Einstellungen(k);
    e.speichereZeitplan(Wochenplan.aus(Map.of(DayOfWeek.MONDAY, "08:00, 18:30", DayOfWeek.SUNDAY, "10:00")));

    Einstellungen neu = new Einstellungen(k);
    assertTrue(neu.zeitplanGespeichert());
    assertEquals("Mo 08:00, 18:30 · So 10:00", neu.zeitplan().beschreibung());
    assertEquals(e.zeitplanGeaendert(), neu.zeitplanGeaendert());
  }

  @Test
  void ausgeschalteterZeitplanFaelltNichtAufDenStandardZurueck()
  {
    Konfiguration k = konfig();
    new Einstellungen(k).speichereZeitplan(Wochenplan.LEER);

    Einstellungen neu = new Einstellungen(k);
    assertTrue(neu.zeitplanGespeichert());
    assertTrue(neu.zeitplan().leer());
  }

  @Test
  void kontakteUndZeitplanUeberschreibenSichNicht()
  {
    Konfiguration k = konfig();
    Einstellungen e = new Einstellungen(k);
    e.speichereZeitplan(Wochenplan.jedenTag("07:00"));
    e.speichereKontakte("eigen@example.org");
    e.speichereZeitplan(Wochenplan.jedenTag("09:00"));

    Einstellungen neu = new Einstellungen(k);
    assertEquals(List.of("eigen@example.org"), neu.eigeneKontakte());
    assertEquals("täglich um 09:00 Uhr", neu.zeitplan().beschreibung());
  }

  @Test
  void beschaedigteDateiSchaltetDieAbrufeNichtAb() throws Exception
  {
    Konfiguration k = konfig();
    Files.createDirectories(k.einstellungenDatei().getParent());
    Files.writeString(k.einstellungenDatei(), "zeitplan.monday=quatsch\nnotfall.kontakte=kein-kontakt\n");

    Einstellungen e = new Einstellungen(k);
    assertFalse(e.zeitplanGespeichert());
    assertEquals("täglich um 12:00 Uhr", e.zeitplan().beschreibung());
    assertEquals(List.of(), e.eigeneKontakte());
  }
}

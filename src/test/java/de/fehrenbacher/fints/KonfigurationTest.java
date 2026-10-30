package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class KonfigurationTest
{
  private static Properties minimal()
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "12345678");
    p.setProperty("fints.benutzer", "Anmeldename");
    p.setProperty("fints.iban", "de00 1234 5678 0000 0000 00");
    return p;
  }

  @Test
  void standardwerte()
  {
    Konfiguration k = Konfiguration.aus(minimal(), Map.of());

    assertEquals("DE00123456780000000000", k.iban());
    assertEquals("Anmeldename", k.kundenId());
    assertNull(k.pin());
    assertNull(k.passportPasswort());
    assertNull(k.tanVerfahren());
    assertNull(k.produktId());
    assertEquals(LocalDate.now(), k.bis());
    assertEquals(LocalDate.now().minusDays(Konfiguration.STANDARD_TAGE), k.von());
    assertEquals(Konfiguration.Format.AUTO, k.format());
    assertEquals(7, Konfiguration.STANDARD_TAGE);
    assertEquals(Path.of("umsaetze.csv"), k.ausgabeDatei());
  }

  @Test
  void abrufTage()
  {
    Properties p = minimal();
    p.setProperty("abruf.tage", "14");
    p.setProperty("abruf.bis", "2026-10-29");

    Konfiguration k = Konfiguration.aus(p, Map.of());
    assertEquals(LocalDate.of(2026, 10, 15), k.von());

    // Ein fester Beginn hat Vorrang
    p.setProperty("abruf.von", "2026-10-01");
    assertEquals(LocalDate.of(2026, 10, 1), Konfiguration.aus(p, Map.of()).von());
  }

  @Test
  void umgebungsvariableUeberschreibtPin()
  {
    Properties p = minimal();
    p.setProperty("fints.pin", "aus-datei");

    assertEquals("aus-datei", Konfiguration.aus(p, Map.of()).pin());
    assertEquals("aus-env", Konfiguration.aus(p, Map.of(Konfiguration.ENV_PIN, "aus-env")).pin());
  }

  @Test
  void alleWerteAusUmgebungsvariablen()
  {
    Konfiguration k = Konfiguration.aus(new Properties(), Map.of(
        "FINTS_BLZ", "12345678",
        "FINTS_BENUTZER", "Anmeldename",
        "FINTS_IBAN", "DE00123456780000000000",
        "ABRUF_TAGE", "3",
        "ABRUF_BIS", "2026-10-29",
        "AUSGABE_DATEI", "/data/umsaetze.csv",
        "PASSPORT_PASSWORT", "geheim"));

    assertEquals("12345678", k.blz());
    assertEquals(LocalDate.of(2026, 10, 26), k.von());
    assertEquals(Path.of("/data/umsaetze.csv"), k.ausgabeDatei());
    assertEquals("geheim", k.passportPasswort());
  }

  @Test
  void umgebungsvariableUeberschreibtDatei()
  {
    Properties p = minimal();
    p.setProperty("abruf.tage", "30");

    assertEquals(30, tage(Konfiguration.aus(p, Map.of())));
    assertEquals(5, tage(Konfiguration.aus(p, Map.of("ABRUF_TAGE", "5"))));
    // Leere Variablen (z.B. aus docker-compose) zählen als nicht gesetzt
    assertEquals(30, tage(Konfiguration.aus(p, Map.of("ABRUF_TAGE", ""))));
    assertEquals("alt", Konfiguration.aus(p, Map.of(Konfiguration.ENV_PASSPORT_PASSWORT, "alt")).passportPasswort());
  }

  private static long tage(Konfiguration k)
  {
    return java.time.temporal.ChronoUnit.DAYS.between(k.von(), k.bis());
  }

  @Test
  void ausgabeZiel()
  {
    assertEquals(Konfiguration.Ziel.CSV, Konfiguration.aus(minimal(), Map.of()).ausgabeZiel());

    Properties p = minimal();
    p.setProperty("ausgabe.ziel", "datenbank");
    // Ohne db.url geht es nicht
    Exception e = assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));
    assertTrue(e.getMessage().contains("DB_URL"), e.getMessage());

    Konfiguration k = Konfiguration.aus(p, Map.of("DB_URL", "jdbc:mariadb://mariadb:3306/umsaetze",
        "DB_BENUTZER", "umsatz", "DB_PASSWORT", "geheim", "AUSGABE_ZIEL", "BEIDE"));
    assertEquals(Konfiguration.Ziel.BEIDE, k.ausgabeZiel());
    assertTrue(k.ausgabeZiel().csv() && k.ausgabeZiel().datenbank());
    assertEquals("umsatz", k.dbBenutzer());

    assertFehler("ausgabe.ziel", "excel");
    p.setProperty("db.url", "jdbc:postgresql://localhost/umsaetze");
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));
  }

  @Test
  void mailBenachrichtigung()
  {
    assertTrue(Konfiguration.aus(minimal(), Map.of()).mailEmpfaenger().isEmpty());

    Properties p = minimal();
    p.setProperty("mail.empfaenger", "ich@example.org; partner@example.org");
    // Ohne Server geht es nicht
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));

    p.setProperty("mail.host", "smtp.example.org");
    p.setProperty("mail.benutzer", "konto@example.org");
    Konfiguration k = Konfiguration.aus(p, Map.of("MAIL_PASSWORT", "geheim"));
    assertEquals(List.of("ich@example.org", "partner@example.org"), k.mailEmpfaenger());
    assertEquals("konto@example.org", k.mailAbsender());
    assertEquals(Konfiguration.MailSicherheit.STARTTLS, k.mailSicherheit());
    assertEquals(587, k.mailPort());
    assertEquals("geheim", k.mailPasswort());

    assertEquals(465, Konfiguration.aus(p, Map.of("MAIL_SICHERHEIT", "ssl")).mailPort());
    assertEquals(2525, Konfiguration.aus(p, Map.of("MAIL_SICHERHEIT", "ssl", "MAIL_PORT", "2525")).mailPort());

    p.setProperty("mail.sicherheit", "tls13");
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));
    p.setProperty("mail.sicherheit", "starttls");
    p.setProperty("mail.empfaenger", "kein-empfaenger");
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));

    // Ohne Benutzer muss ein Absender angegeben werden
    Properties q = minimal();
    q.setProperty("mail.empfaenger", "ich@example.org");
    q.setProperty("mail.host", "mailserver");
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(q, Map.of()));
    q.setProperty("mail.absender", "umsatz@example.org");
    assertEquals("umsatz@example.org", Konfiguration.aus(q, Map.of()).mailAbsender());
  }

  @Test
  void zeitraumUndFormat()
  {
    Properties p = minimal();
    p.setProperty("abruf.von", "2026-01-01");
    p.setProperty("abruf.bis", "2026-03-31");
    p.setProperty("abruf.format", "MT940");

    Konfiguration k = Konfiguration.aus(p, Map.of());
    assertEquals(LocalDate.of(2026, 1, 1), k.von());
    assertEquals(LocalDate.of(2026, 3, 31), k.bis());
    assertEquals(Konfiguration.Format.MT940, k.format());
  }

  @Test
  void fehlendePflichtangabe()
  {
    Properties p = minimal();
    p.remove("fints.benutzer");

    Exception e = assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));
    assertTrue(e.getMessage().contains("fints.benutzer"));
  }

  @Test
  void ungueltigeWerte()
  {
    assertFehler("fints.blz", "1234");
    assertFehler("fints.iban", "kein-iban");
    assertFehler("abruf.von", "01.01.2026");
    assertFehler("abruf.format", "csv");
    assertFehler("abruf.tage", "-1");
    assertFehler("abruf.tage", "eine Woche");
  }

  @Test
  void vonNachBis()
  {
    Properties p = minimal();
    p.setProperty("abruf.von", "2026-05-01");
    p.setProperty("abruf.bis", "2026-04-01");
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()));
  }

  private static void assertFehler(String key, String wert)
  {
    Properties p = minimal();
    p.setProperty(key, wert);
    assertThrows(Konfiguration.KonfigurationsFehler.class, () -> Konfiguration.aus(p, Map.of()), key);
  }
}

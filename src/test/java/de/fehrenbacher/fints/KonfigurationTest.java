package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    assertEquals(Path.of("umsaetze.db"), k.dbDatei());
    assertEquals(Konfiguration.Ziel.DATENBANK, k.ausgabeZiel());
    Path intern = Path.of(System.getProperty("user.home"), ".umsatz-abruf").toAbsolutePath();
    assertEquals(intern, k.internVerzeichnis());
    assertEquals(intern.resolve("umsatz-abruf.passport"), k.passportDatei());
    assertEquals(intern.resolve("status.properties"), k.statusDatei());

    // Mit gesetztem Verzeichnis liegt alles dort
    Konfiguration k2 = Konfiguration.aus(minimal(), Map.of("INTERN_VERZEICHNIS", "/var/lib/umsatz-abruf"));
    assertEquals(Path.of("/var/lib/umsatz-abruf/umsatz-abruf.passport"), k2.passportDatei());
    assertEquals("geheim", k2.mit("passport.passwort", "geheim").passportPasswort());
    assertNull(k2.passportPasswort(), "mit() verändert das Original nicht");
  }

  @Test
  void webPasswortMindestensAchtZeichen()
  {
    Properties p = minimal();
    p.setProperty("web.benutzer", "ich");
    p.setProperty("web.passwort", "8zeichen");
    Konfiguration.aus(p, Map.of()).pruefeServerbetrieb();

    p.setProperty("web.passwort", "7zeiche");
    Exception e = assertThrows(Konfiguration.KonfigurationsFehler.class,
        () -> Konfiguration.aus(p, Map.of()).pruefeServerbetrieb());
    assertTrue(e.getMessage().contains("mindestens 8 Zeichen"), e.getMessage());

    // Ein Hash ist unabhängig von der Länge des Passworts erlaubt
    p.setProperty("web.passwort", "{bcrypt}$2a$10$abcdefghijklmnopqrstuv");
    Konfiguration.aus(p, Map.of()).pruefeServerbetrieb();
  }

  @Test
  void frueheresIntervallWirdErkanntAberNichtMehrGeprueft()
  {
    assertFalse(Konfiguration.aus(minimal(), Map.of()).intervallGesetzt());
    assertTrue(Konfiguration.aus(minimal(), Map.of("ABRUF_INTERVALL", "6h")).intervallGesetzt());
    assertTrue(Konfiguration.aus(minimal(), Map.of("ABRUF_INTERVALL", "5m")).intervallGesetzt(), "verhindert den Start nicht");
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
  void zeitraumNachEinerPause()
  {
    Konfiguration k = Konfiguration.aus(minimal(), Map.of());
    LocalDate heute = LocalDate.now();
    assertEquals(heute.minusDays(7), k.zeitraumNach(null).von(), "kein früherer Abruf");
    assertEquals(heute.minusDays(7), k.zeitraumNach(heute).von(), "keine Pause: normaler Zeitraum");
    assertEquals(heute.minusDays(7), k.zeitraumNach(heute.minusDays(4)).von(), "Überlappung passt noch hinein");
    assertEquals(heute.minusDays(8), k.zeitraumNach(heute.minusDays(5)).von());
    assertEquals(heute.minusDays(23), k.zeitraumNach(heute.minusDays(20)).von());
    assertEquals(heute, k.zeitraumNach(heute.minusDays(20)).bis());
    assertEquals(heute.minusDays(90), k.zeitraumNach(heute.minusDays(87)).von());
    assertEquals(heute.minusDays(90), k.zeitraumNach(heute.minusDays(200)).von(), "höchstens 90 Tage");

    // Ein längerer eingestellter Zeitraum wird nie gekürzt
    Konfiguration lang = Konfiguration.aus(minimal(), Map.of("ABRUF_TAGE", "120"));
    assertEquals(heute.minusDays(120), lang.zeitraumNach(heute.minusDays(200)).von());
    assertEquals(heute.minusDays(120), lang.zeitraumNach(heute).von());

    // Auch mit ganz kurzem Zeitraum bleiben verzögert gebuchte Umsätze im Blick
    assertEquals(heute.minusDays(3), Konfiguration.aus(minimal(), Map.of("ABRUF_TAGE", "0")).zeitraumNach(heute).von());
  }

  @Test
  void festerZeitraumWirdNichtErweitert()
  {
    LocalDate alt = LocalDate.now().minusDays(50);
    Konfiguration von = Konfiguration.aus(minimal(), Map.of("ABRUF_VON", "2026-01-01"));
    assertEquals(LocalDate.of(2026, 1, 1), von.zeitraumNach(LocalDate.now()).von());
    assertTrue(von.festerZeitraum());

    Konfiguration bis = Konfiguration.aus(minimal(), Map.of("ABRUF_BIS", "2026-10-29"));
    assertEquals(LocalDate.of(2026, 10, 22), bis.zeitraumNach(alt).von());
    assertEquals(LocalDate.of(2026, 10, 29), bis.zeitraumNach(alt).bis());

    assertFalse(Konfiguration.aus(minimal(), Map.of()).festerZeitraum());
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
    Konfiguration k = Konfiguration.aus(minimal(), Map.of("AUSGABE_ZIEL", "BEIDE", "DB_DATEI", "/data/buchungen.db"));
    assertEquals(Konfiguration.Ziel.BEIDE, k.ausgabeZiel());
    assertTrue(k.ausgabeZiel().csv() && k.ausgabeZiel().datenbank());
    assertEquals(Path.of("/data/buchungen.db"), k.dbDatei());

    assertFehler("ausgabe.ziel", "excel");
  }

  @Test
  void notfallAdresse()
  {
    Properties p = minimal();
    p.setProperty("mail.host", "smtp.example.org");
    p.setProperty("mail.absender", "umsatz@example.org");
    p.setProperty("mail.notfall", "notfall@example.org");
    assertEquals(List.of("notfall@example.org"), Konfiguration.aus(p, Map.of()).mailEmpfaenger());

    // Früherer Name wird weiter verstanden, mail.notfall hat Vorrang
    p.remove("mail.notfall");
    p.setProperty("mail.empfaenger", "alt@example.org");
    assertEquals(List.of("alt@example.org"), Konfiguration.aus(p, Map.of()).mailEmpfaenger());
    assertEquals(List.of("neu@example.org"), Konfiguration.aus(p, Map.of("MAIL_NOTFALL", "neu@example.org")).mailEmpfaenger());
  }

  @Test
  void mailBenachrichtigung()
  {
    Konfiguration ohne = Konfiguration.aus(minimal(), Map.of());
    assertTrue(ohne.mailEmpfaenger().isEmpty());
    assertTrue(ohne.mailProbleme().isEmpty(), "ohne Mail-Einrichtung kein Problem");
    assertFalse(ohne.mailVersandMoeglich());

    Properties p = minimal();
    p.setProperty("mail.empfaenger", "ich@example.org; partner@example.org");
    // Ohne Server: Start trotzdem möglich, das Problem wird gemeldet
    assertTrue(Konfiguration.aus(p, Map.of()).mailProbleme().stream().anyMatch(m -> m.contains("MAIL_HOST")));

    p.setProperty("mail.host", "smtp.example.org");
    p.setProperty("mail.benutzer", "konto@example.org");
    Konfiguration k = Konfiguration.aus(p, Map.of("MAIL_PASSWORT", "geheim"));
    assertEquals(List.of(), k.mailProbleme());
    assertTrue(k.mailVersandMoeglich());
    assertEquals(List.of("ich@example.org", "partner@example.org"), k.mailEmpfaenger());
    assertEquals("konto@example.org", k.mailAbsender());
    assertEquals(Konfiguration.MailSicherheit.STARTTLS, k.mailSicherheit());
    assertEquals(587, k.mailPort());
    assertEquals("geheim", k.mailPasswort());

    assertEquals(465, Konfiguration.aus(p, Map.of("MAIL_SICHERHEIT", "ssl")).mailPort());
    assertEquals(2525, Konfiguration.aus(p, Map.of("MAIL_SICHERHEIT", "ssl", "MAIL_PORT", "2525")).mailPort());

    assertTrue(Konfiguration.aus(p, Map.of("MAIL_SICHERHEIT", "tls13")).mailProbleme().get(0).contains("MAIL_SICHERHEIT"));
    assertTrue(Konfiguration.aus(p, Map.of("MAIL_PORT", "abc")).mailProbleme().get(0).contains("MAIL_PORT"));
    Konfiguration falsch = Konfiguration.aus(p, Map.of("MAIL_NOTFALL", "kein-empfaenger, ich@example.org"));
    assertEquals(List.of("ich@example.org"), falsch.mailEmpfaenger(), "gültige Adressen bleiben");
    assertTrue(falsch.mailProbleme().get(0).contains("kein-empfaenger"));

    // Ohne Benutzer muss ein Absender angegeben werden
    Properties q = minimal();
    q.setProperty("mail.empfaenger", "ich@example.org");
    q.setProperty("mail.host", "mailserver");
    assertTrue(Konfiguration.aus(q, Map.of()).mailProbleme().get(0).contains("MAIL_ABSENDER"));
    q.setProperty("mail.absender", "umsatz@example.org");
    assertEquals("umsatz@example.org", Konfiguration.aus(q, Map.of()).mailAbsender());

    // Server ohne Empfänger: Test-E-Mails möglich, Benachrichtigungen nicht
    q.remove("mail.empfaenger");
    Konfiguration nurServer = Konfiguration.aus(q, Map.of());
    assertTrue(nurServer.mailVersandMoeglich());
    assertTrue(nurServer.mailProbleme().get(0).contains("MAIL_NOTFALL"));

    assertTrue(Konfiguration.aus(minimal(), Map.of()).exportAktiv());
    assertFalse(Konfiguration.aus(minimal(), Map.of()).exportGeschuetzt());
    assertTrue(Konfiguration.aus(minimal(), Map.of("EXPORT_BENUTZER", "app", "EXPORT_PASSWORT", "12345678")).exportGeschuetzt());
    assertThrows(Konfiguration.KonfigurationsFehler.class,
        () -> Konfiguration.aus(minimal(), Map.of("EXPORT_BENUTZER", "app")), "nur beide zusammen");
    assertThrows(Konfiguration.KonfigurationsFehler.class,
        () -> Konfiguration.aus(minimal(), Map.of("EXPORT_BENUTZER", "app", "EXPORT_PASSWORT", "kurz")));
    assertFalse(Konfiguration.aus(minimal(), Map.of("EXPORT_AKTIV", "false")).exportAktiv());
    assertFehler("export.aktiv", "vielleicht");
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

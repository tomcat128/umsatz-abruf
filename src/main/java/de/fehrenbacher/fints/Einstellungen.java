package de.fehrenbacher.fints;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Was in der Weboberfläche eingestellt wird: eigene Notfallkontakte und die Abrufzeiten je
 * Wochentag - dazu die Adresse, unter der sie zuletzt aufgerufen wurde. Die Datei liegt im Datenverzeichnis neben der Datenbank - sie übersteht damit neue
 * Images und gehört zur Sicherung. Das interne Volume wäre der falsche Ort: Es darf laut
 * Dokumentation gelöscht werden, das kostet nur eine neue Freigabe bei der Bank.
 */
@Component
class Einstellungen
{
  private static final Logger LOG = LoggerFactory.getLogger(Einstellungen.class);

  static final int MAX_KONTAKTE = 20;

  private static final String KONTAKTE = "notfall.kontakte";
  private static final String ZEITPLAN_GEAENDERT = "zeitplan.geaendert";
  private static final String ZEITPLAN_TAG = "zeitplan.";
  private static final String ADRESSE = "web.adresse";

  private final Konfiguration konfig;
  private final Path datei;
  private volatile List<String> eigeneKontakte;
  /** {@code null}, solange noch kein Plan gespeichert wurde. */
  private volatile Wochenplan zeitplan;
  private volatile LocalDateTime zeitplanGeaendert;
  private volatile String gemerkteAdresse;

  Einstellungen(Konfiguration konfig)
  {
    this.konfig = konfig;
    this.datei = konfig.einstellungenDatei();
    Properties p = Betriebszustand.laden(datei);
    this.eigeneKontakte = gespeicherteKontakte(p);
    this.zeitplan = gespeicherterPlan(p);
    this.zeitplanGeaendert = zeitpunkt(p.getProperty(ZEITPLAN_GEAENDERT));
    String adresse = p.getProperty(ADRESSE);
    this.gemerkteAdresse = adresse != null && adresse.matches("https?://\\S+") ? adresse : null;
  }

  /**
   * @return die Adresse der Weboberfläche für Links in E-Mails: web.adresse, sonst die Adresse des
   *         letzten angemeldeten Aufrufs, sonst {@code null}.
   */
  String webAdresse()
  {
    return konfig.webAdresse() != null ? konfig.webAdresse() : gemerkteAdresse;
  }

  /**
   * Merkt sich, unter welcher Adresse die Weboberfläche aufgerufen wird. Automatische E-Mails haben
   * keine Anfrage, aus der sie die Adresse ablesen könnten. Nur mit Anfragen angemeldeter Benutzer
   * aufrufen: Den Host-Header kann sonst jeder setzen und so fremde Links in die E-Mails bringen.
   * Ein Fehler beim Speichern stört die Anfrage nicht - die Adresse gilt dann bis zum Neustart.
   */
  synchronized void merkeAdresse(String adresse)
  {
    if (adresse.equals(gemerkteAdresse))
      return;
    gemerkteAdresse = adresse;
    try
    {
      schreibe(eigeneKontakte, zeitplan, zeitplanGeaendert);
    }
    catch (UncheckedIOException e)
    {
      LOG.warn("Adresse der Weboberfläche konnte nicht gespeichert werden: {}", e.getMessage());
    }
  }

  /** @return die eigenen Kontakte; leer = es gilt der Standard aus der Konfiguration. */
  List<String> eigeneKontakte()
  {
    return eigeneKontakte;
  }

  /** @return an wen Benachrichtigungen gehen: die eigenen Kontakte, sonst mail.notfall. */
  List<String> empfaenger()
  {
    return eigeneKontakte.isEmpty() ? konfig.mailEmpfaenger() : eigeneKontakte;
  }

  /** @return was den Versand verhindert. Mit eigenen Kontakten spielt mail.notfall keine Rolle. */
  List<String> mailProbleme()
  {
    return eigeneKontakte.isEmpty() ? konfig.mailProbleme() : konfig.mailServerProbleme();
  }

  /**
   * @param eingabe die Adressen, getrennt durch Komma, Semikolon, Leerzeichen oder Zeilenumbruch;
   *                leer = zurück zum Standard aus der Konfiguration.
   * @throws IllegalArgumentException bei einer ungültigen Adresse oder zu vielen Adressen.
   */
  synchronized void speichereKontakte(String eingabe)
  {
    List<String> neu = kontakteAus(eingabe);
    schreibe(neu, zeitplan, zeitplanGeaendert);
    eigeneKontakte = neu;
  }

  /** @return der gespeicherte Plan, sonst {@link Wochenplan#STANDARD}. */
  Wochenplan zeitplan()
  {
    Wochenplan plan = zeitplan;
    return plan != null ? plan : Wochenplan.STANDARD;
  }

  boolean zeitplanGespeichert()
  {
    return zeitplan != null;
  }

  /** @return wann der Plan zuletzt geändert wurde oder {@code null}. */
  LocalDateTime zeitplanGeaendert()
  {
    return zeitplanGeaendert;
  }

  synchronized void speichereZeitplan(Wochenplan plan)
  {
    LocalDateTime jetzt = LocalDateTime.now();
    schreibe(eigeneKontakte, plan, jetzt);
    zeitplan = plan;
    zeitplanGeaendert = jetzt;
  }

  static List<String> kontakteAus(String eingabe)
  {
    if (eingabe == null || eingabe.isBlank())
      return List.of();
    List<String> adressen = Stream.of(eingabe.trim().split("[,;\\s]+")).distinct().toList();
    if (adressen.size() > MAX_KONTAKTE)
      throw new IllegalArgumentException("Höchstens " + MAX_KONTAKTE + " Notfallkontakte möglich.");
    for (String adresse : adressen)
    {
      if (!versandfaehig(adresse))
        throw new IllegalArgumentException("Keine gültige E-Mail-Adresse: " + adresse);
    }
    return adressen;
  }

  /** Eine Adresse, die erst beim Versand scheitert, würde die E-Mail an alle Empfänger verhindern. */
  private static boolean versandfaehig(String adresse)
  {
    if (!Konfiguration.gueltigeAdresse(adresse))
      return false;
    try
    {
      new InternetAddress(adresse, true);
      return true;
    }
    catch (AddressException e)
    {
      return false;
    }
  }

  private void schreibe(List<String> kontakte, Wochenplan plan, LocalDateTime geaendert)
  {
    Properties p = new Properties();
    if (!kontakte.isEmpty())
      p.setProperty(KONTAKTE, String.join(",", kontakte));
    if (plan != null)
    {
      for (DayOfWeek tag : DayOfWeek.values())
        p.setProperty(schluessel(tag), plan.text(tag));
    }
    if (geaendert != null)
      p.setProperty(ZEITPLAN_GEAENDERT, geaendert.toString());
    if (gemerkteAdresse != null)
      p.setProperty(ADRESSE, gemerkteAdresse);
    Betriebszustand.schreibe(datei, p, "Umsatz-Abruf: Einstellungen aus der Weboberfläche");
  }

  private static String schluessel(DayOfWeek tag)
  {
    return ZEITPLAN_TAG + tag.name().toLowerCase(Locale.ROOT);
  }

  private List<String> gespeicherteKontakte(Properties p)
  {
    try
    {
      return kontakteAus(p.getProperty(KONTAKTE));
    }
    catch (IllegalArgumentException e)
    {
      LOG.warn("Gespeicherte Notfallkontakte in {} werden ignoriert: {}", datei, e.getMessage());
      return List.of();
    }
  }

  /** Ein unlesbarer Plan darf die Abrufe nicht still abschalten - dann gilt der Standard. */
  private Wochenplan gespeicherterPlan(Properties p)
  {
    Map<DayOfWeek,String> eingabe = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek tag : DayOfWeek.values())
    {
      if (p.getProperty(schluessel(tag)) != null)
        eingabe.put(tag, p.getProperty(schluessel(tag)));
    }
    if (eingabe.isEmpty())
      return null;
    try
    {
      return Wochenplan.aus(eingabe);
    }
    catch (IllegalArgumentException e)
    {
      LOG.warn("Gespeicherter Zeitplan in {} wird ignoriert, es gilt der Standard: {}", datei, e.getMessage());
      return null;
    }
  }

  private static LocalDateTime zeitpunkt(String wert)
  {
    if (wert == null || wert.isBlank())
      return null;
    try
    {
      return LocalDateTime.parse(wert.trim());
    }
    catch (DateTimeParseException e)
    {
      return null;
    }
  }
}

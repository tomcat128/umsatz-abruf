package de.fehrenbacher.fints;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Zugangsdaten und Abruf-Parameter aus der Konfigurationsdatei (Properties, UTF-8)
 * und/oder aus Umgebungsvariablen bzw. Spring-Properties.
 *
 * Jeder Schlüssel kann per Umgebungsvariable überschrieben werden: Großbuchstaben, Punkt
 * wird zu Unterstrich (z.B. "abruf.tage" -> ABRUF_TAGE). Fehlt die Konfigurationsdatei,
 * genügen die Umgebungsvariablen (z.B. im Docker-Container). Fehlen PIN oder
 * Passport-Passwort ganz, werden sie zur Laufzeit auf der Konsole abgefragt.
 */
public final class Konfiguration
{
  static final String ENV_PIN = "FINTS_PIN";
  /** Zusätzlicher, älterer Name für "passport.passwort" (neben PASSPORT_PASSWORT). */
  static final String ENV_PASSPORT_PASSWORT = "FINTS_PASSPORT_PASSWORT";

  /** Alle Schlüssel, die auch per Umgebungsvariable gesetzt werden können. */
  static final List<String> SCHLUESSEL = List.of(
      "fints.blz", "fints.benutzer", "fints.kundenid", "fints.pin", "fints.iban", "fints.url",
      "fints.produktid", "fints.tanverfahren", "fints.tanmedium",
      "intern.verzeichnis", "passport.datei", "passport.passwort",
      "abruf.tage", "abruf.von", "abruf.bis", "abruf.format",
      "ausgabe.ziel", "ausgabe.datei", "db.datei",
      "mail.notfall", "mail.empfaenger", "mail.absender", "mail.host", "mail.port", "mail.sicherheit",
      "mail.benutzer", "mail.passwort", "web.benutzer", "web.passwort", "web.adresse", "web.wartezeit",
      "abruf.intervall", "export.aktiv", "export.benutzer", "export.passwort", "log.level");

  /** Zeitraum in Tagen, der ohne Angabe von "abruf.tage" bzw. "abruf.von" abgerufen wird. */
  static final int STANDARD_TAGE = 7;

  /**
   * Nach einer Pause beginnt der Abruf so viele Tage vor dem letzten erfolgreichen Abruf - Banken
   * buchen teils mit Verzug, etwa über ein Wochenende.
   */
  static final int UEBERLAPPUNG_TAGE = 3;

  /** So weit wird nach einer Pause automatisch nachgeholt. Ältere Umsätze verlangen oft eine TAN. */
  static final int MAX_NACHHOLEN_TAGE = 90;

  private static final Pattern BLZ = Pattern.compile("\\d{8}");
  private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
  private static final Pattern MAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

  /** Mindestlänge eines im Klartext konfigurierten Web-Passworts - die Oberfläche hängt im Internet. */
  static final int WEB_PASSWORT_MINDESTLAENGE = 8;

  /** Abrufzeitraum eines Laufs, beide Tage einschließlich. */
  public record Zeitraum(LocalDate von, LocalDate bis) {}

  /** Umsatzformat, in dem die Bank die Buchungen liefern soll. */
  public enum Format { AUTO, CAMT, MT940 }

  /** Verschlüsselung der Verbindung zum SMTP-Server. */
  public enum MailSicherheit
  {
    STARTTLS(587), SSL(465), KEINE(25);

    private final int standardPort;

    MailSicherheit(int standardPort)
    {
      this.standardPort = standardPort;
    }
  }

  /** Wohin die Buchungen gespeichert werden. */
  public enum Ziel
  {
    CSV, DATENBANK, BEIDE;

    public boolean csv()       { return this != DATENBANK; }
    public boolean datenbank() { return this != CSV; }
  }

  private final String blz;
  private final String benutzer;
  private final String kundenId;
  private final String pin;
  private final String iban;
  private final String url;
  private final String produktId;
  private final String tanVerfahren;
  private final String tanMedium;
  private final Properties quelle;
  private final Path internVerzeichnis;
  private final Path passportDatei;
  private final String passportPasswort;
  private final int abrufTage;
  private final LocalDate festVon;
  private final LocalDate festBis;
  private final Format format;
  private final Ziel ausgabeZiel;
  private final Path ausgabeDatei;
  private final Path dbDatei;
  private final List<String> mailEmpfaenger;
  private final String mailAbsender;
  private final String mailHost;
  private final int mailPort;
  private final MailSicherheit mailSicherheit;
  private final String mailBenutzer;
  private final String mailPasswort;
  private final List<String> mailEmpfaengerProbleme;
  private final List<String> mailServerProbleme;
  private final boolean exportAktiv;
  private final String exportBenutzer;
  private final String exportPasswort;
  private final String webBenutzer;
  private final String webPasswort;
  private final String webAdresse;
  private final int webWartezeitMinuten;
  private final boolean intervallGesetzt;
  private final int logLevel;

  private Konfiguration(Properties p)
  {
    this.quelle = p;
    this.blz = pflicht(p, "fints.blz");
    if (!BLZ.matcher(this.blz).matches())
      throw new KonfigurationsFehler("fints.blz muss aus genau 8 Ziffern bestehen: " + this.blz);

    this.benutzer = pflicht(p, "fints.benutzer");
    this.kundenId = optional(p, "fints.kundenid", this.benutzer);
    this.pin = optional(p, "fints.pin", null);

    this.iban = normalisiereIban(pflicht(p, "fints.iban"));
    if (!IBAN.matcher(this.iban).matches())
      throw new KonfigurationsFehler("fints.iban ist keine gültige IBAN: " + this.iban);

    this.url = optional(p, "fints.url", null);
    this.produktId = optional(p, "fints.produktid", null);
    this.tanVerfahren = optional(p, "fints.tanverfahren", null);
    this.tanMedium = optional(p, "fints.tanmedium", null);

    // Passport-Datei, ihr Schlüssel und der Betriebszustand verwaltet die Anwendung selbst in einem
    // internen Verzeichnis (im Container ein eigenes Volume, nicht das Datenverzeichnis)
    this.internVerzeichnis = Path.of(optional(p, "intern.verzeichnis",
        Path.of(System.getProperty("user.home"), ".umsatz-abruf").toString())).toAbsolutePath();
    this.passportDatei = Path.of(optional(p, "passport.datei",
        internVerzeichnis.resolve("umsatz-abruf.passport").toString()));
    this.passportPasswort = optional(p, "passport.passwort", null);

    String tage = optional(p, "abruf.tage", Integer.toString(STANDARD_TAGE));
    int anzahlTage;
    try
    {
      anzahlTage = Integer.parseInt(tage);
    }
    catch (NumberFormatException e)
    {
      anzahlTage = -1;
    }
    if (anzahlTage < 0)
      throw new KonfigurationsFehler("abruf.tage muss eine ganze Zahl ab 0 sein: " + tage);

    // Der Zeitraum wird je Lauf neu berechnet - ein Server läuft über Tage
    this.abrufTage = anzahlTage;
    this.festBis = datum(p, "abruf.bis", null);
    this.festVon = datum(p, "abruf.von", null);
    Zeitraum z = zeitraum();
    if (z.von().isAfter(z.bis()))
      throw new KonfigurationsFehler("abruf.von (" + z.von() + ") liegt nach abruf.bis (" + z.bis() + ")");

    String f = optional(p, "abruf.format", "auto");
    try
    {
      this.format = Format.valueOf(f.toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      throw new KonfigurationsFehler("abruf.format muss auto, camt oder mt940 sein: " + f);
    }

    String ziel = optional(p, "ausgabe.ziel", "datenbank");
    try
    {
      this.ausgabeZiel = Ziel.valueOf(ziel.toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      throw new KonfigurationsFehler("ausgabe.ziel muss csv, datenbank oder beide sein: " + ziel);
    }
    this.ausgabeDatei = Path.of(optional(p, "ausgabe.datei", "umsaetze.csv"));

    this.dbDatei = Path.of(optional(p, "db.datei", "umsaetze.db"));

    // Benachrichtigung per E-Mail ist aktiv, sobald eine Notfall-Adresse eingetragen ist
    // (mail.empfaenger ist der frühere Name). Eine unvollständige Einrichtung verhindert den
    // Start nicht - die Probleme zeigt die Weboberfläche, E-Mails sind dann aus.
    List<String> empfaengerProbleme = new ArrayList<>();
    List<String> probleme = new ArrayList<>();
    String empfaenger = optional(p, "mail.notfall", optional(p, "mail.empfaenger", null));
    List<String> adressen = empfaenger == null ? List.of()
        : List.of(empfaenger.split("\\s*[,;]\\s*")).stream().filter(e -> !e.isBlank()).toList();
    for (String e : adressen)
    {
      if (!gueltigeAdresse(e))
        empfaengerProbleme.add("MAIL_NOTFALL enthält keine gültige Adresse: " + e);
    }
    this.mailEmpfaenger = adressen.stream().filter(Konfiguration::gueltigeAdresse).toList();
    this.mailHost = optional(p, "mail.host", null);
    this.mailBenutzer = optional(p, "mail.benutzer", null);
    this.mailPasswort = optional(p, "mail.passwort", null);
    this.mailAbsender = optional(p, "mail.absender", this.mailBenutzer);
    boolean mailGewuenscht = !adressen.isEmpty() || this.mailHost != null;
    if (mailGewuenscht && this.mailHost == null)
      probleme.add("MAIL_HOST (SMTP-Server) fehlt");
    if (mailGewuenscht && (this.mailAbsender == null || !gueltigeAdresse(this.mailAbsender)))
      probleme.add("MAIL_ABSENDER (bzw. MAIL_BENUTZER als Absender) ist keine gültige Adresse: "
          + (this.mailAbsender == null ? "nicht gesetzt" : this.mailAbsender));
    if (this.mailHost != null && adressen.isEmpty())
      empfaengerProbleme.add("MAIL_NOTFALL (Empfänger) fehlt");

    String sicherheit = optional(p, "mail.sicherheit", "starttls");
    MailSicherheit ms;
    try
    {
      ms = MailSicherheit.valueOf(sicherheit.toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      probleme.add("MAIL_SICHERHEIT muss starttls, ssl oder keine sein: " + sicherheit);
      ms = MailSicherheit.STARTTLS;
    }
    this.mailSicherheit = ms;
    String port = optional(p, "mail.port", Integer.toString(this.mailSicherheit.standardPort));
    int mp;
    try
    {
      mp = Integer.parseInt(port);
    }
    catch (NumberFormatException e)
    {
      probleme.add("MAIL_PORT muss eine Zahl sein: " + port);
      mp = this.mailSicherheit.standardPort;
    }
    this.mailPort = mp;
    this.mailEmpfaengerProbleme = List.copyOf(empfaengerProbleme);
    this.mailServerProbleme = List.copyOf(probleme);

    String export = optional(p, "export.aktiv", "true");
    if (!export.equalsIgnoreCase("true") && !export.equalsIgnoreCase("false"))
      throw new KonfigurationsFehler("export.aktiv muss true oder false sein: " + export);
    this.exportAktiv = Boolean.parseBoolean(export);

    // Optionaler Schutz der Export-Schnittstelle mit HTTP Basic Auth - nur beide Angaben zusammen
    this.exportBenutzer = optional(p, "export.benutzer", null);
    this.exportPasswort = optional(p, "export.passwort", null);
    if ((this.exportBenutzer == null) != (this.exportPasswort == null))
      throw new KonfigurationsFehler("Für den Schutz des Exports export.benutzer und export.passwort"
          + " (EXPORT_BENUTZER, EXPORT_PASSWORT) beide setzen oder beide leer lassen.");
    if (this.exportPasswort != null && !this.exportPasswort.startsWith("{")
        && this.exportPasswort.length() < WEB_PASSWORT_MINDESTLAENGE)
      throw new KonfigurationsFehler("export.passwort muss mindestens " + WEB_PASSWORT_MINDESTLAENGE
          + " Zeichen lang sein (oder ein Hash wie {bcrypt}...).");

    this.webBenutzer = optional(p, "web.benutzer", null);
    this.webPasswort = optional(p, "web.passwort", null);
    String adresse = optional(p, "web.adresse", null);
    this.webAdresse = adresse == null ? null : adresse.replaceAll("/+$", "");
    if (this.webAdresse != null && !this.webAdresse.matches("https?://\\S+"))
      throw new KonfigurationsFehler("web.adresse muss mit http:// oder https:// beginnen: " + adresse);
    String wartezeit = optional(p, "web.wartezeit", "10");
    try
    {
      this.webWartezeitMinuten = Integer.parseInt(wartezeit);
    }
    catch (NumberFormatException e)
    {
      throw new KonfigurationsFehler("web.wartezeit muss eine Zahl (Minuten) sein: " + wartezeit);
    }
    if (this.webWartezeitMinuten < 1)
      throw new KonfigurationsFehler("web.wartezeit muss mindestens 1 Minute sein: " + wartezeit);
    this.intervallGesetzt = optional(p, "abruf.intervall", null) != null;

    String level = optional(p, "log.level", "2");
    try
    {
      this.logLevel = Integer.parseInt(level);
    }
    catch (NumberFormatException e)
    {
      throw new KonfigurationsFehler("log.level muss eine Zahl zwischen 0 und 5 sein: " + level);
    }
  }

  /**
   * Lädt die Konfiguration aus der angegebenen Datei, ergänzt bzw. überschrieben durch
   * Umgebungsvariablen.
   * @param datei Pfad zur Properties-Datei.
   * @param dateiPflicht {@code false}, wenn die Datei fehlen darf, sofern die Umgebungsvariablen
   *                     die Pflichtangaben enthalten.
   * @return die geprüfte Konfiguration.
   * @throws IOException wenn die Datei nicht gelesen werden kann.
   */
  public static Konfiguration laden(Path datei, boolean dateiPflicht) throws IOException
  {
    return laden(datei, dateiPflicht, System.getenv());
  }

  /**
   * Wie {@link #laden(Path, boolean)}, mit den Werten aus {@code env} statt der Umgebungsvariablen.
   * @param env Werte unter den Namen der Umgebungsvariablen (z.B. ABRUF_TAGE).
   */
  static Konfiguration laden(Path datei, boolean dateiPflicht, Map<String,String> env) throws IOException
  {
    Properties p = new Properties();
    if (Files.isRegularFile(datei))
    {
      try (Reader r = Files.newBufferedReader(datei, StandardCharsets.UTF_8))
      {
        p.load(r);
      }
    }
    else if (dateiPflicht || vonUmgebung(env, envName("fints.blz")) == null)
    {
      throw new KonfigurationsFehler("Konfigurationsdatei nicht gefunden: " + datei.toAbsolutePath()
          + " (Vorlage: fints.properties.example). Alternativ Umgebungsvariablen setzen, z.B. "
          + envName("fints.blz") + ".");
    }
    return aus(p, env);
  }

  static Konfiguration aus(Properties datei, Map<String,String> env)
  {
    Properties p = new Properties();
    p.putAll(datei);
    for (String key : SCHLUESSEL)
    {
      String wert = vonUmgebung(env, envName(key));
      if (wert == null && key.equals("passport.passwort"))
        wert = vonUmgebung(env, ENV_PASSPORT_PASSWORT);
      if (wert != null)
        p.setProperty(key, wert);
    }
    return new Konfiguration(p);
  }

  /** @return den Namen der Umgebungsvariablen zu einem Schlüssel, z.B. "abruf.tage" -> ABRUF_TAGE. */
  static String envName(String key)
  {
    return key.toUpperCase(Locale.ROOT).replace('.', '_');
  }

  static String normalisiereIban(String iban)
  {
    return iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  private static String pflicht(Properties p, String key)
  {
    String wert = optional(p, key, null);
    if (wert == null)
      throw new KonfigurationsFehler("Pflichtangabe fehlt: " + key + " (Umgebungsvariable " + envName(key) + ")");
    return wert;
  }

  private static String optional(Properties p, String key, String standard)
  {
    String wert = p.getProperty(key);
    if (wert == null || wert.isBlank())
      return standard;
    return wert.trim();
  }

  private static String vonUmgebung(Map<String,String> env, String name)
  {
    String wert = env.get(name);
    return wert == null || wert.isEmpty() ? null : wert;
  }

  private static LocalDate datum(Properties p, String key, LocalDate standard)
  {
    String wert = optional(p, key, null);
    if (wert == null)
      return standard;
    try
    {
      return LocalDate.parse(wert);
    }
    catch (DateTimeParseException e)
    {
      throw new KonfigurationsFehler(key + " muss im Format JJJJ-MM-TT angegeben werden: " + wert);
    }
  }

  public String blz()              { return blz; }
  public String benutzer()         { return benutzer; }
  public String kundenId()         { return kundenId; }
  /** @return die PIN oder {@code null}, wenn sie abgefragt werden soll. */
  public String pin()              { return pin; }
  public String iban()             { return iban; }
  /** @return die PIN/TAN-Serveradresse oder {@code null}, wenn sie aus der Bankenliste kommt. */
  public String url()              { return url; }
  /** @return die FinTS-Produktregistrierung oder {@code null} für die Test-Registrierung von HBCI4Java. */
  public String produktId()        { return produktId; }
  /** @return der Code des TAN-Verfahrens (z.B. "921") oder {@code null} für Auswahl. */
  public String tanVerfahren()     { return tanVerfahren; }
  public String tanMedium()        { return tanMedium; }
  /** @return das Verzeichnis, das die Anwendung selbst verwaltet (Passport-Datei, Schlüssel, Status). */
  public Path internVerzeichnis()  { return internVerzeichnis; }
  public Path passportDatei()      { return passportDatei; }
  /** @return das Passport-Passwort oder {@code null}, wenn es abgefragt werden soll. */
  public String passportPasswort() { return passportPasswort; }
  /**
   * @return den Abrufzeitraum ab heute (bzw. den fest konfigurierten). Je Lauf einmal abfragen,
   *         damit sich der Zeitraum um Mitternacht nicht mitten im Lauf verschiebt.
   */
  public Zeitraum zeitraum()
  {
    LocalDate bis = festBis != null ? festBis : LocalDate.now();
    return new Zeitraum(festVon != null ? festVon : bis.minusDays(abrufTage), bis);
  }

  /**
   * @param letzterBis bis zu welchem Tag der letzte erfolgreiche Abruf reichte; {@code null} = unbekannt.
   * @return der Abrufzeitraum, nach einer längeren Pause so weit erweitert, dass keine Lücke
   *         entsteht - höchstens um {@link #MAX_NACHHOLEN_TAGE} Tage. Ein fest eingestellter
   *         Zeitraum bleibt unverändert.
   */
  public Zeitraum zeitraumNach(LocalDate letzterBis)
  {
    Zeitraum normal = zeitraum();
    if (letzterBis == null || festerZeitraum())
      return normal;
    LocalDate gewuenscht = letzterBis.minusDays(UEBERLAPPUNG_TAGE);
    LocalDate grenze = normal.bis().minusDays(MAX_NACHHOLEN_TAGE);
    LocalDate von = gewuenscht.isBefore(grenze) ? grenze : gewuenscht;
    return von.isBefore(normal.von()) ? new Zeitraum(von, normal.bis()) : normal;
  }

  /** @return {@code true}, wenn abruf.von oder abruf.bis fest eingestellt ist. */
  public boolean festerZeitraum()  { return festVon != null || festBis != null; }

  public LocalDate von()           { return zeitraum().von(); }
  public LocalDate bis()           { return zeitraum().bis(); }
  /** @return {@code true}, wenn abruf.von fest eingestellt ist (im Serverbetrieb meist unerwünscht). */
  public boolean festerBeginn()    { return festVon != null; }
  public Format format()           { return format; }
  public Ziel ausgabeZiel()         { return ausgabeZiel; }
  /** @return die CSV-Datei, an die neue Buchungen angehängt werden. */
  public Path ausgabeDatei()       { return ausgabeDatei; }
  /** @return die SQLite-Datei mit den Buchungen. */
  public Path dbDatei()            { return dbDatei; }
  /** @return die Empfänger der Benachrichtigung; leer = keine E-Mails. */
  public List<String> mailEmpfaenger() { return mailEmpfaenger; }
  public String mailAbsender()     { return mailAbsender; }
  public String mailHost()         { return mailHost; }
  public int mailPort()            { return mailPort; }
  public MailSicherheit mailSicherheit() { return mailSicherheit; }
  public String mailBenutzer()     { return mailBenutzer; }
  public String mailPasswort()     { return mailPasswort; }
  /** @return was an der E-Mail-Einrichtung fehlt oder falsch ist; leer = in Ordnung oder nicht gewünscht. */
  public List<String> mailProbleme()
  {
    List<String> alle = new ArrayList<>(mailEmpfaengerProbleme);
    alle.addAll(mailServerProbleme);
    return alle;
  }
  /** @return was am Postfach fehlt oder falsch ist - unabhängig von den Empfängern. */
  public List<String> mailServerProbleme() { return mailServerProbleme; }
  /** @return {@code true}, wenn die Export-Schnittstelle /api/buchungen eingeschaltet ist. */
  public boolean exportAktiv()     { return exportAktiv; }
  /** @return {@code true}, wenn die Export-Schnittstelle Basic Auth verlangt. */
  public boolean exportGeschuetzt() { return exportBenutzer != null; }
  public String exportBenutzer()   { return exportBenutzer; }
  /** @return das Export-Passwort im Klartext oder als Hash mit Präfix, z.B. {bcrypt}... */
  public String exportPasswort()   { return exportPasswort; }
  /** @return {@code true}, wenn Server und Absender für den Versand eingerichtet sind. */
  public boolean mailVersandMoeglich()
  {
    return mailHost != null && mailAbsender != null && gueltigeAdresse(mailAbsender);
  }

  static boolean gueltigeAdresse(String adresse)
  {
    return adresse != null && MAIL.matcher(adresse).matches();
  }
  public String webBenutzer()      { return webBenutzer; }
  /** @return das Web-Passwort im Klartext oder als Hash mit Präfix, z.B. {bcrypt}... */
  public String webPasswort()      { return webPasswort; }
  /** @return die öffentliche Adresse der Weboberfläche (für Links in E-Mails) oder {@code null}. */
  public String webAdresse()       { return webAdresse; }
  /** @return wie lange auf eine Eingabe in der Weboberfläche gewartet wird. */
  public int webWartezeitMinuten() { return webWartezeitMinuten; }
  /** @return {@code true}, wenn noch das frühere abruf.intervall gesetzt ist - es wird ignoriert. */
  public boolean intervallGesetzt() { return intervallGesetzt; }

  /** @return die Datei, in der gespeichert wird, ob die Abrufe ausgesetzt sind. */
  public Path statusDatei()
  {
    return internVerzeichnis.resolve("status.properties");
  }

  /** @return die Datei mit den Einstellungen aus der Weboberfläche, neben der Datenbank. */
  public Path einstellungenDatei()
  {
    return dbDatei.toAbsolutePath().resolveSibling("einstellungen.properties");
  }

  /**
   * @return eine Kopie dieser Konfiguration mit einem geänderten Wert, z.B. dem von der
   *         Anwendung verwalteten Passport-Passwort.
   */
  Konfiguration mit(String key, String wert)
  {
    Properties p = new Properties();
    p.putAll(quelle);
    p.setProperty(key, wert);
    return new Konfiguration(p);
  }

  /**
   * Prüft die Angaben, die nur der Serverbetrieb braucht.
   * @throws KonfigurationsFehler wenn Benutzer oder Passwort fehlen oder das Passwort zu kurz ist.
   */
  public void pruefeServerbetrieb()
  {
    if (webBenutzer == null)
      throw new KonfigurationsFehler("Pflichtangabe für den Serverbetrieb fehlt: web.benutzer (Umgebungsvariable WEB_BENUTZER)");
    if (webPasswort == null)
      throw new KonfigurationsFehler("Pflichtangabe für den Serverbetrieb fehlt: web.passwort (Umgebungsvariable WEB_PASSWORT)");
    if (!webPasswort.startsWith("{") && webPasswort.length() < WEB_PASSWORT_MINDESTLAENGE)
      throw new KonfigurationsFehler("web.passwort muss mindestens " + WEB_PASSWORT_MINDESTLAENGE
          + " Zeichen lang sein - die Weboberfläche ist aus dem Internet erreichbar.");
  }
  public int logLevel()            { return logLevel; }

  /** Fehlerhafte oder unvollständige Konfiguration. */
  public static class KonfigurationsFehler extends RuntimeException
  {
    private static final long serialVersionUID = 1L;

    KonfigurationsFehler(String msg)
    {
      super(msg);
    }
  }
}

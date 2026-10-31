package de.fehrenbacher.fints;

import java.net.InetAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E-Mails an die Notfall-Adresse (mail.notfall):
 * <ul>
 * <li>Der Umsatz-Abruf hat eine Freigabe (TAN, pushTAN-App) bei der Bank angefordert. So lässt
 *     sich eine Push-Nachricht der Bank-App eindeutig diesem System zuordnen - und eine nicht
 *     erwartete Anfrage als verdächtig erkennen.</li>
 * <li>Die automatischen Abrufe sind ausgesetzt, weil die Autorisierung erneuert werden muss.</li>
 * <li>Die Abrufe laufen nach erneuter Autorisierung wieder.</li>
 * </ul>
 */
final class Benachrichtigung
{
  /** Zeitlimit für Verbindung, Lesen und Schreiben - der Bankdialog soll nicht lange warten. */
  private static final String TIMEOUT_MS = "15000";

  private static final Logger LOG = LoggerFactory.getLogger(Benachrichtigung.class);

  private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

  private final Konfiguration konfig;
  private final Session session;

  private Benachrichtigung(Konfiguration konfig)
  {
    this.konfig = konfig;
    this.session = session(konfig);
  }

  /**
   * @return die Benachrichtigung oder {@code null}, wenn keine Empfänger konfiguriert sind.
   */
  static Benachrichtigung aus(Konfiguration konfig)
  {
    return konfig.mailEmpfaenger().isEmpty() ? null : new Benachrichtigung(konfig);
  }

  /**
   * Prüft Verbindung und Anmeldung am SMTP-Server, ohne eine E-Mail zu senden. Wird vor dem
   * Bankabruf aufgerufen, damit eine fehlerhafte Mail-Konfiguration sofort auffällt.
   * @throws MessagingException wenn der Server nicht erreichbar ist oder die Anmeldung scheitert.
   */
  void pruefe() throws MessagingException
  {
    try (Transport t = session.getTransport())
    {
      t.connect();
    }
  }

  /**
   * Sendet eine Test-E-Mail.
   * @throws MessagingException wenn der Versand scheitert.
   */
  void sendeTest() throws MessagingException
  {
    sende("Umsatz-Abruf: Test-E-Mail",
        "Diese Test-E-Mail bestätigt, dass die Benachrichtigung des Umsatz-Abrufs funktioniert.\n\n"
        + kopf(ZonedDateTime.now()));
  }

  /**
   * Meldet eine angeforderte Freigabe. Ein Fehler beim Versand bricht den Abruf nicht ab,
   * sondern wird nur protokolliert.
   * @param art Art der Freigabe, z.B. "Freigabe in der App (pushTAN)".
   * @param bankText Hinweistext der Bank oder {@code null}.
   * @param zeitraum Abrufzeitraum des laufenden Abrufs.
   */
  void freigabeAngefordert(String art, String bankText, Konfiguration.Zeitraum zeitraum)
  {
    sendeOhneFehler("Umsatz-Abruf: Freigabe angefordert für Konto " + maskiereIban(konfig.iban()),
        text(art, bankText, zeitraum, ZonedDateTime.now()));
  }

  /**
   * Meldet an die Notfall-Adresse, dass die automatischen Abrufe ausgesetzt sind, bis die
   * Autorisierung im Browser erneuert wird.
   * @param grund z.B. "Keine Freigabe in der App innerhalb von 10 Minuten".
   */
  void abrufeAusgesetzt(String grund)
  {
    sendeOhneFehler("Umsatz-Abruf: Autorisierung erforderlich - Abrufe ausgesetzt", ausgesetztText(grund, ZonedDateTime.now()));
  }

  /** Meldet, dass die automatischen Abrufe nach erfolgreicher Autorisierung wieder laufen. */
  void abrufeFortgesetzt()
  {
    sendeOhneFehler("Umsatz-Abruf: Autorisierung erneuert - Abrufe laufen wieder",
        "Die Autorisierung bei der Bank wurde erneuert. Die automatischen Abrufe laufen wieder.\n\n"
        + kopf(ZonedDateTime.now()));
  }

  String ausgesetztText(String grund, ZonedDateTime zeit)
  {
    StringBuilder sb = new StringBuilder();
    sb.append("Die Bank verlangt eine erneute Autorisierung, die nicht erfolgt ist.\n")
        .append("Die automatischen Abrufe sind deshalb ausgesetzt - es werden keine weiteren Anfragen\n")
        .append("an die Bank gestellt, bis Sie die Autorisierung im Browser erneuern.\n\n");
    sb.append(kopf(zeit));
    sb.append(String.format("%-14s %s%n", "Grund:", grund));
    sb.append("\nSo geht es weiter:\n")
        .append("1. In der Weboberfläche anmelden")
        .append(konfig.webAdresse() != null ? ": " + konfig.webAdresse() + "/" : "").append('\n')
        .append("2. \"Jetzt autorisieren\" wählen\n")
        .append("3. Die Anfrage in der Banking-App freigeben bzw. die TAN im Browser eingeben\n")
        .append("\nDanach laufen die Abrufe automatisch weiter.\n");
    return sb.toString();
  }

  private void sendeOhneFehler(String betreff, String text)
  {
    try
    {
      sende(betreff, text);
      LOG.info("E-Mail an {} gesendet: {}", String.join(", ", konfig.mailEmpfaenger()), betreff);
    }
    catch (MessagingException | RuntimeException e)
    {
      LOG.warn("E-Mail \"{}\" konnte nicht gesendet werden: {}", betreff, e.getMessage());
    }
  }

  String text(String art, String bankText, Konfiguration.Zeitraum zeitraum, ZonedDateTime zeit)
  {
    StringBuilder sb = new StringBuilder();
    sb.append("Der Umsatz-Abruf hat soeben eine Freigabe bzw. Eingabe angefordert.\n\n");
    sb.append(kopf(zeit));
    sb.append(String.format("%-14s %s%n", "Zeitraum:", zeitraum.von() + " bis " + zeitraum.bis()));
    sb.append(String.format("%-14s %s%n", "Art:", art));
    if (bankText != null && !bankText.isBlank())
      sb.append("\nHinweis der Bank:\n").append(bankText.trim()).append('\n');
    if (konfig.webAdresse() != null)
      sb.append("\nEingaben und Status in der Weboberfläche: ").append(konfig.webAdresse()).append("/\n");
    sb.append("\nHaben Sie bzw. Ihr System diesen Abruf nicht gestartet, geben Sie die Anfrage NICHT frei,\n"
        + "sondern lehnen Sie sie ab und wenden Sie sich an Ihre Bank.\n");
    return sb.toString();
  }

  private String kopf(ZonedDateTime zeit)
  {
    return String.format("%-14s %s%n", "Zeitpunkt:", ZEIT.format(zeit))
        + String.format("%-14s %s%n", "Rechner:", rechner())
        + String.format("%-14s %s%n", "Konto:", maskiereIban(konfig.iban()))
        + String.format("%-14s %s%n", "BLZ:", konfig.blz());
  }

  private void sende(String betreff, String text) throws MessagingException
  {
    MimeMessage m = new MimeMessage(session);
    m.setFrom(new InternetAddress(konfig.mailAbsender()));
    for (String e : konfig.mailEmpfaenger())
      m.addRecipient(Message.RecipientType.TO, new InternetAddress(e));
    m.setSubject(betreff, "UTF-8");
    m.setText(text, "UTF-8");
    m.setSentDate(new Date());
    Transport.send(m);
  }

  /**
   * Die vollständige IBAN gehört nicht in eine E-Mail - erkennbar bleibt sie an Land und Ende.
   */
  static String maskiereIban(String iban)
  {
    if (iban == null || iban.length() < 8)
      return "****";
    return iban.substring(0, 4) + " **** " + iban.substring(iban.length() - 4);
  }

  private static String rechner()
  {
    try
    {
      return InetAddress.getLocalHost().getHostName();
    }
    catch (Exception e)
    {
      return "unbekannt";
    }
  }

  private static Session session(Konfiguration k)
  {
    Properties p = new Properties();
    p.setProperty("mail.transport.protocol", "smtp");
    p.setProperty("mail.smtp.host", k.mailHost());
    p.setProperty("mail.smtp.port", Integer.toString(k.mailPort()));
    p.setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS);
    p.setProperty("mail.smtp.timeout", TIMEOUT_MS);
    p.setProperty("mail.smtp.writetimeout", TIMEOUT_MS);

    switch (k.mailSicherheit())
    {
      case SSL:
        p.setProperty("mail.smtp.ssl.enable", "true");
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        break;
      case STARTTLS:
        // Ohne erfolgreiches STARTTLS wird nicht gesendet - das Passwort geht nie im Klartext raus
        p.setProperty("mail.smtp.starttls.enable", "true");
        p.setProperty("mail.smtp.starttls.required", "true");
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        break;
      default:
        break;
    }

    if (k.mailBenutzer() == null)
      return Session.getInstance(p);

    p.setProperty("mail.smtp.auth", "true");
    p.setProperty("mail.smtp.user", k.mailBenutzer());
    String benutzer = k.mailBenutzer();
    String passwort = k.mailPasswort() != null ? k.mailPasswort() : "";
    return Session.getInstance(p, new Authenticator()
    {
      @Override
      protected PasswordAuthentication getPasswordAuthentication()
      {
        return new PasswordAuthentication(benutzer, passwort);
      }
    });
  }
}

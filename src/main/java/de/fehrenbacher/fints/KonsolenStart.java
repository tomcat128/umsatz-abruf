package de.fehrenbacher.fints;

import jakarta.mail.MessagingException;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/**
 * Befehle "abruf" und "mail-test": einmal ausführen, Ergebnis im Terminal, dann beenden.
 * Exit-Code: 0 = erfolgreich, 1 = Abruf fehlgeschlagen, 2 = Konfigurationsfehler.
 */
@Component
@ConditionalOnNotWebApplication
class KonsolenStart implements CommandLineRunner, ExitCodeGenerator
{
  private final Konfiguration konfig;
  private final UmsatzAbrufAnwendung.Aufruf aufruf;
  private final AbrufDienst dienst;
  private int exitCode;

  KonsolenStart(Konfiguration konfig, UmsatzAbrufAnwendung.Aufruf aufruf, AbrufDienst dienst)
  {
    this.konfig = konfig;
    this.aufruf = aufruf;
    this.dienst = dienst;
  }

  @Override
  public void run(String... args)
  {
    try
    {
      if (aufruf.befehl() == UmsatzAbrufAnwendung.Befehl.MAIL_TEST)
        mailTest();
      else
        abruf();
    }
    catch (Konfiguration.KonfigurationsFehler e)
    {
      System.err.println("Konfigurationsfehler: " + e.getMessage());
      exitCode = 2;
    }
    catch (Exception e)
    {
      System.err.println("Abruf fehlgeschlagen: " + AbrufDienst.fehlertext(e));
      if (Boolean.getBoolean("umsatz.debug"))
        e.printStackTrace();
      exitCode = 1;
    }
  }

  @Override
  public int getExitCode()
  {
    return exitCode;
  }

  private void abruf() throws Exception
  {
    AbrufDienst.Zusammenfassung z = dienst.ausfuehren(new KonsolenInteraktion(), "Konsole");
    new UmsatzAusgabe(System.out).drucke(konfig.iban(), z.neu());
    z.meldungen().forEach(System.out::println);
  }

  private void mailTest()
  {
    Benachrichtigung benachrichtigung = Benachrichtigung.aus(konfig);
    if (benachrichtigung == null)
      throw new Konfiguration.KonfigurationsFehler("Keine E-Mail-Benachrichtigung konfiguriert (mail.empfaenger).");
    try
    {
      benachrichtigung.sendeTest();
    }
    catch (MessagingException e)
    {
      throw new IllegalStateException("Test-E-Mail konnte nicht gesendet werden", e);
    }
    System.out.println("Test-E-Mail an " + String.join(", ", konfig.mailEmpfaenger()) + " gesendet.");
  }
}

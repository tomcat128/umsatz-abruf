package de.fehrenbacher.fints;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.support.CronExpression;

/**
 * Einstieg. Aufruf: {@code java -jar umsatz-abruf.jar [befehl] [konfigurationsdatei] [--schluessel=wert ...]}
 *
 * <ul>
 * <li>{@code abruf} (Standard): einmaliger Abruf, Eingaben im Terminal.</li>
 * <li>{@code server}: Weboberfläche mit Login, Abruf nach Zeitplan und auf Knopfdruck,
 *     Freigaben und Eingaben werden in der Weboberfläche beantwortet.</li>
 * <li>{@code mail-test} (auch {@code --mail-test}): nur eine Test-E-Mail senden.</li>
 * </ul>
 *
 * Die Konfigurationsdatei ist standardmäßig {@code fints.properties} im aktuellen Verzeichnis
 * und darf fehlen, wenn die Werte aus Umgebungsvariablen kommen.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class UmsatzAbrufAnwendung
{
  enum Befehl { ABRUF, SERVER, MAIL_TEST }

  /** Befehl und Konfigurationsdatei aus der Befehlszeile. */
  record Aufruf(Befehl befehl, Path datei, boolean dateiAngegeben) {}

  public static void main(String[] args)
  {
    Aufruf aufruf;
    Konfiguration konfig;
    try
    {
      aufruf = aufruf(args);
      konfig = Konfiguration.laden(aufruf.datei(), aufruf.dateiAngegeben(), werte(args));
      if (aufruf.befehl() == Befehl.SERVER)
        pruefeServerbetrieb(konfig);
    }
    catch (Konfiguration.KonfigurationsFehler e)
    {
      System.err.println("Konfigurationsfehler: " + e.getMessage());
      System.exit(2);
      return;
    }
    catch (Exception e)
    {
      System.err.println("Konfiguration konnte nicht gelesen werden: " + AbrufDienst.fehlertext(e));
      System.exit(2);
      return;
    }

    SpringApplication app = new SpringApplication(UmsatzAbrufAnwendung.class);
    app.setBannerMode(Banner.Mode.OFF);
    app.setLogStartupInfo(false);
    app.setWebApplicationType(aufruf.befehl() == Befehl.SERVER ? WebApplicationType.SERVLET : WebApplicationType.NONE);
    app.addInitializers(ctx ->
    {
      ctx.getBeanFactory().registerSingleton("konfiguration", konfig);
      ctx.getBeanFactory().registerSingleton("aufruf", aufruf);
    });

    ConfigurableApplicationContext ctx = app.run(args);
    if (aufruf.befehl() != Befehl.SERVER)
      System.exit(SpringApplication.exit(ctx));
  }

  static Aufruf aufruf(String[] args)
  {
    Befehl befehl = Befehl.ABRUF;
    String datei = null;
    for (String a : args)
    {
      if (a.equals("--mail-test"))
        befehl = Befehl.MAIL_TEST;
      else if (a.startsWith("--"))
        continue;
      else if (a.equals("abruf") || a.equals("server") || a.equals("mail-test"))
        befehl = Befehl.valueOf(a.toUpperCase(Locale.ROOT).replace('-', '_'));
      else if (datei == null)
        datei = a;
      else
        throw new Konfiguration.KonfigurationsFehler("Unbekanntes Argument: " + a
            + " (erlaubt: abruf, server, mail-test, eine Konfigurationsdatei und --schluessel=wert)");
    }
    return new Aufruf(befehl, Path.of(datei != null ? datei : "fints.properties"), datei != null);
  }

  /**
   * @return Umgebungsvariablen, ergänzt um {@code --schluessel=wert} aus der Befehlszeile
   *         (unter dem Namen der Umgebungsvariablen, damit die Befehlszeile Vorrang hat).
   */
  static Map<String,String> werte(String[] args)
  {
    Map<String,String> werte = new HashMap<>(System.getenv());
    for (String a : args)
    {
      int gleich = a.indexOf('=');
      if (!a.startsWith("--") || gleich < 0)
        continue;
      String key = a.substring(2, gleich);
      if (Konfiguration.SCHLUESSEL.contains(key))
        werte.put(Konfiguration.envName(key), a.substring(gleich + 1));
    }
    return werte;
  }

  private static void pruefeServerbetrieb(Konfiguration konfig)
  {
    konfig.pruefeServerbetrieb();
    if (!ZeitplanDienst.AUS.equals(konfig.zeitplan()))
    {
      try
      {
        CronExpression.parse(konfig.zeitplan());
      }
      catch (IllegalArgumentException e)
      {
        throw new Konfiguration.KonfigurationsFehler("abruf.zeitplan ist kein gültiger Cron-Ausdruck"
            + " (Sekunde Minute Stunde Tag Monat Wochentag, z.B. \"0 0 6 * * *\"): " + konfig.zeitplan());
      }
    }
    if (konfig.festerBeginn())
      System.err.println("Warnung: abruf.von ist fest eingestellt - jeder Lauf ruft ab diesem Tag ab."
          + " Für den Serverbetrieb besser abruf.tage verwenden.");
  }
}

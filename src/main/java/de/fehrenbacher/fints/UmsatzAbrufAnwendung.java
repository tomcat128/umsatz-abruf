package de.fehrenbacher.fints;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Einstieg. Aufruf: {@code java -jar umsatz-abruf.jar [konfigurationsdatei] [--schluessel=wert ...]}
 *
 * Startet die Weboberfläche und ruft die Umsätze im eingestellten Intervall ab. Eingaben und
 * Freigaben, die die Bank verlangt, werden im Browser beantwortet.
 *
 * Die Konfigurationsdatei ist standardmäßig {@code fints.properties} im aktuellen Verzeichnis
 * und darf fehlen, wenn die Werte aus Umgebungsvariablen kommen.
 *
 * Hilfsbefehl: {@code java -jar umsatz-abruf.jar passwort-hash} erzeugt einen Hash für
 * web.passwort, damit das Passwort nicht im Klartext in der Konfiguration steht.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class UmsatzAbrufAnwendung
{
  /** Konfigurationsdatei aus der Befehlszeile. */
  record Aufruf(Path datei, boolean dateiAngegeben) {}

  public static void main(String[] args)
  {
    if (args.length > 0 && args[0].equals("passwort-hash"))
    {
      passwortHash();
      return;
    }

    Aufruf aufruf;
    Konfiguration konfig;
    try
    {
      aufruf = aufruf(args);
      konfig = Konfiguration.laden(aufruf.datei(), aufruf.dateiAngegeben(), werte(args));
      konfig.pruefeServerbetrieb();
      if (konfig.festerBeginn())
        System.err.println("Warnung: abruf.von ist fest eingestellt - jeder Abruf beginnt an diesem Tag."
            + " Besser abruf.tage verwenden.");
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
    app.addInitializers(ctx ->
    {
      ctx.getBeanFactory().registerSingleton("konfiguration", konfig);
      ctx.getBeanFactory().registerSingleton("aufruf", aufruf);
    });
    app.run(args);
  }

  static Aufruf aufruf(String[] args)
  {
    String datei = null;
    for (String a : args)
    {
      // "server" war früher der Befehl für diesen Betrieb - wird weiter akzeptiert
      if (a.startsWith("--") || a.equals("server"))
        continue;
      if (datei != null)
        throw new Konfiguration.KonfigurationsFehler("Unbekanntes Argument: " + a
            + " (erlaubt: eine Konfigurationsdatei und --schluessel=wert)");
      datei = a;
    }
    return new Aufruf(Path.of(datei != null ? datei : "fints.properties"), datei != null);
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

  /**
   * Fragt ein Passwort doppelt ab und gibt den Hash für web.passwort aus.
   */
  private static void passwortHash()
  {
    String passwort = Konsole.geheim("Passwort für die Weboberfläche: ");
    if (!passwort.equals(Konsole.geheim("Wiederholen: ")))
    {
      System.err.println("Die Eingaben stimmen nicht überein.");
      System.exit(2);
    }
    if (passwort.length() < Konfiguration.WEB_PASSWORT_MINDESTLAENGE)
    {
      System.err.println("Das Passwort muss mindestens " + Konfiguration.WEB_PASSWORT_MINDESTLAENGE + " Zeichen lang sein.");
      System.exit(2);
    }
    String hash = "{bcrypt}" + new BCryptPasswordEncoder().encode(passwort);
    System.out.println();
    System.out.println("fints.properties:  web.passwort=" + hash);
    System.out.println(".env:              WEB_PASSWORT=" + hash.replace("$", "$$"));
  }
}

package de.fehrenbacher.fints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Verwaltet die Passport-Datei selbst, damit sich niemand darum kümmern muss: Sie liegt im
 * internen Verzeichnis (intern.verzeichnis), ihr Passwort erzeugt die Anwendung beim ersten
 * Start zufällig und legt es daneben ab (nur für den eigenen Benutzer lesbar).
 *
 * Ist passport.passwort ausdrücklich konfiguriert, wird dieses verwendet und kein Schlüssel
 * gespeichert. Eine Passport-Datei früherer Versionen im Arbeitsverzeichnis (Datenverzeichnis)
 * wird übernommen, wenn ihr Passwort bekannt ist, sonst beiseitegelegt - dann legt die Anwendung
 * eine neue an, und die Bank verlangt einmalig eine Freigabe.
 */
final class PassportVerwaltung
{
  static final String SCHLUESSEL_DATEI = "passport.schluessel";

  /** Name der Passport-Datei früherer Versionen im Arbeitsverzeichnis. */
  static final String ALTE_PASSPORT_DATEI = "umsatz-abruf.passport";

  /** Name der Statusdatei früherer Versionen im Arbeitsverzeichnis. */
  static final String ALTE_STATUS_DATEI = "umsatz-abruf-status.properties";

  private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private PassportVerwaltung()
  {
  }

  /**
   * Richtet das interne Verzeichnis ein und liefert die Konfiguration mit dem Passwort der
   * Passport-Datei.
   * @param arbeitsverzeichnis wo frühere Versionen Passport- und Statusdatei abgelegt haben.
   * @param hinweis für Meldungen an den Betreiber.
   */
  static Konfiguration einrichten(Konfiguration konfig, Path arbeitsverzeichnis, Consumer<String> hinweis)
      throws IOException
  {
    Path intern = konfig.internVerzeichnis();
    legeVerzeichnisAn(intern);

    Path passport = konfig.passportDatei().toAbsolutePath();
    Path schluessel = intern.resolve(SCHLUESSEL_DATEI);
    boolean passwortKonfiguriert = konfig.passportPasswort() != null;

    uebernimmAltbestand(konfig, arbeitsverzeichnis.toAbsolutePath(), passport, schluessel, passwortKonfiguriert, hinweis);

    if (passwortKonfiguriert)
      return konfig;

    if (Files.exists(passport) && !Files.exists(schluessel))
    {
      // Passwort unbekannt (z.B. früher im Browser eingegeben) - nicht zu öffnen
      Path alt = beiseitelegen(passport, intern);
      hinweis.accept("Die Passport-Datei ließ sich nicht mit einem bekannten Passwort öffnen und wurde nach " + alt
          + " verschoben. Es wird eine neue angelegt - die Bank verlangt dafür einmalig eine Freigabe.");
    }
    return konfig.mit("passport.passwort", schluessel(schluessel));
  }

  /**
   * Übernimmt Passport- und Statusdatei früherer Versionen aus dem Arbeitsverzeichnis.
   */
  private static void uebernimmAltbestand(Konfiguration konfig, Path arbeitsverzeichnis, Path passport,
      Path schluessel, boolean passwortKonfiguriert, Consumer<String> hinweis) throws IOException
  {
    Path altStatus = arbeitsverzeichnis.resolve(ALTE_STATUS_DATEI);
    Path status = konfig.statusDatei().toAbsolutePath();
    if (!altStatus.equals(status) && Files.isRegularFile(altStatus) && !Files.exists(status))
      Files.move(altStatus, status);

    Path altPassport = arbeitsverzeichnis.resolve(ALTE_PASSPORT_DATEI);
    if (altPassport.equals(passport) || !Files.isRegularFile(altPassport))
      return;

    if (passwortKonfiguriert && !Files.exists(passport))
    {
      Files.move(altPassport, passport);
      hinweis.accept("Passport-Datei aus " + altPassport + " in das interne Verzeichnis übernommen.");
      return;
    }
    Path alt = beiseitelegen(altPassport, konfig.internVerzeichnis());
    if (!Files.exists(schluessel) && !passwortKonfiguriert)
      hinweis.accept("Die bisherige Passport-Datei " + altPassport + " ist mit einem unbekannten Passwort geschützt und"
          + " wurde nach " + alt + " verschoben. Die Anwendung legt eine neue an und verwaltet sie selbst -"
          + " die Bank verlangt dafür einmalig eine Freigabe (\"Jetzt autorisieren\").");
    else
      hinweis.accept("Bisherige Passport-Datei " + altPassport + " nach " + alt + " verschoben.");
  }

  /** @return das gespeicherte Passwort, beim ersten Aufruf zufällig erzeugt. */
  private static String schluessel(Path datei) throws IOException
  {
    if (Files.exists(datei))
    {
      String s = Files.readString(datei, StandardCharsets.UTF_8).trim();
      if (!s.isEmpty())
        return s;
    }
    byte[] zufall = new byte[32];
    new SecureRandom().nextBytes(zufall);
    String s = Base64.getUrlEncoder().withoutPadding().encodeToString(zufall);

    // Erst vollständig mit eingeschränkten Rechten schreiben, dann an seinen Platz
    Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
    Files.deleteIfExists(tmp);
    if (posix())
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(tmp, s, StandardCharsets.UTF_8);
    Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return s;
  }

  private static Path beiseitelegen(Path datei, Path intern) throws IOException
  {
    Path ordner = intern.resolve("alt");
    Files.createDirectories(ordner);
    Path ziel = ordner.resolve(datei.getFileName() + "." + STEMPEL.format(LocalDateTime.now()));
    Files.move(datei, ziel);
    return ziel;
  }

  private static void legeVerzeichnisAn(Path intern) throws IOException
  {
    Files.createDirectories(intern);
    if (posix())
    {
      try
      {
        Files.setPosixFilePermissions(intern, PosixFilePermissions.fromString("rwx------"));
      }
      catch (IOException e)
      {
        // z.B. fremder Eigentümer - die Rechte des Volumes bleiben dann, wie sie sind
      }
    }
  }

  private static boolean posix()
  {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }
}

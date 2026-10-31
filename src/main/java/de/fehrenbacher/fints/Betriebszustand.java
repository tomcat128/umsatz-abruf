package de.fehrenbacher.fints;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Merkt sich dauerhaft (übersteht Neustarts des Containers):
 * <ul>
 * <li>ob die automatischen Abrufe ausgesetzt sind. Das passiert, wenn die Bank eine
 *     Autorisierung verlangt, die nicht erfolgt ist (keine Freigabe, PIN abgelehnt). Bis zur
 *     erneuten Autorisierung im Browser löst das Programm dann keine Anfragen bei der Bank aus.</li>
 * <li>wann der letzte Abruf begonnen hat - danach richtet sich der nächste automatische Abruf.</li>
 * </ul>
 */
@Component
class Betriebszustand
{
  private static final Logger LOG = LoggerFactory.getLogger(Betriebszustand.class);

  /**
   * @param seit Zeitpunkt der Aussetzung.
   * @param grund für die Anzeige, z.B. "Keine Freigabe in der App innerhalb von 10 Minuten".
   */
  record Aussetzung(LocalDateTime seit, String grund) {}

  private final Path datei;
  private volatile Aussetzung aussetzung;
  private volatile LocalDateTime letzterAbruf;

  @Autowired
  Betriebszustand(Konfiguration konfig)
  {
    this(konfig.statusDatei());
  }

  Betriebszustand(Path datei)
  {
    this.datei = datei;
    Properties p = laden(datei);
    this.aussetzung = aussetzung(p);
    this.letzterAbruf = zeitpunkt(p.getProperty("letzterAbruf"));
    if (aussetzung != null)
      LOG.warn("Automatische Abrufe sind seit {} ausgesetzt: {}", aussetzung.seit(), aussetzung.grund());
  }

  boolean ausgesetzt()
  {
    return aussetzung != null;
  }

  /** @return die Aussetzung oder {@code null}, wenn die Abrufe laufen. */
  Aussetzung aussetzung()
  {
    return aussetzung;
  }

  /** @return der Beginn des letzten Abrufs (auch vor einem Neustart) oder {@code null}. */
  LocalDateTime letzterAbruf()
  {
    return letzterAbruf;
  }

  /** Merkt sich den Beginn eines Abrufs - auch eines, der danach scheitert. */
  synchronized void abrufBegonnen(LocalDateTime beginn)
  {
    letzterAbruf = beginn;
    speichern();
  }

  /**
   * Setzt die automatischen Abrufe aus.
   * @return {@code true}, wenn sie bisher liefen (dann ist eine Benachrichtigung fällig).
   */
  synchronized boolean aussetzen(String grund)
  {
    boolean neu = aussetzung == null;
    aussetzung = new Aussetzung(neu ? LocalDateTime.now() : aussetzung.seit(), grund);
    speichern();
    return neu;
  }

  /**
   * Nimmt die automatischen Abrufe wieder auf.
   * @return {@code true}, wenn sie ausgesetzt waren.
   */
  synchronized boolean fortsetzen()
  {
    if (aussetzung == null)
      return false;
    aussetzung = null;
    speichern();
    return true;
  }

  private void speichern()
  {
    Properties p = new Properties();
    p.setProperty("ausgesetzt", Boolean.toString(aussetzung != null));
    if (letzterAbruf != null)
      p.setProperty("letzterAbruf", letzterAbruf.toString());
    if (aussetzung != null)
    {
      p.setProperty("seit", aussetzung.seit().toString());
      p.setProperty("grund", aussetzung.grund());
    }
    try
    {
      Files.createDirectories(datei.toAbsolutePath().getParent());
      // Erst vollständig schreiben, dann ersetzen - ein Absturz hinterlässt nie eine halbe Datei
      Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
      try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
      {
        p.store(w, "Umsatz-Abruf: Betriebszustand - nicht von Hand bearbeiten");
      }
      Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException e)
    {
      throw new UncheckedIOException("Betriebszustand konnte nicht gespeichert werden: " + datei, e);
    }
  }

  private static Properties laden(Path datei)
  {
    Properties p = new Properties();
    if (!Files.isRegularFile(datei))
      return p;
    try (Reader r = Files.newBufferedReader(datei, StandardCharsets.UTF_8))
    {
      p.load(r);
    }
    catch (IOException e)
    {
      throw new UncheckedIOException("Betriebszustand konnte nicht gelesen werden: " + datei, e);
    }
    return p;
  }

  private static Aussetzung aussetzung(Properties p)
  {
    if (!Boolean.parseBoolean(p.getProperty("ausgesetzt")))
      return null;
    LocalDateTime seit = zeitpunkt(p.getProperty("seit"));
    return new Aussetzung(seit != null ? seit : LocalDateTime.now(), p.getProperty("grund", "unbekannt"));
  }

  /** @return der Zeitpunkt oder {@code null}, wenn er fehlt oder nicht lesbar ist. */
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

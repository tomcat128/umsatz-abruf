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
import java.util.List;
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
 * <li>wie viele Erinnerungen an die ausstehende Autorisierung schon verschickt wurden.</li>
 * </ul>
 */
@Component
class Betriebszustand
{
  private static final Logger LOG = LoggerFactory.getLogger(Betriebszustand.class);

  /** Nach so vielen Tagen Aussetzung geht je eine Erinnerung raus, danach keine mehr. */
  static final List<Integer> ERINNERUNG_NACH_TAGEN = List.of(1, 2, 3, 7, 14);

  /**
   * @param seit Zeitpunkt der Aussetzung.
   * @param grund für die Anzeige, z.B. "Keine Freigabe in der App innerhalb von 10 Minuten".
   */
  record Aussetzung(LocalDateTime seit, String grund) {}

  private final Path datei;
  private volatile Aussetzung aussetzung;
  private volatile LocalDateTime letzterAbruf;
  private volatile int erinnerungen;

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
    this.erinnerungen = anzahl(p.getProperty("erinnerungen"));
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
    if (neu)
      erinnerungen = 0;
    speichern();
    return neu;
  }

  /** @return wie viele Erinnerungen zur aktuellen Aussetzung verschickt wurden. */
  int erinnerungenGesendet()
  {
    return erinnerungen;
  }

  /**
   * @return die Nummer (ab 1) der jetzt fälligen Erinnerung, 0 wenn keine fällig ist. Sind mehrere
   *         überfällig, die späteste - die früheren werden nicht nachgeholt.
   */
  int faelligeErinnerung(LocalDateTime jetzt)
  {
    Aussetzung a = aussetzung;
    if (a == null)
      return 0;
    int faellig = 0;
    for (int i = 0; i < ERINNERUNG_NACH_TAGEN.size(); i++)
    {
      if (!jetzt.isBefore(a.seit().plusDays(ERINNERUNG_NACH_TAGEN.get(i))))
        faellig = i + 1;
    }
    return faellig > erinnerungen ? faellig : 0;
  }

  /** @return wann die nächste Erinnerung fällig wird oder {@code null}, wenn keine mehr kommt. */
  LocalDateTime naechsteErinnerung()
  {
    Aussetzung a = aussetzung;
    int gesendet = erinnerungen;
    if (a == null || gesendet >= ERINNERUNG_NACH_TAGEN.size())
      return null;
    return a.seit().plusDays(ERINNERUNG_NACH_TAGEN.get(gesendet));
  }

  synchronized void erinnerungGesendet(int nummer)
  {
    erinnerungen = nummer;
    speichern();
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
    erinnerungen = 0;
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
      p.setProperty("erinnerungen", Integer.toString(erinnerungen));
    }
    schreibe(datei, p, "Umsatz-Abruf: Betriebszustand - nicht von Hand bearbeiten");
  }

  /** Erst vollständig schreiben, dann ersetzen - ein Absturz hinterlässt nie eine halbe Datei. */
  static void schreibe(Path datei, Properties p, String kommentar)
  {
    try
    {
      Files.createDirectories(datei.toAbsolutePath().getParent());
      Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
      try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
      {
        p.store(w, kommentar);
      }
      Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException e)
    {
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden: " + datei, e);
    }
  }

  static Properties laden(Path datei)
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
      throw new UncheckedIOException("Datei konnte nicht gelesen werden: " + datei, e);
    }
    return p;
  }

  private static int anzahl(String wert)
  {
    try
    {
      return wert == null ? 0 : Math.max(0, Integer.parseInt(wert.trim()));
    }
    catch (NumberFormatException e)
    {
      return 0;
    }
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

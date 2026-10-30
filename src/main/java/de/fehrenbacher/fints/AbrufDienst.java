package de.fehrenbacher.fints;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;

import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Führt einen Abruf durch: Mailserver und Datenbank prüfen, Umsätze bei der Bank abrufen,
 * neue Buchungen in CSV-Datei und/oder Datenbank speichern. Es läuft immer höchstens ein
 * Abruf gleichzeitig - HBCI4Java hält seinen Zustand global.
 */
@Service
class AbrufDienst
{
  private static final Logger LOG = LoggerFactory.getLogger(AbrufDienst.class);

  /** So viele Läufe zeigt die Weboberfläche im Verlauf. */
  static final int VERLAUF_GROESSE = 20;

  enum Status { LAEUFT, ERFOLGREICH, FEHLER }

  /**
   * Ein Lauf für Verlauf und Statusanzeige.
   * @param ende {@code null}, solange der Lauf läuft.
   */
  record Lauf(LocalDateTime beginn, LocalDateTime ende, String ausloeser, Status status, int geliefert,
      int neu, String meldung) {}

  /**
   * Ergebnis eines erfolgreichen Abrufs.
   * @param neu die neu gespeicherten Buchungen.
   * @param meldungen Zusammenfassung je Speicherziel.
   */
  record Zusammenfassung(int geliefert, List<UmsLine> neu, List<String> meldungen) {}

  private final Konfiguration konfig;
  private final ReentrantLock sperre = new ReentrantLock();
  private final Deque<Lauf> verlauf = new ArrayDeque<>();
  private volatile Lauf aktuell;
  private ExecutorService hintergrund;

  AbrufDienst(Konfiguration konfig)
  {
    this.konfig = konfig;
  }

  /**
   * Führt einen Abruf im aufrufenden Thread durch.
   * @param interaktion für Eingaben während des Bankdialogs.
   * @param ausloeser für den Verlauf, z.B. "Zeitplan".
   * @return die Zusammenfassung.
   * @throws IllegalStateException wenn bereits ein Abruf läuft.
   * @throws Exception wenn der Abruf scheitert; er steht dann mit Fehler im Verlauf.
   */
  Zusammenfassung ausfuehren(Interaktion interaktion, String ausloeser) throws Exception
  {
    if (!sperre.tryLock())
      throw new IllegalStateException("Es läuft bereits ein Abruf.");
    LocalDateTime beginn = LocalDateTime.now();
    aktuell = new Lauf(beginn, null, ausloeser, Status.LAEUFT, 0, 0, null);
    try
    {
      Zusammenfassung z = abrufen(interaktion);
      protokolliere(new Lauf(beginn, LocalDateTime.now(), ausloeser, Status.ERFOLGREICH, z.geliefert(),
          z.neu().size(), String.join(" ", z.meldungen())));
      return z;
    }
    catch (Exception e)
    {
      protokolliere(new Lauf(beginn, LocalDateTime.now(), ausloeser, Status.FEHLER, 0, 0, fehlertext(e)));
      throw e;
    }
    finally
    {
      aktuell = null;
      sperre.unlock();
    }
  }

  /**
   * Startet einen Abruf im Hintergrund (Serverbetrieb).
   * @return {@code false}, wenn bereits ein Abruf läuft.
   */
  synchronized boolean starteImHintergrund(Interaktion interaktion, String ausloeser)
  {
    if (laeuft())
      return false;
    if (hintergrund == null)
      hintergrund = Executors.newSingleThreadExecutor(r -> new Thread(r, "abruf"));
    // Schon beim Einreihen als laufend anzeigen, damit ein zweiter Klick nichts startet
    aktuell = new Lauf(LocalDateTime.now(), null, ausloeser, Status.LAEUFT, 0, 0, null);
    hintergrund.submit(() ->
    {
      try
      {
        Zusammenfassung z = ausfuehren(interaktion, ausloeser);
        LOG.info("Abruf ({}) beendet: {}", ausloeser, String.join(" ", z.meldungen()));
      }
      catch (Exception e)
      {
        LOG.warn("Abruf ({}) fehlgeschlagen: {}", ausloeser, fehlertext(e));
      }
    });
    return true;
  }

  boolean laeuft()
  {
    return aktuell != null;
  }

  /** @return der laufende Abruf oder {@code null}. */
  Lauf aktuell()
  {
    return aktuell;
  }

  /** @return die letzten Läufe, neuester zuerst. */
  List<Lauf> verlauf()
  {
    synchronized (verlauf)
    {
      return new ArrayList<>(verlauf);
    }
  }

  @PreDestroy
  synchronized void beenden()
  {
    if (hintergrund != null)
      hintergrund.shutdownNow();
  }

  private Zusammenfassung abrufen(Interaktion interaktion) throws Exception
  {
    Konfiguration.Ziel ziel = konfig.ausgabeZiel();
    Konfiguration.Zeitraum zeitraum = konfig.zeitraum();

    // Mailserver und Datenbank vor dem Kontakt zur Bank prüfen, damit ein Fehler keine TAN kostet
    Benachrichtigung benachrichtigung = Benachrichtigung.aus(konfig);
    if (benachrichtigung != null)
      pruefeMailserver(benachrichtigung);

    try (UmsatzDatenbank db = ziel.datenbank()
        ? UmsatzDatenbank.verbinden(konfig.dbUrl(), konfig.dbBenutzer(), konfig.dbPasswort()) : null)
    {
      UmsatzAbruf.Ergebnis ergebnis = new UmsatzAbruf(konfig, zeitraum, interaktion, benachrichtigung).abrufen();
      List<UmsLine> buchungen = ergebnis.buchungen();
      List<UmsLine> neu = List.of();
      List<String> meldungen = new ArrayList<>();

      if (ziel.csv())
      {
        neu = new UmsatzDatei(konfig.ausgabeDatei()).ergaenze(buchungen);
        meldungen.add(neu.size() + " neue Buchung(en) in " + konfig.ausgabeDatei().toAbsolutePath()
            + " ergänzt, " + (buchungen.size() - neu.size()) + " waren bereits vorhanden.");
      }
      if (db != null)
      {
        neu = db.speichere(ergebnis.konto(), zeitraum.von(), zeitraum.bis(), ergebnis.format(), buchungen);
        meldungen.add(neu.size() + " neue Buchung(en) in der Datenbank gespeichert, "
            + (buchungen.size() - neu.size()) + " waren bereits vorhanden.");
      }
      return new Zusammenfassung(buchungen.size(), neu, meldungen);
    }
  }

  private void protokolliere(Lauf lauf)
  {
    synchronized (verlauf)
    {
      verlauf.addFirst(lauf);
      while (verlauf.size() > VERLAUF_GROESSE)
        verlauf.removeLast();
    }
  }

  private static void pruefeMailserver(Benachrichtigung benachrichtigung)
  {
    try
    {
      benachrichtigung.pruefe();
    }
    catch (MessagingException e)
    {
      throw new IllegalStateException("Mailserver für die Benachrichtigung nicht erreichbar"
          + " oder Anmeldung fehlgeschlagen", e);
    }
  }

  /**
   * HBCI4Java verschachtelt Exceptions tief - für den Benutzer zählt die Kette der Meldungen.
   */
  static String fehlertext(Throwable t)
  {
    StringBuilder sb = new StringBuilder();
    String letzte = null;
    for (Throwable c = t; c != null; c = c.getCause())
    {
      String m = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
      if (m.equals(letzte))
        continue;
      if (sb.length() > 0)
        sb.append("\n  Ursache: ");
      sb.append(m);
      letzte = m;
    }
    return sb.toString();
  }
}

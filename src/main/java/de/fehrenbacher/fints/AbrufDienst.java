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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Führt einen Abruf durch: Umsätze bei der Bank abrufen, neue Buchungen in Datenbank und/oder
 * CSV-Datei speichern. Es läuft immer höchstens ein Abruf gleichzeitig - HBCI4Java hält seinen
 * Zustand global.
 *
 * Scheitert ein Abruf, weil eine verlangte Autorisierung (PIN, TAN, Freigabe in der App) nicht
 * erfolgt ist, werden die automatischen Abrufe ausgesetzt ({@link Betriebszustand}) und die
 * Notfall-Adresse benachrichtigt. Der nächste erfolgreiche Abruf - gestartet im Browser - hebt
 * die Aussetzung wieder auf.
 */
@Service
class AbrufDienst
{
  private static final Logger LOG = LoggerFactory.getLogger(AbrufDienst.class);

  /** So viele Läufe zeigt die Weboberfläche im Verlauf. */
  static final int VERLAUF_GROESSE = 20;

  enum Status
  {
    LAEUFT, ERFOLGREICH, FEHLER,
    /** Autorisierung verlangt, aber nicht erfolgt - automatische Abrufe ausgesetzt. */
    AUTORISIERUNG_FEHLT
  }

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

  /** Der eigentliche Bankdialog - austauschbar für Tests. */
  @FunctionalInterface
  interface BankZugriff
  {
    UmsatzAbruf.Ergebnis abrufen(Konfiguration konfig, Konfiguration.Zeitraum zeitraum, Interaktion interaktion,
        Benachrichtigung benachrichtigung);
  }

  private final Konfiguration konfig;
  private final Betriebszustand zustand;
  private final BankZugriff bank;
  private final ReentrantLock sperre = new ReentrantLock();
  private final Deque<Lauf> verlauf = new ArrayDeque<>();
  private volatile Lauf aktuell;
  private ExecutorService hintergrund;

  @Autowired
  AbrufDienst(Konfiguration konfig, Betriebszustand zustand)
  {
    this(konfig, zustand, (k, z, i, b) -> new UmsatzAbruf(k, z, i, b).abrufen());
  }

  AbrufDienst(Konfiguration konfig, Betriebszustand zustand, BankZugriff bank)
  {
    this.konfig = konfig;
    this.zustand = zustand;
    this.bank = bank;
  }

  /**
   * Führt einen Abruf im aufrufenden Thread durch.
   * @param interaktion für Eingaben während des Bankdialogs.
   * @param ausloeser für den Verlauf, z.B. "Intervall".
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
    // Dauerhaft merken, damit der nächste automatische Abruf auch nach einem Neustart passt
    zustand.abrufBegonnen(beginn);
    Beobachter beobachter = new Beobachter(interaktion);
    Benachrichtigung benachrichtigung = Benachrichtigung.aus(konfig);
    try
    {
      Zusammenfassung z = abrufen(beobachter, benachrichtigung);
      protokolliere(new Lauf(beginn, LocalDateTime.now(), ausloeser, Status.ERFOLGREICH, z.geliefert(),
          z.neu().size(), String.join(" ", z.meldungen())));
      if (zustand.fortsetzen())
      {
        LOG.info("Autorisierung erneuert - automatische Abrufe laufen wieder.");
        if (benachrichtigung != null)
          benachrichtigung.abrufeFortgesetzt();
      }
      return z;
    }
    catch (Exception e)
    {
      String grund = autorisierungFehlt(e, beobachter);
      if (grund == null)
      {
        protokolliere(new Lauf(beginn, LocalDateTime.now(), ausloeser, Status.FEHLER, 0, 0, fehlertext(e)));
      }
      else
      {
        protokolliere(new Lauf(beginn, LocalDateTime.now(), ausloeser, Status.AUTORISIERUNG_FEHLT, 0, 0, grund));
        if (zustand.aussetzen(grund))
        {
          LOG.warn("Autorisierung fehlt - automatische Abrufe ausgesetzt: {}", grund);
          if (benachrichtigung != null)
            benachrichtigung.abrufeAusgesetzt(grund);
        }
      }
      throw e;
    }
    finally
    {
      aktuell = null;
      sperre.unlock();
    }
  }

  /**
   * Startet einen Abruf im Hintergrund.
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

  /**
   * @return der Beginn des laufenden bzw. letzten Abrufs - auch eines vor dem letzten Neustart -
   *         oder {@code null}, wenn noch nie einer lief.
   */
  LocalDateTime letzterBeginn()
  {
    Lauf a = aktuell;
    if (a != null)
      return a.beginn();
    synchronized (verlauf)
    {
      if (!verlauf.isEmpty())
        return verlauf.getFirst().beginn();
    }
    return zustand.letzterAbruf();
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

  private Zusammenfassung abrufen(Interaktion interaktion, Benachrichtigung benachrichtigung) throws Exception
  {
    Konfiguration.Ziel ziel = konfig.ausgabeZiel();
    Konfiguration.Zeitraum zeitraum = konfig.zeitraum();

    // Ein nicht erreichbarer Mailserver soll die Abrufe nicht verhindern - nur melden
    if (benachrichtigung != null)
      pruefeMailserver(benachrichtigung);

    // Datenbank vor dem Kontakt zur Bank öffnen, damit ein Fehler keine Freigabe kostet
    try (UmsatzDatenbank db = ziel.datenbank() ? UmsatzDatenbank.oeffnen(konfig.dbDatei()) : null)
    {
      UmsatzAbruf.Ergebnis ergebnis = bank.abrufen(konfig, zeitraum, interaktion, benachrichtigung);
      List<UmsLine> buchungen = ergebnis.buchungen();
      List<UmsLine> neu = List.of();
      List<String> meldungen = new ArrayList<>();

      if (db != null)
      {
        neu = db.speichere(ergebnis.konto(), zeitraum.von(), zeitraum.bis(), ergebnis.format(), buchungen);
        meldungen.add(neu.size() + " neue Buchung(en) gespeichert, "
            + (buchungen.size() - neu.size()) + " waren bereits vorhanden.");
      }
      if (ziel.csv())
      {
        List<UmsLine> neuCsv = new UmsatzDatei(konfig.ausgabeDatei()).ergaenze(buchungen);
        if (db == null)
          neu = neuCsv;
        meldungen.add(neuCsv.size() + " neue Buchung(en) in der CSV-Datei ergänzt.");
      }
      return new Zusammenfassung(buchungen.size(), neu, meldungen);
    }
  }

  /**
   * @return der Grund, wenn der Abruf an einer fehlenden Autorisierung gescheitert ist, sonst
   *         {@code null} (z.B. Netzwerkfehler - dann einfach beim nächsten Intervall erneut).
   */
  static String autorisierungFehlt(Throwable e, Beobachter beobachter)
  {
    for (Throwable c = e; c != null; c = c.getCause())
    {
      if (c instanceof AutorisierungsFehler)
        return c.getMessage();
    }
    if (beobachter.eingabeVerlangt())
      return "Die Bank hat eine Autorisierung verlangt, der Abruf ist danach gescheitert: " + kurz(e);
    return null;
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
      LOG.warn("Mailserver für Benachrichtigungen nicht erreichbar oder Anmeldung fehlgeschlagen: {}",
          fehlertext(e));
    }
  }

  /** @return die letzte (innerste) Fehlermeldung. */
  private static String kurz(Throwable t)
  {
    String m = null;
    for (Throwable c = t; c != null; c = c.getCause())
    {
      if (c.getMessage() != null)
        m = c.getMessage();
    }
    return m != null ? m : t.getClass().getSimpleName();
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

  /**
   * Reicht alle Aufrufe durch und merkt sich, ob die Bank eine Eingabe oder Freigabe verlangt hat.
   */
  static final class Beobachter implements Interaktion
  {
    private final Interaktion ziel;
    private volatile boolean eingabeVerlangt;

    Beobachter(Interaktion ziel)
    {
      this.ziel = ziel;
    }

    boolean eingabeVerlangt()
    {
      return eingabeVerlangt;
    }

    @Override
    public String geheim(String titel, boolean neu)
    {
      eingabeVerlangt = true;
      return ziel.geheim(titel, neu);
    }

    @Override
    public String tan(String text, byte[] bild, String mimetype)
    {
      eingabeVerlangt = true;
      return ziel.tan(text, bild, mimetype);
    }

    @Override
    public int auswahl(String titel, List<String> optionen)
    {
      eingabeVerlangt = true;
      return ziel.auswahl(titel, optionen);
    }

    @Override
    public void appFreigabe(String text, boolean erneut, int pauseSekunden)
    {
      eingabeVerlangt = true;
      ziel.appFreigabe(text, erneut, pauseSekunden);
    }

    @Override
    public void hinweis(String text)
    {
      ziel.hinweis(text);
    }

    @Override
    public void protokoll(String text)
    {
      ziel.protokoll(text);
    }

    @Override
    public boolean fern()
    {
      return ziel.fern();
    }

    @Override
    public void beendet()
    {
      ziel.beendet();
    }
  }
}

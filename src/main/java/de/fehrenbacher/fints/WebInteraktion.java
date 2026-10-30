package de.fehrenbacher.fints;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.kapott.hbci.exceptions.HBCI_Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Eingaben über die Weboberfläche (Serverbetrieb). Fordert die Bank eine Eingabe an, wird sie
 * als offene {@link Anfrage} bereitgestellt; der Bankdialog wartet, bis sie in der Weboberfläche
 * beantwortet oder abgebrochen wird oder die Wartezeit (web.wartezeit) abläuft.
 *
 * Bei der Freigabe in der App (decoupled) ist keine Eingabe nötig: HBCI4Java fragt selbst
 * regelmäßig bei der Bank nach. Die Weboberfläche zeigt dann nur den Status und bietet
 * "Jetzt prüfen" und "Abbrechen".
 */
@Component
@ConditionalOnWebApplication
class WebInteraktion implements Interaktion
{
  private static final Logger LOG = LoggerFactory.getLogger(WebInteraktion.class);

  /** So viele Hinweise der Bank zeigt die Übersicht. */
  private static final int HINWEISE = 20;

  enum Art
  {
    /** PIN oder Passwort. */
    GEHEIM,
    /** Neues Passwort, doppelt einzugeben. */
    GEHEIM_NEU,
    TAN,
    AUSWAHL,
    /** Freigabe in der App, keine Eingabe nötig. */
    APP_FREIGABE
  }

  /**
   * Eine offene Anfrage.
   * @param id zufällig, damit eine veraltete Seite keine neue Anfrage beantwortet.
   * @param bild {@code true}, wenn eine TAN-Grafik unter /freigabe/bild abrufbar ist.
   */
  record Anfrage(String id, Art art, String titel, String text, List<String> optionen, boolean bild,
      LocalDateTime erstellt, LocalDateTime ablauf) {}

  /** Hinweis der Bank bzw. des Ablaufs mit Zeitpunkt. */
  record Hinweis(LocalDateTime zeit, String text) {}

  private final Duration wartezeit;
  private final Object lock = new Object();
  private final Deque<Hinweis> hinweise = new ArrayDeque<>();

  private Anfrage anfrage;
  private byte[] bild;
  private String bildTyp;
  private CompletableFuture<String> antwort;

  @Autowired
  WebInteraktion(Konfiguration konfig)
  {
    this(Duration.ofMinutes(konfig.webWartezeitMinuten()));
  }

  WebInteraktion(Duration wartezeit)
  {
    this.wartezeit = wartezeit;
  }

  @Override
  public String geheim(String titel, boolean neu)
  {
    return warte(neu ? Art.GEHEIM_NEU : Art.GEHEIM, titel, null, List.of(), null, null);
  }

  @Override
  public String tan(String text, byte[] bild, String mimetype)
  {
    return warte(Art.TAN, "TAN eingeben", text, List.of(), bild, mimetype);
  }

  @Override
  public int auswahl(String titel, List<String> optionen)
  {
    String wert = warte(Art.AUSWAHL, titel, null, optionen, null, null);
    try
    {
      int i = Integer.parseInt(wert);
      if (i >= 0 && i < optionen.size())
        return i;
    }
    catch (NumberFormatException e)
    {
      // unten
    }
    throw new HBCI_Exception("Ungültige Auswahl: " + wert);
  }

  @Override
  public void appFreigabe(String text, boolean erneut, int pauseSekunden)
  {
    CompletableFuture<String> f;
    LocalDateTime ablauf;
    synchronized (lock)
    {
      if (!erneut || anfrage == null || anfrage.art() != Art.APP_FREIGABE)
      {
        // Erste Aufforderung: anzeigen und sofort zurück - HBCI4Java wartet die von der
        // Bank verlangte Mindestzeit selbst ab und fragt dann nach
        LocalDateTime jetzt = LocalDateTime.now();
        setze(new Anfrage(UUID.randomUUID().toString(), Art.APP_FREIGABE, "Freigabe in der App",
            text, List.of(), false, jetzt, jetzt.plus(wartezeit)), null, null);
        hinweis("Warte auf Freigabe in der App.");
        return;
      }
      f = antwort;
      ablauf = anfrage.ablauf();
    }

    long rest = Duration.between(LocalDateTime.now(), ablauf).toMillis();
    if (rest <= 0)
    {
      leere(null);
      throw new HBCI_Exception("Keine Freigabe in der App innerhalb von " + wartezeit.toMinutes()
          + " Minuten - Abruf abgebrochen.");
    }
    try
    {
      // Bis zur nächsten Nachfrage warten - "Jetzt prüfen" verkürzt, "Abbrechen" beendet
      f.get(Math.min(rest, pauseSekunden * 1000L), TimeUnit.MILLISECONDS);
      synchronized (lock)
      {
        if (antwort == f)
          antwort = new CompletableFuture<>();
      }
    }
    catch (TimeoutException e)
    {
      // nächste Nachfrage bei der Bank
    }
    catch (ExecutionException e)
    {
      leere(null);
      throw new HBCI_Exception("Freigabe in der Weboberfläche abgebrochen.");
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      throw new HBCI_Exception("Warten auf die Freigabe wurde unterbrochen.");
    }
  }

  @Override
  public void hinweis(String text)
  {
    LOG.info(text);
    synchronized (hinweise)
    {
      hinweise.addFirst(new Hinweis(LocalDateTime.now(), text));
      while (hinweise.size() > HINWEISE)
        hinweise.removeLast();
    }
  }

  @Override
  public void protokoll(String text)
  {
    LOG.info("[HBCI] {}", text);
  }

  @Override
  public boolean fern()
  {
    return true;
  }

  @Override
  public void beendet()
  {
    leere(null);
  }

  /** @return die offene Anfrage oder {@code null}. */
  Anfrage anfrage()
  {
    synchronized (lock)
    {
      return anfrage;
    }
  }

  /** @return die TAN-Grafik der Anfrage oder {@code null}. */
  byte[] bild(String id)
  {
    synchronized (lock)
    {
      return anfrage != null && anfrage.id().equals(id) ? bild : null;
    }
  }

  String bildTyp()
  {
    synchronized (lock)
    {
      return bildTyp;
    }
  }

  List<Hinweis> hinweise()
  {
    synchronized (hinweise)
    {
      return new ArrayList<>(hinweise);
    }
  }

  /**
   * Beantwortet die offene Anfrage.
   * @return {@code false}, wenn die Anfrage nicht mehr offen ist.
   */
  boolean beantworte(String id, String wert)
  {
    synchronized (lock)
    {
      if (anfrage == null || !anfrage.id().equals(id) || anfrage.art() == Art.APP_FREIGABE || antwort == null)
        return false;
      return antwort.complete(wert);
    }
  }

  /**
   * Bei der App-Freigabe: sofort bei der Bank nachfragen statt die Pause abzuwarten.
   * @return {@code false}, wenn keine App-Freigabe mit dieser ID offen ist.
   */
  boolean jetztPruefen(String id)
  {
    synchronized (lock)
    {
      if (anfrage == null || !anfrage.id().equals(id) || anfrage.art() != Art.APP_FREIGABE || antwort == null)
        return false;
      return antwort.complete("pruefen");
    }
  }

  /**
   * Bricht die offene Anfrage und damit den Abruf ab.
   * @return {@code false}, wenn die Anfrage nicht mehr offen ist.
   */
  boolean abbrechen(String id)
  {
    synchronized (lock)
    {
      if (anfrage == null || !anfrage.id().equals(id) || antwort == null)
        return false;
      return antwort.completeExceptionally(new IllegalStateException("abgebrochen"));
    }
  }

  private String warte(Art art, String titel, String text, List<String> optionen, byte[] bild, String typ)
  {
    LocalDateTime jetzt = LocalDateTime.now();
    Anfrage a = new Anfrage(UUID.randomUUID().toString(), art, titel, text, List.copyOf(optionen),
        bild != null, jetzt, jetzt.plus(wartezeit));
    CompletableFuture<String> f;
    synchronized (lock)
    {
      f = setze(a, bild, typ);
    }
    hinweis("Eingabe erforderlich: " + titel);
    try
    {
      return f.get(wartezeit.toMillis(), TimeUnit.MILLISECONDS);
    }
    catch (TimeoutException e)
    {
      throw new HBCI_Exception("Keine Eingabe in der Weboberfläche innerhalb von " + wartezeit.toMinutes()
          + " Minuten (" + titel + ") - Abruf abgebrochen.");
    }
    catch (ExecutionException e)
    {
      throw new HBCI_Exception("Eingabe in der Weboberfläche abgebrochen (" + titel + ").");
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      throw new HBCI_Exception("Warten auf die Eingabe wurde unterbrochen.");
    }
    finally
    {
      leere(a);
    }
  }

  private CompletableFuture<String> setze(Anfrage a, byte[] bild, String typ)
  {
    this.anfrage = a;
    this.bild = bild;
    this.bildTyp = typ;
    this.antwort = new CompletableFuture<>();
    return this.antwort;
  }

  /** Entfernt die Anfrage {@code a} (bzw. jede, wenn {@code null}). */
  private void leere(Anfrage a)
  {
    synchronized (lock)
    {
      if (a == null || anfrage == a)
      {
        anfrage = null;
        bild = null;
        bildTyp = null;
        antwort = null;
      }
    }
  }
}

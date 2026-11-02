package de.fehrenbacher.fints;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Startet die Abrufe zu den Zeiten des {@link Wochenplan}s und erinnert per E-Mail an eine
 * ausstehende Autorisierung.
 *
 * Maßgeblich ist der Beginn des letzten Abrufs, auch über einen Neustart hinweg (gespeichert im
 * {@link Betriebszustand}): Wurde eine geplante Zeit seitdem verpasst, z.B. weil der Container
 * nicht lief, folgt der Abruf {@link #ERSTER_ABRUF_NACH} nach dem Start. Sind die Abrufe
 * ausgesetzt, wird nichts gestartet.
 */
@Component
@ConditionalOnWebApplication
class ZeitplanDienst
{
  /** Frühester Abruf nach dem Start - wenn noch keiner lief oder der nächste überfällig ist. */
  static final Duration ERSTER_ABRUF_NACH = Duration.ofMinutes(1);

  /**
   * Lief so kurz vor einer geplanten Zeit schon ein Abruf (etwa per Knopf), entfällt sie. Muss
   * kleiner als {@link Wochenplan#MIN_ABSTAND} sein, damit kein geplanter Abruf den nächsten
   * verdrängt.
   */
  static final Duration KEIN_ABRUF_KURZ_NACH = Duration.ofMinutes(10);

  /** Scheitert der Versand einer Erinnerung, wird es erst nach dieser Pause erneut versucht. */
  static final Duration ERINNERUNG_ERNEUT_NACH = Duration.ofHours(1);

  /** Wie oft geprüft wird, ob ein Abruf oder eine Erinnerung fällig ist. */
  private static final Duration PRUEFTAKT = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(ZeitplanDienst.class);

  /** Der Versand einer Erinnerung - austauschbar für Tests. */
  @FunctionalInterface
  interface ErinnerungsVersand
  {
    /** @return {@code false}, wenn kein Postfach oder Empfänger eingerichtet ist. */
    boolean senden(Betriebszustand.Aussetzung aussetzung, int nummer, int gesamt) throws Exception;
  }

  private final Einstellungen einstellungen;
  private final AbrufDienst dienst;
  private final WebInteraktion interaktion;
  private final Betriebszustand zustand;
  private final ErinnerungsVersand versand;
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
  private final LocalDateTime gestartet = LocalDateTime.now();
  private LocalDateTime erinnerungNichtVor;

  @Autowired
  ZeitplanDienst(Konfiguration konfig, Einstellungen einstellungen, AbrufDienst dienst, WebInteraktion interaktion,
      Betriebszustand zustand)
  {
    this(einstellungen, dienst, interaktion, zustand, (aussetzung, nummer, gesamt) ->
    {
      Benachrichtigung b = Benachrichtigung.aus(konfig, einstellungen);
      if (b == null)
        return false;
      b.sendeErinnerung(aussetzung, nummer, gesamt);
      return true;
    });
  }

  ZeitplanDienst(Einstellungen einstellungen, AbrufDienst dienst, WebInteraktion interaktion, Betriebszustand zustand,
      ErinnerungsVersand versand)
  {
    this.einstellungen = einstellungen;
    this.dienst = dienst;
    this.interaktion = interaktion;
    this.zustand = zustand;
    this.versand = versand;
  }

  @EventListener(ApplicationReadyEvent.class)
  void starten()
  {
    // Läuft auch ohne geplante Abrufe: Der Plan kann sich jederzeit ändern, Erinnerungen bleiben fällig
    scheduler.setThreadNamePrefix("zeitplan-");
    scheduler.initialize();
    scheduler.scheduleWithFixedDelay(this::pruefe, PRUEFTAKT);
    LocalDateTime naechster = naechsterLauf();
    LOG.info("Automatischer Abruf: {}{}", einstellungen.zeitplan().beschreibung(),
        naechster != null ? ", nächster: " + naechster.withNano(0)
            : zustand.ausgesetzt() ? ", derzeit ausgesetzt (Autorisierung im Browser erforderlich)" : "");
  }

  @PreDestroy
  void beenden()
  {
    scheduler.shutdown();
  }

  /**
   * @return der Zeitpunkt des nächsten automatischen Abrufs, {@code null} wenn keiner geplant
   *         ist oder die Abrufe ausgesetzt sind.
   */
  LocalDateTime naechsterLauf()
  {
    Wochenplan plan = einstellungen.zeitplan();
    if (plan.leer() || zustand.ausgesetzt())
      return null;
    LocalDateTime fruehestens = gestartet.plus(ERSTER_ABRUF_NACH);
    LocalDateTime letzter = dienst.letzterBeginn();
    if (letzter == null)
      return fruehestens;

    // Zeiten vor einer Änderung des Plans gelten nicht als verpasst
    LocalDateTime ab = letzter.plus(KEIN_ABRUF_KURZ_NACH);
    LocalDateTime geaendert = einstellungen.zeitplanGeaendert();
    if (geaendert != null && geaendert.isAfter(ab))
      ab = geaendert;
    LocalDateTime geplant = plan.naechsterNach(ab);
    return geplant.isBefore(fruehestens) ? fruehestens : geplant;
  }

  void pruefe()
  {
    LocalDateTime faellig = naechsterLauf();
    if (faellig != null && !dienst.laeuft() && !LocalDateTime.now().isBefore(faellig))
      dienst.starteImHintergrund(interaktion, "Zeitplan");
    pruefeErinnerung(LocalDateTime.now());
  }

  /**
   * Sendet die fällige Erinnerung. Waren mehrere fällig, z.B. weil der Container nicht lief, geht
   * nur eine E-Mail raus. Während eines Abrufs ruht das - er kann die Autorisierung gerade erneuern.
   */
  void pruefeErinnerung(LocalDateTime jetzt)
  {
    if (dienst.laeuft() || (erinnerungNichtVor != null && jetzt.isBefore(erinnerungNichtVor)))
      return;
    int nummer = zustand.faelligeErinnerung(jetzt);
    Betriebszustand.Aussetzung aussetzung = zustand.aussetzung();
    if (nummer == 0 || aussetzung == null)
      return;
    int gesamt = Betriebszustand.ERINNERUNG_NACH_TAGEN.size();
    try
    {
      if (versand.senden(aussetzung, nummer, gesamt))
      {
        zustand.erinnerungGesendet(nummer);
        LOG.info("Erinnerung {} von {} an die ausstehende Autorisierung gesendet.", nummer, gesamt);
      }
    }
    catch (Exception e)
    {
      erinnerungNichtVor = jetzt.plus(ERINNERUNG_ERNEUT_NACH);
      LOG.warn("Erinnerung {} von {} konnte nicht gesendet werden, neuer Versuch um {}: {}", nummer, gesamt,
          erinnerungNichtVor.withNano(0), AbrufDienst.fehlertext(e));
    }
  }
}

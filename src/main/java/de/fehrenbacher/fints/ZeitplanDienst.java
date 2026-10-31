package de.fehrenbacher.fints;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Startet die Abrufe im eingestellten Intervall (abruf.intervall, Standard 6 Stunden). Gezählt
 * wird ab dem Beginn des letzten Abrufs - auch eines im Browser gestarteten und auch über einen
 * Neustart hinweg (gespeichert im {@link Betriebszustand}). Ist der nächste Abruf beim Start
 * schon überfällig, folgt er {@link #ERSTER_ABRUF_NACH} nach dem Start. Sind die Abrufe
 * ausgesetzt, wird nichts gestartet.
 */
@Component
@ConditionalOnWebApplication
class ZeitplanDienst
{
  /** Frühester Abruf nach dem Start - wenn noch keiner lief oder der nächste überfällig ist. */
  static final Duration ERSTER_ABRUF_NACH = Duration.ofMinutes(1);

  /** Wie oft geprüft wird, ob ein Abruf fällig ist. */
  private static final Duration PRUEFTAKT = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(ZeitplanDienst.class);

  private final Konfiguration konfig;
  private final AbrufDienst dienst;
  private final WebInteraktion interaktion;
  private final Betriebszustand zustand;
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
  private final LocalDateTime gestartet = LocalDateTime.now();

  ZeitplanDienst(Konfiguration konfig, AbrufDienst dienst, WebInteraktion interaktion, Betriebszustand zustand)
  {
    this.konfig = konfig;
    this.dienst = dienst;
    this.interaktion = interaktion;
    this.zustand = zustand;
  }

  @EventListener(ApplicationReadyEvent.class)
  void starten()
  {
    if (!aktiv())
    {
      LOG.info("Kein automatischer Abruf (abruf.intervall={}).", Konfiguration.INTERVALL_AUS);
      return;
    }
    scheduler.setThreadNamePrefix("intervall-");
    scheduler.initialize();
    scheduler.scheduleWithFixedDelay(this::pruefe, PRUEFTAKT);
    LocalDateTime naechster = naechsterLauf();
    LOG.info("Automatischer Abruf alle {}, {}", text(konfig.intervall()),
        naechster != null ? "erster Abruf: " + naechster.withNano(0) : "derzeit ausgesetzt (Autorisierung im Browser erforderlich)");
  }

  @PreDestroy
  void beenden()
  {
    scheduler.shutdown();
  }

  boolean aktiv()
  {
    return konfig.intervall() != null;
  }

  /**
   * @return der Zeitpunkt des nächsten automatischen Abrufs, {@code null} wenn abgeschaltet
   *         oder ausgesetzt.
   */
  LocalDateTime naechsterLauf()
  {
    if (!aktiv() || zustand.ausgesetzt())
      return null;
    LocalDateTime fruehestens = gestartet.plus(ERSTER_ABRUF_NACH);
    LocalDateTime letzter = dienst.letzterBeginn();
    if (letzter == null)
      return fruehestens;
    LocalDateTime planmaessig = letzter.plus(konfig.intervall());
    return planmaessig.isBefore(fruehestens) ? fruehestens : planmaessig;
  }

  /** z.B. "6 Stunden", "30 Minuten". */
  static String text(Duration d)
  {
    if (d == null)
      return "aus";
    if (d.toMinutes() % (24 * 60) == 0)
      return d.toDays() + (d.toDays() == 1 ? " Tag" : " Tage");
    if (d.toMinutes() % 60 == 0)
      return d.toHours() + (d.toHours() == 1 ? " Stunde" : " Stunden");
    return d.toMinutes() + " Minuten";
  }

  void pruefe()
  {
    LocalDateTime faellig = naechsterLauf();
    if (faellig == null || dienst.laeuft() || LocalDateTime.now().isBefore(faellig))
      return;
    dienst.starteImHintergrund(interaktion, "Intervall");
  }
}

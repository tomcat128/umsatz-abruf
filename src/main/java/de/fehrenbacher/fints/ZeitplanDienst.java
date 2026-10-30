package de.fehrenbacher.fints;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Startet im Serverbetrieb den Abruf nach Zeitplan (abruf.zeitplan, Cron-Ausdruck).
 */
@Component
@ConditionalOnWebApplication
class ZeitplanDienst
{
  /** Wert von abruf.zeitplan für "kein automatischer Abruf". */
  static final String AUS = "-";

  private static final Logger LOG = LoggerFactory.getLogger(ZeitplanDienst.class);

  private final Konfiguration konfig;
  private final AbrufDienst dienst;
  private final WebInteraktion interaktion;
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  ZeitplanDienst(Konfiguration konfig, AbrufDienst dienst, WebInteraktion interaktion)
  {
    this.konfig = konfig;
    this.dienst = dienst;
    this.interaktion = interaktion;
  }

  @EventListener(ApplicationReadyEvent.class)
  void starten()
  {
    if (!aktiv())
    {
      LOG.info("Kein automatischer Abruf (abruf.zeitplan={}).", AUS);
      return;
    }
    scheduler.setThreadNamePrefix("zeitplan-");
    scheduler.initialize();
    scheduler.schedule(this::ausloesen, new CronTrigger(konfig.zeitplan()));
    LOG.info("Automatischer Abruf nach Zeitplan \"{}\", nächster Lauf: {}", konfig.zeitplan(), naechsterLauf());
  }

  @PreDestroy
  void beenden()
  {
    scheduler.shutdown();
  }

  boolean aktiv()
  {
    return !AUS.equals(konfig.zeitplan());
  }

  /** @return der Zeitpunkt des nächsten automatischen Abrufs oder {@code null}. */
  LocalDateTime naechsterLauf()
  {
    return aktiv() ? CronExpression.parse(konfig.zeitplan()).next(LocalDateTime.now()) : null;
  }

  private void ausloesen()
  {
    if (!dienst.starteImHintergrund(interaktion, "Zeitplan"))
      LOG.warn("Geplanter Abruf übersprungen - es läuft bereits ein Abruf.");
  }
}

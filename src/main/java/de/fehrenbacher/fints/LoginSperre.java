package de.fehrenbacher.fints;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Sperrt eine Adresse nach {@value #MAX_FEHLVERSUCHE} falschen Anmeldungen für
 * {@link #SPERRDAUER} - erschwert das Durchprobieren von Passwörtern.
 *
 * Hinter einem Reverse-Proxy muss Spring die Client-Adresse aus X-Forwarded-For übernehmen
 * (server.forward-headers-strategy=native), sonst teilen sich alle Clients die Adresse des Proxys.
 */
@Component
@ConditionalOnWebApplication
class LoginSperre
{
  static final int MAX_FEHLVERSUCHE = 5;
  static final Duration SPERRDAUER = Duration.ofMinutes(15);

  private static final Logger LOG = LoggerFactory.getLogger(LoginSperre.class);

  private record Stand(int fehlversuche, Instant ersterVersuch, Instant gesperrtBis) {}

  private final Map<String,Stand> staende = new ConcurrentHashMap<>();
  private final Clock uhr;

  LoginSperre()
  {
    this(Clock.systemUTC());
  }

  LoginSperre(Clock uhr)
  {
    this.uhr = uhr;
  }

  boolean gesperrt(String adresse)
  {
    Stand s = staende.get(adresse);
    return s != null && s.gesperrtBis() != null && uhr.instant().isBefore(s.gesperrtBis());
  }

  @EventListener
  void fehlgeschlagen(AuthenticationFailureBadCredentialsEvent e)
  {
    String adresse = adresse(e.getAuthentication().getDetails());
    if (adresse != null)
      fehlversuch(adresse);
  }

  @EventListener
  void erfolgreich(AuthenticationSuccessEvent e)
  {
    String adresse = adresse(e.getAuthentication().getDetails());
    if (adresse != null)
      staende.remove(adresse);
  }

  void fehlversuch(String adresse)
  {
    Instant jetzt = uhr.instant();
    Stand neu = staende.compute(adresse, (a, s) ->
    {
      // Fehlversuche verfallen nach der Sperrdauer
      if (s == null || jetzt.isAfter(s.ersterVersuch().plus(SPERRDAUER)) && !gesperrtZu(s, jetzt))
        return new Stand(1, jetzt, null);
      int anzahl = s.fehlversuche() + 1;
      return new Stand(anzahl, s.ersterVersuch(), anzahl >= MAX_FEHLVERSUCHE ? jetzt.plus(SPERRDAUER) : s.gesperrtBis());
    });
    if (neu.fehlversuche() == MAX_FEHLVERSUCHE)
      LOG.warn("Anmeldung für {} nach {} Fehlversuchen für {} Minuten gesperrt.", adresse, MAX_FEHLVERSUCHE,
          SPERRDAUER.toMinutes());
  }

  private static boolean gesperrtZu(Stand s, Instant jetzt)
  {
    return s.gesperrtBis() != null && jetzt.isBefore(s.gesperrtBis());
  }

  private static String adresse(Object details)
  {
    return details instanceof WebAuthenticationDetails w ? w.getRemoteAddress() : null;
  }
}

package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginSperreTest
{
  /** Uhr, die der Test vorstellen kann. */
  private static final class Uhr extends Clock
  {
    Instant jetzt = Instant.parse("2026-10-30T08:00:00Z");

    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return jetzt; }
  }

  @Test
  void sperrtNachFehlversuchenUndGibtWiederFrei()
  {
    Uhr uhr = new Uhr();
    LoginSperre sperre = new LoginSperre(uhr);

    for (int i = 1; i < LoginSperre.MAX_FEHLVERSUCHE; i++)
      sperre.fehlversuch("1.2.3.4");
    assertFalse(sperre.gesperrt("1.2.3.4"));

    sperre.fehlversuch("1.2.3.4");
    assertTrue(sperre.gesperrt("1.2.3.4"));
    assertFalse(sperre.gesperrt("5.6.7.8"), "Andere Adressen sind nicht betroffen");

    uhr.jetzt = uhr.jetzt.plus(LoginSperre.SPERRDAUER).plusSeconds(1);
    assertFalse(sperre.gesperrt("1.2.3.4"));

    // Danach beginnt die Zählung neu
    sperre.fehlversuch("1.2.3.4");
    assertFalse(sperre.gesperrt("1.2.3.4"));
  }

  @Test
  void alteFehlversucheVerfallen()
  {
    Uhr uhr = new Uhr();
    LoginSperre sperre = new LoginSperre(uhr);
    for (int i = 1; i < LoginSperre.MAX_FEHLVERSUCHE; i++)
      sperre.fehlversuch("1.2.3.4");

    uhr.jetzt = uhr.jetzt.plus(Duration.ofHours(1));
    sperre.fehlversuch("1.2.3.4");
    assertFalse(sperre.gesperrt("1.2.3.4"));
  }
}

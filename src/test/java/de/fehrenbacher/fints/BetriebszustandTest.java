package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BetriebszustandTest
{
  @TempDir
  Path verzeichnis;

  @Test
  void aussetzungUeberstehtNeustart()
  {
    Path datei = verzeichnis.resolve("daten/status.properties");
    Betriebszustand z = new Betriebszustand(datei);
    assertFalse(z.ausgesetzt());

    assertTrue(z.aussetzen("Keine Freigabe in der App"), "erste Aussetzung meldet sich");
    assertFalse(z.aussetzen("Noch immer keine Freigabe"), "erneutes Aussetzen ist keine neue Meldung");

    // "Neustart": neu aus der Datei laden
    Betriebszustand neu = new Betriebszustand(datei);
    assertTrue(neu.ausgesetzt());
    assertEquals("Noch immer keine Freigabe", neu.aussetzung().grund());
    assertEquals(z.aussetzung().seit(), neu.aussetzung().seit(), "Beginn der Aussetzung bleibt erhalten");

    assertTrue(neu.fortsetzen());
    assertFalse(neu.fortsetzen());
    assertNull(new Betriebszustand(datei).aussetzung());
  }

  @Test
  void letzterAbrufUeberstehtNeustart() throws Exception
  {
    Path datei = verzeichnis.resolve("status.properties");
    Betriebszustand z = new Betriebszustand(datei);
    assertNull(z.letzterAbruf());

    LocalDateTime beginn = LocalDateTime.of(2026, 10, 31, 14, 55, 0);
    z.abrufBegonnen(beginn);
    z.aussetzen("Keine Freigabe");
    z.fortsetzen();

    Betriebszustand neu = new Betriebszustand(datei);
    assertEquals(beginn, neu.letzterAbruf(), "Aussetzen und Fortsetzen lassen den Zeitpunkt stehen");
    assertFalse(neu.ausgesetzt());
  }

  @Test
  void statusdateiFruehererVersionenOhneZeitpunkt() throws Exception
  {
    Path datei = verzeichnis.resolve("status.properties");
    Files.writeString(datei, "ausgesetzt=true\nseit=2026-10-31T03\\:00\\:00\ngrund=Test\n");
    Betriebszustand z = new Betriebszustand(datei);
    assertTrue(z.ausgesetzt());
    assertNull(z.letzterAbruf());
  }
}

package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

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
}

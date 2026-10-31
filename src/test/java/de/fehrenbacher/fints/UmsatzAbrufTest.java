package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UmsatzAbrufTest
{
  @TempDir
  Path verzeichnis;

  @Test
  void leereTemporaerePassportDateienWerdenEntfernt() throws Exception
  {
    Path passport = verzeichnis.resolve("umsatz-abruf.passport");
    Files.writeString(passport, "inhalt");
    Path leer = Files.createFile(verzeichnis.resolve("umsatz-abruf.passport_4912796011727503316"));
    Path gefuellt = Files.writeString(verzeichnis.resolve("umsatz-abruf.passport_123"), "x");
    Path fremd = Files.createFile(verzeichnis.resolve("anderes.passport_123"));
    Path aehnlich = Files.createFile(verzeichnis.resolve("umsatz-abruf.passport_abc"));

    UmsatzAbruf.raeumeAuf(passport);

    assertFalse(Files.exists(leer));
    assertTrue(Files.exists(passport));
    assertTrue(Files.exists(gefuellt), "nicht leere Dateien bleiben");
    assertTrue(Files.exists(fremd));
    assertTrue(Files.exists(aehnlich));
  }
}

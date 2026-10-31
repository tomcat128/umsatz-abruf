package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PassportVerwaltungTest
{
  @TempDir
  Path verzeichnis;

  private final List<String> hinweise = new ArrayList<>();

  private Konfiguration konfig(String passwort)
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "12345678");
    p.setProperty("fints.benutzer", "bank");
    p.setProperty("fints.iban", "DE00123456780000000000");
    p.setProperty("intern.verzeichnis", verzeichnis.resolve("intern").toString());
    if (passwort != null)
      p.setProperty("passport.passwort", passwort);
    return Konfiguration.aus(p, Map.of());
  }

  private Konfiguration einrichten(Konfiguration k) throws Exception
  {
    return PassportVerwaltung.einrichten(k, verzeichnis.resolve("daten"), hinweise::add);
  }

  @Test
  void passwortWirdErzeugtUndWiederverwendet() throws Exception
  {
    Konfiguration k1 = einrichten(konfig(null));
    String passwort = k1.passportPasswort();
    assertTrue(passwort.length() >= 40, passwort);

    Path schluessel = verzeichnis.resolve("intern").resolve(PassportVerwaltung.SCHLUESSEL_DATEI);
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(schluessel)));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(verzeichnis.resolve("intern"))));

    // Neustart: dasselbe Passwort
    assertEquals(passwort, einrichten(konfig(null)).passportPasswort());
    assertTrue(hinweise.isEmpty(), hinweise.toString());
  }

  @Test
  void konfiguriertesPasswortHatVorrang() throws Exception
  {
    assertEquals("eigenes", einrichten(konfig("eigenes")).passportPasswort());
    assertFalse(Files.exists(verzeichnis.resolve("intern").resolve(PassportVerwaltung.SCHLUESSEL_DATEI)));
  }

  @Test
  void altePassportDateiMitBekanntemPasswortWirdUebernommen() throws Exception
  {
    Path daten = Files.createDirectories(verzeichnis.resolve("daten"));
    Files.writeString(daten.resolve(PassportVerwaltung.ALTE_PASSPORT_DATEI), "passport");
    Files.writeString(daten.resolve(PassportVerwaltung.ALTE_STATUS_DATEI), "ausgesetzt=true\n");

    Konfiguration k = einrichten(konfig("eigenes"));

    assertEquals("passport", Files.readString(k.passportDatei()));
    assertTrue(Files.exists(k.statusDatei()), "Betriebszustand wird mit übernommen");
    assertFalse(Files.exists(daten.resolve(PassportVerwaltung.ALTE_PASSPORT_DATEI)));
    assertTrue(new Betriebszustand(k.statusDatei()).ausgesetzt());
  }

  @Test
  void altePassportDateiMitUnbekanntemPasswortWirdBeiseitegelegt() throws Exception
  {
    // Frühere Version: Passwort wurde im Browser eingegeben, die Anwendung kennt es nicht
    Path daten = Files.createDirectories(verzeichnis.resolve("daten"));
    Files.writeString(daten.resolve(PassportVerwaltung.ALTE_PASSPORT_DATEI), "passport");

    Konfiguration k = einrichten(konfig(null));

    assertFalse(Files.exists(daten.resolve(PassportVerwaltung.ALTE_PASSPORT_DATEI)));
    assertFalse(Files.exists(k.passportDatei()), "eine neue wird beim nächsten Abruf angelegt");
    try (var alt = Files.list(verzeichnis.resolve("intern/alt")))
    {
      assertEquals(1, alt.count());
    }
    assertEquals(1, hinweise.size());
    assertTrue(hinweise.get(0).contains("einmalig eine Freigabe"), hinweise.get(0));
    assertNotEquals(null, k.passportPasswort());
  }

  @Test
  void interneDateiOhneSchluesselWirdErsetzt() throws Exception
  {
    Path intern = Files.createDirectories(verzeichnis.resolve("intern"));
    Files.writeString(intern.resolve("umsatz-abruf.passport"), "passport");

    Konfiguration k = einrichten(konfig(null));

    assertFalse(Files.exists(k.passportDatei()));
    assertTrue(Files.exists(intern.resolve(PassportVerwaltung.SCHLUESSEL_DATEI)));
    assertEquals(1, hinweise.size());
  }
}

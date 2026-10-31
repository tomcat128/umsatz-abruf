package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.structures.Konto;

/**
 * Aussetzen und Wiederaufnehmen der automatischen Abrufe - mit simulierter Bank.
 */
class AbrufDienstTest
{
  @TempDir
  Path verzeichnis;

  private Konfiguration konfig;
  private Betriebszustand zustand;

  /** Interaktion, bei der niemand antwortet - wie ein nächtlicher Abruf ohne Reaktion. */
  private static class NiemandAntwortet implements Interaktion
  {
    @Override public String geheim(String titel, boolean neu) { throw new AutorisierungsFehler("Keine Eingabe (" + titel + ")."); }
    @Override public String tan(String text, byte[] bild, String typ) { throw new AutorisierungsFehler("Keine TAN eingegeben."); }
    @Override public int auswahl(String titel, List<String> optionen) { throw new AutorisierungsFehler("Keine Auswahl."); }
    @Override public void appFreigabe(String text, boolean erneut, int pause) { throw new AutorisierungsFehler("Keine Freigabe in der App."); }
    @Override public void hinweis(String text) { }
    @Override public void protokoll(String text) { }
    @Override public boolean fern() { return true; }
  }

  @BeforeEach
  void vorbereiten()
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "12345678");
    p.setProperty("fints.benutzer", "bank");
    p.setProperty("fints.iban", "DE00123456780000000000");
    p.setProperty("intern.verzeichnis", verzeichnis.resolve("intern").toString());
    p.setProperty("db.datei", verzeichnis.resolve("umsaetze.db").toString());
    konfig = Konfiguration.aus(p, Map.of());
    zustand = new Betriebszustand(konfig);
  }

  private static UmsatzAbruf.Ergebnis leer()
  {
    Konto k = new Konto();
    k.iban = "DE00123456780000000000";
    return new UmsatzAbruf.Ergebnis(k, "CAMT", List.of());
  }

  @Test
  void fehlendeFreigabeSetztAus()
  {
    AbrufDienst dienst = new AbrufDienst(konfig, zustand, (k, z, i, b) ->
    {
      i.appFreigabe("Bitte in der App freigeben", false, 0);
      return leer();
    });

    assertThrows(AutorisierungsFehler.class, () -> dienst.ausfuehren(new NiemandAntwortet(), "Intervall"));
    assertTrue(zustand.ausgesetzt());
    assertEquals("Keine Freigabe in der App.", zustand.aussetzung().grund());
    assertEquals(AbrufDienst.Status.AUTORISIERUNG_FEHLT, dienst.verlauf().get(0).status());
    assertTrue(konfig.statusDatei().toFile().exists(), "Aussetzung ist gespeichert");
  }

  @Test
  void abgelehntePinSetztAus()
  {
    // HBCI4Java verpackt Fehler aus dem Callback - die Ursache wird trotzdem erkannt
    AbrufDienst dienst = new AbrufDienst(konfig, zustand, (k, z, i, b) ->
    {
      throw new HBCI_Exception("Dialog fehlgeschlagen", new AutorisierungsFehler("Die Bank hat die PIN abgelehnt."));
    });
    assertThrows(HBCI_Exception.class, () -> dienst.ausfuehren(new NiemandAntwortet(), "Intervall"));
    assertEquals("Die Bank hat die PIN abgelehnt.", zustand.aussetzung().grund());
  }

  @Test
  void netzwerkfehlerSetztNichtAus()
  {
    AbrufDienst dienst = new AbrufDienst(konfig, zustand, (k, z, i, b) ->
    {
      throw new HBCI_Exception("Verbindung zur Bank fehlgeschlagen");
    });
    assertThrows(HBCI_Exception.class, () -> dienst.ausfuehren(new NiemandAntwortet(), "Intervall"));
    assertFalse(zustand.ausgesetzt());
    assertEquals(AbrufDienst.Status.FEHLER, dienst.verlauf().get(0).status());
  }

  @Test
  void fehlerNachVerlangterEingabeSetztAus()
  {
    // Die Bank verlangt eine TAN, wird beantwortet, lehnt sie dann aber ab
    Interaktion antwortet = new NiemandAntwortet()
    {
      @Override public String tan(String text, byte[] bild, String typ) { return "123456"; }
    };
    AbrufDienst dienst = new AbrufDienst(konfig, zustand, (k, z, i, b) ->
    {
      i.tan("TAN?", null, null);
      throw new HBCI_Exception("TAN ungültig");
    });
    assertThrows(HBCI_Exception.class, () -> dienst.ausfuehren(antwortet, "Autorisierung"));
    assertTrue(zustand.ausgesetzt());
    assertTrue(zustand.aussetzung().grund().contains("TAN ungültig"), zustand.aussetzung().grund());
  }

  @Test
  void erfolgreicherAbrufHebtAussetzungAuf() throws Exception
  {
    zustand.aussetzen("Keine Freigabe in der App.");
    AbrufDienst dienst = new AbrufDienst(konfig, zustand, (k, z, i, b) -> leer());

    AbrufDienst.Zusammenfassung z = dienst.ausfuehren(new NiemandAntwortet(), "Autorisierung");
    assertEquals(0, z.geliefert());
    assertFalse(zustand.ausgesetzt());
    assertNotNull(dienst.letzterBeginn());
  }

  @Test
  void zeitplanPausiertWaehrendDerAussetzung() throws Exception
  {
    AbrufDienst dienst = new AbrufDienst(konfig, zustand, (k, z, i, b) -> leer());
    ZeitplanDienst plan = new ZeitplanDienst(konfig, dienst, new WebInteraktion(Duration.ofSeconds(1)), zustand);

    assertNotNull(plan.naechsterLauf(), "erster Abruf kurz nach dem Start");
    zustand.aussetzen("Keine Freigabe");
    assertNull(plan.naechsterLauf());

    // Nach dem Abruf zählt das Intervall ab dessen Beginn
    dienst.ausfuehren(new NiemandAntwortet(), "Autorisierung");
    assertEquals(dienst.letzterBeginn().plus(Duration.ofHours(6)), plan.naechsterLauf());
  }

  @Test
  void intervallText()
  {
    assertEquals("6 Stunden", ZeitplanDienst.text(Duration.ofHours(6)));
    assertEquals("1 Stunde", ZeitplanDienst.text(Duration.ofHours(1)));
    assertEquals("30 Minuten", ZeitplanDienst.text(Duration.ofMinutes(30)));
    assertEquals("1 Tag", ZeitplanDienst.text(Duration.ofDays(1)));
    assertEquals("aus", ZeitplanDienst.text(null));
  }
}

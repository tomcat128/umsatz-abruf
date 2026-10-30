package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.kapott.hbci.exceptions.HBCI_Exception;

class WebInteraktionTest
{
  /** Wartet, bis der Bankdialog-Thread eine Anfrage gestellt hat. */
  private static WebInteraktion.Anfrage warteAufAnfrage(WebInteraktion w) throws InterruptedException
  {
    for (int i = 0; i < 200; i++)
    {
      WebInteraktion.Anfrage a = w.anfrage();
      if (a != null)
        return a;
      Thread.sleep(10);
    }
    throw new AssertionError("Keine Anfrage gestellt");
  }

  @Test
  void tanWirdBeantwortet() throws Exception
  {
    WebInteraktion w = new WebInteraktion(Duration.ofSeconds(5));
    byte[] bild = { 1, 2, 3 };
    CompletableFuture<String> tan = CompletableFuture.supplyAsync(() -> w.tan("Bitte TAN eingeben", bild, "image/png"));

    WebInteraktion.Anfrage a = warteAufAnfrage(w);
    assertEquals(WebInteraktion.Art.TAN, a.art());
    assertTrue(a.bild());
    assertEquals(bild, w.bild(a.id()));
    assertNull(w.bild("andere-id"));

    assertFalse(w.beantworte("falsche-id", "123456"));
    assertTrue(w.beantworte(a.id(), "123456"));
    assertEquals("123456", tan.get(2, TimeUnit.SECONDS));
    assertNull(w.anfrage());
  }

  @Test
  void auswahlLiefertIndex() throws Exception
  {
    WebInteraktion w = new WebInteraktion(Duration.ofSeconds(5));
    CompletableFuture<Integer> wahl = CompletableFuture.supplyAsync(() -> w.auswahl("TAN-Verfahren", List.of("A", "B")));
    assertTrue(w.beantworte(warteAufAnfrage(w).id(), "1"));
    assertEquals(1, wahl.get(2, TimeUnit.SECONDS));
  }

  @Test
  void ohneAntwortWirdAbgebrochen()
  {
    WebInteraktion w = new WebInteraktion(Duration.ofMillis(200));
    HBCI_Exception e = assertThrows(HBCI_Exception.class, () -> w.geheim("PIN", false));
    assertTrue(e.getMessage().contains("Keine Eingabe"), e.getMessage());
    assertNull(w.anfrage());
  }

  @Test
  void abbrechenBeendetDieAnfrage() throws Exception
  {
    WebInteraktion w = new WebInteraktion(Duration.ofSeconds(5));
    CompletableFuture<String> pin = CompletableFuture.supplyAsync(() -> w.geheim("PIN", false));
    assertTrue(w.abbrechen(warteAufAnfrage(w).id()));
    Exception e = assertThrows(Exception.class, () -> pin.get(2, TimeUnit.SECONDS));
    assertTrue(e.getCause() instanceof HBCI_Exception, e.toString());
  }

  @Test
  void appFreigabeWartetUndLaesstSichBeschleunigen() throws Exception
  {
    WebInteraktion w = new WebInteraktion(Duration.ofSeconds(30));

    // Erste Aufforderung kehrt sofort zurück, die Anfrage bleibt sichtbar
    w.appFreigabe("Bitte in der App freigeben", false, 0);
    WebInteraktion.Anfrage a = w.anfrage();
    assertNotNull(a);
    assertEquals(WebInteraktion.Art.APP_FREIGABE, a.art());
    assertFalse(w.beantworte(a.id(), "x"), "App-Freigabe braucht keine Eingabe");

    // Noch nicht freigegeben: wartet die Pause ab ...
    long start = System.nanoTime();
    w.appFreigabe(null, true, 1);
    assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(900));

    // ... außer "Jetzt prüfen" wird gedrückt
    CompletableFuture<Void> warten = CompletableFuture.runAsync(() -> w.appFreigabe(null, true, 30));
    Thread.sleep(100);
    assertTrue(w.jetztPruefen(a.id()));
    warten.get(2, TimeUnit.SECONDS);
    assertEquals(a.id(), w.anfrage().id(), "Anfrage bleibt bis zum Ende des Dialogs bestehen");

    // Abbrechen beendet das Warten mit Fehler
    CompletableFuture<Void> abbruch = CompletableFuture.runAsync(() -> w.appFreigabe(null, true, 30));
    Thread.sleep(100);
    assertTrue(w.abbrechen(a.id()));
    Exception e = assertThrows(Exception.class, () -> abbruch.get(2, TimeUnit.SECONDS));
    assertTrue(e.getCause() instanceof HBCI_Exception, e.toString());
    assertNull(w.anfrage());
  }

  @Test
  void appFreigabeEndetNachWartezeit() throws Exception
  {
    WebInteraktion w = new WebInteraktion(Duration.ofMillis(300));
    w.appFreigabe("Bitte freigeben", false, 0);
    Thread.sleep(400);
    HBCI_Exception e = assertThrows(HBCI_Exception.class, () -> w.appFreigabe(null, true, 1));
    assertTrue(e.getMessage().contains("Keine Freigabe"), e.getMessage());
  }

  @Test
  void beendetEntferntAnzeige()
  {
    WebInteraktion w = new WebInteraktion(Duration.ofSeconds(5));
    w.appFreigabe("Bitte freigeben", false, 0);
    w.beendet();
    assertNull(w.anfrage());
  }
}

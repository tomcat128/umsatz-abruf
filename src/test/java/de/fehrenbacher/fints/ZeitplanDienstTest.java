package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kapott.hbci.structures.Konto;

/**
 * Abrufe nach Wochenplan und Erinnerungen an die ausstehende Autorisierung - ohne Bank und Mailserver.
 */
class ZeitplanDienstTest
{
  @TempDir
  Path verzeichnis;

  private Konfiguration konfig;
  private Betriebszustand zustand;
  private Einstellungen einstellungen;
  private AbrufDienst dienst;
  private final WebInteraktion interaktion = new WebInteraktion(Duration.ofSeconds(1));

  /** Die gesendeten Erinnerungen, oder ein Fehler beim nächsten Versand. */
  private final List<Integer> gesendet = new ArrayList<>();
  private Exception versandFehler;
  private boolean postfachEingerichtet = true;

  @BeforeEach
  void vorbereiten()
  {
    konfig = konfig();
    zustand = new Betriebszustand(konfig);
    einstellungen = new Einstellungen(konfig);
    dienst = new AbrufDienst(konfig, zustand, einstellungen, (k, z, i, b) -> leer());
  }

  private Konfiguration konfig()
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "12345678");
    p.setProperty("fints.benutzer", "bank");
    p.setProperty("fints.iban", "DE00123456780000000000");
    p.setProperty("intern.verzeichnis", verzeichnis.resolve("intern").toString());
    p.setProperty("db.datei", verzeichnis.resolve("umsaetze.db").toString());
    return Konfiguration.aus(p, Map.of());
  }

  private static UmsatzAbruf.Ergebnis leer()
  {
    Konto k = new Konto();
    k.iban = "DE00123456780000000000";
    return new UmsatzAbruf.Ergebnis(k, "CAMT", List.of());
  }

  private ZeitplanDienst plan()
  {
    return plan(dienst, zustand);
  }

  private ZeitplanDienst plan(AbrufDienst abrufDienst, Betriebszustand z)
  {
    return new ZeitplanDienst(einstellungen, abrufDienst, interaktion, z, (aussetzung, nummer, gesamt) ->
    {
      assertEquals(5, gesamt);
      if (versandFehler != null)
        throw versandFehler;
      if (!postfachEingerichtet)
        return false;
      gesendet.add(nummer);
      return true;
    });
  }

  private static Wochenplan jedenTagUm(LocalDateTime zeit)
  {
    return Wochenplan.jedenTag(DateTimeFormatter.ofPattern("HH:mm").format(zeit));
  }

  @Test
  void ersterAbrufKurzNachDemStart()
  {
    LocalDateTime naechster = plan().naechsterLauf();
    assertNotNull(naechster);
    assertTrue(naechster.isBefore(LocalDateTime.now().plus(ZeitplanDienst.ERSTER_ABRUF_NACH).plusSeconds(1)));
  }

  @Test
  void ohneGespeichertenPlanTaeglichUmZwoelf()
  {
    LocalDateTime letzter = LocalDateTime.now().minusMinutes(1);
    zustand.abrufBegonnen(letzter);

    LocalDateTime naechster = plan().naechsterLauf();
    assertEquals(12, naechster.getHour());
    assertEquals(0, naechster.getMinute());
    assertTrue(naechster.isAfter(letzter));
    assertTrue(naechster.isBefore(letzter.plusDays(1).plusMinutes(11)), naechster.toString());
  }

  @Test
  void leererPlanStartetNichts() throws Exception
  {
    einstellungen.speichereZeitplan(Wochenplan.LEER);
    ZeitplanDienst plan = plan();
    assertNull(plan.naechsterLauf());
    plan.pruefe();
    assertTrue(!dienst.laeuft());
  }

  @Test
  void pausiertWaehrendDerAussetzung() throws Exception
  {
    ZeitplanDienst plan = plan();
    assertNotNull(plan.naechsterLauf());
    zustand.aussetzen("Keine Freigabe");
    assertNull(plan.naechsterLauf());

    dienst.ausfuehren(new WebInteraktion(Duration.ofSeconds(1)), "Autorisierung");
    assertNotNull(plan.naechsterLauf(), "ein erfolgreicher Abruf hebt die Aussetzung auf");
  }

  @Test
  void ueberstehtNeustartPlanmaessig()
  {
    // Vor 5 Minuten lief ein Abruf, die nächste geplante Zeit liegt in einer Stunde
    LocalDateTime jetzt = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    LocalDateTime geplant = jetzt.plusHours(1);
    einstellungen.speichereZeitplan(jedenTagUm(geplant));
    zustand.abrufBegonnen(jetzt.minusMinutes(5));

    Betriebszustand nachNeustart = new Betriebszustand(konfig);
    assertEquals(geplant, plan(new AbrufDienst(konfig, nachNeustart, einstellungen, (k, z, i, b) -> leer()), nachNeustart)
        .naechsterLauf(), "nicht eine Minute nach dem Start, sondern planmäßig");
  }

  @Test
  void verpassterAbrufWirdNachDemStartNachgeholt()
  {
    // Die Zeit im Plan lag vor einer Stunde, der letzte Abruf war davor: Der Container lief nicht
    LocalDateTime jetzt = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    einstellungen.speichereZeitplan(jedenTagUm(jetzt.minusHours(1)));
    zustand.abrufBegonnen(jetzt.minusHours(30));
    // Die Änderung des Plans liegt länger zurück als die verpasste Zeit
    Properties p = Betriebszustand.laden(konfig.einstellungenDatei());
    p.setProperty("zeitplan.geaendert", jetzt.minusDays(5).toString());
    Betriebszustand.schreibe(konfig.einstellungenDatei(), p, "Test");
    einstellungen = new Einstellungen(konfig);

    LocalDateTime naechster = plan().naechsterLauf();
    assertTrue(naechster.isBefore(LocalDateTime.now().plus(ZeitplanDienst.ERSTER_ABRUF_NACH).plusSeconds(1)),
        naechster.toString());
  }

  @Test
  void aenderungDesPlansLoestKeinenSofortigenAbrufAus()
  {
    // Die neue Zeit lag heute schon vorbei - das gilt nicht als verpasst
    LocalDateTime jetzt = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    LocalDateTime geplant = jetzt.minusHours(1);
    zustand.abrufBegonnen(jetzt.minusHours(3));
    einstellungen.speichereZeitplan(jedenTagUm(geplant));

    assertEquals(geplant.plusDays(1), plan().naechsterLauf());
  }

  @Test
  void abrufKurzVorDerGeplantenZeitErsetztSie()
  {
    // Per Knopf 5 Minuten vor der geplanten Zeit abgerufen: Erst am nächsten Tag wieder
    LocalDateTime jetzt = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    LocalDateTime geplant = jetzt.plusMinutes(5);
    einstellungen.speichereZeitplan(jedenTagUm(geplant));
    zustand.abrufBegonnen(jetzt);

    assertEquals(geplant.plusDays(1), plan().naechsterLauf());
  }

  @Test
  void geplanterAbrufVerdraengtNichtDenNaechsten()
  {
    // Geplant im kleinsten erlaubten Abstand, der Abruf startet durch den Prüftakt etwas verspätet
    LocalDateTime erster = LocalDateTime.of(2026, 11, 2, 12, 0);
    Wochenplan plan = Wochenplan.jedenTag("12:00, 12:15");
    LocalDateTime gestartet = erster.plusSeconds(45);
    assertEquals(erster.plusMinutes(15), plan.naechsterNach(gestartet.plus(ZeitplanDienst.KEIN_ABRUF_KURZ_NACH)));
  }

  /** Geht die Folge von Tag 0 bis 30 stundenweise durch und merkt sich, wann was gesendet wurde. */
  @Test
  void erinnerungenAnDenTagen1237Und14()
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime seit = zustand.aussetzung().seit();
    ZeitplanDienst plan = plan();

    List<Long> anTagen = new ArrayList<>();
    for (int stunde = 0; stunde <= 30 * 24; stunde++)
    {
      int vorher = gesendet.size();
      plan.pruefeErinnerung(seit.plusHours(stunde));
      if (gesendet.size() > vorher)
        anTagen.add(stunde / 24L);
    }
    assertEquals(List.of(1L, 2L, 3L, 7L, 14L), anTagen);
    assertEquals(List.of(1, 2, 3, 4, 5), gesendet);
    assertNull(zustand.naechsteErinnerung(), "danach keine mehr");
  }

  @Test
  void keineErinnerungVorAblaufEinesTages()
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime seit = zustand.aussetzung().seit();
    plan().pruefeErinnerung(seit.plusDays(1).minusSeconds(1));
    assertEquals(List.of(), gesendet);
    assertEquals(seit.plusDays(1), zustand.naechsteErinnerung());
  }

  @Test
  void ohneAussetzungKeineErinnerung()
  {
    plan().pruefeErinnerung(LocalDateTime.now().plusDays(30));
    assertEquals(List.of(), gesendet);
  }

  @Test
  void verpassteErinnerungenWerdenNurEinmalNachgeholt()
  {
    // Der Container lief vom ersten bis zum achten Tag nicht
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime seit = zustand.aussetzung().seit();
    ZeitplanDienst plan = plan();

    plan.pruefeErinnerung(seit.plusDays(8));
    plan.pruefeErinnerung(seit.plusDays(8).plusMinutes(1));
    assertEquals(List.of(4), gesendet, "nur eine E-Mail, die letzte der fälligen");

    plan.pruefeErinnerung(seit.plusDays(14));
    assertEquals(List.of(4, 5), gesendet);
  }

  @Test
  void erinnerungenUeberstehenNeustart()
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime seit = zustand.aussetzung().seit();
    plan().pruefeErinnerung(seit.plusDays(1));
    assertEquals(List.of(1), gesendet);

    Betriebszustand nachNeustart = new Betriebszustand(konfig);
    assertEquals(1, nachNeustart.erinnerungenGesendet());
    ZeitplanDienst plan = plan(new AbrufDienst(konfig, nachNeustart, einstellungen, (k, z, i, b) -> leer()), nachNeustart);
    plan.pruefeErinnerung(seit.plusDays(1).plusHours(1));
    assertEquals(List.of(1), gesendet, "nicht erneut");
    plan.pruefeErinnerung(seit.plusDays(2));
    assertEquals(List.of(1, 2), gesendet);
  }

  @Test
  void neueAussetzungBeginntVonVorn()
  {
    zustand.aussetzen("Keine Freigabe");
    plan().pruefeErinnerung(zustand.aussetzung().seit().plusDays(14));
    assertEquals(List.of(5), gesendet);

    zustand.fortsetzen();
    assertEquals(0, zustand.erinnerungenGesendet());
    zustand.aussetzen("Wieder keine Freigabe");
    assertEquals(0, zustand.erinnerungenGesendet());
    plan().pruefeErinnerung(zustand.aussetzung().seit().plusDays(1));
    assertEquals(List.of(5, 1), gesendet);
  }

  @Test
  void gescheiterterAutorisierungsversuchVerschiebtDieFolgeNicht()
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime seit = zustand.aussetzung().seit();
    ZeitplanDienst plan = plan();
    plan.pruefeErinnerung(seit.plusDays(1));

    zustand.aussetzen("Wieder keine Freigabe");
    assertEquals(seit, zustand.aussetzung().seit());
    assertEquals(1, zustand.erinnerungenGesendet());
    plan.pruefeErinnerung(seit.plusDays(2));
    assertEquals(List.of(1, 2), gesendet);
  }

  @Test
  void fehlgeschlagenerVersandWirdNachEinerStundeWiederholt()
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime tag1 = zustand.aussetzung().seit().plusDays(1);
    ZeitplanDienst plan = plan();

    versandFehler = new jakarta.mail.MessagingException("Mailserver nicht erreichbar");
    plan.pruefeErinnerung(tag1);
    assertEquals(0, zustand.erinnerungenGesendet(), "gilt nicht als gesendet");

    versandFehler = null;
    plan.pruefeErinnerung(tag1.plusMinutes(30));
    assertEquals(List.of(), gesendet, "keine Wiederholung im Prüftakt");
    plan.pruefeErinnerung(tag1.plus(ZeitplanDienst.ERINNERUNG_ERNEUT_NACH));
    assertEquals(List.of(1), gesendet);
  }

  @Test
  void ohnePostfachBleibtDieErinnerungOffen()
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime tag1 = zustand.aussetzung().seit().plusDays(1);
    ZeitplanDienst plan = plan();

    postfachEingerichtet = false;
    plan.pruefeErinnerung(tag1);
    assertEquals(0, zustand.erinnerungenGesendet());

    postfachEingerichtet = true;
    plan.pruefeErinnerung(tag1.plusSeconds(30));
    assertEquals(List.of(1), gesendet);
  }

  @Test
  void waehrendEinesAbrufsRuhenDieErinnerungen() throws Exception
  {
    zustand.aussetzen("Keine Freigabe");
    LocalDateTime tag1 = zustand.aussetzung().seit().plusDays(1);
    CountDownLatch weiter = new CountDownLatch(1);
    CountDownLatch laeuft = new CountDownLatch(1);
    AbrufDienst blockiert = new AbrufDienst(konfig, zustand, einstellungen, (k, z, i, b) ->
    {
      laeuft.countDown();
      try
      {
        weiter.await(5, TimeUnit.SECONDS);
      }
      catch (InterruptedException e)
      {
        Thread.currentThread().interrupt();
      }
      return leer();
    });
    ZeitplanDienst plan = plan(blockiert, zustand);
    try
    {
      assertTrue(blockiert.starteImHintergrund(interaktion, "Autorisierung"));
      assertTrue(laeuft.await(5, TimeUnit.SECONDS));
      plan.pruefeErinnerung(tag1);
      assertEquals(List.of(), gesendet);
    }
    finally
    {
      weiter.countDown();
      blockiert.beenden();
    }
  }
}

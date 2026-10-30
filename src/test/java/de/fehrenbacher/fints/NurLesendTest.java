package de.fehrenbacher.fints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;

/**
 * Stellt sicher, dass das Programm nur Umsätze abruft und keine Aufträge (Überweisungen,
 * Lastschriften, Daueraufträge ...) ausführen kann - auch nach späteren Änderungen.
 */
class NurLesendTest
{
  private static final Path QUELLEN = Path.of("src/main/java");

  @Test
  void nurUmsatzabfragenErlaubt()
  {
    assertEquals(Set.of("KUmsAll", "KUmsAllCamt"), NurLesend.ERLAUBT);

    // Abgewiesen wird vor jedem Kontakt zur Bank (der Handler wird gar nicht benutzt)
    for (String auftrag : List.of("UebSEPA", "MultiUebSEPA", "TermUebSEPA", "DauerSEPANew", "LastSEPA",
        "MultiLastSEPA", "UebForeign", "DauerSEPADel", "SaldoReq"))
    {
      Exception e = assertThrows(IllegalStateException.class, () -> NurLesend.auftrag(null, auftrag));
      assertTrue(e.getMessage().contains(auftrag), e.getMessage());
    }
  }

  @Test
  void auftraegeNurUeberNurLesend() throws IOException
  {
    // newJob/newLowlevelJob von HBCI4Java darf nur NurLesend aufrufen
    Pattern erzeugung = Pattern.compile("\\.\\s*new(Lowlevel)?Job\\s*\\(");
    List<String> verstoesse = new ArrayList<>();
    for (Path datei : quellen())
    {
      if (datei.getFileName().toString().equals("NurLesend.java"))
        continue;
      Matcher m = erzeugung.matcher(Files.readString(datei, StandardCharsets.UTF_8));
      if (m.find())
        verstoesse.add(datei.toString());
    }
    assertEquals(List.of(), verstoesse, "Aufträge nur über NurLesend.auftrag() erzeugen");
  }

  @Test
  void keineAuftragsklassenVonHbci4Java() throws IOException
  {
    // Aus dem Paket org.kapott.hbci.GV (Auftragsklassen wie GVUebSEPA) nur die allgemeine Job-Schnittstelle
    Pattern gv = Pattern.compile("org\\.kapott\\.hbci\\.GV\\.(\\w+|\\*)");
    List<String> verstoesse = new ArrayList<>();
    for (Path datei : quellen())
    {
      Matcher m = gv.matcher(Files.readString(datei, StandardCharsets.UTF_8));
      while (m.find())
      {
        if (!m.group(1).equals("HBCIJob"))
          verstoesse.add(datei.getFileName() + ": " + m.group());
      }
    }
    assertEquals(List.of(), verstoesse, "Keine Auftragsklassen aus org.kapott.hbci.GV verwenden");
  }

  @Test
  void empfaengerueberpruefungBrichtAb()
  {
    Properties p = new Properties();
    p.setProperty("fints.blz", "12345678");
    p.setProperty("fints.benutzer", "bank");
    p.setProperty("fints.iban", "DE00123456780000000000");
    Konfiguration k = Konfiguration.aus(p, Map.of());
    BankCallback callback = new BankCallback(k, k.zeitraum(), "host", new KonsolenInteraktion(), null);

    HBCI_Exception e = assertThrows(HBCI_Exception.class, () -> callback.callback(null, HBCICallback.HAVE_VOP_RESULT,
        "Name abweichend", HBCICallback.TYPE_TEXT, new StringBuffer()));
    assertTrue(e.getMessage().contains("keine Zahlungen"), e.getMessage());
  }

  private static List<Path> quellen() throws IOException
  {
    try (Stream<Path> s = Files.walk(QUELLEN))
    {
      List<Path> dateien = s.filter(f -> f.toString().endsWith(".java")).toList();
      assertTrue(dateien.size() > 10, "Quellcode nicht gefunden: " + QUELLEN.toAbsolutePath());
      return dateien;
    }
  }
}

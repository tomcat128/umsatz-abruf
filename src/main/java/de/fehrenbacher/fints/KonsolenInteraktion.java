package de.fehrenbacher.fints;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Eingaben im Terminal (Befehl "abruf").
 */
final class KonsolenInteraktion implements Interaktion
{
  @Override
  public String geheim(String titel, boolean neu)
  {
    if (!neu)
      return Konsole.geheim(titel + ": ");

    while (true)
    {
      String p1 = Konsole.geheim(titel + ": ");
      String p2 = Konsole.geheim("Wiederholen: ");
      if (!p1.isEmpty() && p1.equals(p2))
        return p1;
      System.out.println("Die Eingaben sind leer oder stimmen nicht überein.");
    }
  }

  @Override
  public String tan(String text, byte[] bild, String mimetype)
  {
    zeige(text);
    if (bild == null)
      return Konsole.zeile("TAN: ");

    // Grafik speichern, nach Möglichkeit öffnen und nach der Eingabe wieder löschen
    String endung = mimetype != null && mimetype.contains("jpeg") ? ".jpg" : ".png";
    Path datei = Path.of("tan-grafik" + endung).toAbsolutePath();
    try
    {
      Files.write(datei, bild);
      try
      {
        System.out.println("TAN-Grafik gespeichert unter: " + datei);
        oeffne(datei);
        return Konsole.zeile("TAN: ");
      }
      finally
      {
        Files.deleteIfExists(datei);
      }
    }
    catch (IOException e)
    {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public int auswahl(String titel, List<String> optionen)
  {
    System.out.println(titel + ":");
    for (int i = 0; i < optionen.size(); i++)
      System.out.printf("  %d) %s%n", i + 1, optionen.get(i));
    while (true)
    {
      String eingabe = Konsole.zeile("Auswahl (1-" + optionen.size() + "): ");
      try
      {
        int n = Integer.parseInt(eingabe);
        if (n >= 1 && n <= optionen.size())
          return n - 1;
      }
      catch (NumberFormatException e)
      {
        // erneut fragen
      }
    }
  }

  @Override
  public void appFreigabe(String text, boolean erneut, int pauseSekunden)
  {
    if (erneut)
    {
      Konsole.zeile("Noch nicht freigegeben. Bitte in der App freigeben und Enter drücken ...");
      return;
    }
    zeige(text);
    Konsole.zeile("Bitte den Vorgang in der App freigeben und danach Enter drücken ...");
  }

  @Override
  public void hinweis(String text)
  {
    System.out.println(text);
  }

  @Override
  public void protokoll(String text)
  {
    System.err.println("[HBCI] " + text);
  }

  @Override
  public boolean fern()
  {
    return false;
  }

  private static void zeige(String text)
  {
    if (text == null || text.isBlank())
      return;
    System.out.println();
    System.out.println(text);
  }

  private static void oeffne(Path datei)
  {
    try
    {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
        Desktop.getDesktop().open(datei.toFile());
    }
    catch (Exception e)
    {
      // Kein Desktop verfügbar - der Pfad wurde bereits ausgegeben
    }
  }
}

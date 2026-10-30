package de.fehrenbacher.fints;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * Eingaben auf der Konsole. Geheime Eingaben werden verdeckt gelesen, sofern ein
 * Terminal vorhanden ist (bei "mvn exec:java" oder umgeleiteter Eingabe ggf. nicht).
 */
final class Konsole
{
  private static BufferedReader reader;

  private Konsole()
  {
  }

  static String zeile(String prompt)
  {
    Console c = System.console();
    if (c != null)
      return pruefe(c.readLine("%s", prompt));

    System.out.print(prompt);
    System.out.flush();
    try
    {
      return pruefe(reader().readLine());
    }
    catch (IOException e)
    {
      throw new UncheckedIOException(e);
    }
  }

  static String geheim(String prompt)
  {
    Console c = System.console();
    if (c != null)
    {
      char[] wert = c.readPassword("%s", prompt);
      return pruefe(wert == null ? null : new String(wert));
    }
    return zeile(prompt);
  }

  private static String pruefe(String eingabe)
  {
    if (eingabe == null)
      throw new IllegalStateException("Eingabe beendet (EOF) - Abbruch.");
    return eingabe.trim();
  }

  private static synchronized BufferedReader reader()
  {
    if (reader == null)
      reader = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
    return reader;
  }
}

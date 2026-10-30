package de.fehrenbacher.fints;

import java.util.List;

/**
 * Eingaben und Hinweise während des Bankdialogs. Im Konsolenbetrieb tippt der Benutzer im
 * Terminal, im Serverbetrieb beantwortet er die Anfragen in der Weboberfläche.
 *
 * Alle Methoden werden im Thread des Bankdialogs aufgerufen und blockieren, bis die Eingabe
 * vorliegt. Bricht der Benutzer ab oder läuft die Wartezeit ab, werfen sie eine Exception.
 */
interface Interaktion
{
  /**
   * Fragt einen geheimen Wert ab (PIN, Passwort).
   * @param titel z.B. "PIN für Anmeldename".
   * @param neu {@code true}, wenn ein neues Passwort festgelegt wird - dann doppelt abfragen.
   */
  String geheim(String titel, boolean neu);

  /**
   * Fragt eine TAN ab.
   * @param text Hinweis der Bank, bereits ohne HTML.
   * @param bild photoTAN-/QR-Grafik oder {@code null}.
   * @param mimetype Typ der Grafik, z.B. "image/png".
   */
  String tan(String text, byte[] bild, String mimetype);

  /**
   * Lässt eine Option wählen (TAN-Verfahren, TAN-Medium).
   * @return der Index der gewählten Option.
   */
  int auswahl(String titel, List<String> optionen);

  /**
   * Die Bank wartet auf die Freigabe in der App (decoupled, z.B. pushTAN 2.0). Kehrt zurück,
   * sobald HBCI4Java bei der Bank nachfragen soll, ob freigegeben wurde.
   * @param text Hinweis der Bank, bereits ohne HTML.
   * @param erneut {@code false} beim ersten Aufruf, {@code true} wenn noch nicht freigegeben wurde.
   * @param pauseSekunden empfohlene Pause bis zur nächsten Nachfrage bei der Bank.
   */
  void appFreigabe(String text, boolean erneut, int pauseSekunden);

  /** Nachricht der Bank oder sonstiger Hinweis für den Benutzer. */
  void hinweis(String text);

  /** Protokollzeile von HBCI4Java. */
  void protokoll(String text);

  /**
   * @return {@code true}, wenn der Benutzer nicht vor dem Terminal sitzt (Serverbetrieb) - dann
   *         werden auch PIN- und Passwortabfragen per E-Mail gemeldet.
   */
  boolean fern();

  /** Der Bankdialog ist beendet; offene Anzeigen (z.B. "bitte in der App freigeben") entfernen. */
  default void beendet()
  {
  }
}

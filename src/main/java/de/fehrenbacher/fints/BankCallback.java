package de.fehrenbacher.fints;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.kapott.hbci.callback.AbstractHBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.manager.MatrixCode;
import org.kapott.hbci.manager.QRCode;
import org.kapott.hbci.passport.AbstractPinTanPassport;
import org.kapott.hbci.passport.HBCIPassport;

/**
 * Über diesen Callback fragt HBCI4Java Zugangsdaten, TAN-Verfahren und TANs ab.
 * Zugangsdaten kommen aus der {@link Konfiguration}, alles Übrige über die {@link Interaktion}
 * (Konsole oder Weboberfläche). Fordert die Bank eine Freigabe an, wird - falls konfiguriert -
 * per E-Mail benachrichtigt.
 */
final class BankCallback extends AbstractHBCICallback
{
  /** Kürzeste Pause zwischen zwei Nachfragen bei der Bank während der App-Freigabe. */
  static final int MIN_PAUSE_SEKUNDEN = 5;

  private final Konfiguration konfig;
  private final Konfiguration.Zeitraum zeitraum;
  private final String host;
  private final Interaktion interaktion;
  /** {@code null}, wenn keine E-Mail-Benachrichtigung konfiguriert ist. */
  private final Benachrichtigung benachrichtigung;

  private String pin;
  private String passportPasswort;

  BankCallback(Konfiguration konfig, Konfiguration.Zeitraum zeitraum, String host, Interaktion interaktion,
      Benachrichtigung benachrichtigung)
  {
    this.konfig = konfig;
    this.zeitraum = zeitraum;
    this.host = host;
    this.interaktion = interaktion;
    this.benachrichtigung = benachrichtigung;
    this.pin = konfig.pin();
    this.passportPasswort = konfig.passportPasswort();
  }

  @Override
  public void log(String msg, int level, Date date, StackTraceElement trace)
  {
    // Die Filterung nach Level übernimmt HBCI4Java anhand von "log.loglevel.default"
    interaktion.protokoll(msg);
  }

  @Override
  public void status(HBCIPassport passport, int statusTag, Object[] o)
  {
  }

  @Override
  public void callback(HBCIPassport passport, int reason, String msg, int datatype, StringBuffer retData)
  {
    switch (reason)
    {
      case NEED_PASSPHRASE_LOAD:
        setze(retData, passportPasswort(false));
        break;

      case NEED_PASSPHRASE_SAVE:
        setze(retData, passportPasswort(true));
        break;

      case NEED_PT_PIN:
        if (pin == null)
        {
          String titel = "PIN für " + konfig.benutzer();
          meldeFern("PIN-Eingabe", titel);
          pin = interaktion.geheim(titel, false);
        }
        setze(retData, pin);
        break;

      case WRONG_PIN:
        // Nicht mit derselben PIN erneut versuchen - sonst droht die Sperre des Zugangs
        pin = null;
        throw new HBCI_Exception("Die Bank hat die PIN abgelehnt. Abbruch, um eine Sperre des Zugangs zu vermeiden.");

      case NEED_COUNTRY:    setze(retData, "DE"); break;
      case NEED_BLZ:        setze(retData, konfig.blz()); break;
      case NEED_HOST:       setze(retData, host); break;
      case NEED_PORT:       setze(retData, "443"); break;
      case NEED_FILTER:     setze(retData, "Base64"); break;
      case NEED_USERID:     setze(retData, konfig.benutzer()); break;
      case NEED_CUSTOMERID: setze(retData, konfig.kundenId()); break;

      case NEED_PT_SECMECH:
        setze(retData, waehleTanVerfahren(retData.toString()));
        break;

      case NEED_PT_TANMEDIA:
        waehleTanMedium(retData);
        break;

      case NEED_PT_TAN:
      {
        String text = ohneHtml(msg);
        if (retData.length() > 0)
          text += "\n\nHinweis: chipTAN optisch (Flickercode) kann nicht angezeigt werden."
              + " Bitte ggf. ein anderes TAN-Verfahren konfigurieren (fints.tanverfahren).";
        melde("TAN-Eingabe", msg);
        setze(retData, interaktion.tan(text, null, null));
        break;
      }

      case NEED_PT_PHOTOTAN:
      {
        MatrixCode code;
        try
        {
          code = new MatrixCode(retData.toString());
        }
        catch (Exception e)
        {
          throw new HBCI_Exception("photoTAN-Grafik konnte nicht gelesen werden", e);
        }
        melde("photoTAN", msg);
        setze(retData, interaktion.tan(ohneHtml(msg), code.getImage(), code.getMimetype()));
        break;
      }

      case NEED_PT_QRTAN:
      {
        QRCode code;
        try
        {
          code = new QRCode(retData.toString(), msg);
        }
        catch (Exception e)
        {
          throw new HBCI_Exception("QR-TAN-Grafik konnte nicht gelesen werden", e);
        }
        // Sparkassen packen die Grafik u.U. in den Text - QRCode liefert ihn bereinigt
        melde("QR-TAN", code.getMessage());
        setze(retData, interaktion.tan(ohneHtml(code.getMessage()), code.getImage(), code.getMimetype()));
        break;
      }

      case NEED_PT_DECOUPLED:
        melde("Freigabe in der App (z.B. pushTAN)", msg);
        interaktion.appFreigabe(ohneHtml(msg), false, 0);
        break;

      case NEED_PT_DECOUPLED_RETRY:
        interaktion.appFreigabe(null, true, pause(passport, retData.toString()));
        break;

      case HAVE_VOP_RESULT:
        // Die Empfängerüberprüfung gibt es nur bei Zahlungen - die dieses Programm nie auslöst.
        // Kommt sie trotzdem, sofort abbrechen statt nur abzulehnen.
        throw new HBCI_Exception("Die Bank meldet eine Empfängerüberprüfung, die es nur bei Zahlungen gibt."
            + " Dieses Programm führt keine Zahlungen aus - Abbruch.");

      case HAVE_INST_MSG:
        interaktion.hinweis("Nachricht der Bank: " + ohneHtml(msg));
        break;

      case USERID_CHANGED:
        interaktion.hinweis("Hinweis: Die Bank hat Benutzer-/Kundenkennung geändert: " + retData);
        break;

      case HAVE_ERROR:
        interaktion.protokoll("Fehler: " + msg);
        // Nicht-leerer Wert = Fehler nicht ignorieren, HBCI4Java bricht ab
        setze(retData, "abbrechen");
        break;

      default:
        break;
    }
  }

  private String passportPasswort(boolean speichern)
  {
    if (passportPasswort != null)
      return passportPasswort;

    // Neue Passport-Datei: Passwort zur Sicherheit doppelt abfragen
    boolean neu = speichern && !Files.exists(konfig.passportDatei());
    String titel = neu ? "Neues Passwort für die Passport-Datei " + konfig.passportDatei()
        : "Passwort der Passport-Datei";
    meldeFern("Passwort der Passport-Datei", titel);
    passportPasswort = interaktion.geheim(titel, neu);
    return passportPasswort;
  }

  /**
   * @param optionen Format: {@code code1:name1|code2:name2|...}
   */
  private String waehleTanVerfahren(String optionen)
  {
    if (konfig.tanVerfahren() != null)
      return konfig.tanVerfahren();

    String[] liste = optionen.split("\\|");
    if (liste.length == 1)
      return liste[0].split(":", 2)[0];

    List<String> namen = new ArrayList<>();
    for (String o : liste)
    {
      String[] teile = o.split(":", 2);
      namen.add((teile.length > 1 ? teile[1] : teile[0]) + " (" + teile[0] + ")");
    }
    meldeFern("Auswahl des TAN-Verfahrens", null);
    String code = liste[interaktion.auswahl("Verfügbare TAN-Verfahren", namen)].split(":", 2)[0];
    interaktion.hinweis("Tipp: fints.tanverfahren=" + code + " in der Konfiguration erspart diese Abfrage.");
    return code;
  }

  /**
   * @param retData enthält die Optionen im Format {@code name1|name2|...}. Bei nur
   *                einer Option bleibt der Wert unverändert.
   */
  private void waehleTanMedium(StringBuffer retData)
  {
    if (konfig.tanMedium() != null)
    {
      setze(retData, konfig.tanMedium());
      return;
    }

    String[] liste = retData.toString().split("\\|");
    if (liste.length <= 1)
      return;

    meldeFern("Auswahl des TAN-Mediums", null);
    setze(retData, liste[interaktion.auswahl("Verfügbare TAN-Medien", Arrays.asList(liste))]);
  }

  /**
   * Pause zwischen zwei Nachfragen bei der Bank, ob die App-Freigabe erfolgt ist: mindestens
   * die von der Bank verlangte Zeit, und so lang, dass die erlaubte Anzahl an Nachfragen für
   * die ganze Wartezeit (web.wartezeit) reicht.
   * @param mindestSekunden von HBCI4Java übergebene Mindestzeit der Bank.
   */
  private int pause(HBCIPassport passport, String mindestSekunden)
  {
    int pause = MIN_PAUSE_SEKUNDEN;
    try
    {
      pause = Math.max(pause, Integer.parseInt(mindestSekunden.trim()));
    }
    catch (NumberFormatException e)
    {
      // keine Vorgabe der Bank
    }
    Integer max = passport instanceof AbstractPinTanPassport p ? p.getDecoupledMaxRefreshes() : null;
    if (max != null && max > 0)
      pause = Math.max(pause, (int) Math.ceil(konfig.webWartezeitMinuten() * 60.0 / max));
    return pause;
  }

  private void melde(String art, String bankText)
  {
    if (benachrichtigung != null)
      benachrichtigung.freigabeAngefordert(art, bankText == null ? null : ohneHtml(bankText), zeitraum);
  }

  /** Eingaben ohne Bankfreigabe werden nur gemeldet, wenn niemand vor dem Terminal sitzt. */
  private void meldeFern(String art, String text)
  {
    if (interaktion.fern())
      melde(art, text);
  }

  /** Banktexte enthalten teils einfaches HTML. */
  static String ohneHtml(String msg)
  {
    if (msg == null)
      return "";
    return msg.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
  }

  private static void setze(StringBuffer retData, String wert)
  {
    retData.replace(0, retData.length(), wert);
  }
}

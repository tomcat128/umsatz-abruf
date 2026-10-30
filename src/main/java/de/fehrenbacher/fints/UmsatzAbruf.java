package de.fehrenbacher.fints;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.kapott.hbci.GV.HBCIJob;
import org.kapott.hbci.GV_Result.GVRKUms;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.manager.BankInfo;
import org.kapott.hbci.manager.HBCIHandler;
import org.kapott.hbci.manager.HBCIUtils;
import org.kapott.hbci.manager.HBCIVersion;
import org.kapott.hbci.passport.AbstractHBCIPassport;
import org.kapott.hbci.passport.HBCIPassport;
import org.kapott.hbci.status.HBCIExecStatus;
import org.kapott.hbci.structures.Konto;

/**
 * Ruft per FinTS (PIN/TAN) die gebuchten Umsätze des konfigurierten Kontos ab.
 * Speichern und Ausgeben übernimmt der {@link AbrufDienst}.
 */
final class UmsatzAbruf
{
  private static final String JOB_CAMT = "KUmsAllCamt";
  private static final String JOB_MT940 = "KUmsAll";

  /** Wird zusammen mit der Produktregistrierung an die Bank gemeldet (max. 5 Zeichen). */
  private static final String PRODUKT_VERSION = "1.0";

  private final Konfiguration konfig;
  private final Konfiguration.Zeitraum zeitraum;
  private final Interaktion interaktion;
  private final Benachrichtigung benachrichtigung;

  /**
   * @param konto das abgerufene Konto, wie die Bank es meldet.
   * @param format "CAMT" oder "MT940".
   * @param buchungen die gebuchten Umsätze in der von der Bank gelieferten Reihenfolge.
   */
  record Ergebnis(Konto konto, String format, List<UmsLine> buchungen) {}

  /**
   * @param benachrichtigung E-Mail bei Freigabe-Anforderungen oder {@code null}.
   */
  UmsatzAbruf(Konfiguration konfig, Konfiguration.Zeitraum zeitraum, Interaktion interaktion,
      Benachrichtigung benachrichtigung)
  {
    this.konfig = konfig;
    this.zeitraum = zeitraum;
    this.interaktion = interaktion;
    this.benachrichtigung = benachrichtigung;
  }

  /**
   * Baut die Verbindung zur Bank auf und ruft die Umsätze des konfigurierten Kontos ab.
   * @return Konto, Format und Buchungen.
   */
  Ergebnis abrufen()
  {
    String host = konfig.url() != null ? konfig.url() : ermittleHost(konfig.blz());

    Properties props = new Properties();
    props.setProperty("client.passport.default", "PinTan");
    props.setProperty("client.passport.PinTan.init", "1");
    props.setProperty("log.loglevel.default", Integer.toString(konfig.logLevel()));
    if (konfig.produktId() != null)
    {
      props.setProperty("client.product.name", konfig.produktId());
      props.setProperty("client.product.version", PRODUKT_VERSION);
    }
    HBCIUtils.init(props, new BankCallback(konfig, zeitraum, host, interaktion, benachrichtigung));

    HBCIPassport passport = null;
    HBCIHandler handle = null;
    try
    {
      // Die Passport-Datei speichert BPD/UPD und die System-ID verschlüsselt. Sie wird beim
      // ersten Lauf angelegt; ohne sie verlangt die Bank bei jedem Lauf eine neue Freigabe.
      passport = AbstractHBCIPassport.getInstance(konfig.passportDatei().toFile());
      pruefePassport(passport);

      passport.setCountry("DE");
      passport.setHost(host);
      passport.setPort(443);
      passport.setFilterType("Base64");

      handle = new HBCIHandler(HBCIVersion.HBCI_300.getId(), passport);

      Konto konto = findeKonto(passport.getAccounts());
      String jobName = jobName(handle);
      String format = JOB_CAMT.equals(jobName) ? "CAMT" : "MT940";
      interaktion.hinweis("Rufe Umsätze vom " + zeitraum.von() + " bis " + zeitraum.bis() + " ab (" + format + ") ...");

      HBCIJob job = handle.newJob(jobName);
      job.setParam("my", konto);
      job.setParam("startdate", alsDate(zeitraum.von()));
      job.setParam("enddate", alsDate(zeitraum.bis()));
      job.addToQueue();

      HBCIExecStatus status = handle.execute();
      if (!status.isOK())
        throw new IllegalStateException("Kommunikation mit der Bank fehlgeschlagen: " + status.getErrorString());

      GVRKUms ergebnis = (GVRKUms) job.getJobResult();
      if (!ergebnis.isOK())
        throw new IllegalStateException("Umsatzabruf abgelehnt: " + ergebnis.getJobStatus().getErrorString());

      return new Ergebnis(konto, format, new ArrayList<>(ergebnis.getFlatData()));
    }
    finally
    {
      if (handle != null)
        handle.close();
      else if (passport != null)
        passport.close();
      HBCIUtils.done();
      interaktion.beendet();
    }
  }

  private static String ermittleHost(String blz)
  {
    BankInfo info = HBCIUtils.getBankInfo(blz);
    if (info == null || info.getPinTanAddress() == null || info.getPinTanAddress().isBlank())
      throw new Konfiguration.KonfigurationsFehler("Für BLZ " + blz
          + " ist keine PIN/TAN-Adresse bekannt. Bitte fints.url (Umgebungsvariable FINTS_URL) angeben.");
    return info.getPinTanAddress();
  }

  /**
   * Eine bestehende Passport-Datei gehört fest zu einem Bankzugang.
   */
  private void pruefePassport(HBCIPassport passport)
  {
    String blz = passport.getBLZ();
    String user = passport.getUserId();
    if ((blz != null && !blz.equals(konfig.blz())) || (user != null && !user.equals(konfig.benutzer())))
      throw new Konfiguration.KonfigurationsFehler("Die Passport-Datei " + konfig.passportDatei()
          + " gehört zu BLZ " + blz + " / Benutzer " + user
          + ". Bitte eine andere Datei konfigurieren (passport.datei) oder die alte löschen.");
  }

  private Konto findeKonto(Konto[] konten)
  {
    if (konten == null || konten.length == 0)
      throw new IllegalStateException("Die Bank hat keine Konten zu diesem Zugang gemeldet.");

    StringBuilder vorhanden = new StringBuilder();
    for (Konto k : konten)
    {
      if (k.iban == null)
        continue;
      if (Konfiguration.normalisiereIban(k.iban).equals(konfig.iban()))
        return k;
      vorhanden.append("\n  ").append(k.iban).append(k.type != null ? " (" + k.type + ")" : "");
    }
    throw new Konfiguration.KonfigurationsFehler("Konto " + konfig.iban()
        + " gehört nicht zu diesem Zugang. Verfügbare Konten:" + vorhanden);
  }

  private String jobName(HBCIHandler handle)
  {
    switch (konfig.format())
    {
      case CAMT:  return JOB_CAMT;
      case MT940: return JOB_MT940;
      default:    return handle.isSupported(JOB_CAMT) ? JOB_CAMT : JOB_MT940;
    }
  }

  private static Date alsDate(LocalDate d)
  {
    return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}

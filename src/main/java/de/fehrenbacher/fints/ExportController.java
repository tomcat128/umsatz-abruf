package de.fehrenbacher.fints;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export-Schnittstelle für externe Anwendungen:
 * <pre>
 * GET /api/buchungen.csv | .json | .xml     (oder /api/buchungen?format=csv|json|xml, Standard json)
 *     Zeitraum (höchstens eine Angabe, siehe {@link ExportZeitraum}):
 *       ?von=JJJJ-MM-TT&amp;bis=JJJJ-MM-TT   ?tage=3   ?monat=JJJJ-MM   ?jahr=JJJJ
 *     ?nachId=123       nur Buchungen mit größerer ID (fortlaufendes Abholen neuer Buchungen)
 * </pre>
 * Offen oder - mit export.benutzer/export.passwort - per HTTP Basic Auth geschützt (siehe
 * {@link WebSicherheit}); abschaltbar mit export.aktiv=false.
 */
@RestController
@RequestMapping("/api")
@ConditionalOnWebApplication
class ExportController
{
  private static final Logger LOG = LoggerFactory.getLogger(ExportController.class);

  private final Konfiguration konfig;
  private final ObjectMapper json;

  ExportController(Konfiguration konfig, ObjectMapper json)
  {
    this.konfig = konfig;
    this.json = json;
  }

  @PostConstruct
  void warnen()
  {
    if (!konfig.exportAktiv())
      LOG.info("Export-Schnittstelle /api/buchungen ist abgeschaltet.");
    else if (konfig.exportGeschuetzt())
      LOG.info("Export-Schnittstelle /api/buchungen ist mit Basic Auth geschützt (Benutzer {}).",
          konfig.exportBenutzer());
    else
      LOG.warn("Export-Schnittstelle /api/buchungen ist OHNE Anmeldung erreichbar - jeder mit Zugriff auf die"
          + " Adresse kann alle Buchungen abrufen. Schützen mit EXPORT_BENUTZER/EXPORT_PASSWORT,"
          + " abschalten mit EXPORT_AKTIV=false.");
  }

  @GetMapping("/buchungen.{format}")
  ResponseEntity<?> exportMitEndung(@PathVariable String format, @RequestParam(required = false) String von,
      @RequestParam(required = false) String bis, @RequestParam(required = false) String tage,
      @RequestParam(required = false) String monat, @RequestParam(required = false) String jahr,
      @RequestParam(required = false) String nachId)
  {
    return export(format, von, bis, tage, monat, jahr, nachId);
  }

  @GetMapping("/buchungen")
  ResponseEntity<?> exportMitParameter(@RequestParam(defaultValue = "json") String format,
      @RequestParam(required = false) String von, @RequestParam(required = false) String bis,
      @RequestParam(required = false) String tage, @RequestParam(required = false) String monat,
      @RequestParam(required = false) String jahr, @RequestParam(required = false) String nachId)
  {
    return export(format, von, bis, tage, monat, jahr, nachId);
  }

  private ResponseEntity<?> export(String formatName, String von, String bis, String tage, String monat,
      String jahr, String nachIdText)
  {
    if (!konfig.exportAktiv())
      return fehler(HttpStatus.NOT_FOUND, "Die Export-Schnittstelle ist abgeschaltet (export.aktiv=false).");
    UmsatzExport.Format format = UmsatzExport.Format.von(formatName);
    if (format == null)
      return fehler(HttpStatus.BAD_REQUEST, "Unbekanntes Format: " + formatName + " (erlaubt: csv, json, xml)");
    if (!konfig.ausgabeZiel().datenbank())
      return fehler(HttpStatus.CONFLICT, "Der Export benötigt die Datenbank (ausgabe.ziel=datenbank oder beide).");

    ExportZeitraum.Zeitraum zeitraum;
    Long nachId = null;
    try
    {
      zeitraum = ExportZeitraum.bestimme(von, bis, tage, monat, jahr, LocalDate.now());
      if (nachIdText != null && !nachIdText.isBlank())
        nachId = Long.parseLong(nachIdText.trim());
    }
    catch (NumberFormatException e)
    {
      return fehler(HttpStatus.BAD_REQUEST, "nachId muss eine ganze Zahl sein: " + nachIdText);
    }
    catch (IllegalArgumentException e)
    {
      return fehler(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    UmsatzExport.Daten daten;
    ByteArrayOutputStream inhalt = new ByteArrayOutputStream();
    try (UmsatzDatenbank db = UmsatzDatenbank.oeffnen(konfig.dbDatei()))
    {
      daten = db.exportiere(konfig.iban(), zeitraum.von(), zeitraum.bis(), nachId);
      UmsatzExport.schreibe(daten, format, inhalt, json);
    }
    catch (Exception e)
    {
      LOG.warn("Export fehlgeschlagen: {}", AbrufDienst.fehlertext(e));
      return fehler(HttpStatus.INTERNAL_SERVER_ERROR, "Die Buchungen konnten nicht gelesen werden.");
    }

    String dateiname = "umsaetze-" + konfig.iban() + "." + format.name().toLowerCase();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(format.mimeTyp + ";charset=UTF-8"))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(dateiname).build().toString())
        .header("X-Anzahl-Buchungen", Integer.toString(daten.anzahl()))
        .body(inhalt.toByteArray());
  }

  private static ResponseEntity<String> fehler(HttpStatus status, String text)
  {
    return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(text + "\n");
  }
}

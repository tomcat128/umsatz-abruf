package de.fehrenbacher.fints;

import java.io.UncheckedIOException;
import java.security.Principal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Weboberfläche: Übersicht mit offenen Anfragen der Bank, Status und Abrufverlauf, Buchungsliste.
 */
@Controller
@ConditionalOnWebApplication
class WebController
{
  private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

  /** Was die Weboberfläche über das Postfach anzeigt - ohne Passwort. */
  record MailAnzeige(String host, int port, String sicherheit, String benutzer, String absender,
      List<String> probleme, boolean versandMoeglich) {}

  /** Eine Zeile im Formular der Abrufzeiten. */
  record ZeitplanZeile(String feld, String name, String zeiten) {}

  private final Konfiguration konfig;
  private final AbrufDienst dienst;
  private final WebInteraktion interaktion;
  private final ZeitplanDienst zeitplan;
  private final Betriebszustand zustand;
  private final BuchungsAnsicht buchungen;
  private final Einstellungen einstellungen;

  WebController(Konfiguration konfig, AbrufDienst dienst, WebInteraktion interaktion, ZeitplanDienst zeitplan,
      Betriebszustand zustand, BuchungsAnsicht buchungen, Einstellungen einstellungen)
  {
    this.konfig = konfig;
    this.dienst = dienst;
    this.interaktion = interaktion;
    this.zeitplan = zeitplan;
    this.zustand = zustand;
    this.buchungen = buchungen;
    this.einstellungen = einstellungen;
  }

  @GetMapping("/login")
  String login()
  {
    return "login";
  }

  @GetMapping("/")
  String uebersicht(Model model, HttpServletRequest anfrage)
  {
    merkeAdresse(anfrage);
    model.addAttribute("anfrage", interaktion.anfrage());
    model.addAttribute("aktuell", dienst.aktuell());
    model.addAttribute("verlauf", dienst.verlauf());
    model.addAttribute("hinweise", interaktion.hinweise());
    model.addAttribute("naechsterLauf", zeitplan.naechsterLauf());
    model.addAttribute("letzterAbruf", dienst.letzterBeginn());
    Wochenplan plan = einstellungen.zeitplan();
    model.addAttribute("zeitplanText", plan.beschreibung());
    model.addAttribute("zeitplanGespeichert", einstellungen.zeitplanGespeichert());
    model.addAttribute("zeitplanZeilen", Arrays.stream(DayOfWeek.values())
        .map(t -> new ZeitplanZeile(feld(t), Wochenplan.name(t), plan.text(t))).toList());
    model.addAttribute("aussetzung", zustand.aussetzung());
    model.addAttribute("naechsteErinnerung", zustand.naechsteErinnerung());
    model.addAttribute("erinnerungenGesendet", zustand.erinnerungenGesendet());
    model.addAttribute("erinnerungenGesamt", Betriebszustand.ERINNERUNG_NACH_TAGEN.size());
    model.addAttribute("notfall", String.join(", ", einstellungen.empfaenger()));
    model.addAttribute("notfallStandard", String.join(", ", konfig.mailEmpfaenger()));
    model.addAttribute("notfallEigene", String.join("\n", einstellungen.eigeneKontakte()));
    model.addAttribute("mail", new MailAnzeige(konfig.mailHost(), konfig.mailPort(),
        konfig.mailSicherheit().name().toLowerCase(Locale.ROOT), konfig.mailBenutzer(), konfig.mailAbsender(),
        einstellungen.mailProbleme(), konfig.mailVersandMoeglich()));
    model.addAttribute("iban", konfig.iban());
    model.addAttribute("exportAktiv", konfig.exportAktiv() && konfig.ausgabeZiel().datenbank());
    model.addAttribute("exportBasis", konfig.webAdresse() != null ? konfig.webAdresse() : "");
    model.addAttribute("exportBenutzer", konfig.exportGeschuetzt() ? konfig.exportBenutzer() : null);
    model.addAttribute("exportBeispiele", ExportZeitraum.beispiele(LocalDate.now()));
    model.addAttribute("zustand", zustand());
    return "uebersicht";
  }

  /** "Jetzt abrufen" bzw. - wenn die Abrufe ausgesetzt sind - "Jetzt autorisieren". */
  @PostMapping("/abruf")
  String abrufStarten(Principal benutzer, RedirectAttributes ra)
  {
    boolean autorisieren = zustand.ausgesetzt();
    String ausloeser = (autorisieren ? "Autorisierung" : "Weboberfläche") + " (" + benutzer.getName() + ")";
    if (dienst.starteImHintergrund(interaktion, ausloeser))
      ra.addFlashAttribute("meldung", autorisieren
          ? "Autorisierung gestartet - bitte die Anfrage der Bank hier beantworten bzw. in der App freigeben."
          : "Abruf gestartet.");
    else
      ra.addFlashAttribute("fehler", "Es läuft bereits ein Abruf.");
    return "redirect:/";
  }

  /** Speichert die Abrufzeiten je Wochentag. Ein leeres Feld bedeutet: an dem Tag kein Abruf. */
  @PostMapping("/zeitplan")
  String zeitplanSpeichern(@RequestParam Map<String,String> felder, RedirectAttributes ra)
  {
    Map<DayOfWeek,String> eingabe = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek tag : DayOfWeek.values())
      eingabe.put(tag, felder.get(feld(tag)));
    try
    {
      Wochenplan plan = Wochenplan.aus(eingabe);
      einstellungen.speichereZeitplan(plan);
      ra.addFlashAttribute("meldung", "Abrufzeiten gespeichert: " + plan.beschreibung() + ".");
    }
    catch (IllegalArgumentException e)
    {
      ra.addFlashAttribute("fehler", "Abrufzeiten nicht gespeichert: " + e.getMessage());
    }
    catch (UncheckedIOException e)
    {
      LOG.warn("Abrufzeiten konnten nicht gespeichert werden: {}", AbrufDienst.fehlertext(e));
      ra.addFlashAttribute("fehler", "Abrufzeiten konnten nicht gespeichert werden: " + e.getMessage());
    }
    return "redirect:/";
  }

  /** Speichert die eigenen Notfallkontakte. Leer = wieder der Standard aus der Konfiguration. */
  @PostMapping("/notfallkontakte")
  String notfallkontakteSpeichern(@RequestParam(required = false) String adressen, RedirectAttributes ra)
  {
    try
    {
      einstellungen.speichereKontakte(adressen);
      ra.addFlashAttribute("meldung", einstellungen.eigeneKontakte().isEmpty()
          ? "Keine eigenen Notfallkontakte mehr - es gilt der Standard aus der Konfiguration."
          : "Notfallkontakte gespeichert: " + String.join(", ", einstellungen.eigeneKontakte()) + ".");
    }
    catch (IllegalArgumentException e)
    {
      ra.addFlashAttribute("fehler", "Notfallkontakte nicht gespeichert: " + e.getMessage());
    }
    catch (UncheckedIOException e)
    {
      LOG.warn("Notfallkontakte konnten nicht gespeichert werden: {}", AbrufDienst.fehlertext(e));
      ra.addFlashAttribute("fehler", "Notfallkontakte konnten nicht gespeichert werden: " + e.getMessage());
    }
    return "redirect:/";
  }

  /**
   * Für Links in den E-Mails. Alle Aufrufer verlangen eine Anmeldung ({@link WebSicherheit}) - auf
   * den Host-Header einer anonymen Anfrage darf sich das nicht stützen.
   */
  private void merkeAdresse(HttpServletRequest anfrage)
  {
    einstellungen.merkeAdresse(ServletUriComponentsBuilder.fromContextPath(anfrage).build().toUriString());
  }

  private static String feld(DayOfWeek tag)
  {
    return "tag_" + tag.name().toLowerCase(Locale.ROOT);
  }

  /**
   * Test-E-Mail über das konfigurierte Postfach - an die Notfall-Adresse oder an eine im
   * Formular angegebene Adresse.
   */
  @PostMapping("/mail-test")
  String mailTest(@RequestParam(required = false) String an, HttpServletRequest anfrage, RedirectAttributes ra)
  {
    merkeAdresse(anfrage);
    return sende(an, ra, "Test-E-Mail", (b, empfaenger) -> b.sendeTest(empfaenger));
  }

  /**
   * Die E-Mail "Autorisierung erforderlich" auf Anforderung - als Erinnerung, wenn die Abrufe
   * ausgesetzt sind, sonst als gekennzeichnetes Muster.
   */
  @PostMapping("/mail-autorisierung")
  String autorisierungsMail(@RequestParam(required = false) String an, HttpServletRequest anfrage,
      RedirectAttributes ra)
  {
    merkeAdresse(anfrage);
    Betriebszustand.Aussetzung aussetzung = zustand.aussetzung();
    return sende(an, ra, aussetzung != null ? "Autorisierungs-Mail" : "Autorisierungs-Mail (Muster)",
        (b, empfaenger) -> b.sendeAutorisierungsMail(empfaenger, aussetzung));
  }

  @FunctionalInterface
  private interface Versand
  {
    void sende(Benachrichtigung b, List<String> empfaenger) throws Exception;
  }

  /** Prüft Postfach und Empfänger, sendet und meldet das Ergebnis in der Oberfläche. */
  private String sende(String an, RedirectAttributes ra, String was, Versand versand)
  {
    Benachrichtigung b = Benachrichtigung.fuerTest(konfig, einstellungen);
    if (b == null)
    {
      ra.addFlashAttribute("fehler", einstellungen.mailProbleme().isEmpty()
          ? "Kein Postfach eingerichtet (MAIL_HOST, MAIL_BENUTZER, MAIL_PASSWORT, MAIL_NOTFALL in der .env)."
          : "Das Postfach ist nicht vollständig eingerichtet: " + String.join("; ", einstellungen.mailProbleme()));
      return "redirect:/";
    }
    List<String> empfaenger = an == null || an.isBlank() ? einstellungen.empfaenger() : List.of(an.trim());
    if (empfaenger.isEmpty())
    {
      ra.addFlashAttribute("fehler", "Bitte eine Empfänger-Adresse angeben (keine Notfall-Adresse konfiguriert).");
      return "redirect:/";
    }
    if (!empfaenger.stream().allMatch(Konfiguration::gueltigeAdresse))
    {
      ra.addFlashAttribute("fehler", "Keine gültige E-Mail-Adresse: " + an);
      return "redirect:/";
    }
    try
    {
      versand.sende(b, empfaenger);
      ra.addFlashAttribute("meldung", was + " an " + String.join(", ", empfaenger) + " gesendet.");
    }
    catch (Exception e)
    {
      LOG.warn("{} fehlgeschlagen: {}", was, AbrufDienst.fehlertext(e));
      ra.addFlashAttribute("fehler", was + " fehlgeschlagen: " + AbrufDienst.fehlertext(e));
    }
    return "redirect:/";
  }


  @PostMapping("/freigabe")
  String beantworten(@RequestParam String id, @RequestParam(required = false) String wert,
      @RequestParam(required = false) String wiederholung, RedirectAttributes ra)
  {
    WebInteraktion.Anfrage a = interaktion.anfrage();
    if (a == null || !a.id().equals(id))
    {
      ra.addFlashAttribute("fehler", "Die Anfrage ist nicht mehr offen.");
      return "redirect:/";
    }
    String w = wert == null ? "" : wert.trim();
    if (w.isEmpty())
    {
      ra.addFlashAttribute("fehler", "Bitte einen Wert eingeben.");
      return "redirect:/";
    }
    if (a.art() == WebInteraktion.Art.GEHEIM_NEU && !w.equals(wiederholung == null ? "" : wiederholung.trim()))
    {
      ra.addFlashAttribute("fehler", "Die beiden Eingaben stimmen nicht überein.");
      return "redirect:/";
    }
    if (interaktion.beantworte(id, w))
      ra.addFlashAttribute("meldung", "Eingabe übermittelt.");
    else
      ra.addFlashAttribute("fehler", "Die Anfrage ist nicht mehr offen.");
    return "redirect:/";
  }

  @PostMapping("/freigabe/pruefen")
  String jetztPruefen(@RequestParam String id, RedirectAttributes ra)
  {
    if (interaktion.jetztPruefen(id))
      ra.addFlashAttribute("meldung", "Frage bei der Bank nach, ob die Freigabe erfolgt ist ...");
    return "redirect:/";
  }

  @PostMapping("/freigabe/abbrechen")
  String abbrechen(@RequestParam String id, Principal benutzer, RedirectAttributes ra)
  {
    if (interaktion.abbrechen(id))
    {
      LOG.info("Anfrage von {} in der Weboberfläche abgebrochen.", benutzer.getName());
      ra.addFlashAttribute("meldung", "Abruf abgebrochen.");
    }
    return "redirect:/";
  }

  @GetMapping("/freigabe/bild")
  ResponseEntity<byte[]> bild(@RequestParam String id)
  {
    byte[] bild = interaktion.bild(id);
    if (bild == null)
      return ResponseEntity.notFound().build();
    String typ = interaktion.bildTyp();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(typ != null ? MediaType.parseMediaType(typ) : MediaType.IMAGE_PNG)
        .body(bild);
  }

  /** Für die automatische Aktualisierung: ändert sich der Wert, lädt die Seite neu. */
  @GetMapping("/status")
  @ResponseBody
  Map<String,String> status()
  {
    return Map.of("zustand", zustand());
  }

  @GetMapping("/buchungen")
  String buchungen(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate von,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bis,
      @RequestParam(required = false) String suche, @RequestParam(defaultValue = "1") int seite, Model model)
  {
    BuchungsAnsicht.Filter filter = new BuchungsAnsicht.Filter(von, bis, suche, seite);
    model.addAttribute("filter", filter);
    model.addAttribute("exportAktiv", konfig.exportAktiv() && konfig.ausgabeZiel().datenbank());
    // Nur gesetzte Filter in die Export-Links (Datumswerte enthalten keine Sonderzeichen)
    List<String> teile = new ArrayList<>();
    if (von != null)
      teile.add("von=" + von);
    if (bis != null)
      teile.add("bis=" + bis);
    model.addAttribute("exportFilter", teile.isEmpty() ? "" : "?" + String.join("&", teile));
    try
    {
      model.addAttribute("ergebnis", buchungen.suche(filter));
    }
    catch (Exception e)
    {
      LOG.warn("Buchungen konnten nicht gelesen werden: {}", AbrufDienst.fehlertext(e));
      model.addAttribute("fehler", "Buchungen konnten nicht gelesen werden: " + e.getMessage());
    }
    return "buchungen";
  }

  private String zustand()
  {
    WebInteraktion.Anfrage a = interaktion.anfrage();
    return (a != null ? a.id() : "-") + "/" + dienst.laeuft() + "/" + dienst.verlauf().size()
        + "/" + interaktion.hinweise().size() + "/" + zustand.ausgesetzt();
  }
}

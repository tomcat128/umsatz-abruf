package de.fehrenbacher.fints;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;

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

/**
 * Weboberfläche: Übersicht mit offenen Anfragen der Bank und Abrufverlauf, Buchungsliste.
 */
@Controller
@ConditionalOnWebApplication
class WebController
{
  private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

  private final Konfiguration konfig;
  private final AbrufDienst dienst;
  private final WebInteraktion interaktion;
  private final ZeitplanDienst zeitplan;
  private final BuchungsAnsicht buchungen;

  WebController(Konfiguration konfig, AbrufDienst dienst, WebInteraktion interaktion, ZeitplanDienst zeitplan,
      BuchungsAnsicht buchungen)
  {
    this.konfig = konfig;
    this.dienst = dienst;
    this.interaktion = interaktion;
    this.zeitplan = zeitplan;
    this.buchungen = buchungen;
  }

  @GetMapping("/login")
  String login()
  {
    return "login";
  }

  @GetMapping("/")
  String uebersicht(Model model)
  {
    model.addAttribute("anfrage", interaktion.anfrage());
    model.addAttribute("aktuell", dienst.aktuell());
    model.addAttribute("verlauf", dienst.verlauf());
    model.addAttribute("hinweise", interaktion.hinweise());
    model.addAttribute("naechsterLauf", zeitplan.naechsterLauf());
    model.addAttribute("zeitplan", konfig.zeitplan());
    model.addAttribute("iban", konfig.iban());
    model.addAttribute("zustand", zustand());
    return "uebersicht";
  }

  @PostMapping("/abruf")
  String abrufStarten(Principal benutzer, RedirectAttributes ra)
  {
    if (dienst.starteImHintergrund(interaktion, "Weboberfläche (" + benutzer.getName() + ")"))
      ra.addFlashAttribute("meldung", "Abruf gestartet.");
    else
      ra.addFlashAttribute("fehler", "Es läuft bereits ein Abruf.");
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
        + "/" + interaktion.hinweise().size();
  }
}

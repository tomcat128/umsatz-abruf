package de.fehrenbacher.fints;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WebOberflaecheTest
{
  static final Path VERZEICHNIS;

  static
  {
    try
    {
      VERZEICHNIS = Files.createTempDirectory("umsatz-web");
      Konto konto = new Konto();
      konto.iban = "DE00123456780000000000";
      try (UmsatzDatenbank db = UmsatzDatenbank.oeffnen(VERZEICHNIS.resolve("umsaetze.db")))
      {
        db.speichere(konto, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "CAMT", List.of(
            buchung(1, "2500.00", "Gehalt Oktober", "Arbeitgeber GmbH"),
            buchung(15, "-89.90", "Rechnung 4711", "Stadtwerke"),
            buchung(20, "-3.50", "<script>alert(1)</script>", "Café Bohne")));
      }
    }
    catch (Exception e)
    {
      throw new IllegalStateException(e);
    }
  }

  private static UmsLine buchung(int tag, String betrag, String zweck, String name)
  {
    UmsLine b = new UmsLine();
    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.OCTOBER, tag, 0, 0, 0);
    b.bdate = c.getTime();
    b.valuta = c.getTime();
    b.value = new Value(betrag, "EUR");
    b.text = "SEPA";
    b.usage.add(zweck);
    b.other = new Konto();
    b.other.name = name;
    return b;
  }

  @TestConfiguration
  static class Testkonfiguration
  {
    @Bean
    Konfiguration konfiguration()
    {
      Properties p = new Properties();
      p.setProperty("fints.blz", "12345678");
      p.setProperty("fints.benutzer", "bank");
      p.setProperty("fints.iban", "DE00123456780000000000");
      p.setProperty("db.datei", VERZEICHNIS.resolve("umsaetze.db").toString());
      p.setProperty("intern.verzeichnis", VERZEICHNIS.resolve("intern").toString());
      p.setProperty("web.benutzer", "reisender");
      p.setProperty("web.passwort", "ein-langes-passwort");
      p.setProperty("abruf.intervall", "aus");
      Konfiguration k = Konfiguration.aus(p, Map.of());
      k.pruefeServerbetrieb();
      return k;
    }

    @Bean
    UmsatzAbrufAnwendung.Aufruf aufruf()
    {
      return new UmsatzAbrufAnwendung.Aufruf(Path.of("fints.properties"), false);
    }
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  WebInteraktion interaktion;

  @Autowired
  Betriebszustand zustand;

  @Test
  void ohneAnmeldungZumLogin() throws Exception
  {
    mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost/login"));
    mvc.perform(get("/buchungen")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/login")).andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", WebSicherheit.CSP))
        .andExpect(header().string("X-Frame-Options", "DENY"));
  }

  @Test
  void anmeldung() throws Exception
  {
    mvc.perform(formLogin().user("reisender").password("ein-langes-passwort"))
        .andExpect(redirectedUrl("/"));
    mvc.perform(formLogin().user("reisender").password("falsch"))
        .andExpect(redirectedUrl("/login?fehler"));
  }

  @Test
  void uebersicht() throws Exception
  {
    mvc.perform(get("/").with(user("reisender")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Jetzt abrufen")))
        .andExpect(content().string(containsString("kein automatischer Abruf")))
        .andExpect(content().string(containsString("nicht eingerichtet")))
        .andExpect(content().string(not(containsString("Autorisierung erforderlich"))))
        .andExpect(content().string(containsString("DE00123456780000000000")));
  }

  @Test
  void aktionenNurMitCsrfToken() throws Exception
  {
    mvc.perform(post("/abruf").with(user("reisender"))).andExpect(status().isForbidden());
    mvc.perform(post("/freigabe").param("id", "x").param("wert", "1").with(user("reisender")))
        .andExpect(status().isForbidden());
  }

  @Test
  void tanUeberDieWeboberflaeche() throws Exception
  {
    CompletableFuture<String> tan = CompletableFuture.supplyAsync(() -> interaktion.tan("Bitte TAN aus der App eingeben", null, null));
    WebInteraktion.Anfrage a = null;
    for (int i = 0; i < 200 && a == null; i++)
    {
      Thread.sleep(10);
      a = interaktion.anfrage();
    }

    mvc.perform(get("/").with(user("reisender")))
        .andExpect(content().string(containsString("Bitte TAN aus der App eingeben")))
        .andExpect(content().string(containsString("name=\"wert\"")));

    mvc.perform(post("/freigabe").param("id", a.id()).param("wert", " 482913 ").with(user("reisender")).with(csrf()))
        .andExpect(redirectedUrl("/"));
    assertEquals("482913", tan.get(2, TimeUnit.SECONDS));

    // Eine bereits beantwortete Anfrage lässt sich nicht erneut beantworten
    mvc.perform(post("/freigabe").param("id", a.id()).param("wert", "000000").with(user("reisender")).with(csrf()))
        .andExpect(redirectedUrl("/"));
  }

  @Test
  void ausgesetztZeigtAutorisierung() throws Exception
  {
    zustand.aussetzen("Keine Freigabe in der App innerhalb von 10 Minuten.");
    try
    {
      mvc.perform(get("/").with(user("reisender")))
          .andExpect(content().string(containsString("Autorisierung erforderlich")))
          .andExpect(content().string(containsString("Keine Freigabe in der App innerhalb von 10 Minuten.")))
          .andExpect(content().string(containsString("Jetzt autorisieren")))
          .andExpect(content().string(containsString("ausgesetzt – Autorisierung erforderlich")));
    }
    finally
    {
      zustand.fortsetzen();
    }
  }

  @Test
  void testMailOhneNotfallAdresse() throws Exception
  {
    mvc.perform(post("/mail-test").with(user("reisender")).with(csrf()))
        .andExpect(redirectedUrl("/"))
        .andExpect(flash().attribute("fehler", containsString("Keine Notfall-Adresse")));
  }

  @Test
  void buchungenAusDatenbank() throws Exception
  {
    mvc.perform(get("/buchungen").with(user("reisender")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Arbeitgeber GmbH")))
        .andExpect(content().string(containsString("2.500,00 EUR")))
        .andExpect(content().string(containsString("-89,90 EUR")))
        // HTML aus Bankdaten wird maskiert
        .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
        .andExpect(content().string(not(containsString("<script>alert"))))
        .andExpect(content().string(containsString("Café Bohne")))
        .andExpect(content().string(containsString("Quelle: Datenbank")));

    mvc.perform(get("/buchungen").param("suche", "stadtwerke").with(user("reisender")))
        .andExpect(content().string(containsString("Stadtwerke")))
        .andExpect(content().string(not(containsString("Arbeitgeber GmbH"))));

    mvc.perform(get("/buchungen").param("von", "2026-10-10").param("bis", "2026-10-16").with(user("reisender")))
        .andExpect(content().string(containsString("Rechnung 4711")))
        .andExpect(content().string(not(containsString("Café Bohne"))));
  }

  @Test
  void statusFuerAutomatischeAktualisierung() throws Exception
  {
    mvc.perform(get("/status").with(user("reisender")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"zustand\"")));
  }
}

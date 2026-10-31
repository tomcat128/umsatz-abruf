package de.fehrenbacher.fints;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Export-Schnittstelle mit Basic Auth (EXPORT_BENUTZER/EXPORT_PASSWORT).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportBasicAuthTest
{
  @TestConfiguration
  static class Testkonfiguration
  {
    @Bean
    Konfiguration konfiguration() throws Exception
    {
      Path verzeichnis = Files.createTempDirectory("umsatz-export");
      Properties p = new Properties();
      p.setProperty("fints.blz", "12345678");
      p.setProperty("fints.benutzer", "bank");
      p.setProperty("fints.iban", "DE00123456780000000000");
      p.setProperty("db.datei", verzeichnis.resolve("umsaetze.db").toString());
      p.setProperty("intern.verzeichnis", verzeichnis.resolve("intern").toString());
      p.setProperty("web.benutzer", "reisender");
      p.setProperty("web.passwort", "ein-langes-passwort");
      p.setProperty("abruf.intervall", "aus");
      p.setProperty("export.benutzer", "buchhaltung");
      p.setProperty("export.passwort", "export-geheim");
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

  @Test
  void ohneZugangsdatenAbgewiesen() throws Exception
  {
    mvc.perform(get("/api/buchungen.json"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", containsString("Basic realm=\"Umsatz-Abruf Export\"")));
    mvc.perform(get("/api/buchungen.csv").with(httpBasic("buchhaltung", "falsch")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void mitZugangsdatenErlaubt() throws Exception
  {
    mvc.perform(get("/api/buchungen.json").with(httpBasic("buchhaltung", "export-geheim")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"anzahl\" : 0")));
    mvc.perform(get("/api/buchungen.xml").with(httpBasic("buchhaltung", "export-geheim")))
        .andExpect(status().isOk());
  }

  @Test
  void angemeldeteSitzungDarfExportieren() throws Exception
  {
    mvc.perform(get("/api/buchungen.csv").with(user("reisender"))).andExpect(status().isOk());
  }

  @Test
  void exportZugangsdatenGeltenNichtFuerDieWeboberflaeche() throws Exception
  {
    mvc.perform(formLogin().user("buchhaltung").password("export-geheim")).andExpect(redirectedUrl("/login?fehler"));
    mvc.perform(get("/").with(httpBasic("buchhaltung", "export-geheim"))).andExpect(status().is3xxRedirection());
    // Und umgekehrt: Basic Auth mit den Web-Zugangsdaten gilt nicht für den Export
    mvc.perform(get("/api/buchungen.json").with(httpBasic("reisender", "ein-langes-passwort")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nurLesend() throws Exception
  {
    mvc.perform(post("/api/buchungen.json").with(httpBasic("buchhaltung", "export-geheim")))
        .andExpect(status().isForbidden());
  }

  @Test
  void sperreNachFehlversuchen() throws Exception
  {
    for (int i = 0; i < LoginSperre.MAX_FEHLVERSUCHE; i++)
      mvc.perform(get("/api/buchungen.json").with(httpBasic("buchhaltung", "rate-" + i))
          .with(r -> { r.setRemoteAddr("10.9.8.7"); return r; }))
          .andExpect(status().isUnauthorized());
    // Jetzt gesperrt - auch mit richtigem Passwort
    mvc.perform(get("/api/buchungen.json").with(httpBasic("buchhaltung", "export-geheim"))
        .with(r -> { r.setRemoteAddr("10.9.8.7"); return r; }))
        .andExpect(status().is(429));
    // Andere Adressen sind nicht betroffen
    mvc.perform(get("/api/buchungen.json").with(httpBasic("buchhaltung", "export-geheim"))
        .with(r -> { r.setRemoteAddr("10.1.1.1"); return r; }))
        .andExpect(status().isOk());
  }
}

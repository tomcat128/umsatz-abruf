package de.fehrenbacher.fints;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anmeldung an der Weboberfläche: genau ein Benutzer, Name und Passwort aus der Konfiguration
 * (web.benutzer, web.passwort). Das Passwort darf im Klartext oder als Hash mit Präfix
 * angegeben werden, z.B. {@code {bcrypt}$2a$10$...}.
 *
 * Die Export-Schnittstelle /api/** hat eine eigene Filterkette: offen, oder - wenn export.benutzer
 * und export.passwort gesetzt sind - mit HTTP Basic Auth. Die Export-Zugangsdaten gelten nur dort;
 * eine angemeldete Sitzung der Weboberfläche darf den Export ebenfalls abrufen.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication
class WebSicherheit
{
  /** Strikte Content-Security-Policy: nur eigene Skripte, Styles und Bilder. */
  static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self';"
      + " form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

  static final String EXPORT_REALM = "Umsatz-Abruf Export";

  @Bean
  PasswordEncoder passwordEncoder()
  {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /** Der Benutzer der Weboberfläche - nur dieser kann sich über das Formular anmelden. */
  @Bean
  UserDetailsService benutzer(Konfiguration konfig)
  {
    return new InMemoryUserDetailsManager(User.withUsername(konfig.webBenutzer())
        .password(hash(konfig.webPasswort())).roles("BENUTZER").build());
  }

  /** Export-Schnittstelle: eigene Kette mit Vorrang, nur lesende Aufrufe. */
  @Bean
  @Order(1)
  SecurityFilterChain export(HttpSecurity http, Konfiguration konfig, LoginSperre sperre, PasswordEncoder encoder,
      AuthenticationEventPublisher ereignisse) throws Exception
  {
    http
        .securityMatcher("/api/**")
        // Nur GET ist erlaubt - es gibt nichts zu verändern, also auch kein CSRF-Risiko
        .csrf(c -> c.disable())
        .authorizeHttpRequests(a ->
        {
          if (konfig.exportGeschuetzt())
            a.requestMatchers(HttpMethod.GET, "/api/**").authenticated();
          else
            a.requestMatchers(HttpMethod.GET, "/api/**").permitAll();
          a.anyRequest().denyAll();
        })
        .headers(WebSicherheit::kopfzeilen);

    if (konfig.exportGeschuetzt())
    {
      DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(
          User.withUsername(konfig.exportBenutzer()).password(hash(konfig.exportPasswort())).roles("EXPORT").build()));
      provider.setPasswordEncoder(encoder);
      ProviderManager manager = new ProviderManager(provider);
      // Fehlversuche zählen für die Sperre wie beim Formular-Login
      manager.setAuthenticationEventPublisher(ereignisse);
      http
          .authenticationManager(manager)
          .httpBasic(b -> b.realmName(EXPORT_REALM))
          .addFilterBefore(new SperrFilter(sperre, true), BasicAuthenticationFilter.class);
    }
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain sicherheit(HttpSecurity http, LoginSperre sperre) throws Exception
  {
    http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/login", "/app.css", "/favicon.svg", "/error").permitAll()
            .anyRequest().authenticated())
        .formLogin(f -> f
            .loginPage("/login")
            .defaultSuccessUrl("/", true)
            .failureUrl("/login?fehler"))
        .logout(l -> l.logoutSuccessUrl("/login?abgemeldet"))
        .headers(WebSicherheit::kopfzeilen)
        .sessionManagement(s -> s.sessionFixation(f -> f.newSession()))
        .addFilterBefore(new SperrFilter(sperre, false), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  private static void kopfzeilen(HeadersConfigurer<HttpSecurity> h)
  {
    h.contentSecurityPolicy(c -> c.policyDirectives(CSP))
        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER));
  }

  /** Klartext beim Start hashen, damit er nicht im Speicher des Benutzerobjekts liegt. */
  private static String hash(String passwort)
  {
    return passwort.startsWith("{") ? passwort : "{bcrypt}" + new BCryptPasswordEncoder().encode(passwort);
  }

  /** Weist Anmeldeversuche ab, solange die Adresse gesperrt ist. */
  static final class SperrFilter extends OncePerRequestFilter
  {
    private final LoginSperre sperre;
    private final boolean basicAuth;

    /**
     * @param basicAuth {@code true}: Anfragen mit Basic-Auth-Kopfzeile prüfen (Export),
     *                  {@code false}: das Anmeldeformular (POST /login).
     */
    SperrFilter(LoginSperre sperre, boolean basicAuth)
    {
      this.sperre = sperre;
      this.basicAuth = basicAuth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException
    {
      if (anmeldeversuch(req) && sperre.gesperrt(req.getRemoteAddr()))
      {
        if (basicAuth)
        {
          res.setStatus(429);
          res.setContentType("text/plain;charset=UTF-8");
          res.getWriter().println("Zu viele Fehlversuche - bitte später erneut versuchen.");
        }
        else
        {
          res.sendRedirect(req.getContextPath() + "/login?gesperrt");
        }
        return;
      }
      chain.doFilter(req, res);
    }

    private boolean anmeldeversuch(HttpServletRequest req)
    {
      if (basicAuth)
        return req.getHeader(HttpHeaders.AUTHORIZATION) != null;
      return "POST".equals(req.getMethod()) && "/login".equals(req.getServletPath());
    }
  }
}

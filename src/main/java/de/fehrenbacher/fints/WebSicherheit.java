package de.fehrenbacher.fints;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anmeldung an der Weboberfläche: genau ein Benutzer, Name und Passwort aus der Konfiguration
 * (web.benutzer, web.passwort). Das Passwort darf im Klartext oder als Hash mit Präfix
 * angegeben werden, z.B. {@code {bcrypt}$2a$10$...}.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication
class WebSicherheit
{
  /** Strikte Content-Security-Policy: nur eigene Skripte, Styles und Bilder. */
  static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self';"
      + " form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

  @Bean
  PasswordEncoder passwordEncoder()
  {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService benutzer(Konfiguration konfig)
  {
    String passwort = konfig.webPasswort();
    // Klartext beim Start hashen, damit er nicht im Speicher des Benutzerobjekts liegt
    String hash = passwort.startsWith("{") ? passwort : "{bcrypt}" + new BCryptPasswordEncoder().encode(passwort);
    return new InMemoryUserDetailsManager(User.withUsername(konfig.webBenutzer()).password(hash).roles("BENUTZER").build());
  }

  @Bean
  SecurityFilterChain sicherheit(HttpSecurity http, LoginSperre sperre) throws Exception
  {
    http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/login", "/app.css", "/favicon.svg", "/error").permitAll()
            // Export-Schnittstelle für externe Anwendungen - vorerst ohne Anmeldung
            .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
            .anyRequest().authenticated())
        .formLogin(f -> f
            .loginPage("/login")
            .defaultSuccessUrl("/", true)
            .failureUrl("/login?fehler"))
        .logout(l -> l.logoutSuccessUrl("/login?abgemeldet"))
        .headers(h -> h
            .contentSecurityPolicy(c -> c.policyDirectives(CSP))
            .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .sessionManagement(s -> s.sessionFixation(f -> f.newSession()))
        .addFilterBefore(new SperrFilter(sperre), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /** Weist Anmeldeversuche ab, solange die Adresse gesperrt ist. */
  static final class SperrFilter extends OncePerRequestFilter
  {
    private final LoginSperre sperre;

    SperrFilter(LoginSperre sperre)
    {
      this.sperre = sperre;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException
    {
      if ("POST".equals(req.getMethod()) && "/login".equals(req.getServletPath()) && sperre.gesperrt(req.getRemoteAddr()))
      {
        res.sendRedirect(req.getContextPath() + "/login?gesperrt");
        return;
      }
      chain.doFilter(req, res);
    }
  }
}

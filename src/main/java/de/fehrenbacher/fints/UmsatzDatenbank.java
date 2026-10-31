package de.fehrenbacher.fints;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;

/**
 * Speichert die Buchungen in einer SQLite-Datei (Schema: schema.sql).
 *
 * Bereits gespeicherte Buchungen werden wie bei der CSV-Datei erkannt: Der Schlüssel ist ein
 * SHA-256 über die fachlichen Merkmale (die CSV-Zeile). Identische Buchungen innerhalb eines
 * Abrufs werden über die Spalte "vorkommen" durchgezählt, so dass z.B. zwei gleiche
 * Kartenzahlungen am selben Tag beide gespeichert werden, bei erneutem Abruf aber keine doppelt.
 *
 * Beträge stehen in ganzen Cent, Datumswerte als Text JJJJ-MM-TT.
 */
final class UmsatzDatenbank implements AutoCloseable
{
  private static final String SCHEMA = "schema.sql";
  private static final int IN_BLOCK = 500;

  private final Connection con;

  private UmsatzDatenbank(Connection con)
  {
    this.con = con;
  }

  /**
   * Öffnet die Datenbankdatei (legt sie bei Bedarf an) und legt fehlende Tabellen an. Wird vor
   * dem Bankabruf aufgerufen, damit ein Datenbankfehler nicht erst nach der TAN-Freigabe auffällt.
   * @param datei Pfad der SQLite-Datei.
   * @return die geöffnete Datenbank.
   * @throws SQLException bei Fehlern beim Öffnen oder Anlegen der Tabellen.
   */
  static UmsatzDatenbank oeffnen(Path datei) throws SQLException
  {
    Path absolut = datei.toAbsolutePath();
    try
    {
      if (absolut.getParent() != null)
        Files.createDirectories(absolut.getParent());
    }
    catch (IOException e)
    {
      throw new SQLException("Verzeichnis für die Datenbank " + absolut + " kann nicht angelegt werden", e);
    }

    Connection con;
    try
    {
      con = DriverManager.getConnection("jdbc:sqlite:" + absolut);
    }
    catch (SQLException e)
    {
      throw new SQLException("Datenbank " + absolut + " kann nicht geöffnet werden", e);
    }
    try
    {
      try (Statement st = con.createStatement())
      {
        // WAL: Die Weboberfläche kann lesen, während ein Abruf schreibt
        st.execute("PRAGMA journal_mode = WAL");
        st.execute("PRAGMA foreign_keys = ON");
        st.execute("PRAGMA busy_timeout = 10000");
      }
      legeSchemaAn(con);
      return new UmsatzDatenbank(con);
    }
    catch (SQLException | RuntimeException e)
    {
      con.close();
      throw e;
    }
  }

  /**
   * Speichert die Buchungen, die noch nicht in der Datenbank stehen, und protokolliert den Abruf.
   * @return die neu gespeicherten Buchungen in der gelieferten Reihenfolge.
   * @throws SQLException bei Datenbankfehlern; es wird dann nichts gespeichert.
   */
  List<UmsLine> speichere(Konto konto, LocalDate von, LocalDate bis, String format, List<UmsLine> buchungen)
      throws SQLException
  {
    con.setAutoCommit(false);
    try
    {
      long kontoId = speichereKonto(konto);

      // Schlüssel und laufende Nummer je identischer Buchung bestimmen
      List<String> schluessel = new ArrayList<>(buchungen.size());
      List<Integer> vorkommen = new ArrayList<>(buchungen.size());
      Map<String,Integer> zaehler = new HashMap<>();
      for (UmsLine b : buchungen)
      {
        String s = schluessel(b);
        schluessel.add(s);
        vorkommen.add(zaehler.merge(s, 1, Integer::sum));
      }
      Set<String> vorhanden = vorhandene(kontoId, new HashSet<>(schluessel));

      long abrufId = protokolliereAbruf(kontoId, von, bis, format, buchungen.size());

      List<UmsLine> neu = new ArrayList<>();
      try (PreparedStatement ps = con.prepareStatement(INSERT_BUCHUNG))
      {
        for (int i = 0; i < buchungen.size(); i++)
        {
          if (vorhanden.contains(schluessel.get(i) + "#" + vorkommen.get(i)))
            continue;
          setzeBuchung(ps, kontoId, abrufId, schluessel.get(i), vorkommen.get(i), format, buchungen.get(i));
          ps.addBatch();
          neu.add(buchungen.get(i));
        }
        if (!neu.isEmpty())
          ps.executeBatch();
      }

      try (PreparedStatement ps = con.prepareStatement("UPDATE abruf SET anzahl_neu = ? WHERE id = ?"))
      {
        ps.setInt(1, neu.size());
        ps.setLong(2, abrufId);
        ps.executeUpdate();
      }

      con.commit();
      return neu;
    }
    catch (SQLException | RuntimeException e)
    {
      con.rollback();
      throw e;
    }
    finally
    {
      con.setAutoCommit(true);
    }
  }

  /**
   * Sucht gespeicherte Buchungen eines Kontos für die Weboberfläche, neueste zuerst.
   * @param iban das eigene Konto.
   */
  BuchungsAnsicht.Seite suche(String iban, BuchungsAnsicht.Filter filter) throws SQLException
  {
    StringBuilder where = new StringBuilder(" FROM buchung b JOIN konto k ON k.id = b.konto_id WHERE k.iban = ?");
    List<Object> parameter = new ArrayList<>();
    parameter.add(Konfiguration.normalisiereIban(iban));
    if (filter.von() != null)
    {
      where.append(" AND b.buchungsdatum >= ?");
      parameter.add(filter.von().toString());
    }
    if (filter.bis() != null)
    {
      where.append(" AND b.buchungsdatum <= ?");
      parameter.add(filter.bis().toString());
    }
    if (filter.suche() != null && !filter.suche().isBlank())
    {
      // Platzhalter im Suchtext wörtlich nehmen. SQLite vergleicht bei LIKE nur ASCII-Buchstaben
      // ohne Groß-/Kleinschreibung - Umlaute müssen in der richtigen Schreibweise gesucht werden.
      String muster = "%" + filter.suche().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      where.append(" AND (b.buchungstext LIKE ? ESCAPE '\\' OR b.gegen_name LIKE ? ESCAPE '\\'"
          + " OR b.gegen_iban LIKE ? ESCAPE '\\' OR b.gegen_kontonummer LIKE ? ESCAPE '\\'"
          + " OR b.verwendungszweck LIKE ? ESCAPE '\\')");
      for (int i = 0; i < 5; i++)
        parameter.add(muster);
    }

    int gesamt;
    BigDecimal einnahmen;
    BigDecimal ausgaben;
    try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*),"
        + " COALESCE(SUM(CASE WHEN b.betrag_cent > 0 THEN b.betrag_cent ELSE 0 END), 0),"
        + " COALESCE(SUM(CASE WHEN b.betrag_cent < 0 THEN b.betrag_cent ELSE 0 END), 0)" + where))
    {
      setze(ps, parameter);
      try (ResultSet rs = ps.executeQuery())
      {
        rs.next();
        gesamt = rs.getInt(1);
        einnahmen = BigDecimal.valueOf(rs.getLong(2), 2);
        ausgaben = BigDecimal.valueOf(rs.getLong(3), 2);
      }
    }

    int seiten = Math.max(1, (gesamt + BuchungsAnsicht.PRO_SEITE - 1) / BuchungsAnsicht.PRO_SEITE);
    int seite = Math.min(Math.max(1, filter.seite()), seiten);
    List<BuchungsAnsicht.Zeile> zeilen = new ArrayList<>();
    try (PreparedStatement ps = con.prepareStatement("SELECT b.buchungsdatum, b.valuta, b.betrag_cent, b.waehrung,"
        + " b.buchungstext, b.gegen_name, COALESCE(b.gegen_iban, b.gegen_kontonummer), b.verwendungszweck, b.storno"
        + where + " ORDER BY b.buchungsdatum DESC, b.id DESC LIMIT ? OFFSET ?"))
    {
      List<Object> p = new ArrayList<>(parameter);
      p.add(BuchungsAnsicht.PRO_SEITE);
      p.add((seite - 1) * BuchungsAnsicht.PRO_SEITE);
      setze(ps, p);
      try (ResultSet rs = ps.executeQuery())
      {
        while (rs.next())
          zeilen.add(new BuchungsAnsicht.Zeile(datum(rs.getString(1)), datum(rs.getString(2)),
              BigDecimal.valueOf(rs.getLong(3), 2), rs.getString(4), rs.getString(5), rs.getString(6),
              rs.getString(7), rs.getString(8), rs.getInt(9) != 0));
      }
    }
    return new BuchungsAnsicht.Seite(zeilen, gesamt, seite, seiten, einnahmen, ausgaben, "Datenbank");
  }

  @Override
  public void close() throws SQLException
  {
    con.close();
  }

  /**
   * @return SHA-256 (hex) über die fachlichen Merkmale der Buchung - dieselben wie in der CSV-Datei.
   */
  static String schluessel(UmsLine b)
  {
    try
    {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(String.join("\u001F", UmsatzDatei.zeile(b)).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    }
    catch (NoSuchAlgorithmException e)
    {
      throw new IllegalStateException(e);
    }
  }

  /** @return der Betrag in ganzen Cent (kaufmännisch gerundet) oder {@code null}. */
  static Long cent(BigDecimal betrag)
  {
    return betrag == null ? null : betrag.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact();
  }

  private static void legeSchemaAn(Connection con) throws SQLException
  {
    String sql;
    try (InputStream in = UmsatzDatenbank.class.getResourceAsStream(SCHEMA))
    {
      if (in == null)
        throw new IllegalStateException(SCHEMA + " fehlt im Programm");
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    catch (IOException e)
    {
      throw new IllegalStateException(SCHEMA + " konnte nicht gelesen werden", e);
    }

    sql = sql.replaceAll("--[^\\n]*", "");
    try (Statement st = con.createStatement())
    {
      for (String anweisung : sql.split(";"))
      {
        if (!anweisung.isBlank())
          st.execute(anweisung);
      }
    }
  }

  private long speichereKonto(Konto k) throws SQLException
  {
    String iban = Konfiguration.normalisiereIban(k.iban);
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO konto (iban, bic, blz, kontonummer, inhaber, kontoart, waehrung) VALUES (?, ?, ?, ?, ?, ?, ?)"
        + " ON CONFLICT (iban) DO UPDATE SET bic = excluded.bic, blz = excluded.blz,"
        + " kontonummer = excluded.kontonummer, inhaber = excluded.inhaber, kontoart = excluded.kontoart,"
        + " waehrung = excluded.waehrung, aktualisiert_am = CURRENT_TIMESTAMP RETURNING id"))
    {
      ps.setString(1, iban);
      ps.setString(2, leerAlsNull(k.bic));
      ps.setString(3, leerAlsNull(k.blz));
      ps.setString(4, leerAlsNull(k.number));
      ps.setString(5, leerAlsNull(name(k)));
      ps.setString(6, leerAlsNull(k.type));
      ps.setString(7, leerAlsNull(k.curr));
      try (ResultSet rs = ps.executeQuery())
      {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * @return die bereits gespeicherten Kombinationen "schluessel#vorkommen" zu den Schlüsseln.
   */
  private Set<String> vorhandene(long kontoId, Set<String> schluessel) throws SQLException
  {
    Set<String> vorhanden = new HashSet<>();
    List<String> liste = new ArrayList<>(schluessel);
    for (int start = 0; start < liste.size(); start += IN_BLOCK)
    {
      List<String> block = liste.subList(start, Math.min(start + IN_BLOCK, liste.size()));
      String platzhalter = String.join(",", Collections.nCopies(block.size(), "?"));
      try (PreparedStatement ps = con.prepareStatement(
          "SELECT schluessel, vorkommen FROM buchung WHERE konto_id = ? AND schluessel IN (" + platzhalter + ")"))
      {
        ps.setLong(1, kontoId);
        for (int i = 0; i < block.size(); i++)
          ps.setString(i + 2, block.get(i));
        try (ResultSet rs = ps.executeQuery())
        {
          while (rs.next())
            vorhanden.add(rs.getString(1) + "#" + rs.getInt(2));
        }
      }
    }
    return vorhanden;
  }

  private long protokolliereAbruf(long kontoId, LocalDate von, LocalDate bis, String format, int anzahl)
      throws SQLException
  {
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO abruf (konto_id, von, bis, format, anzahl_geliefert, anzahl_neu) VALUES (?, ?, ?, ?, ?, 0)"
        + " RETURNING id"))
    {
      ps.setLong(1, kontoId);
      ps.setString(2, von.toString());
      ps.setString(3, bis.toString());
      ps.setString(4, format);
      ps.setInt(5, anzahl);
      try (ResultSet rs = ps.executeQuery())
      {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static final String INSERT_BUCHUNG = "INSERT INTO buchung ("
      + "konto_id, abruf_id, schluessel, vorkommen, buchungsdatum, valuta, betrag_cent, waehrung, saldo_cent,"
      + " ursprungsbetrag_cent, ursprungswaehrung, gebuehren_cent, gebuehren_waehrung, storno,"
      + " buchungstext, gv_code, textschluessel_zusatz, primanota, verwendungszweck, zusatzinfo,"
      + " gegen_name, gegen_iban, gegen_bic, gegen_kontonummer, gegen_blz, glaeubiger_id,"
      + " end_to_end_id, mandatsreferenz, purpose_code, kundenreferenz, bankreferenz, bank_buchungs_id,"
      + " sepa, format"
      + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static void setzeBuchung(PreparedStatement ps, long kontoId, long abrufId, String schluessel,
      int vorkommen, String format, UmsLine b) throws SQLException
  {
    Konto g = b.other;
    int i = 0;
    ps.setLong(++i, kontoId);
    ps.setLong(++i, abrufId);
    ps.setString(++i, schluessel);
    ps.setInt(++i, vorkommen);
    ps.setString(++i, datum(b.bdate));
    ps.setString(++i, datum(b.valuta));
    Long betrag = cent(betrag(b.value));
    ps.setLong(++i, betrag != null ? betrag : 0L);
    String waehrung = waehrung(b.value);
    ps.setString(++i, waehrung != null ? waehrung : "");
    ps.setObject(++i, b.saldo != null ? cent(betrag(b.saldo.value)) : null);
    ps.setObject(++i, cent(betrag(b.orig_value)));
    ps.setString(++i, waehrung(b.orig_value));
    ps.setObject(++i, cent(betrag(b.charge_value)));
    ps.setString(++i, waehrung(b.charge_value));
    ps.setInt(++i, b.isStorno ? 1 : 0);
    ps.setString(++i, leerAlsNull(b.text));
    ps.setString(++i, leerAlsNull(b.gvcode));
    ps.setString(++i, leerAlsNull(b.addkey));
    ps.setString(++i, leerAlsNull(b.primanota));
    ps.setString(++i, leerAlsNull(UmsatzDatei.verwendungszweck(b)));
    ps.setString(++i, leerAlsNull(b.additional));
    ps.setString(++i, g != null ? leerAlsNull(name(g)) : null);
    ps.setString(++i, g != null && g.iban != null && !g.iban.isBlank() ? Konfiguration.normalisiereIban(g.iban) : null);
    ps.setString(++i, g != null ? leerAlsNull(g.bic) : null);
    ps.setString(++i, g != null ? leerAlsNull(g.number) : null);
    ps.setString(++i, g != null ? leerAlsNull(g.blz) : null);
    ps.setString(++i, g != null ? leerAlsNull(g.creditorid) : null);
    ps.setString(++i, leerAlsNull(b.endToEndId));
    ps.setString(++i, leerAlsNull(b.mandateId));
    ps.setString(++i, leerAlsNull(b.purposecode));
    ps.setString(++i, leerAlsNull(b.customerref));
    ps.setString(++i, leerAlsNull(b.instref));
    ps.setString(++i, leerAlsNull(b.id));
    ps.setInt(++i, b.isSepa ? 1 : 0);
    ps.setString(++i, format);
  }

  private static void setze(PreparedStatement ps, List<Object> parameter) throws SQLException
  {
    for (int i = 0; i < parameter.size(); i++)
      ps.setObject(i + 1, parameter.get(i));
  }

  private static String datum(Date d)
  {
    return d == null ? null : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
  }

  private static LocalDate datum(String s)
  {
    return s == null || s.isBlank() ? null : LocalDate.parse(s);
  }

  private static BigDecimal betrag(Value v)
  {
    return v == null ? null : v.getBigDecimalValue();
  }

  private static String waehrung(Value v)
  {
    return v == null ? null : leerAlsNull(v.getCurr());
  }

  private static String name(Konto k)
  {
    String n1 = k.name == null ? "" : k.name.trim();
    String n2 = k.name2 == null ? "" : k.name2.trim();
    return n1.isEmpty() || n2.isEmpty() ? n1 + n2 : n1 + " " + n2;
  }

  static String leerAlsNull(String s)
  {
    if (s == null || s.isBlank())
      return null;
    return s.trim();
  }
}

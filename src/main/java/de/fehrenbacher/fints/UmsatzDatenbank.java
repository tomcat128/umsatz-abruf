package de.fehrenbacher.fints;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
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
import java.util.Properties;
import java.util.Set;

import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Value;

/**
 * Speichert die Buchungen in einer MariaDB- bzw. MySQL-Datenbank (Schema: schema.sql).
 *
 * Bereits gespeicherte Buchungen werden wie bei der CSV-Datei erkannt: Der Schlüssel ist ein
 * SHA-256 über die fachlichen Merkmale (die CSV-Zeile). Identische Buchungen innerhalb eines
 * Abrufs werden über die Spalte "vorkommen" durchgezählt, so dass z.B. zwei gleiche
 * Kartenzahlungen am selben Tag beide gespeichert werden, bei erneutem Abruf aber keine doppelt.
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
   * Verbindet sich mit der Datenbank und legt fehlende Tabellen an. Wird vor dem Bankabruf
   * aufgerufen, damit ein Datenbankfehler nicht erst nach der TAN-Freigabe auffällt.
   * @param url JDBC-URL (jdbc:mariadb://... oder jdbc:mysql://...).
   * @param benutzer Datenbankbenutzer oder {@code null}.
   * @param passwort Passwort oder {@code null}.
   * @return die geöffnete Datenbank.
   * @throws SQLException bei Verbindungs- oder Schemafehlern.
   */
  static UmsatzDatenbank verbinden(String url, String benutzer, String passwort) throws SQLException
  {
    // Der MariaDB-Treiber akzeptiert "jdbc:mysql:" nur mit Zusatzparameter
    String jdbcUrl = url.startsWith("jdbc:mysql:") ? "jdbc:mariadb:" + url.substring("jdbc:mysql:".length()) : url;

    Properties props = new Properties();
    if (benutzer != null)
      props.setProperty("user", benutzer);
    if (passwort != null)
      props.setProperty("password", passwort);

    Connection con;
    try
    {
      con = DriverManager.getConnection(jdbcUrl, props);
    }
    catch (SQLException e)
    {
      throw new SQLException("Verbindung zur Datenbank " + ohneParameter(url) + " fehlgeschlagen", e);
    }
    try
    {
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
      parameter.add(filter.von());
    }
    if (filter.bis() != null)
    {
      where.append(" AND b.buchungsdatum <= ?");
      parameter.add(filter.bis());
    }
    if (filter.suche() != null && !filter.suche().isBlank())
    {
      // Platzhalter im Suchtext wörtlich nehmen; Vergleich ohne Groß-/Kleinschreibung (Collation)
      String muster = "%" + filter.suche().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      where.append(" AND (b.buchungstext LIKE ? OR b.gegen_name LIKE ? OR b.gegen_iban LIKE ?"
          + " OR b.gegen_kontonummer LIKE ? OR b.verwendungszweck LIKE ?)");
      for (int i = 0; i < 5; i++)
        parameter.add(muster);
    }

    int gesamt;
    BigDecimal einnahmen;
    BigDecimal ausgaben;
    try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*),"
        + " COALESCE(SUM(CASE WHEN b.betrag > 0 THEN b.betrag ELSE 0 END), 0),"
        + " COALESCE(SUM(CASE WHEN b.betrag < 0 THEN b.betrag ELSE 0 END), 0)" + where))
    {
      setze(ps, parameter);
      try (ResultSet rs = ps.executeQuery())
      {
        rs.next();
        gesamt = rs.getInt(1);
        einnahmen = rs.getBigDecimal(2);
        ausgaben = rs.getBigDecimal(3);
      }
    }

    int seiten = Math.max(1, (gesamt + BuchungsAnsicht.PRO_SEITE - 1) / BuchungsAnsicht.PRO_SEITE);
    int seite = Math.min(Math.max(1, filter.seite()), seiten);
    List<BuchungsAnsicht.Zeile> zeilen = new ArrayList<>();
    try (PreparedStatement ps = con.prepareStatement("SELECT b.buchungsdatum, b.valuta, b.betrag, b.waehrung,"
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
          zeilen.add(new BuchungsAnsicht.Zeile(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
              rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
              rs.getString(8), rs.getBoolean(9)));
      }
    }
    String quelle = "Datenbank " + ohneParameter(con.getMetaData().getURL());
    return new BuchungsAnsicht.Seite(zeilen, gesamt, seite, seiten, einnahmen, ausgaben, quelle);
  }

  private static void setze(PreparedStatement ps, List<Object> parameter) throws SQLException
  {
    for (int i = 0; i < parameter.size(); i++)
      ps.setObject(i + 1, parameter.get(i));
  }

  /** URL ohne Parameter - dort könnte ein Passwort stehen. */
  static String ohneParameter(String url)
  {
    int frage = url == null ? -1 : url.indexOf('?');
    return frage < 0 ? url : url.substring(0, frage);
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

    sql = sql.replaceAll("(?m)^\\s*--.*$", "");
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
        + " ON DUPLICATE KEY UPDATE bic = VALUES(bic), blz = VALUES(blz), kontonummer = VALUES(kontonummer),"
        + " inhaber = VALUES(inhaber), kontoart = VALUES(kontoart), waehrung = VALUES(waehrung)"))
    {
      ps.setString(1, kurz(iban, 34));
      ps.setString(2, kurz(k.bic, 11));
      ps.setString(3, kurz(k.blz, 30));
      ps.setString(4, kurz(k.number, 30));
      ps.setString(5, kurz(name(k), 255));
      ps.setString(6, kurz(k.type, 255));
      ps.setString(7, kurz(k.curr, 3));
      ps.executeUpdate();
    }
    try (PreparedStatement ps = con.prepareStatement("SELECT id FROM konto WHERE iban = ?"))
    {
      ps.setString(1, kurz(iban, 34));
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
        "INSERT INTO abruf (konto_id, von, bis, format, anzahl_geliefert, anzahl_neu) VALUES (?, ?, ?, ?, ?, 0)",
        Statement.RETURN_GENERATED_KEYS))
    {
      ps.setLong(1, kontoId);
      ps.setObject(2, von);
      ps.setObject(3, bis);
      ps.setString(4, format);
      ps.setInt(5, anzahl);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys())
      {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static final String INSERT_BUCHUNG = "INSERT INTO buchung ("
      + "konto_id, abruf_id, schluessel, vorkommen, buchungsdatum, valuta, betrag, waehrung, saldo,"
      + " ursprungsbetrag, ursprungswaehrung, gebuehren, gebuehren_waehrung, storno,"
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
    ps.setObject(++i, datum(b.bdate), Types.DATE);
    ps.setObject(++i, datum(b.valuta), Types.DATE);
    ps.setBigDecimal(++i, b.value != null ? b.value.getBigDecimalValue() : BigDecimal.ZERO);
    String waehrung = waehrung(b.value);
    ps.setString(++i, waehrung != null ? waehrung : "");
    ps.setBigDecimal(++i, b.saldo != null ? betrag(b.saldo.value) : null);
    ps.setBigDecimal(++i, betrag(b.orig_value));
    ps.setString(++i, waehrung(b.orig_value));
    ps.setBigDecimal(++i, betrag(b.charge_value));
    ps.setString(++i, waehrung(b.charge_value));
    ps.setBoolean(++i, b.isStorno);
    ps.setString(++i, kurz(b.text, 255));
    ps.setString(++i, kurz(b.gvcode, 3));
    ps.setString(++i, kurz(b.addkey, 10));
    ps.setString(++i, kurz(b.primanota, 20));
    ps.setString(++i, leerAlsNull(UmsatzAusgabe.verwendungszweck(b)));
    ps.setString(++i, leerAlsNull(b.additional));
    ps.setString(++i, g != null ? kurz(name(g), 255) : null);
    ps.setString(++i, g != null && g.iban != null ? kurz(Konfiguration.normalisiereIban(g.iban), 34) : null);
    ps.setString(++i, g != null ? kurz(g.bic, 11) : null);
    ps.setString(++i, g != null ? kurz(g.number, 30) : null);
    ps.setString(++i, g != null ? kurz(g.blz, 30) : null);
    ps.setString(++i, g != null ? kurz(g.creditorid, 35) : null);
    ps.setString(++i, kurz(b.endToEndId, 255));
    ps.setString(++i, kurz(b.mandateId, 255));
    ps.setString(++i, kurz(b.purposecode, 4));
    ps.setString(++i, kurz(b.customerref, 255));
    ps.setString(++i, kurz(b.instref, 255));
    ps.setString(++i, kurz(b.id, 255));
    ps.setBoolean(++i, b.isSepa);
    ps.setString(++i, format);
  }

  private static LocalDate datum(Date d)
  {
    return d == null ? null : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
  }

  private static BigDecimal betrag(Value v)
  {
    return v == null ? null : v.getBigDecimalValue();
  }

  private static String waehrung(Value v)
  {
    return v == null ? null : kurz(v.getCurr(), 3);
  }

  private static String name(Konto k)
  {
    String n1 = k.name == null ? "" : k.name.trim();
    String n2 = k.name2 == null ? "" : k.name2.trim();
    return n1.isEmpty() || n2.isEmpty() ? n1 + n2 : n1 + " " + n2;
  }

  /**
   * Leere Werte werden NULL, überlange auf die Spaltenbreite gekürzt - ein unerwartet langer
   * Wert der Bank soll nicht den ganzen Abruf scheitern lassen.
   */
  static String kurz(String s, int max)
  {
    String t = leerAlsNull(s);
    return t == null || t.length() <= max ? t : t.substring(0, max);
  }

  private static String leerAlsNull(String s)
  {
    if (s == null || s.isBlank())
      return null;
    return s.trim();
  }
}

package de.fehrenbacher.fints;

import java.util.Set;

import org.kapott.hbci.GV.HBCIJob;
import org.kapott.hbci.manager.HBCIHandler;

/**
 * Einzige Stelle, an der Geschäftsvorfälle für die Bank erzeugt werden. Erlaubt sind nur
 * Umsatzabfragen - Überweisungen, Lastschriften, Daueraufträge und alle anderen Aufträge
 * werden abgewiesen, bevor eine Nachricht an die Bank geht.
 *
 * Der Test {@code NurLesendTest} stellt sicher, dass kein anderer Code an dieser Klasse
 * vorbei Aufträge erzeugt oder Auftragsklassen von HBCI4Java verwendet.
 */
final class NurLesend
{
  /** Die erlaubten Geschäftsvorfälle (Namen wie bei {@link HBCIHandler#newJob(String)}). */
  static final Set<String> ERLAUBT = Set.of("KUmsAll", "KUmsAllCamt");

  private NurLesend()
  {
  }

  /**
   * Erzeugt einen Auftrag, sofern er nur liest.
   * @throws IllegalStateException bei jedem nicht erlaubten Geschäftsvorfall.
   */
  static HBCIJob auftrag(HBCIHandler handle, String name)
  {
    if (!ERLAUBT.contains(name))
      throw new IllegalStateException("Geschäftsvorfall \"" + name + "\" ist nicht erlaubt -"
          + " dieses Programm ruft nur Umsätze ab und führt keine Aufträge aus.");
    return handle.newJob(name);
  }
}

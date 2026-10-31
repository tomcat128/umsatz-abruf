package de.fehrenbacher.fints;

import org.kapott.hbci.exceptions.HBCI_Exception;

/**
 * Die Bank verlangt eine Autorisierung (PIN, TAN, Freigabe in der App), die nicht erfolgt ist -
 * keine Antwort innerhalb der Wartezeit, abgebrochen oder PIN abgelehnt. Der {@link AbrufDienst}
 * setzt daraufhin die automatischen Abrufe aus, bis im Browser erneut autorisiert wurde.
 */
class AutorisierungsFehler extends HBCI_Exception
{
  private static final long serialVersionUID = 1L;

  AutorisierungsFehler(String msg)
  {
    super(msg);
  }
}

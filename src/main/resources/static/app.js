// Lädt die Übersicht neu, sobald sich der Zustand ändert (neue Anfrage der Bank, Abruf beendet).
// Während eine Eingabe getippt wird, wird nicht neu geladen.
(function () {
  var zustand = document.body.getAttribute('data-zustand');
  if (!zustand) return;

  function tipptGerade() {
    var el = document.activeElement;
    return el && el.tagName === 'INPUT' && el.value !== '';
  }

  function pruefe() {
    fetch('status', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) {
        // Abgelaufene Sitzung: zur Anmeldung
        if (r.redirected || !r.ok) { window.location.reload(); return null; }
        return r.json();
      })
      .then(function (d) {
        if (d && d.zustand !== zustand && !tipptGerade()) window.location.reload();
      })
      .catch(function () { /* Netz weg - beim nächsten Mal erneut */ });
  }

  setInterval(pruefe, 4000);
})();

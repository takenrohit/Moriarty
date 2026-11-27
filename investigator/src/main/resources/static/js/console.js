// Investigator console script, served from this application's static path only (Req 1.2).
// It never assigns HTML strings to elements and never runs strings as code (Req 9.2).
// Server-rendered, already escaped fragments are parsed with DOMParser and swapped in.
(function () {
  'use strict';

  // List page: refresh the incident table body every 10 seconds (Req 2.4).
  var LIST_REFRESH_MS = 10000;

  function setIndicator(failed) {
    var indicator = document.getElementById('refresh-failed');
    if (indicator) {
      indicator.hidden = !failed;
    }
  }

  // Fetches the server-rendered <tbody id="incident-rows"> and replaces the current one. On any failure
  // the last table stays and the "refresh failed" indicator shows until the next success (Req 2.6).
  function refreshList() {
    fetch('/incidents/fragment', { headers: { Accept: 'text/html' }, cache: 'no-store' })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('refresh failed');
        }
        return response.text();
      })
      .then(function (html) {
        // A bare <tbody> is dropped by the HTML parser outside a table, so parse it inside one.
        var parsed = new DOMParser().parseFromString('<table>' + html + '</table>', 'text/html');
        var fresh = parsed.getElementById('incident-rows');
        var current = document.getElementById('incident-rows');
        if (!fresh || !current) {
          throw new Error('refresh failed');
        }
        current.replaceWith(document.importNode(fresh, true));
        setIndicator(false);
      })
      .catch(function () {
        setIndicator(true);
      })
      .then(function () {
        setTimeout(refreshList, LIST_REFRESH_MS);
      });
  }

  function start() {
    // Only the list page carries the data-live-list marker.
    if (document.querySelector('[data-live-list]')) {
      setTimeout(refreshList, LIST_REFRESH_MS);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();

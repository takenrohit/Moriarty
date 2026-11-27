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

  // Report page: while the investigation is not finished, poll the unchanged incident JSON every
  // 3 seconds and reload once investigationStatus is a Finished_Status, so the server renders the
  // report or the failure (Req 4.3). It does not poll /report: during a re-investigation the earlier
  // report stays stored there and would trigger a reload loop.
  var REPORT_POLL_MS = 3000;
  var FINISHED = ['COMPLETED', 'INCONCLUSIVE', 'FAILED'];
  var STATUS_NAME = /^[A-Z_]+$/;

  function pollIncident(id) {
    fetch('/api/v1/incidents/' + encodeURIComponent(id), {
      headers: { Accept: 'application/json' }, cache: 'no-store'
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('poll failed');
        }
        return response.json();
      })
      .then(function (body) {
        var status = body && body.investigationStatus;
        if (typeof status !== 'string' || !STATUS_NAME.test(status)) {
          throw new Error('poll failed');
        }
        // The status is a safe enum name; it is written as text, never as markup (Req 9.2).
        var badges = document.querySelectorAll('#investigation-status .badge, #in-progress .badge');
        Array.prototype.forEach.call(badges, function (badge) {
          badge.textContent = status;
          badge.className = 'badge badge-status-' + status;
        });
        if (FINISHED.indexOf(status) >= 0) {
          location.reload();
        } else {
          setTimeout(function () { pollIncident(id); }, REPORT_POLL_MS);
        }
      })
      .catch(function () {
        setTimeout(function () { pollIncident(id); }, REPORT_POLL_MS);
      });
  }

  function pad2(n) {
    return (n < 10 ? '0' : '') + n;
  }

  // The current time as ISO-8601 with offset, seconds precision, in the IANA zone `zone` (the
  // Display_Timezone), for example 2026-11-23T20:10:00+05:30. Falls back to the browser's zone.
  function nowIso(zone) {
    var now = new Date(Math.floor(Date.now() / 1000) * 1000);
    var y, mo, d, h, mi, s, offsetMinutes;
    try {
      var parts = {};
      new Intl.DateTimeFormat('en-US', {
        timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      }).formatToParts(now).forEach(function (p) { parts[p.type] = p.value; });
      y = +parts.year; mo = +parts.month; d = +parts.day;
      h = +parts.hour % 24; mi = +parts.minute; s = +parts.second;
      offsetMinutes = Math.round((Date.UTC(y, mo - 1, d, h, mi, s) - now.getTime()) / 60000);
    } catch (e) {
      y = now.getFullYear(); mo = now.getMonth() + 1; d = now.getDate();
      h = now.getHours(); mi = now.getMinutes(); s = now.getSeconds();
      offsetMinutes = -now.getTimezoneOffset();
    }
    var offset = 'Z';
    if (offsetMinutes !== 0) {
      var abs = Math.abs(offsetMinutes);
      offset = (offsetMinutes < 0 ? '-' : '+') + pad2(Math.floor(abs / 60)) + ':' + pad2(abs % 60);
    }
    return y + '-' + pad2(mo) + '-' + pad2(d) + 'T' + pad2(h) + ':' + pad2(mi) + ':' + pad2(s) + offset;
  }

  // New-alert page: choosing a scenario preset fills the editable fields from the option's data-*
  // attributes and sets the detected time to now (Req 3.2, 3.3). Only input values are written.
  function startPresetSelect(form) {
    var select = form.querySelector('[data-preset-select]');
    if (!select) {
      return;
    }
    select.addEventListener('change', function () {
      var option = select.options[select.selectedIndex];
      if (!option) {
        return;
      }
      ['alert', 'service', 'severity'].forEach(function (name) {
        var input = form.elements.namedItem(name);
        var value = option.dataset[name];
        if (input && typeof value === 'string') {
          input.value = value;
        }
      });
      var detectedAt = form.elements.namedItem('detectedAt');
      if (detectedAt) {
        detectedAt.value = nowIso(form.dataset.zone);
      }
    });
  }

  function start() {
    // Only the list page carries the data-live-list marker.
    if (document.querySelector('[data-live-list]')) {
      setTimeout(refreshList, LIST_REFRESH_MS);
    }
    // The server renders the data-live-report marker only while the status is not finished, so a
    // finished page never polls (Req 4.5). The id comes from a data attribute, not from markup.
    var liveReport = document.querySelector('[data-live-report][data-incident-id]');
    if (liveReport && liveReport.dataset.incidentId) {
      var incidentId = liveReport.dataset.incidentId;
      setTimeout(function () { pollIncident(incidentId); }, REPORT_POLL_MS);
    }
    var newAlertForm = document.querySelector('form[data-new-alert]');
    if (newAlertForm) {
      startPresetSelect(newAlertForm);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();

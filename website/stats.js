(function () {
  var STATS_URL = "https://tubik-releases.23p.dev/stats.json";
  var container = document.getElementById("release-downloads");
  var totalEl = document.getElementById("release-downloads-total");
  var weekEl = document.getElementById("release-downloads-week");
  if (!container || !totalEl || !weekEl || typeof fetch !== "function") {
    return;
  }

  function isNonNegativeInt(value) {
    return typeof value === "number" && Number.isFinite(value) && value >= 0 && Math.floor(value) === value;
  }

  fetch(STATS_URL, { credentials: "omit", referrerPolicy: "no-referrer" })
    .then(function (response) {
      if (!response.ok) {
        throw new Error("stats fetch failed");
      }
      return response.json();
    })
    .then(function (data) {
      if (!data || !isNonNegativeInt(data.downloadsTotal) || !isNonNegativeInt(data.downloadsThisWeek)) {
        return;
      }
      if (data.downloadsTotal === 0) {
        return;
      }
      var formatter = new Intl.NumberFormat();
      totalEl.textContent = formatter.format(data.downloadsTotal) + " total";
      weekEl.textContent = "· " + formatter.format(data.downloadsThisWeek) + " this week";
      container.hidden = false;
    })
    .catch(function () {
      // Progressive enhancement: leave the stats entry hidden if unavailable.
    });
})();

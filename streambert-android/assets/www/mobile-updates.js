/*
 * Streambert Mobile - built-in updater settings card
 * --------------------------------------------------
 * The native updater (UpdateManager.java) checks a release source, downloads
 * the APK and hands it to the Android installer. This file adds the controls
 * for it to the existing Settings page: an automatic-check toggle, a manual
 * "check now" button and the state of a downloaded update.
 *
 * Streambert is GPL-3.0. This file is part of a modified/ported build and is
 * therefore also GPL-3.0. Original project: https://github.com/truelockmc/streambert
 */
(function () {
  "use strict";

  var bridge = window.Android || null;

  function available() {
    return !!(bridge && typeof bridge.getUpdateState === "function");
  }

  function call(fn) {
    try {
      return fn();
    } catch (e) {
      return null;
    }
  }

  function readState() {
    var raw = call(function () { return bridge.getUpdateState(); });
    if (!raw) return null;
    try {
      var parsed = JSON.parse(raw);
      return parsed && typeof parsed === "object" ? parsed : null;
    } catch (e) {
      return null;
    }
  }

  function element(tag, className, text) {
    var el = document.createElement(tag);
    if (className) el.className = className;
    if (text != null) el.textContent = text;
    return el;
  }

  function formatLastCheck(ms) {
    if (!ms) return "never";
    var diff = Date.now() - Number(ms);
    if (!isFinite(diff) || diff < 0) return "never";
    var minutes = Math.floor(diff / 60000);
    if (minutes < 2) return "just now";
    if (minutes < 60) return minutes + " minutes ago";
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return hours === 1 ? "an hour ago" : hours + " hours ago";
    var days = Math.floor(hours / 24);
    return days === 1 ? "yesterday" : days + " days ago";
  }

  function findVersionSection() {
    var titles = document.querySelectorAll(".settings-section-title");
    for (var i = 0; i < titles.length; i++) {
      if (/^app version$/i.test((titles[i].textContent || "").trim())) {
        return titles[i].parentElement;
      }
    }
    return null;
  }

  // The shipped settings page says updates are sideloaded only; the app can
  // now fetch and install them, so correct that line in place.
  function fixVersionCopy(section) {
    var nodes = section.querySelectorAll("div");
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.children.length) continue;
      if (/does not download or install updates/i.test(node.textContent || "")) {
        node.textContent =
          "Streambert checks for a newer APK automatically on startup and can " +
          "install it for you. You can also check manually below.";
        return;
      }
    }
  }

  function buildCard() {
    var card = element("div", "sb-updates");
    card.innerHTML =
      '<div class="sb-updates-title">App updates</div>' +
      '<div class="sb-updates-text">Download and install newer Streambert builds ' +
        'without leaving the app. Android asks you to confirm the installation.</div>' +
      '<div class="sb-updates-actions">' +
        '<button type="button" class="btn btn-ghost sb-updates-check">Check for updates</button>' +
        '<button type="button" class="btn btn-ghost sb-updates-toggle" aria-pressed="true" ' +
          'aria-label="Automatic update checks">' +
          'Automatic checks: On</button>' +
        '<button type="button" class="btn btn-primary sb-updates-install" hidden>' +
          'Install downloaded update</button>' +
      '</div>' +
      '<div class="sb-updates-status"></div>';

    var check = card.querySelector(".sb-updates-check");
    var toggle = card.querySelector(".sb-updates-toggle");
    var install = card.querySelector(".sb-updates-install");
    var status = card.querySelector(".sb-updates-status");
    var checkTimer = null;

    check.addEventListener("click", function () {
      if (!available()) return;
      call(function () { bridge.checkForUpdates(); });
      check.disabled = true;
      if (status) status.textContent = "Checking for updates\u2026";
      if (checkTimer) clearTimeout(checkTimer);
      checkTimer = setTimeout(function () {
        check.disabled = false;
        render();
      }, 8000);
    });

    toggle.addEventListener("click", function () {
      if (!available()) return;
      var next = !(toggle.getAttribute("aria-pressed") === "true");
      call(function () { bridge.setAutoUpdateCheck(next); });
      if (status) status.textContent = "";
      render();
    });

    install.addEventListener("click", function () {
      if (!available()) return;
      call(function () { bridge.installPendingUpdate(); });
      install.hidden = true;
    });

    function render() {
      var state = readState();
      if (!state) return;

      var auto = state.autoCheck !== false;
      var pressed = toggle.getAttribute("aria-pressed") === "true";
      if (pressed !== auto) toggle.setAttribute("aria-pressed", auto ? "true" : "false");
      var label = "Automatic checks: " + (auto ? "On" : "Off");
      if (toggle.textContent !== label) toggle.textContent = label;

      if (install) install.hidden = !state.pendingUpdate;

      if (status && !status.textContent) {
        var version = state.versionName ? "v" + state.versionName : "this build";
        var parts = ["Installed " + version];
        parts.push("last check " + formatLastCheck(state.lastCheck));
        if (state.pendingUpdate) parts.push("an update is ready to install");
        if (state.canInstall === false) parts.push("allow installs to update");
        status.textContent = parts.join(" \u00b7 ");
      }
    }

    card.render = render;
    render();
    return card;
  }

  function ensureCard() {
    if (!available()) return;
    var section = findVersionSection();
    if (!section) return;
    fixVersionCopy(section);

    var existing = section.querySelector(".sb-updates");
    if (existing) {
      if (existing.render) existing.render();
      return;
    }

    var card = buildCard();
    section.appendChild(card);
    // Clear the "Checking…" status once the native dialog has had time to
    // show; the dialog itself is the source of truth for the result.
    setTimeout(function () {
      var status = card.querySelector(".sb-updates-status");
      if (status) status.textContent = "";
      card.render();
    }, 9000);
  }

  var pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    (window.requestAnimationFrame || function (cb) { setTimeout(cb, 50); })(function () {
      pending = false;
      try { ensureCard(); } catch (e) {}
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", schedule);
  } else {
    schedule();
  }
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true
  });
})();

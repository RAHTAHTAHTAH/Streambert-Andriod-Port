/*
 * Streambert Mobile - parental PIN for the age rating setting
 * -----------------------------------------------------------
 * Adds a "Parental PIN" card below the age rating controls and gates the
 * Save button of that section behind a 4-digit PIN. The PIN and the recovery
 * code live in the same Android secure storage (SharedPreferences) used for
 * the TMDB token; if that bridge is missing, localStorage is used instead.
 *
 * Streambert is GPL-3.0. This file is part of a modified/ported build and is
 * therefore also GPL-3.0. Original project: https://github.com/truelockmc/streambert
 */
(function () {
  "use strict";

  var bridge = window.Android || {};
  var LS = null;
  try { LS = window.localStorage; } catch (e) {}

  var PIN_KEY = "parentalPin";
  var REC_KEY = "parentalRecovery";
  var PIN_LS = "streambert_parentalPin";
  var REC_LS = "streambert_parentalRecovery";

  var state = { loaded: false, loading: null, pin: null, recovery: null };
  var bypassNextSave = false;
  var current = null;

  function toast(msg) {
    try { if (bridge.toast) bridge.toast(String(msg)); } catch (e) {}
  }

  function secureAvailable() {
    return !!(window.Android &&
      typeof window.Android.secureGet === "function" &&
      typeof window.Android.secureSet === "function");
  }

  function readKey(key, lsKey) {
    if (secureAvailable()) {
      try {
        var v = window.Android.secureGet(key);
        if (v) return Promise.resolve(String(v));
      } catch (e) {}
    }
    try { return Promise.resolve(LS.getItem(lsKey)); } catch (e) {
      return Promise.resolve(null);
    }
  }

  function writeKey(key, lsKey, value) {
    if (secureAvailable()) {
      try {
        window.Android.secureSet(key, value == null ? "" : String(value));
        return;
      } catch (e) {}
    }
    try {
      if (value == null) LS.removeItem(lsKey);
      else LS.setItem(lsKey, String(value));
    } catch (e) {}
  }

  function clearKey(key, lsKey) {
    if (secureAvailable()) {
      try { window.Android.secureSet(key, ""); } catch (e) {}
    }
    try { LS.removeItem(lsKey); } catch (e) {}
  }

  function loadState() {
    if (state.loaded) return Promise.resolve(state);
    if (state.loading) return state.loading;
    state.loading = Promise.all([
      readKey(PIN_KEY, PIN_LS),
      readKey(REC_KEY, REC_LS)
    ]).then(function (values) {
      state.pin = values[0] || null;
      state.recovery = values[1] || null;
      state.loaded = true;
      state.loading = null;
      updateBlock();
      return state;
    });
    return state.loading;
  }

  // -- recovery codes ---------------------------------------------------

  function generateCode() {
    var alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    var bytes = new Uint8Array(8);
    if (window.crypto && window.crypto.getRandomValues) {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < bytes.length; i++) {
        bytes[i] = Math.floor(Math.random() * 256);
      }
    }
    var out = "";
    for (var j = 0; j < bytes.length; j++) out += alphabet[bytes[j] % alphabet.length];
    return out.slice(0, 4) + "-" + out.slice(4);
  }

  function normalizeCode(value) {
    return String(value || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  }

  // -- modal plumbing ---------------------------------------------------

  function element(tag, className, text) {
    var el = document.createElement(tag);
    if (className) el.className = className;
    if (text != null) el.textContent = text;
    return el;
  }

  function modal(title, text) {
    var overlay = element("div", "sb-pin-overlay");
    var card = element("div", "sb-pin-card");
    card.setAttribute("role", "dialog");
    card.setAttribute("aria-modal", "true");

    card.appendChild(element("div", "sb-pin-title", title));
    if (text) card.appendChild(element("div", "sb-pin-text", text));

    var body = element("div", "sb-pin-body");
    var error = element("div", "sb-pin-error");
    error.hidden = true;
    var actions = element("div", "sb-pin-actions");
    card.appendChild(body);
    card.appendChild(error);
    card.appendChild(actions);
    overlay.appendChild(card);
    document.body.appendChild(overlay);

    return {
      overlay: overlay,
      card: card,
      body: body,
      error: error,
      actions: actions,
      showError: function (msg) {
        error.textContent = msg;
        error.hidden = false;
      },
      clearError: function () {
        error.textContent = "";
        error.hidden = true;
      }
    };
  }

  function closeModal() {
    if (!current) return;
    try {
      document.removeEventListener("keydown", current.onKey, true);
    } catch (e) {}
    if (current.overlay && current.overlay.parentElement) {
      current.overlay.parentElement.removeChild(current.overlay);
    }
    current = null;
    document.body.classList.remove("sb-pin-open");
  }

  function track(overlay, onKey) {
    closeModal();
    current = { overlay: overlay, onKey: onKey };
    document.body.classList.add("sb-pin-open");
    if (onKey) document.addEventListener("keydown", onKey, true);
  }

  function button(label, className) {
    var btn = element("button", className || "btn btn-ghost", label);
    btn.type = "button";
    return btn;
  }

  function numericInput(placeholder) {
    var input = document.createElement("input");
    input.className = "sb-pin-input";
    input.type = "password";
    input.inputMode = "numeric";
    input.setAttribute("autocomplete", "off");
    input.setAttribute("autocorrect", "off");
    input.setAttribute("spellcheck", "false");
    input.maxLength = 4;
    input.placeholder = placeholder || "4-digit PIN";
    input.addEventListener("input", function () {
      input.value = input.value.replace(/\D/g, "").slice(0, 4);
    });
    return input;
  }

  function bindEnter(input, submit) {
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter") {
        e.preventDefault();
        submit();
      }
    });
  }

  function escapeKey(onCancel) {
    return function (e) {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        closeModal();
        if (onCancel) onCancel();
      }
    };
  }

  function focusLater(input) {
    setTimeout(function () {
      try { input.focus(); } catch (e) {}
    }, 60);
  }

  // -- flows ------------------------------------------------------------

  // Single PIN entry used before changing/removing and before saving.
  function askPin(text, allowRecovery) {
    return new Promise(function (resolve) {
      var ui = modal("Enter PIN", text);
      var input = numericInput("4-digit PIN");
      ui.body.appendChild(input);

      if (allowRecovery) {
        var link = element("button", "sb-pin-link", "Forgot PIN? Use recovery code");
        link.type = "button";
        link.addEventListener("click", function () {
          closeModal();
          resolve("recovery");
          startRecovery();
        });
        ui.body.appendChild(link);
      }

      var cancel = button("Cancel", "btn btn-ghost");
      var ok = button("Unlock", "btn btn-primary");
      ui.actions.appendChild(cancel);
      ui.actions.appendChild(ok);

      function submit() {
        var value = input.value;
        ui.clearError();
        if (!/^\d{4}$/.test(value)) {
          ui.showError("Enter your 4-digit PIN.");
          return;
        }
        if (value !== state.pin) {
          input.value = "";
          ui.showError("Incorrect PIN. Try again.");
          focusLater(input);
          return;
        }
        closeModal();
        resolve(true);
      }

      ok.addEventListener("click", submit);
      cancel.addEventListener("click", function () {
        closeModal();
        resolve(false);
      });
      bindEnter(input, submit);
      track(ui.overlay, escapeKey(function () { resolve(false); }));
      focusLater(input);
    });
  }

  // Create a PIN, or replace the current one (mode: "create" | "change" | "recovery").
  function setupPin(mode) {
    var title = mode === "create" ? "Create parental PIN" : "Set a new PIN";
    var text = mode === "create"
      ? "Choose a 4-digit PIN. You will need it to change the age rating."
      : "Choose a new 4-digit PIN.";
    var ui = modal(title, text);

    var first = numericInput("New 4-digit PIN");
    var second = numericInput("Confirm PIN");
    ui.body.appendChild(first);
    ui.body.appendChild(second);

    var cancel = button("Cancel", "btn btn-ghost");
    var save = button("Save PIN", "btn btn-primary");
    ui.actions.appendChild(cancel);
    ui.actions.appendChild(save);

    function submit() {
      ui.clearError();
      if (!/^\d{4}$/.test(first.value)) {
        ui.showError("Use exactly 4 digits.");
        focusLater(first);
        return;
      }
      if (first.value !== second.value) {
        second.value = "";
        ui.showError("The PINs do not match.");
        focusLater(second);
        return;
      }

      var pin = first.value;
      var rotate = mode !== "change" || !state.recovery;
      state.pin = pin;
      writeKey(PIN_KEY, PIN_LS, pin);

      var code = null;
      if (rotate) {
        code = generateCode();
        state.recovery = code;
        writeKey(REC_KEY, REC_LS, code);
      }
      state.loaded = true;
      closeModal();
      updateBlock();

      if (code) {
        showRecoveryCode(code, mode === "recovery"
          ? "Your old recovery code no longer works. Write down this new one."
          : "Write this code down. It is the only way back in if you forget the PIN.");
      } else {
        toast("Parental PIN changed.");
      }
    }

    save.addEventListener("click", submit);
    cancel.addEventListener("click", closeModal);
    bindEnter(first, function () { focusLater(second); });
    bindEnter(second, submit);
    track(ui.overlay, escapeKey());
    focusLater(first);
  }

  function showRecoveryCode(code, note) {
    var ui = modal("Recovery code", note || "Keep this code somewhere safe.");
    var box = element("div", "sb-pin-code", code);
    ui.body.appendChild(box);
    ui.body.appendChild(element("div", "sb-pin-note",
      "You can use this code to reset or view your PIN if you forget it."));

    var copy = button("Copy", "btn btn-ghost");
    var done = button("I saved it", "btn btn-primary");
    ui.actions.appendChild(copy);
    ui.actions.appendChild(done);

    copy.addEventListener("click", function () {
      var text = code;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          toast("Recovery code copied.");
        }).catch(function () {
          selectText(box);
          toast("Select the code and copy it.");
        });
      } else {
        selectText(box);
        toast("Select the code and copy it.");
      }
    });
    done.addEventListener("click", closeModal);
    track(ui.overlay, escapeKey());
  }

  function selectText(el) {
    try {
      var range = document.createRange();
      range.selectNodeContents(el);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    } catch (e) {}
  }

  function startRecovery() {
    if (!state.recovery) {
      toast("No recovery code is set for this PIN.");
      return;
    }

    var ui = modal("Use recovery code",
      "Enter the recovery code you saved when you created the PIN.");
    var input = document.createElement("input");
    input.className = "sb-pin-input sb-pin-code-input";
    input.type = "text";
    input.maxLength = 9;
    input.placeholder = "XXXX-XXXX";
    input.setAttribute("autocomplete", "off");
    input.setAttribute("autocorrect", "off");
    input.setAttribute("autocapitalize", "characters");
    input.setAttribute("spellcheck", "false");
    input.addEventListener("input", function () {
      input.value = input.value.toUpperCase().replace(/[^A-Z0-9-]/g, "").slice(0, 9);
    });
    ui.body.appendChild(input);

    var cancel = button("Cancel", "btn btn-ghost");
    var ok = button("Continue", "btn btn-primary");
    ui.actions.appendChild(cancel);
    ui.actions.appendChild(ok);

    function submit() {
      ui.clearError();
      if (normalizeCode(input.value) !== normalizeCode(state.recovery)) {
        input.value = "";
        ui.showError("That recovery code does not match.");
        focusLater(input);
        return;
      }
      closeModal();
      recoveryMenu();
    }

    ok.addEventListener("click", submit);
    cancel.addEventListener("click", closeModal);
    bindEnter(input, submit);
    track(ui.overlay, escapeKey());
    focusLater(input);
  }

  // Shown after a valid recovery code: change the PIN or look at it.
  function recoveryMenu() {
    var ui = modal("Recovery code accepted",
      "What would you like to do?");
    var change = button("Change PIN", "btn btn-primary");
    var view = button("View PIN", "btn btn-ghost");
    var close = element("button", "sb-pin-link", "Close");
    close.type = "button";
    ui.actions.appendChild(change);
    ui.actions.appendChild(view);
    ui.body.appendChild(close);

    change.addEventListener("click", function () {
      closeModal();
      setupPin("recovery");
    });
    view.addEventListener("click", function () {
      closeModal();
      showPin();
    });
    close.addEventListener("click", closeModal);
    track(ui.overlay, escapeKey());
  }

  function showPin() {
    var ui = modal("Your PIN", "Keep this code private.");
    ui.body.appendChild(element("div", "sb-pin-code", state.pin || "----"));
    var done = button("Done", "btn btn-primary");
    ui.actions.appendChild(done);
    done.addEventListener("click", closeModal);
    track(ui.overlay, escapeKey());
  }

  // -- settings block ---------------------------------------------------

  function findAgeSection() {
    var titles = document.querySelectorAll(".settings-section-title");
    for (var i = 0; i < titles.length; i++) {
      var text = (titles[i].textContent || "").trim();
      if (/^Age Rating & Parental Controls$/i.test(text)) {
        return titles[i].parentElement;
      }
    }
    return null;
  }

  function findSaveButton(section) {
    var buttons = section.querySelectorAll("button");
    for (var i = 0; i < buttons.length; i++) {
      var btn = buttons[i];
      if (btn.classList.contains("btn-primary") &&
          /^save$/i.test((btn.textContent || "").trim())) {
        return btn;
      }
    }
    return null;
  }

  function buildBlock() {
    var block = element("div", "sb-parental");
    block.innerHTML =
      '<div class="sb-parental-head">' +
        '<div class="sb-parental-title">Parental PIN</div>' +
        '<span class="sb-parental-badge" hidden>PIN is on</span>' +
      '</div>' +
      '<div class="sb-parental-text">Require a 4-digit PIN before the age rating can be changed.</div>' +
      '<div class="sb-parental-actions">' +
        '<button type="button" class="btn btn-ghost sb-parental-set">Set parental PIN</button>' +
        '<button type="button" class="btn btn-ghost sb-parental-change" hidden>Change PIN</button>' +
        '<button type="button" class="btn btn-ghost sb-parental-remove" hidden>Remove PIN</button>' +
      '</div>';

    block.querySelector(".sb-parental-set").addEventListener("click", function () {
      setupPin("create");
    });
    block.querySelector(".sb-parental-change").addEventListener("click", function () {
      askPin("Enter your current PIN to change it.", true).then(function (ok) {
        if (ok === true) setupPin("change");
      });
    });
    block.querySelector(".sb-parental-remove").addEventListener("click", function () {
      askPin("Enter your current PIN to turn off parental control.", true).then(function (ok) {
        if (ok !== true) return;
        state.pin = null;
        state.recovery = null;
        clearKey(PIN_KEY, PIN_LS);
        clearKey(REC_KEY, REC_LS);
        updateBlock();
        toast("Parental PIN removed.");
      });
    });

    return block;
  }

  function ensureBlock() {
    var section = findAgeSection();
    if (!section) return;
    if (section.querySelector(".sb-parental")) {
      updateBlock();
      return;
    }
    var save = findSaveButton(section);
    if (!save || !save.parentElement || !save.parentElement.parentElement) return;
    var row = save.parentElement;
    var block = buildBlock();
    row.parentElement.insertBefore(block, row.nextSibling);
    updateBlock();
    if (!state.loaded) loadState();
  }

  function updateBlock() {
    var block = document.querySelector(".sb-parental");
    if (!block) return;

    var on = !!state.pin;
    var badge = block.querySelector(".sb-parental-badge");
    var set = block.querySelector(".sb-parental-set");
    var change = block.querySelector(".sb-parental-change");
    var remove = block.querySelector(".sb-parental-remove");

    if (badge.hidden !== !on) badge.hidden = !on;
    if (set.hidden !== on) set.hidden = on;
    if (change.hidden !== !on) change.hidden = !on;
    if (remove.hidden !== !on) remove.hidden = !on;
    if (set.disabled !== !state.loaded) set.disabled = !state.loaded;
    if (change.disabled !== !state.loaded) change.disabled = !state.loaded;
    if (remove.disabled !== !state.loaded) remove.disabled = !state.loaded;

    var label = state.loaded ? "Set parental PIN" : "Loading…";
    if (set.textContent !== label) set.textContent = label;
  }

  // -- Save gate --------------------------------------------------------

  function saveWithPin(btn) {
    askPin("Enter your parental PIN to change the age rating.", true).then(function (ok) {
      if (ok !== true) {
        if (ok !== "recovery") toast("Age rating was not changed.");
        return;
      }
      bypassNextSave = true;
      btn.click();
      setTimeout(function () { bypassNextSave = false; }, 0);
    });
  }

  document.addEventListener("click", function (e) {
    var target = e.target;
    var btn = target && target.closest ? target.closest("button") : null;
    if (!btn) return;
    if (!btn.classList.contains("btn-primary")) return;
    if (!/^save$/i.test((btn.textContent || "").trim())) return;

    var section = findAgeSection();
    if (!section || !section.contains(btn)) return;

    if (bypassNextSave) {
      bypassNextSave = false;
      return;
    }

    if (!state.loaded) {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      loadState().then(function () {
        if (state.pin) saveWithPin(btn);
        else {
          bypassNextSave = true;
          btn.click();
        }
      });
      return;
    }

    if (!state.pin) return;

    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
    saveWithPin(btn);
  }, true);

  // -- boot -------------------------------------------------------------

  var pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    (window.requestAnimationFrame || function (cb) { setTimeout(cb, 50); })(function () {
      pending = false;
      try { ensureBlock(); } catch (e) {}
    });
  }

  loadState();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", schedule);
  } else {
    schedule();
  }
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true
  });

  // Android hardware Back: close an open PIN dialog before the shim's own
  // navigation handling runs.
  var shimBack = window.__streambertBack;
  window.__streambertBack = function () {
    if (current) {
      closeModal();
      return true;
    }
    return shimBack ? shimBack() : false;
  };
})();

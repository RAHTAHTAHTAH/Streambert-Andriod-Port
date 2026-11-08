/* Streambert Mobile - Netflix-style profiles.
 *
 * Loaded as a classic script after mobile-shim.js / mobile-parental.js and
 * before the app bundle. It owns the "Who's watching?" gate, the avatar
 * sheet and the per-profile storage namespace.
 *
 * Per-profile keys (everything else stays device-wide):
 *   saved, savedOrder, history, progress, watched, searchHistory
 *
 * Those six keys are transparently rewritten to
 *   streambert_p_<profileId>_<key>
 * so the existing React store needs no changes. The API token, age limit,
 * parental PIN, downloads and UI settings remain shared by the device.
 */
(function () {
  "use strict";

  var LS = window.localStorage;
  var REGISTRY_KEY = "streambert_profiles";
  var PROFILE_KEYS = {
    saved: 1,
    savedOrder: 1,
    history: 1,
    progress: 1,
    watched: 1,
    searchHistory: 1,
  };
  var MAX_PROFILES = 5;
  var COLORS = [
    "#E50914",
    "#2F80ED",
    "#9B51E0",
    "#27AE60",
    "#F2994A",
    "#00B8A9",
    "#EB5757",
    "#F2C94C",
  ];
  var EMOJIS = ["🎬", "🚀", "👾", "🦊", "🐼", "🎧", "⭐", "🍿", "🌙", "🔥"];

  var registry = null;
  var currentProfile = null;
  var namespaceInstalled = false;
  var namespacePrefix = "";
  var booted = false;

  var gateEl = null;
  var gateGridEl = null;
  var gateTitleEl = null;
  var gateActionsEl = null;
  var gateManage = false;
  var gateFromApp = false;
  var sheetEl = null;
  var editorEl = null;
  var confirmEl = null;

  // -----------------------------------------------------------------------
  // Small helpers
  // -----------------------------------------------------------------------
  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function button(className, text) {
    var node = el("button", className, text);
    node.type = "button";
    return node;
  }

  function copy(value) {
    var out = {};
    Object.keys(value || {}).forEach(function (key) {
      out[key] = value[key];
    });
    return out;
  }

  function toast(message) {
    try {
      if (window.Android && typeof window.Android.toast === "function") {
        window.Android.toast(message);
      }
    } catch (e) {}
  }

  function makeId() {
    return (
      "p" +
      Date.now().toString(36) +
      Math.random().toString(36).slice(2, 6)
    );
  }

  // -----------------------------------------------------------------------
  // Registry and profile CRUD
  // -----------------------------------------------------------------------
  function findProfile(id) {
    if (!registry || !id) return null;
    for (var i = 0; i < registry.profiles.length; i++) {
      if (registry.profiles[i].id === id) return registry.profiles[i];
    }
    return null;
  }

  function findByName(name, exceptId) {
    var wanted = String(name || "").toLowerCase();
    for (var i = 0; i < registry.profiles.length; i++) {
      var item = registry.profiles[i];
      if (item.id !== exceptId && item.name.toLowerCase() === wanted) return item;
    }
    return null;
  }

  function sanitizeProfile(raw) {
    if (!raw || typeof raw.id !== "string") return null;
    var name = typeof raw.name === "string" ? raw.name.trim() : "";
    if (!name) return null;
    return {
      id: raw.id,
      name: name.slice(0, 24),
      color: COLORS.indexOf(raw.color) >= 0 ? raw.color : COLORS[0],
      emoji: EMOJIS.indexOf(raw.emoji) >= 0 ? raw.emoji : EMOJIS[0],
      createdAt: raw.createdAt || Date.now(),
    };
  }

  function loadRegistry() {
    var raw = null;
    try {
      raw = LS.getItem(REGISTRY_KEY);
    } catch (e) {}
    if (!raw) return { v: 1, active: null, profiles: [] };

    try {
      var parsed = JSON.parse(raw);
      var list = [];
      if (parsed && Array.isArray(parsed.profiles)) {
        parsed.profiles.slice(0, MAX_PROFILES).forEach(function (item) {
          var clean = sanitizeProfile(item);
          if (clean) list.push(clean);
        });
      }
      var active = parsed && parsed.active;
      if (!active || !list.some(function (p) { return p.id === active; })) {
        active = list.length ? list[0].id : null;
      }
      return { v: 1, active: active, profiles: list };
    } catch (e) {
      return { v: 1, active: null, profiles: [] };
    }
  }

  function saveRegistry() {
    try {
      LS.setItem(REGISTRY_KEY, JSON.stringify(registry));
    } catch (e) {}
  }

  function validateDraft(draft, exceptId) {
    var name = String((draft && draft.name) || "")
      .replace(/\s+/g, " ")
      .trim();
    if (!name) return { ok: false, error: "Enter a name." };
    if (name.length > 24) {
      return { ok: false, error: "Keep names to 24 characters." };
    }
    if (findByName(name, exceptId)) {
      return { ok: false, error: "That name is already in use." };
    }
    return {
      ok: true,
      name: name,
      color: COLORS.indexOf(draft.color) >= 0 ? draft.color : COLORS[0],
      emoji: EMOJIS.indexOf(draft.emoji) >= 0 ? draft.emoji : EMOJIS[0],
    };
  }

  function addProfile(draft) {
    if (!registry) return { ok: false, error: "Profiles are not ready." };
    if (registry.profiles.length >= MAX_PROFILES) {
      return { ok: false, error: "You can have up to 5 profiles." };
    }
    var valid = validateDraft(draft, null);
    if (!valid.ok) return valid;
    var profile = {
      id: makeId(),
      name: valid.name,
      color: valid.color,
      emoji: valid.emoji,
      createdAt: Date.now(),
    };
    registry.profiles.push(profile);
    saveRegistry();
    refreshProfileViews();
    return { ok: true, profile: copy(profile) };
  }

  function updateProfile(id, draft) {
    var profile = findProfile(id);
    if (!profile) return { ok: false, error: "Profile not found." };
    var valid = validateDraft(draft, id);
    if (!valid.ok) return valid;
    profile.name = valid.name;
    profile.color = valid.color;
    profile.emoji = valid.emoji;
    saveRegistry();
    if (registry.active === id) {
      setActive(id);
      updateAvatarDom();
    }
    refreshProfileViews();
    return { ok: true, profile: copy(profile) };
  }

  function removeProfile(id) {
    if (!registry) return { ok: false, error: "Profiles are not ready." };
    if (registry.profiles.length <= 1) {
      return { ok: false, error: "You need at least one profile." };
    }
    var profile = findProfile(id);
    if (!profile) return { ok: false, error: "Profile not found." };

    var wasActive = registry.active === id;
    registry.profiles = registry.profiles.filter(function (item) {
      return item.id !== id;
    });
    if (wasActive) registry.active = registry.profiles[0].id;

    // Remove the profile's watch data. The model uses raw storage on purpose:
    // these keys are already namespaced and must not be redirected again.
    var prefix = "streambert_p_" + id + "_";
    try {
      Object.keys(LS).forEach(function (key) {
        if (key.indexOf(prefix) === 0) LS.removeItem(key);
      });
    } catch (e) {}

    saveRegistry();
    if (wasActive) {
      setActive(registry.active);
      installNamespace(registry.active);
    }
    refreshProfileViews();

    return {
      ok: true,
      activeDeleted: wasActive,
      nextActive: wasActive ? registry.active : null,
    };
  }

  // -----------------------------------------------------------------------
  // Legacy data migration + per-profile storage
  // -----------------------------------------------------------------------
  function migrateLegacy(profileId) {
    Object.keys(PROFILE_KEYS).forEach(function (key) {
      var legacy = "streambert_" + key;
      var target = "streambert_p_" + profileId + "_" + key;
      try {
        var value = LS.getItem(legacy);
        if (value === null) return;
        if (LS.getItem(target) === null) LS.setItem(target, value);
        LS.removeItem(legacy);
      } catch (e) {}
    });
  }

  function installNamespace(profileId) {
    namespacePrefix = "streambert_p_" + profileId + "_";
    if (namespaceInstalled) return;
    namespaceInstalled = true;

    var proto = window.Storage && window.Storage.prototype;
    if (!proto) return;

    var originalGet = proto.getItem;
    var originalSet = proto.setItem;
    var originalRemove = proto.removeItem;

    function mapped(key) {
      if (typeof key !== "string" || key.indexOf("streambert_") !== 0) return key;
      var short = key.slice(11);
      if (!PROFILE_KEYS[short]) return key;
      return namespacePrefix + short;
    }

    function isLocal(storage) {
      return storage === LS;
    }

    try {
      proto.getItem = function (key) {
        return originalGet.call(this, isLocal(this) ? mapped(key) : key);
      };
      proto.setItem = function (key, value) {
        return originalSet.call(this, isLocal(this) ? mapped(key) : key, value);
      };
      proto.removeItem = function (key) {
        return originalRemove.call(this, isLocal(this) ? mapped(key) : key);
      };
    } catch (e) {
      // If the prototype is frozen we simply run without profile isolation.
    }
  }

  function setActive(id) {
    var profile = findProfile(id);
    if (!profile) return null;
    registry.active = id;
    saveRegistry();
    currentProfile = profile;
    window.SB_PROFILE = {
      id: profile.id,
      name: profile.name,
      color: profile.color,
      emoji: profile.emoji,
    };
    // Remember the choice for this WebView session. In-app reloads (theme,
    // language, backup restore) then skip the "Who's watching?" gate; a fresh
    // app launch has an empty sessionStorage and shows it again.
    try {
      sessionStorage.setItem("streambert_profile_session", id);
    } catch (e) {}
    return profile;
  }

  function sessionProfile() {
    try {
      var id = sessionStorage.getItem("streambert_profile_session");
      return id && findProfile(id) ? id : null;
    } catch (e) {
      return null;
    }
  }

  function updateAvatarDom() {
    if (!currentProfile) return;
    var avatar = document.querySelector(".sb-home-avatar");
    if (!avatar) return;
    avatar.textContent = currentProfile.emoji;
    avatar.style.background = currentProfile.color;
    avatar.setAttribute("aria-label", "Profile: " + currentProfile.name);
    avatar.title = "Profiles";
  }

  // -----------------------------------------------------------------------
  // App boot
  // -----------------------------------------------------------------------
  function bootApp() {
    if (booted) return;
    booted = true;
    try {
      var script = document.createElement("script");
      script.type = "module";
      script.src = "./assets/index-C4x6xwoJ.js";
      script.crossOrigin = "anonymous";
      document.body.appendChild(script);
    } catch (e) {}
  }

  function cleanProfileParam() {
    try {
      if (window.history && history.replaceState) {
        history.replaceState(null, "", location.pathname + location.hash);
      }
    } catch (e) {}
  }

  function switchTo(id) {
    var profile = findProfile(id);
    if (!profile) return;
    setActive(id);
    try {
      location.replace(
        location.pathname + "?profile=" + encodeURIComponent(id)
      );
    } catch (e) {
      location.reload();
    }
  }

  function activate(id) {
    // Switch the namespace without navigating (used by tests and by the
    // editor when the active profile is renamed).
    if (!findProfile(id)) return false;
    setActive(id);
    installNamespace(id);
    updateAvatarDom();
    return true;
  }

  function openSettings() {
    try {
      var buttons = document.querySelectorAll(".sidebar-btn");
      for (var i = 0; i < buttons.length; i++) {
        var label = (
          buttons[i].getAttribute("aria-label") ||
          buttons[i].textContent ||
          ""
        ).trim();
        if (/^Settings$/i.test(label)) {
          buttons[i].click();
          return;
        }
      }
    } catch (e) {}
  }

  // -----------------------------------------------------------------------
  // "Who's watching?" gate
  // -----------------------------------------------------------------------
  function avatarNode(profile, extraClass) {
    var node = el("div", "sb-profile-avatar" + (extraClass ? " " + extraClass : ""));
    node.style.background = profile.color;
    node.textContent = profile.emoji;
    return node;
  }

  function renderGateGrid() {
    if (!gateGridEl) return;
    gateGridEl.textContent = "";

    registry.profiles.forEach(function (profile) {
      var tile = button(
        "sb-profile-tile" +
          (gateManage ? " is-manage" : "") +
          (registry.active === profile.id ? " is-active" : "")
      );
      tile.setAttribute(
        "aria-label",
        (gateManage ? "Edit " : "Watch as ") + profile.name
      );
      tile.appendChild(avatarNode(profile, "sb-profile-avatar--tile"));

      var name = el("span", "sb-profile-name");
      name.textContent = profile.name;
      tile.appendChild(name);

      if (gateManage) {
        var badge = el("span", "sb-profile-edit-badge", "✎");
        badge.setAttribute("aria-hidden", "true");
        tile.appendChild(badge);
      }

      tile.addEventListener("click", function () {
        if (gateManage) openEditor(profile.id);
        else chooseProfile(profile.id);
      });

      gateGridEl.appendChild(tile);
    });

    if (gateManage && registry.profiles.length < MAX_PROFILES) {
      var add = button("sb-profile-tile sb-profile-add");
      add.setAttribute("aria-label", "Add profile");
      add.appendChild(
        avatarNode(
          { color: "#1f1f1f", emoji: "+" },
          "sb-profile-avatar--tile sb-profile-avatar--add"
        )
      );
      var addName = el("span", "sb-profile-name", "Add Profile");
      add.appendChild(addName);
      add.addEventListener("click", function () {
        openEditor(null);
      });
      gateGridEl.appendChild(add);
    }
  }

  function renderGateActions() {
    if (!gateActionsEl) return;
    gateActionsEl.textContent = "";

    if (gateManage) {
      var done = button(
        "sb-profile-gate-action sb-profile-gate-action--primary",
        "Done"
      );
      done.addEventListener("click", function () {
        if (gateFromApp) {
          closeGate();
          return;
        }
        gateManage = false;
        renderGate();
      });
      gateActionsEl.appendChild(done);
    } else {
      var manage = button("sb-profile-gate-action", "Manage Profiles");
      manage.addEventListener("click", function () {
        gateManage = true;
        renderGate();
      });
      gateActionsEl.appendChild(manage);
    }
  }

  function renderGate() {
    if (!gateEl) return;
    if (gateTitleEl) {
      gateTitleEl.textContent = gateManage
        ? "Manage Profiles"
        : "Who's watching?";
    }
    renderGateGrid();
    renderGateActions();
  }

  function openGate(fromApp, manage) {
    closeGate();
    gateFromApp = !!fromApp;
    gateManage = !!manage;

    gateEl = el("div", "sb-profile-gate");
    gateEl.setAttribute("role", "dialog");
    gateEl.setAttribute("aria-modal", "true");

    if (gateFromApp) {
      var close = button("sb-profile-gate-close", "✕");
      close.setAttribute("aria-label", "Close profiles");
      close.addEventListener("click", closeGate);
      gateEl.appendChild(close);
    }

    var inner = el("div", "sb-profile-gate-inner");
    inner.appendChild(el("div", "sb-profile-logo", "STREAMBERT"));

    gateTitleEl = el("h1", "sb-profile-title", "Who's watching?");
    inner.appendChild(gateTitleEl);

    gateGridEl = el("div", "sb-profile-grid");
    inner.appendChild(gateGridEl);

    gateActionsEl = el("div", "sb-profile-gate-actions");
    inner.appendChild(gateActionsEl);

    gateEl.appendChild(inner);
    document.body.appendChild(gateEl);
    renderGate();
  }

  function closeGate() {
    if (gateEl && gateEl.parentNode) gateEl.parentNode.removeChild(gateEl);
    gateEl = null;
    gateGridEl = null;
    gateTitleEl = null;
    gateActionsEl = null;
    gateManage = false;
    gateFromApp = false;
  }

  function chooseProfile(id) {
    if (!findProfile(id)) return;
    setActive(id);
    closeGate();
    installNamespace(id);
    bootApp();
  }

  // -----------------------------------------------------------------------
  // Avatar sheet (opened from the home wordmark avatar)
  // -----------------------------------------------------------------------
  function renderSheet(sheet) {
    sheet.textContent = "";

    var head = el("div", "sb-profile-sheet-head");
    head.appendChild(el("div", "sb-profile-sheet-title", "Profiles"));
    var close = button("sb-profile-sheet-close", "✕");
    close.setAttribute("aria-label", "Close profiles");
    close.addEventListener("click", closeSheet);
    head.appendChild(close);
    sheet.appendChild(head);

    registry.profiles.forEach(function (profile) {
      var active = registry.active === profile.id;
      var row = button(
        "sb-profile-row" + (active ? " is-active" : "")
      );
      row.appendChild(avatarNode(profile, "sb-profile-avatar--row"));

      var name = el("span", "sb-profile-row-name");
      name.textContent = profile.name;
      row.appendChild(name);

      if (active) {
        row.appendChild(el("span", "sb-profile-check", "✓"));
      } else {
        row.appendChild(el("span", "sb-profile-switch", "Switch"));
      }

      row.addEventListener("click", function () {
        if (active) {
          closeSheet();
          return;
        }
        closeSheet();
        switchTo(profile.id);
      });

      sheet.appendChild(row);
    });

    var actions = el("div", "sb-profile-sheet-actions");
    var manage = button("sb-profile-action", "Manage Profiles");
    manage.addEventListener("click", function () {
      closeSheet();
      openGate(true, true);
    });
    actions.appendChild(manage);

    var add = button("sb-profile-action", "Add Profile");
    if (registry.profiles.length >= MAX_PROFILES) {
      add.disabled = true;
      add.textContent = "Profile limit reached (5)";
    } else {
      add.addEventListener("click", function () {
        openEditor(null);
      });
    }
    actions.appendChild(add);

    var settings = button("sb-profile-action", "Settings");
    settings.addEventListener("click", function () {
      closeSheet();
      openSettings();
    });
    actions.appendChild(settings);

    sheet.appendChild(actions);
  }

  function openSheet() {
    closeSheet();
    sheetEl = el("div", "sb-profile-sheet-backdrop");
    sheetEl.addEventListener("click", function (event) {
      if (event.target === sheetEl) closeSheet();
    });
    var sheet = el("div", "sb-profile-sheet");
    sheet.setAttribute("role", "dialog");
    sheet.setAttribute("aria-modal", "true");
    sheetEl.appendChild(sheet);
    document.body.appendChild(sheetEl);
    renderSheet(sheet);
  }

  function closeSheet() {
    if (sheetEl && sheetEl.parentNode) sheetEl.parentNode.removeChild(sheetEl);
    sheetEl = null;
  }

  // -----------------------------------------------------------------------
  // Profile editor
  // -----------------------------------------------------------------------
  function refreshProfileViews() {
    if (gateEl) renderGate();
    if (sheetEl) {
      var sheet = sheetEl.querySelector(".sb-profile-sheet");
      if (sheet) renderSheet(sheet);
    }
  }

  function openEditor(id) {
    var existing = id ? findProfile(id) : null;
    var draft = {
      name: existing ? existing.name : "",
      color: existing ? existing.color : COLORS[0],
      emoji: existing ? existing.emoji : EMOJIS[0],
    };

    closeEditor();

    editorEl = el("div", "sb-profile-editor-overlay");
    editorEl.addEventListener("click", function (event) {
      if (event.target === editorEl) closeEditor();
    });

    var dialog = el("div", "sb-profile-editor");
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");

    dialog.appendChild(
      el("div", "sb-profile-editor-title", existing ? "Edit Profile" : "Add Profile")
    );

    var preview = avatarNode(draft, "sb-profile-avatar--preview");
    var previewWrap = el("div", "sb-profile-editor-preview");
    previewWrap.appendChild(preview);
    dialog.appendChild(previewWrap);

    var nameInput = el("input", "sb-profile-input");
    nameInput.type = "text";
    nameInput.value = draft.name;
    nameInput.placeholder = "Name";
    nameInput.maxLength = 24;
    nameInput.setAttribute("aria-label", "Profile name");
    nameInput.addEventListener("input", function () {
      draft.name = nameInput.value;
      errorEl.textContent = "";
    });
    dialog.appendChild(nameInput);

    dialog.appendChild(el("div", "sb-profile-label", "Color"));
    var colors = el("div", "sb-profile-colors");
    COLORS.forEach(function (color) {
      var swatch = button("sb-profile-color" + (draft.color === color ? " is-selected" : ""));
      swatch.style.background = color;
      swatch.setAttribute("aria-label", "Color " + color);
      swatch.addEventListener("click", function () {
        draft.color = color;
        preview.style.background = color;
        Array.prototype.forEach.call(
          colors.querySelectorAll(".sb-profile-color"),
          function (node) {
            node.classList.toggle("is-selected", node === swatch);
          }
        );
      });
      colors.appendChild(swatch);
    });
    dialog.appendChild(colors);

    dialog.appendChild(el("div", "sb-profile-label", "Icon"));
    var emojis = el("div", "sb-profile-emojis");
    EMOJIS.forEach(function (emoji) {
      var choice = button(
        "sb-profile-emoji" + (draft.emoji === emoji ? " is-selected" : ""),
        emoji
      );
      choice.addEventListener("click", function () {
        draft.emoji = emoji;
        preview.textContent = emoji;
        Array.prototype.forEach.call(
          emojis.querySelectorAll(".sb-profile-emoji"),
          function (node) {
            node.classList.toggle("is-selected", node === choice);
          }
        );
      });
      emojis.appendChild(choice);
    });
    dialog.appendChild(emojis);

    var errorEl = el("div", "sb-profile-error");
    dialog.appendChild(errorEl);

    var actions = el("div", "sb-profile-editor-actions");

    if (existing && registry.profiles.length > 1) {
      var remove = button("sb-profile-delete", "Delete");
      remove.addEventListener("click", function () {
        openConfirm(existing);
      });
      actions.appendChild(remove);
    }

    var cancel = button("sb-profile-cancel", "Cancel");
    cancel.addEventListener("click", closeEditor);
    actions.appendChild(cancel);

    var save = button("sb-profile-save", "Save");
    save.addEventListener("click", function () {
      var result = existing
        ? updateProfile(existing.id, draft)
        : addProfile(draft);
      if (!result.ok) {
        errorEl.textContent = result.error;
        return;
      }
      closeEditor();
      refreshProfileViews();
      toast(existing ? "Profile updated" : "Profile added");
    });
    actions.appendChild(save);

    dialog.appendChild(actions);
    editorEl.appendChild(dialog);
    document.body.appendChild(editorEl);
  }

  function closeEditor() {
    if (editorEl && editorEl.parentNode) editorEl.parentNode.removeChild(editorEl);
    editorEl = null;
  }

  function openConfirm(profile) {
    closeConfirm();
    confirmEl = el("div", "sb-profile-confirm-overlay");
    confirmEl.addEventListener("click", function (event) {
      if (event.target === confirmEl) closeConfirm();
    });

    var card = el("div", "sb-profile-confirm");
    card.setAttribute("role", "alertdialog");
    card.setAttribute("aria-modal", "true");
    card.appendChild(el("div", "sb-profile-confirm-title", "Delete profile?"));
    card.appendChild(
      el(
        "div",
        "sb-profile-confirm-text",
        "“" + profile.name + "” and its watchlist, history and progress will be permanently removed."
      )
    );

    var actions = el("div", "sb-profile-confirm-actions");
    var cancel = button("sb-profile-cancel", "Cancel");
    cancel.addEventListener("click", closeConfirm);
    actions.appendChild(cancel);

    var remove = button("sb-profile-delete", "Delete");
    remove.addEventListener("click", function () {
      var result = removeProfile(profile.id);
      closeConfirm();
      closeEditor();
      if (!result.ok) {
        toast(result.error);
        return;
      }
      if (result.activeDeleted) {
        if (!booted) {
          // Stay on the picker when the app has not booted yet.
          try {
            sessionStorage.removeItem("streambert_profile_session");
          } catch (e) {}
          refreshProfileViews();
          toast("Profile deleted");
          return;
        }
        closeSheet();
        closeGate();
        toast("Profile deleted");
        try {
          location.replace(
            location.pathname +
              "?profile=" +
              encodeURIComponent(result.nextActive)
          );
        } catch (e) {
          location.reload();
        }
        return;
      }
      refreshProfileViews();
      toast("Profile deleted");
    });
    actions.appendChild(remove);

    card.appendChild(actions);
    confirmEl.appendChild(card);
    document.body.appendChild(confirmEl);
  }

  function closeConfirm() {
    if (confirmEl && confirmEl.parentNode) confirmEl.parentNode.removeChild(confirmEl);
    confirmEl = null;
  }

  function closeAll() {
    closeConfirm();
    closeEditor();
    closeSheet();
    closeGate();
  }

  // -----------------------------------------------------------------------
  // Hardware Back chaining (shim -> parental -> profiles)
  // -----------------------------------------------------------------------
  var previousBack = window.__streambertBack;
  window.__streambertBack = function () {
    if (confirmEl) {
      closeConfirm();
      return true;
    }
    if (editorEl) {
      closeEditor();
      return true;
    }
    if (sheetEl) {
      closeSheet();
      return true;
    }
    if (gateEl) {
      if (gateFromApp) {
        closeGate();
        return true;
      }
      if (gateManage) {
        gateManage = false;
        renderGate();
        return true;
      }
    }
    return previousBack ? previousBack() : false;
  };

  // -----------------------------------------------------------------------
  // Start-up
  // -----------------------------------------------------------------------
  function createFirstProfile() {
    var profile = {
      id: makeId(),
      name: "Profile 1",
      color: COLORS[0],
      emoji: EMOJIS[0],
      createdAt: Date.now(),
    };
    registry = { v: 1, active: profile.id, profiles: [profile] };
    migrateLegacy(profile.id);
    saveRegistry();
    setActive(profile.id);
    return profile;
  }

  function init() {
    registry = loadRegistry();

    if (!registry.profiles.length) {
      createFirstProfile();
      installNamespace(registry.active);
      bootApp();
      return;
    }

    var requested = null;
    try {
      requested = new URLSearchParams(location.search).get("profile");
    } catch (e) {}

    if (requested) {
      if (findProfile(requested)) {
        setActive(requested);
        cleanProfileParam();
        installNamespace(requested);
        bootApp();
        return;
      }
      cleanProfileParam();
    }

    if (registry.profiles.length > 1 && !sessionProfile()) {
      openGate(false, false);
      return;
    }

    var target = sessionProfile() || registry.active || registry.profiles[0].id;
    setActive(target);
    installNamespace(target);
    bootApp();
  }

  window.SBProfiles = {
    active: function () {
      return currentProfile ? copy(currentProfile) : null;
    },
    list: function () {
      return registry ? registry.profiles.map(copy) : [];
    },
    openSheet: openSheet,
    openGate: openGate,
    switchTo: switchTo,
    activate: activate,
    add: addProfile,
    update: updateProfile,
    remove: removeProfile,
    closeAll: closeAll,
    booted: function () {
      return booted;
    },
  };

  init();
})();

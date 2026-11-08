/* Streambert Mobile - optional cross-device sync through a private GitHub Gist.
 *
 * The TMDB token identifies the app, not a user account, and TMDB has no
 * storage for profiles or watch state. This script therefore uses the TMDB
 * token only as a stable sync key: the first 24 hex characters of its SHA-256
 * hash name a private Gist in the user's GitHub account.
 *
 * The user pastes a GitHub token once per device (fine-grained token with
 * "Gists" read/write, or a classic token with the `gist` scope). That token
 * never leaves the device except in GitHub API requests. Profiles and the six
 * per-profile watch keys are merged on launch/resume, and local changes are
 * pushed a few seconds after they happen.
 *
 * Deletions are carried in a small tombstone map so a title removed on one
 * device is not resurrected by another device's older snapshot. Profile
 * deletions get their own tombstones, and profile edits win by updatedAt.
 *
 * Streambert is GPL-3.0. This file is part of a modified/ported build and is
 * therefore also GPL-3.0. Original project: https://github.com/truelockmc/streambert
 */
(function () {
  "use strict";

  var bridge = window.Android || {};
  var LS = null;
  try { LS = window.localStorage; } catch (e) {}

  var REGISTRY_KEY = "streambert_profiles";
  var META_KEY = "streambert_sync_meta";
  var DEVICE_KEY = "streambert_sync_device";
  var GIST_CACHE_KEY = "streambert_sync_gist";
  var LAST_AT_KEY = "streambert_sync_last_at";
  var TIP_KEY = "streambert_sync_tip_seen";
  var TOKEN_KEY = "githubSyncToken";

  var APP = "streambert-mobile-sync";
  var DESC_PREFIX = "Streambert Mobile Sync ";
  var GIST_FILE = "streambert-sync.json";
  var API = "https://api.github.com";

  var DATA_KEYS = [
    "saved",
    "savedOrder",
    "history",
    "progress",
    "watched",
    "searchHistory",
  ];
  var OBJECT_KEYS = { saved: 1, watched: 1, progress: 1 };
  var META_KEYS = ["saved", "watched", "progress", "history"];

  // The profiles UI deliberately caps at five. The sync payload keeps a few
  // more so that a profile created on another device is never destroyed just
  // because this device happened to be full; the extra rows stay invisible.
  var SYNC_MAX_PROFILES = 12;
  var META_MAX = 400;
  var PROFILE_META_MAX = 100;
  var HISTORY_MAX = 100;
  var SEARCH_MAX = 50;

  var overlay = null;
  var busy = false;
  var applying = false;
  var pushTimer = null;
  var pushFirstAt = 0;
  var pushFailures = 0;
  var lastError = "";
  var lastSyncAt = 0;
  var lastPullRequestedAt = 0;
  var bootAt = Date.now();

  // -----------------------------------------------------------------------
  // Tiny DOM helpers
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

  function toast(message) {
    try {
      if (bridge && typeof bridge.toast === "function") {
        bridge.toast(String(message));
      }
    } catch (e) {}
  }

  function openExternal(url) {
    try {
      if (bridge && typeof bridge.openUrl === "function") {
        bridge.openUrl(String(url));
        return;
      }
    } catch (e) {}
    try { window.open(url, "_blank"); } catch (e) {}
  }

  // -----------------------------------------------------------------------
  // Secure token storage (same SharedPreferences as the TMDB token)
  // -----------------------------------------------------------------------
  function secureGet(key) {
    if (bridge && typeof bridge.secureGet === "function") {
      try {
        var value = bridge.secureGet(String(key));
        if (value != null && value !== "") return String(value);
      } catch (e) {}
    }
    try {
      var fallback = LS.getItem("streambert_secure_" + key);
      return fallback == null || fallback === "" ? null : fallback;
    } catch (e) {
      return null;
    }
  }

  function secureSet(key, value) {
    if (bridge && typeof bridge.secureSet === "function") {
      try {
        bridge.secureSet(String(key), value == null ? "" : String(value));
        return;
      } catch (e) {}
    }
    try {
      if (value == null || value === "") {
        LS.removeItem("streambert_secure_" + key);
      } else {
        LS.setItem("streambert_secure_" + key, String(value));
      }
    } catch (e) {}
  }

  function tmdbToken() {
    var token = secureGet("apikey");
    return token ? String(token).trim() : "";
  }

  function githubToken() {
    var token = secureGet(TOKEN_KEY);
    return token ? String(token).trim() : "";
  }

  function isConnected() {
    return !!tmdbToken() && !!githubToken();
  }

  // -----------------------------------------------------------------------
  // Storage helpers
  // -----------------------------------------------------------------------
  function rawGet(key) {
    try { return LS.getItem(key); } catch (e) { return null; }
  }

  function rawSet(key, value) {
    try { LS.setItem(key, value); } catch (e) {}
  }

  function rawRemove(key) {
    try { LS.removeItem(key); } catch (e) {}
  }

  function parse(raw, fallback) {
    if (raw == null) return fallback;
    try {
      var value = JSON.parse(raw);
      return value == null ? fallback : value;
    } catch (e) {
      return fallback;
    }
  }

  function clone(value) {
    if (value == null) return value;
    try { return JSON.parse(JSON.stringify(value)); } catch (e) { return value; }
  }

  function isObject(value) {
    return !!value && typeof value === "object" && !Array.isArray(value);
  }

  function hasOwn(object, key) {
    return !!object && Object.prototype.hasOwnProperty.call(object, key);
  }

  function stable(value) {
    if (value === undefined) return "undefined";
    if (value === null || typeof value !== "object") return JSON.stringify(value);
    if (Array.isArray(value)) {
      return "[" + value.map(stable).join(",") + "]";
    }
    return (
      "{" +
      Object.keys(value)
        .sort()
        .map(function (key) {
          return JSON.stringify(key) + ":" + stable(value[key]);
        })
        .join(",") +
      "}"
    );
  }

  function syncError(code, message) {
    var error = new Error(message);
    error.code = code;
    return error;
  }

  function errorMessage(error) {
    if (!error) return "Something went wrong.";
    if (error.code === "busy") return "A sync is already running.";
    return error.message || "Something went wrong.";
  }

  // -----------------------------------------------------------------------
  // Sync key: hash of the TMDB token
  // -----------------------------------------------------------------------
  function fallbackHash(text) {
    var h1 = 0x811c9dc5;
    var h2 = 0x01000193;
    for (var i = 0; i < text.length; i++) {
      var c = text.charCodeAt(i);
      h1 = Math.imul(h1 ^ c, 16777619) >>> 0;
      h2 = Math.imul(h2 ^ c, 2246822519) >>> 0;
    }
    var len = text.length >>> 0;
    return (
      ("00000000" + h1.toString(16)).slice(-8) +
      ("00000000" + h2.toString(16)).slice(-8) +
      ("00000000" + len.toString(16)).slice(-8)
    );
  }

  function hex(buffer) {
    var bytes = new Uint8Array(buffer);
    var out = "";
    for (var i = 0; i < bytes.length; i++) {
      out += ("0" + bytes[i].toString(16)).slice(-2);
    }
    return out;
  }

  function hashKey(token) {
    token = String(token || "").trim();
    if (!token) return Promise.resolve("");
    try {
      if (
        window.crypto &&
        window.crypto.subtle &&
        typeof window.TextEncoder === "function"
      ) {
        return window.crypto.subtle
          .digest("SHA-256", new window.TextEncoder().encode(token))
          .then(function (buffer) {
            return hex(buffer).slice(0, 24);
          })
          .catch(function () {
            return fallbackHash(token);
          });
      }
    } catch (e) {}
    return Promise.resolve(fallbackHash(token));
  }

  // -----------------------------------------------------------------------
  // Registry model
  // -----------------------------------------------------------------------
  // Uploaded avatars travel with the profile registry. The same strict
  // validation and size cap as the profiles UI keeps a hand-edited Gist from
  // injecting an unsupported data URL.
  var PHOTO_MAX = 400000;
  function cleanPhoto(value) {
    if (typeof value !== "string") return "";
    if (value.length > PHOTO_MAX) return "";
    if (
      !/^data:image\/(?:jpeg|jpg|png|webp);base64,[A-Za-z0-9+/=]+$/i.test(value)
    ) {
      return "";
    }
    return value;
  }

  function sanitizeOne(raw) {
    if (!raw || typeof raw.id !== "string" || !raw.id) return null;
    var name = typeof raw.name === "string" ? raw.name.trim() : "";
    if (!name) return null;
    var created = Number(raw.createdAt) || 0;
    var updated = Number(raw.updatedAt);
    if (!isFinite(updated) || updated <= 0) updated = created;
    return {
      id: raw.id,
      name: name.slice(0, 24),
      color:
        typeof raw.color === "string" && /^#[0-9a-f]{6}$/i.test(raw.color)
          ? raw.color
          : "#E50914",
      emoji:
        typeof raw.emoji === "string" && raw.emoji
          ? raw.emoji.slice(0, 8)
          : "🎬",
      photo: cleanPhoto(raw.photo),
      createdAt: created || updated || Date.now(),
      updatedAt: updated || created || 0,
    };
  }

  function sanitizeRegistry(raw) {
    var out = { v: 1, active: null, profiles: [] };
    if (!raw || !Array.isArray(raw.profiles)) return out;
    raw.profiles.forEach(function (item) {
      if (out.profiles.length >= SYNC_MAX_PROFILES) return;
      var clean = sanitizeOne(item);
      if (clean) out.profiles.push(clean);
    });
    var active = raw.active;
    if (!active || !out.profiles.some(function (p) { return p.id === active; })) {
      active = out.profiles.length ? out.profiles[0].id : null;
    }
    out.active = active;
    return out;
  }

  function readRegistry() {
    return sanitizeRegistry(parse(rawGet(REGISTRY_KEY), null));
  }

  function writeRegistry(registry) {
    rawSet(REGISTRY_KEY, JSON.stringify(sanitizeRegistry(registry)));
  }

  function dataKey(profileId, key) {
    return "streambert_p_" + profileId + "_" + key;
  }

  function readData(profileId) {
    var out = {};
    DATA_KEYS.forEach(function (key) {
      var raw = rawGet(dataKey(profileId, key));
      if (raw === null) return;
      var value = parse(raw, null);
      if (value !== null && value !== undefined) out[key] = value;
    });
    return out;
  }

  function removeData(profileId) {
    DATA_KEYS.forEach(function (key) {
      rawRemove(dataKey(profileId, key));
    });
  }

  function applyProfileData(profileId, next, old) {
    next = next || {};
    old = old || {};
    DATA_KEYS.forEach(function (key) {
      var hasNext = hasOwn(next, key) && next[key] !== undefined;
      var hasOld = hasOwn(old, key) && old[key] !== undefined;
      if (!hasNext) {
        if (hasOld) rawRemove(dataKey(profileId, key));
        return;
      }
      if (next[key] === null) {
        if (hasOld) rawRemove(dataKey(profileId, key));
        return;
      }
      if (hasOld && stable(next[key]) === stable(old[key])) return;
      rawSet(dataKey(profileId, key), JSON.stringify(next[key]));
    });
  }

  // -----------------------------------------------------------------------
  // Sync metadata (tombstones and "added at" timestamps)
  // -----------------------------------------------------------------------
  function normalizeMeta(raw) {
    var out = { v: 1, profiles: {}, deletedProfiles: {}, bootstrappedAt: 0 };
    if (!raw || typeof raw !== "object") return out;
    out.bootstrappedAt = Number(raw.bootstrappedAt) || 0;
    if (isObject(raw.deletedProfiles)) {
      Object.keys(raw.deletedProfiles).forEach(function (id) {
        var ts = Number(raw.deletedProfiles[id]) || 0;
        if (ts > 0) out.deletedProfiles[id] = ts;
      });
    }
    if (isObject(raw.profiles)) {
      Object.keys(raw.profiles).forEach(function (profileId) {
        var source = raw.profiles[profileId];
        if (!isObject(source)) return;
        var dest = {};
        META_KEYS.forEach(function (kind) {
          var src = source[kind];
          if (!isObject(src)) return;
          var entry = { adds: {}, dels: {} };
          ["adds", "dels"].forEach(function (bucket) {
            if (!isObject(src[bucket])) return;
            Object.keys(src[bucket]).forEach(function (item) {
              var ts = Number(src[bucket][item]) || 0;
              if (ts > 0) entry[bucket][item] = ts;
            });
          });
          dest[kind] = entry;
        });
        out.profiles[profileId] = dest;
      });
    }
    return out;
  }

  function readMeta() {
    return normalizeMeta(parse(rawGet(META_KEY), null));
  }

  function writeMeta(meta) {
    rawSet(META_KEY, JSON.stringify(meta));
  }

  function pruneEntry(entry) {
    ["adds", "dels"].forEach(function (bucket) {
      var map = entry[bucket];
      if (!map) return;
      var keys = Object.keys(map);
      if (keys.length <= META_MAX) return;
      keys
        .sort(function (a, b) { return (map[a] || 0) - (map[b] || 0); })
        .slice(0, keys.length - META_MAX)
        .forEach(function (key) { delete map[key]; });
    });
  }

  function pruneDeletedProfiles(meta) {
    var map = meta.deletedProfiles || {};
    var keys = Object.keys(map);
    if (keys.length <= PROFILE_META_MAX) return;
    keys
      .sort(function (a, b) { return (map[a] || 0) - (map[b] || 0); })
      .slice(0, keys.length - PROFILE_META_MAX)
      .forEach(function (key) { delete map[key]; });
  }

  function histId(item) {
    if (!item || typeof item !== "object" || item.id == null) return "";
    return [
      item.media_type || "",
      item.id,
      item.season == null ? "" : item.season,
      item.episode == null ? "" : item.episode,
    ].join("|");
  }

  function deviceId() {
    var id = rawGet(DEVICE_KEY);
    if (id) return id;
    id =
      "d" +
      Date.now().toString(36) +
      Math.random().toString(36).slice(2, 8);
    rawSet(DEVICE_KEY, id);
    return id;
  }

  function buildLocalPayload(key) {
    var registry = readRegistry();
    var data = {};
    registry.profiles.forEach(function (profile) {
      data[profile.id] = readData(profile.id);
    });
    return {
      app: APP,
      version: 1,
      key: key,
      updatedAt: Date.now(),
      device: deviceId(),
      registry: registry,
      data: data,
      meta: readMeta(),
    };
  }

  // -----------------------------------------------------------------------
  // Local change tracking
  // -----------------------------------------------------------------------
  function schedulePush(delay) {
    if (applying || !isConnected()) return;
    if (pushFirstAt === 0) pushFirstAt = Date.now();
    clearTimeout(pushTimer);
    var wait =
      delay != null
        ? delay
        : Date.now() - pushFirstAt > 30000
        ? 2000
        : 9000;
    pushTimer = setTimeout(function () {
      pushTimer = null;
      pushFirstAt = 0;
      pushNow();
    }, wait);
  }

  function handleRegistryChange(oldRaw, newRaw) {
    var oldReg = parse(oldRaw, null);
    var newReg = parse(newRaw, null);
    if (!newReg || !Array.isArray(newReg.profiles)) {
      schedulePush();
      return;
    }
    var oldIds = {};
    var newIds = {};
    if (oldReg && Array.isArray(oldReg.profiles)) {
      oldReg.profiles.forEach(function (profile) {
        if (profile && profile.id) oldIds[profile.id] = 1;
      });
    }
    newReg.profiles.forEach(function (profile) {
      if (profile && profile.id) newIds[profile.id] = 1;
    });

    var meta = readMeta();
    var changed = false;
    Object.keys(oldIds).forEach(function (id) {
      if (!newIds[id]) {
        meta.deletedProfiles[id] = Date.now();
        changed = true;
      }
    });
    Object.keys(newIds).forEach(function (id) {
      if (!oldIds[id] && meta.deletedProfiles[id]) {
        delete meta.deletedProfiles[id];
        changed = true;
      }
    });
    if (changed) {
      pruneDeletedProfiles(meta);
      writeMeta(meta);
    }

    // Switching profiles only changes `active`; that is device-local and does
    // not need a network round-trip. Adds, edits and deletes do.
    var before = oldReg ? stable(oldReg.profiles || []) : "";
    var after = stable(newReg.profiles || []);
    if (changed || before !== after) schedulePush();
  }

  function onProfileChanged(key, oldValue, newValue) {
    if (applying) return;
    if (key === REGISTRY_KEY) {
      handleRegistryChange(oldValue, newValue);
      return;
    }
    var match = /^streambert_p_([A-Za-z0-9]+)_(saved|savedOrder|history|progress|watched|searchHistory)$/.exec(
      key
    );
    if (!match) return;
    var profileId = match[1];
    var short = match[2];
    if (oldValue === newValue) return;
    if (short === "savedOrder" || short === "searchHistory") {
      schedulePush();
      return;
    }

    var meta = readMeta();
    var profileMeta = meta.profiles[profileId] || (meta.profiles[profileId] = {});
    var entry = profileMeta[short] || (profileMeta[short] = { adds: {}, dels: {} });
    entry.adds = entry.adds || {};
    entry.dels = entry.dels || {};
    var now = Date.now();
    var changed = false;

    if (OBJECT_KEYS[short]) {
      var oldObj = parse(oldValue, {});
      var newObj = parse(newValue, {});
      if (!isObject(oldObj)) oldObj = {};
      if (!isObject(newObj)) newObj = {};
      Object.keys(newObj).forEach(function (item) {
        if (!hasOwn(oldObj, item)) {
          entry.adds[item] = now;
          delete entry.dels[item];
          changed = true;
        }
      });
      Object.keys(oldObj).forEach(function (item) {
        if (!hasOwn(newObj, item)) {
          entry.dels[item] = now;
          delete entry.adds[item];
          changed = true;
        }
      });
    } else if (short === "history") {
      var oldArr = parse(oldValue, []);
      var newArr = parse(newValue, []);
      if (!Array.isArray(oldArr)) oldArr = [];
      if (!Array.isArray(newArr)) newArr = [];
      var oldIds = {};
      var newIds = {};
      oldArr.forEach(function (item) {
        var id = histId(item);
        if (id) oldIds[id] = 1;
      });
      newArr.forEach(function (item) {
        var id = histId(item);
        if (id) newIds[id] = 1;
      });
      Object.keys(newIds).forEach(function (id) {
        if (!oldIds[id]) {
          entry.adds[id] = now;
          delete entry.dels[id];
          changed = true;
        }
      });
      Object.keys(oldIds).forEach(function (id) {
        if (!newIds[id]) {
          entry.dels[id] = now;
          delete entry.adds[id];
          changed = true;
        }
      });
    }

    if (changed) {
      pruneEntry(entry);
      writeMeta(meta);
    }
    // Progress updates keep the same keys but change the value, so a push is
    // needed even when no tombstone was recorded.
    schedulePush();
  }

  function bootstrapMeta(registry, meta) {
    if (meta.bootstrappedAt) return false;
    var now = Date.now();
    registry.profiles.forEach(function (profile) {
      var data = readData(profile.id);
      var profileMeta = meta.profiles[profile.id] || (meta.profiles[profile.id] = {});
      META_KEYS.forEach(function (kind) {
        var value = data[kind];
        if (value === undefined) return;
        var entry = profileMeta[kind] || (profileMeta[kind] = { adds: {}, dels: {} });
        entry.adds = entry.adds || {};
        entry.dels = entry.dels || {};
        if (OBJECT_KEYS[kind]) {
          if (isObject(value)) {
            Object.keys(value).forEach(function (item) {
              if (!entry.dels[item] && !entry.adds[item]) entry.adds[item] = now;
            });
          }
        } else if (kind === "history" && Array.isArray(value)) {
          value.forEach(function (item) {
            var id = histId(item);
            if (id && !entry.dels[id] && !entry.adds[id]) entry.adds[id] = now;
          });
        }
      });
    });
    meta.bootstrappedAt = now;
    writeMeta(meta);
    return true;
  }

  // -----------------------------------------------------------------------
  // Merge
  // -----------------------------------------------------------------------
  function mergeDeletedProfiles(localMeta, remoteMeta) {
    var out = {};
    [localMeta, remoteMeta].forEach(function (meta) {
      if (!meta || !isObject(meta.deletedProfiles)) return;
      Object.keys(meta.deletedProfiles).forEach(function (id) {
        var ts = Number(meta.deletedProfiles[id]) || 0;
        if (ts > (out[id] || 0)) out[id] = ts;
      });
    });
    return out;
  }

  function mergeMeta(localMeta, remoteMeta, idMap) {
    var out = {
      v: 1,
      profiles: {},
      deletedProfiles: mergeDeletedProfiles(localMeta, remoteMeta),
      bootstrappedAt: Math.max(
        Number(localMeta && localMeta.bootstrappedAt) || 0,
        Number(remoteMeta && remoteMeta.bootstrappedAt) || 0
      ),
    };
    function mergeOne(destId, source) {
      if (!source || typeof source !== "object") return;
      var dest = out.profiles[destId] || (out.profiles[destId] = {});
      META_KEYS.forEach(function (kind) {
        var src = source[kind];
        if (!src) return;
        var entry = dest[kind] || (dest[kind] = { adds: {}, dels: {} });
        ["adds", "dels"].forEach(function (bucket) {
          var map = src[bucket];
          if (!map) return;
          var target = entry[bucket] || (entry[bucket] = {});
          Object.keys(map).forEach(function (item) {
            var ts = Number(map[item]) || 0;
            if (ts > (target[item] || 0)) target[item] = ts;
          });
        });
        pruneEntry(entry);
      });
    }
    if (localMeta && isObject(localMeta.profiles)) {
      Object.keys(localMeta.profiles).forEach(function (id) {
        mergeOne(id, localMeta.profiles[id]);
      });
    }
    if (remoteMeta && isObject(remoteMeta.profiles)) {
      Object.keys(remoteMeta.profiles).forEach(function (remoteId) {
        mergeOne(idMap[remoteId] || remoteId, remoteMeta.profiles[remoteId]);
      });
    }
    return out;
  }

  function hasAnyData(data) {
    if (!data) return false;
    ["saved", "watched", "progress"].forEach(function (key) {
      var value = data[key];
      if (isObject(value) && Object.keys(value).length) return true;
      if (Array.isArray(value) && value.length) return true;
    });
    if (Array.isArray(data.history) && data.history.length) return true;
    if (Array.isArray(data.savedOrder) && data.savedOrder.length) return true;
    if (Array.isArray(data.searchHistory) && data.searchHistory.length) return true;
    return false;
  }

  function isBlankDefault(registry, localData) {
    if (!registry || registry.profiles.length !== 1) return false;
    var profile = registry.profiles[0];
    if (profile.name !== "Profile 1") return false;
    if (profile.photo) return false;
    return !hasAnyData((localData && localData[profile.id]) || readData(profile.id));
  }

  function mergeRegistry(localReg, remoteReg, localData, deletedProfiles) {
    var idMap = {};
    var removeIds = [];
    localReg = sanitizeRegistry(localReg);
    remoteReg = sanitizeRegistry(remoteReg);

    // Drop profiles that the other device deleted. Profile ids are random and
    // never reused, so a tombstone can win unconditionally.
    var keptLocal = [];
    localReg.profiles.forEach(function (profile) {
      if (deletedProfiles && deletedProfiles[profile.id]) {
        removeIds.push(profile.id);
        return;
      }
      keptLocal.push(profile);
    });
    localReg = {
      v: 1,
      active: localReg.active,
      profiles: keptLocal,
    };
    if (!localReg.profiles.some(function (p) { return p.id === localReg.active; })) {
      localReg.active = localReg.profiles.length ? localReg.profiles[0].id : null;
    }

    var keptRemote = [];
    remoteReg.profiles.forEach(function (profile) {
      if (deletedProfiles && deletedProfiles[profile.id]) return;
      keptRemote.push(profile);
    });
    remoteReg = {
      v: 1,
      active: remoteReg.active,
      profiles: keptRemote,
    };
    if (!remoteReg.profiles.some(function (p) { return p.id === remoteReg.active; })) {
      remoteReg.active = remoteReg.profiles.length ? remoteReg.profiles[0].id : null;
    }

    if (!localReg.profiles.length) {
      var fresh = sanitizeRegistry({
        v: 1,
        active: remoteReg.active,
        profiles: remoteReg.profiles,
      });
      remoteReg.profiles.forEach(function (profile) {
        idMap[profile.id] = profile.id;
      });
      return { registry: fresh, idMap: idMap, removeIds: removeIds };
    }

    if (remoteReg.profiles.length && isBlankDefault(localReg, localData)) {
      var replacement = sanitizeRegistry({
        v: 1,
        active: remoteReg.active,
        profiles: remoteReg.profiles,
      });
      if (replacement.profiles.length) {
        removeIds.push(localReg.profiles[0].id);
        remoteReg.profiles.forEach(function (profile) {
          idMap[profile.id] = profile.id;
        });
        return { registry: replacement, idMap: idMap, removeIds: removeIds };
      }
    }

    var out = sanitizeRegistry(localReg);
    var byName = {};
    out.profiles.forEach(function (profile) {
      byName[profile.name.toLowerCase()] = profile;
    });

    function nameConflict(profile, wanted, exceptId) {
      return out.profiles.some(function (item) {
        return (
          item.id !== exceptId &&
          item.name.toLowerCase() === wanted.toLowerCase()
        );
      });
    }

    remoteReg.profiles.forEach(function (remoteProfile) {
      var clean = sanitizeOne(remoteProfile);
      if (!clean) return;
      var existing = null;
      for (var i = 0; i < out.profiles.length; i++) {
        if (out.profiles[i].id === clean.id) {
          existing = out.profiles[i];
          break;
        }
      }
      if (existing) {
        idMap[clean.id] = existing.id;
        if ((clean.updatedAt || 0) > (existing.updatedAt || 0)) {
          if (
            clean.name &&
            clean.name.toLowerCase() !== existing.name.toLowerCase() &&
            !nameConflict(existing, clean.name, existing.id)
          ) {
            existing.name = clean.name;
          }
          existing.color = clean.color;
          existing.emoji = clean.emoji;
          existing.photo = clean.photo;
          existing.updatedAt = clean.updatedAt;
        }
        return;
      }

      var sameName = byName[clean.name.toLowerCase()];
      if (sameName) {
        idMap[clean.id] = sameName.id;
        if ((clean.updatedAt || 0) > (sameName.updatedAt || 0)) {
          sameName.color = clean.color;
          sameName.emoji = clean.emoji;
          sameName.photo = clean.photo;
          sameName.updatedAt = clean.updatedAt;
        }
        return;
      }

      if (out.profiles.length >= SYNC_MAX_PROFILES) return;
      out.profiles.push(clean);
      byName[clean.name.toLowerCase()] = clean;
      idMap[clean.id] = clean.id;
    });

    if (!out.profiles.some(function (p) { return p.id === out.active; })) {
      out.active = out.profiles.some(function (p) { return p.id === remoteReg.active; })
        ? remoteReg.active
        : out.profiles.length
        ? out.profiles[0].id
        : null;
    }

    return { registry: out, idMap: idMap, removeIds: removeIds };
  }

  function mergeProgress(a, b, hasA, hasB) {
    if (!hasA) return b;
    if (!hasB) return a;
    if (typeof a === "number" && typeof b === "number") return Math.max(a, b);
    if (isObject(a) || isObject(b)) {
      var ta = isObject(a)
        ? Number(a.updatedAt || a.updated || a.ts || 0)
        : 0;
      var tb = isObject(b)
        ? Number(b.updatedAt || b.updated || b.ts || 0)
        : 0;
      if (ta !== tb) return ta > tb ? a : b;
      var ca = isObject(a) ? Number(a.currentTime || 0) : 0;
      var cb = isObject(b) ? Number(b.currentTime || 0) : 0;
      if (ca !== cb) return ca > cb ? a : b;
      return a;
    }
    return a;
  }

  function mergeObject(a, b, kind, entry) {
    var aObj = isObject(a);
    var bObj = isObject(b);

    // Legacy installs could have arrays/strings in these keys. Keep a legacy
    // value untouched only when there is no modern object on either side.
    if (!aObj && !bObj) {
      if (a !== undefined) return clone(a);
      if (b !== undefined) return clone(b);
      return {};
    }

    // Treat a missing side as an empty object so a tombstone from that side
    // still removes the item from the surviving side. This is the important
    // cross-device delete case (device B deletes the last saved title, its
    // `saved` key disappears, and device A must not resurrect it).
    var left = aObj ? a : {};
    var right = bObj ? b : {};

    var out = {};
    var adds = (entry[kind] && entry[kind].adds) || {};
    var dels = (entry[kind] && entry[kind].dels) || {};
    var keys = {};
    Object.keys(left).forEach(function (key) { keys[key] = 1; });
    Object.keys(right).forEach(function (key) { keys[key] = 1; });
    Object.keys(keys).forEach(function (key) {
      var add = adds[key] || 0;
      var del = dels[key] || 0;
      if (del > add) return;
      var hasA = hasOwn(left, key);
      var hasB = hasOwn(right, key);
      var value;
      if (kind === "progress") {
        value = mergeProgress(hasA ? left[key] : undefined, hasB ? right[key] : undefined, hasA, hasB);
      } else if (kind === "watched") {
        value = true;
      } else {
        value = hasA ? left[key] : right[key];
      }
      out[key] = value;
      if (!adds[key]) adds[key] = 1;
    });
    entry[kind] = { adds: adds, dels: dels };
    return out;
  }

  function mergeOrder(a, b, saved) {
    var aArr = Array.isArray(a) ? a : null;
    var bArr = Array.isArray(b) ? b : null;
    if (!aArr && !bArr) {
      if (a !== undefined) return a;
      if (b !== undefined) return b;
      return null;
    }
    var out = [];
    var seen = {};
    (aArr || []).concat(bArr || []).forEach(function (key) {
      if (typeof key !== "string" || seen[key]) return;
      if (isObject(saved) && !hasOwn(saved, key)) return;
      seen[key] = 1;
      out.push(key);
    });
    if (isObject(saved)) {
      Object.keys(saved).forEach(function (key) {
        if (seen[key]) return;
        seen[key] = 1;
        out.push(key);
      });
    }
    return out;
  }

  function mergeHistory(a, b, entry) {
    var merged = {};
    function add(item) {
      if (!item || typeof item !== "object" || item.id == null) return;
      var id = histId(item);
      if (!id) return;
      var previous = merged[id];
      if (
        !previous ||
        (Number(item.watchedAt) || 0) > (Number(previous.watchedAt) || 0)
      ) {
        merged[id] = item;
      }
    }
    (Array.isArray(a) ? a : []).forEach(add);
    (Array.isArray(b) ? b : []).forEach(add);

    var adds = (entry.history && entry.history.adds) || {};
    var dels = (entry.history && entry.history.dels) || {};
    var items = Object.keys(merged)
      .map(function (id) { return merged[id]; })
      .filter(function (item) {
        var id = histId(item);
        var add = adds[id] || Number(item.watchedAt) || 1;
        var del = dels[id] || 0;
        if (del > add) return false;
        if (!adds[id]) adds[id] = Number(item.watchedAt) || 1;
        return true;
      });
    items.sort(function (x, y) {
      return (Number(y.watchedAt) || 0) - (Number(x.watchedAt) || 0);
    });
    entry.history = { adds: adds, dels: dels };
    return items.slice(0, HISTORY_MAX);
  }

  function mergeList(a, b, cap) {
    var out = [];
    var seen = {};
    function add(value) {
      var key = stable(value);
      if (seen[key]) return;
      seen[key] = 1;
      out.push(value);
    }
    (Array.isArray(a) ? a : []).forEach(add);
    (Array.isArray(b) ? b : []).forEach(add);
    return out.slice(0, cap);
  }

  function mergeData(local, remote, entry) {
    local = local || {};
    remote = remote || {};
    entry = entry || {};
    var out = {};

    if (local.saved !== undefined || remote.saved !== undefined) {
      out.saved = mergeObject(local.saved, remote.saved, "saved", entry);
    }
    if (local.watched !== undefined || remote.watched !== undefined) {
      out.watched = mergeObject(local.watched, remote.watched, "watched", entry);
    }
    if (local.progress !== undefined || remote.progress !== undefined) {
      out.progress = mergeObject(local.progress, remote.progress, "progress", entry);
    }
    if (local.savedOrder !== undefined || remote.savedOrder !== undefined) {
      out.savedOrder = mergeOrder(local.savedOrder, remote.savedOrder, out.saved);
    }
    if (local.history !== undefined || remote.history !== undefined) {
      out.history = mergeHistory(local.history, remote.history, entry);
    }
    if (local.searchHistory !== undefined || remote.searchHistory !== undefined) {
      out.searchHistory = mergeList(local.searchHistory, remote.searchHistory, SEARCH_MAX);
    }
    return out;
  }

  // Comparison helpers. `active`, profile order and the order of the
  // saved/search lists are device-local presentation details; two devices
  // must not keep pushing the Gist back and forth just because they display
  // those in a different order.
  function registrySig(registry) {
    return {
      v: registry.v,
      profiles: (registry.profiles || []).slice().sort(function (a, b) {
        return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
      }),
    };
  }

  function dataSig(data) {
    var out = {};
    Object.keys(data || {})
      .sort()
      .forEach(function (profileId) {
        var source = data[profileId] || {};
        var item = {};
        if (source.saved !== undefined) item.saved = source.saved;
        if (source.watched !== undefined) item.watched = source.watched;
        if (source.progress !== undefined) item.progress = source.progress;
        if (source.savedOrder !== undefined) {
          item.savedOrder = Array.isArray(source.savedOrder)
            ? source.savedOrder.slice().map(String).sort()
            : source.savedOrder;
        }
        if (source.history !== undefined) {
          item.history = Array.isArray(source.history)
            ? source.history.slice().sort(function (a, b) {
                return stable(a).localeCompare(stable(b));
              })
            : source.history;
        }
        if (source.searchHistory !== undefined) {
          item.searchHistory = Array.isArray(source.searchHistory)
            ? source.searchHistory.map(stable).sort()
            : source.searchHistory;
        }
        out[profileId] = item;
      });
    return out;
  }

  function contentSig(registry, data) {
    return stable({ registry: registrySig(registry), data: dataSig(data) });
  }

  function fullSig(registry, data, meta) {
    return stable({
      registry: registrySig(registry),
      data: dataSig(data),
      meta: meta,
    });
  }

  function mergePayloads(local, remote, key) {
    var localReg = sanitizeRegistry(local.registry);
    var remoteReg = remote ? sanitizeRegistry(remote.registry) : { v: 1, active: null, profiles: [] };
    var localData = local.data || {};
    var remoteData = remote ? remote.data || {} : {};

    var deletedProfiles = mergeDeletedProfiles(local.meta, remote && remote.meta);
    var info = mergeRegistry(localReg, remoteReg, localData, deletedProfiles);

    // Canonical profile order (creation order, then id) so the same set of
    // profiles is written the same way on every device.
    info.registry.profiles.sort(function (a, b) {
      var ca = Number(a.createdAt) || 0;
      var cb = Number(b.createdAt) || 0;
      if (ca !== cb) return ca - cb;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });

    var meta = mergeMeta(local.meta, remote && remote.meta, info.idMap);
    meta.deletedProfiles = deletedProfiles;

    var keep = {};
    info.registry.profiles.forEach(function (profile) { keep[profile.id] = 1; });
    Object.keys(meta.profiles).forEach(function (id) {
      if (!keep[id]) delete meta.profiles[id];
    });

    var remoteByLocal = {};
    Object.keys(info.idMap).forEach(function (remoteId) {
      remoteByLocal[info.idMap[remoteId]] = remoteId;
    });

    var data = {};
    info.registry.profiles.forEach(function (profile) {
      var profileMeta = meta.profiles[profile.id] || (meta.profiles[profile.id] = {});
      var remoteId = remoteByLocal[profile.id];
      data[profile.id] = mergeData(
        localData[profile.id] || {},
        remoteId ? remoteData[remoteId] || {} : {},
        profileMeta
      );
      META_KEYS.forEach(function (kind) {
        if (profileMeta[kind]) pruneEntry(profileMeta[kind]);
      });
    });

    Object.keys(meta.profiles).forEach(function (id) {
      if (!keep[id]) delete meta.profiles[id];
    });

    var payload = {
      app: APP,
      version: 1,
      key: key,
      updatedAt: Date.now(),
      device: local.device,
      registry: info.registry,
      data: data,
      meta: meta,
    };

    var localSig = contentSig(localReg, localData);
    var mergedSig = contentSig(info.registry, data);
    var remoteSig = remote
      ? fullSig(remoteReg, remoteData, normalizeMeta(remote.meta))
      : "";
    var mergedFullSig = fullSig(info.registry, data, meta);

    return {
      payload: payload,
      removeIds: info.removeIds,
      localChanged: mergedSig !== localSig,
      metaChanged: stable(meta) !== stable(normalizeMeta(local.meta)),
      remoteChanged: !remote || mergedFullSig !== remoteSig,
    };
  }

  // -----------------------------------------------------------------------
  // GitHub API
  // -----------------------------------------------------------------------
  function githubRequest(token, method, path, body) {
    var fetchFn = window.fetch;
    if (typeof fetchFn !== "function") {
      return Promise.reject(
        syncError("network", "This WebView cannot reach GitHub.")
      );
    }
    var headers = {
      Accept: "application/vnd.github+json",
      Authorization: "Bearer " + token,
      "X-GitHub-Api-Version": "2022-11-28",
    };
    var options = {
      method: method,
      headers: headers,
      cache: "no-store",
    };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(body);
    }
    var controller = null;
    if (typeof window.AbortController === "function") {
      controller = new window.AbortController();
      options.signal = controller.signal;
    }
    var timer = setTimeout(function () {
      if (controller) {
        try { controller.abort(); } catch (e) {}
      }
    }, 15000);

    return fetchFn(API + path, options).then(
      function (response) {
        clearTimeout(timer);
        return response.text().then(function (text) {
          var data = null;
          try { data = text ? JSON.parse(text) : null; } catch (e) {}
          return {
            status: response.status,
            data: data,
            remaining: response.headers.get("X-RateLimit-Remaining"),
          };
        });
      },
      function () {
        clearTimeout(timer);
        throw syncError(
          "network",
          "Cannot reach GitHub. Check your internet connection."
        );
      }
    );
  }

  function errorFromResponse(response) {
    if (response.status === 401) {
      return syncError(
        "auth",
        "GitHub rejected the token. Check that you copied the whole token."
      );
    }
    if (response.status === 403) {
      if (response.remaining === "0") {
        return syncError(
          "rate",
          "GitHub rate limit reached. Try again in a few minutes."
        );
      }
      return syncError(
        "forbidden",
        "This token cannot access Gists. Give it Gist read/write permission."
      );
    }
    if (response.status === 404) {
      return syncError("missing", "The sync Gist was not found.");
    }
    if (response.status >= 500) {
      return syncError("server", "GitHub is temporarily unavailable.");
    }
    return syncError("http", "GitHub returned an error (" + response.status + ").");
  }

  function api(method, path, body) {
    var token = githubToken();
    if (!token) {
      return Promise.resolve({ status: 0, data: null, remaining: null });
    }
    return githubRequest(token, method, path, body);
  }

  function validateToken(token) {
    return githubRequest(token, "GET", "/gists?per_page=1").then(function (response) {
      if (response.status === 200) return true;
      if (response.status === 401) {
        throw syncError(
          "auth",
          "GitHub rejected that token. Check that you copied the whole token."
        );
      }
      if (response.status === 403) {
        throw syncError(
          "forbidden",
          "That token cannot access Gists. Give it Gist read/write access."
        );
      }
      throw errorFromResponse(response);
    });
  }

  function gistDescription(key) {
    return DESC_PREFIX + key;
  }

  function cacheGist(key, id) {
    rawSet(GIST_CACHE_KEY, JSON.stringify({ key: key, id: id }));
  }

  function clearGistCache() {
    rawRemove(GIST_CACHE_KEY);
  }

  function findGist(key) {
    var cached = parse(rawGet(GIST_CACHE_KEY), null);
    var cachedLookup =
      cached && cached.key === key && cached.id
        ? api("GET", "/gists/" + encodeURIComponent(cached.id)).then(function (response) {
            if (response.status === 200 && response.data && response.data.description === gistDescription(key)) {
              return response.data;
            }
            if (response.status === 401 || response.status === 403) {
              throw errorFromResponse(response);
            }
            if (response.status === 404) clearGistCache();
            return null;
          })
        : Promise.resolve(null);

    return cachedLookup.then(function (found) {
      if (found) return found;
      return listGists(key, 1);
    });
  }

  function listGists(key, page) {
    if (page > 10) return Promise.resolve(null);
    return api("GET", "/gists?per_page=100&page=" + page).then(function (response) {
      if (response.status !== 200 || !Array.isArray(response.data)) {
        throw errorFromResponse(response);
      }
      var list = response.data;
      for (var i = 0; i < list.length; i++) {
        if (list[i] && list[i].description === gistDescription(key)) {
          cacheGist(key, list[i].id);
          return api("GET", "/gists/" + encodeURIComponent(list[i].id)).then(function (full) {
            if (full.status === 200 && full.data) return full.data;
            throw errorFromResponse(full);
          });
        }
      }
      if (list.length < 100) return null;
      return listGists(key, page + 1);
    });
  }

  function readRemotePayload(gist) {
    var file = gist && gist.files && gist.files[GIST_FILE];
    if (!file || typeof file.content !== "string" || !file.content.trim()) {
      return null;
    }
    var raw = parse(file.content, null);
    if (
      !raw ||
      raw.app !== APP ||
      !raw.registry ||
      !Array.isArray(raw.registry.profiles) ||
      !isObject(raw.data)
    ) {
      return null;
    }
    var registry = sanitizeRegistry(raw.registry);
    var data = {};
    registry.profiles.forEach(function (profile) {
      var value = raw.data[profile.id];
      data[profile.id] = isObject(value) ? value : {};
    });
    return {
      app: APP,
      version: 1,
      key: raw.key || "",
      updatedAt: Number(raw.updatedAt) || 0,
      device: raw.device || "",
      registry: registry,
      data: data,
      meta: normalizeMeta(raw.meta),
    };
  }

  function createGist(key, payload) {
    var files = {};
    files[GIST_FILE] = { content: JSON.stringify(payload, null, 2) };
    return api("POST", "/gists", {
      description: gistDescription(key),
      public: false,
      files: files,
    }).then(function (response) {
      if (response.status !== 201) throw errorFromResponse(response);
      if (response.data && response.data.id) cacheGist(key, response.data.id);
      return response.data;
    });
  }

  function updateGist(gist, payload) {
    if (!gist || !gist.id) {
      return Promise.reject(syncError("missing", "The sync Gist was not found."));
    }
    var files = {};
    files[GIST_FILE] = { content: JSON.stringify(payload, null, 2) };
    return api("PATCH", "/gists/" + encodeURIComponent(gist.id), {
      files: files,
    }).then(function (response) {
      if (response.status === 404) {
        clearGistCache();
        throw syncError("missing", "The sync Gist was removed; recreating it.");
      }
      if (response.status !== 200) throw errorFromResponse(response);
      return response.data;
    });
  }

  function syncRemote(key, gist, payload) {
    if (!gist) return createGist(key, payload);
    return updateGist(gist, payload).catch(function (error) {
      if (error && error.code === "missing") {
        clearGistCache();
        return createGist(key, payload);
      }
      throw error;
    });
  }

  // -----------------------------------------------------------------------
  // Apply merged data
  // -----------------------------------------------------------------------
  function markSynced() {
    lastSyncAt = Date.now();
    rawSet(LAST_AT_KEY, String(lastSyncAt));
  }

  function shouldReload(force) {
    if (force) return true;
    try {
      if (window.SBProfiles && typeof window.SBProfiles.booted === "function" && !window.SBProfiles.booted()) {
        return true;
      }
    } catch (e) {}
    return Date.now() - bootAt < 15000;
  }

  function reloadNow() {
    if (window.__SB_SYNC_NO_RELOAD) return;
    var count = 0;
    try {
      count = Number(sessionStorage.getItem("streambert_sync_reloads")) || 0;
    } catch (e) {}
    if (count >= 2) {
      showBanner();
      return;
    }
    try { sessionStorage.setItem("streambert_sync_reloads", String(count + 1)); } catch (e) {}
    setTimeout(function () {
      try { location.reload(); } catch (e) {}
    }, 120);
  }

  function clearReloadGuard() {
    try { sessionStorage.removeItem("streambert_sync_reloads"); } catch (e) {}
  }

  function showBanner() {
    if (document.querySelector(".sb-sync-banner")) return;
    var bar = el("div", "sb-sync-banner");
    bar.appendChild(el("span", "sb-sync-banner-text", "Profile data synced from another device"));
    var refresh = button("sb-sync-banner-refresh", "Refresh");
    refresh.addEventListener("click", function () {
      try { location.reload(); } catch (e) {}
    });
    bar.appendChild(refresh);
    var close = button("sb-sync-banner-close", "✕");
    close.setAttribute("aria-label", "Dismiss");
    close.addEventListener("click", function () {
      if (bar.parentNode) bar.parentNode.removeChild(bar);
    });
    bar.appendChild(close);
    document.body.appendChild(bar);
  }

  function applyMerged(result, local, opts) {
    opts = opts || {};
    var payload = result.payload;
    applying = true;
    try {
      (result.removeIds || []).forEach(function (id) {
        removeData(id);
      });
      writeRegistry(payload.registry);
      payload.registry.profiles.forEach(function (profile) {
        applyProfileData(
          profile.id,
          payload.data[profile.id] || {},
          (local.data && local.data[profile.id]) || {}
        );
      });
      writeMeta(payload.meta);
      markSynced();
    } finally {
      applying = false;
    }
    if (shouldReload(opts.forceReload)) {
      reloadNow();
    } else {
      clearReloadGuard();
      showBanner();
    }
    return true;
  }

  // -----------------------------------------------------------------------
  // Sync cycles
  // -----------------------------------------------------------------------
  function pull(opts) {
    opts = opts || {};
    if (!isConnected()) {
      return Promise.resolve({ ok: false, reason: "not_connected" });
    }
    if (busy) {
      return Promise.resolve({ ok: false, reason: "busy" });
    }
    busy = true;
    var token = tmdbToken();
    lastPullRequestedAt = Date.now();

    return hashKey(token)
      .then(function (key) {
        var local = buildLocalPayload(key);
        bootstrapMeta(local.registry, local.meta);
        return findGist(key).then(function (gist) {
          if (!gist) {
            return createGist(key, local).then(function () {
              markSynced();
              lastError = "";
              clearReloadGuard();
              return { ok: true, created: true, changed: false };
            });
          }

          var remote = readRemotePayload(gist);
          if (!remote) {
            return syncRemote(key, gist, local).then(function () {
              markSynced();
              lastError = "";
              clearReloadGuard();
              return { ok: true, changed: false };
            });
          }

          var merged = mergePayloads(local, remote, key);
          var push = merged.remoteChanged
            ? syncRemote(key, gist, merged.payload)
            : Promise.resolve();

          return push
            .then(function () {
              lastError = "";
            })
            .catch(function (error) {
              lastError = errorMessage(error);
            })
            .then(function () {
              var applied = false;
              if (merged.localChanged) {
                applied = applyMerged(merged, local, opts);
              } else {
                writeMeta(merged.payload.meta);
                markSynced();
                clearReloadGuard();
              }
              return {
                ok: true,
                changed: applied,
                pushed: merged.remoteChanged,
                key: key,
                error: lastError || "",
              };
            });
        });
      })
      .catch(function (error) {
        lastError = errorMessage(error);
        return { ok: false, error: lastError };
      })
      .then(
        function (result) {
          busy = false;
          return result;
        },
        function (error) {
          busy = false;
          lastError = errorMessage(error);
          return { ok: false, error: lastError };
        }
      );
  }

  function pushNow() {
    if (!isConnected()) {
      return Promise.resolve({ ok: false, reason: "not_connected" });
    }
    if (busy) {
      schedulePush(5000);
      return Promise.resolve({ ok: false, reason: "busy" });
    }
    busy = true;
    var token = tmdbToken();

    return hashKey(token)
      .then(function (key) {
        var local = buildLocalPayload(key);
        bootstrapMeta(local.registry, local.meta);
        return findGist(key).then(function (gist) {
          var remote = gist ? readRemotePayload(gist) : null;
          var merged = remote
            ? mergePayloads(local, remote, key)
            : { payload: local, remoteChanged: true };
          if (!merged.remoteChanged && remote) {
            writeMeta(merged.payload.meta);
            markSynced();
            return { ok: true, changed: false };
          }
          return syncRemote(key, gist, merged.payload).then(function () {
            writeMeta(merged.payload.meta);
            markSynced();
            lastError = "";
            pushFailures = 0;
            return { ok: true, changed: true };
          });
        });
      })
      .catch(function (error) {
        lastError = errorMessage(error);
        pushFailures += 1;
        if (pushFailures <= 4) {
          schedulePush(Math.min(60000, 10000 * pushFailures));
        }
        return { ok: false, error: lastError };
      })
      .then(
        function (result) {
          busy = false;
          return result;
        },
        function (error) {
          busy = false;
          lastError = errorMessage(error);
          return { ok: false, error: lastError };
        }
      );
  }

  // -----------------------------------------------------------------------
  // Sync dialog
  // -----------------------------------------------------------------------
  function closeOverlay() {
    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
    overlay = null;
  }

  function openOverlay(dialog) {
    closeOverlay();
    overlay = el("div", "sb-sync-overlay");
    overlay.addEventListener("click", function (event) {
      if (event.target === overlay) closeOverlay();
    });
    overlay.appendChild(dialog);
    document.body.appendChild(overlay);
  }

  function openSetup(error) {
    var dialog = el("div", "sb-sync-dialog");
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");
    dialog.appendChild(el("div", "sb-sync-title", "Sync across devices"));
    dialog.appendChild(
      el(
        "p",
        "sb-sync-text",
        "Keep profiles, My List and watch progress in step across your devices. Streambert stores a private copy in a GitHub Gist keyed by your TMDB token."
      )
    );
    dialog.appendChild(
      el(
        "p",
        "sb-sync-note",
        "Set this up once on each device with the same GitHub account. The token only needs Gist read/write access."
      )
    );

    var link = el("a", "sb-sync-link", "Create a GitHub token with Gist access");
    link.href =
      "https://github.com/settings/tokens/new?scopes=gist&description=Streambert%20Mobile%20Sync";
    link.target = "_blank";
    link.rel = "noopener";
    link.addEventListener("click", function (event) {
      event.preventDefault();
      openExternal(link.href);
    });
    dialog.appendChild(link);

    dialog.appendChild(el("div", "sb-sync-label", "GitHub token"));
    var input = el("input", "sb-sync-input");
    input.type = "password";
    input.placeholder = "ghp_… or github_pat_…";
    input.autocomplete = "off";
    input.autocapitalize = "off";
    input.spellcheck = false;
    input.setAttribute("aria-label", "GitHub token");
    dialog.appendChild(input);
    dialog.appendChild(
      el(
        "div",
        "sb-sync-hint",
        "Stored only on this device. Revoke the token anytime on GitHub."
      )
    );

    var errorEl = el("div", "sb-sync-error", error || "");
    dialog.appendChild(errorEl);

    var actions = el("div", "sb-sync-actions");
    var cancel = button("sb-sync-cancel", "Cancel");
    cancel.addEventListener("click", closeOverlay);
    actions.appendChild(cancel);

    var connect = button("sb-sync-connect", "Connect & Sync");
    connect.addEventListener("click", function () {
      var token = input.value.trim();
      if (!token) {
        errorEl.textContent = "Paste a GitHub token first.";
        input.focus();
        return;
      }
      connect.disabled = true;
      connect.textContent = "Connecting…";
      errorEl.textContent = "";
      validateToken(token)
        .then(function () {
          secureSet(TOKEN_KEY, token);
          closeOverlay();
          toast("Connected. Syncing…");
          return pull({ forceReload: true, setup: true });
        })
        .then(function (result) {
          if (!result.ok) {
            toast(result.error || "Sync failed");
            openStatus(result.error || "");
            return;
          }
          if (!result.changed) toast("Sync connected");
        })
        .catch(function (err) {
          if (!overlay) {
            toast(errorMessage(err));
            openStatus(errorMessage(err));
            return;
          }
          connect.disabled = false;
          connect.textContent = "Connect & Sync";
          errorEl.textContent = errorMessage(err);
        });
    });
    actions.appendChild(connect);
    dialog.appendChild(actions);

    openOverlay(dialog);
  }

  function openStatus(error) {
    var dialog = el("div", "sb-sync-dialog");
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");
    dialog.appendChild(el("div", "sb-sync-title", "Sync across devices"));

    var state = el("div", "sb-sync-state");
    state.appendChild(el("span", "sb-sync-dot"));
    state.appendChild(el("span", null, "Connected"));
    dialog.appendChild(state);

    dialog.appendChild(
      el(
        "div",
        "sb-sync-detail",
        lastSyncAt ? "Last synced " + timeAgo(lastSyncAt) : "Waiting for the first sync"
      )
    );

    var message = error || lastError;
    if (message) dialog.appendChild(el("div", "sb-sync-error", message));

    dialog.appendChild(
      el(
        "p",
        "sb-sync-note",
        "The Gist is private. Changes from another device appear after the next sync; a refresh applies them immediately."
      )
    );

    var actions = el("div", "sb-sync-actions");
    var disconnect = button("sb-sync-disconnect", "Disconnect");
    disconnect.addEventListener("click", function () {
      disconnectSync();
      closeOverlay();
      toast("Sync disconnected");
    });
    actions.appendChild(disconnect);

    var close = button("sb-sync-cancel", "Close");
    close.addEventListener("click", closeOverlay);
    actions.appendChild(close);

    var sync = button("sb-sync-connect", "Sync now");
    sync.addEventListener("click", function () {
      closeOverlay();
      toast("Syncing…");
      pull({ forceReload: true, manual: true }).then(function (result) {
        if (!result.ok) {
          toast(result.error || (result.reason === "busy" ? "Sync is already running" : "Sync failed"));
          openStatus(result.error || "");
          return;
        }
        if (!result.changed) toast("Already up to date");
      });
    });
    actions.appendChild(sync);
    dialog.appendChild(actions);

    openOverlay(dialog);
  }

  function timeAgo(timestamp) {
    var delta = Math.max(0, Date.now() - timestamp);
    if (delta < 60000) return "just now";
    if (delta < 3600000) return Math.floor(delta / 60000) + "m ago";
    if (delta < 86400000) return Math.floor(delta / 3600000) + "h ago";
    return Math.floor(delta / 86400000) + "d ago";
  }

  function open() {
    if (isConnected()) openStatus();
    else openSetup();
  }

  function disconnectSync() {
    secureSet(TOKEN_KEY, "");
    clearGistCache();
    rawRemove(LAST_AT_KEY);
    lastSyncAt = 0;
    lastError = "";
    clearTimeout(pushTimer);
    pushTimer = null;
    pushFirstAt = 0;
  }

  function status() {
    return {
      connected: isConnected(),
      lastSyncAt: lastSyncAt,
      error: lastError,
    };
  }

  // -----------------------------------------------------------------------
  // Start-up and lifecycle
  // -----------------------------------------------------------------------
  function maybeTip() {
    if (rawGet(TIP_KEY)) return;
    rawSet(TIP_KEY, "1");
    setTimeout(function () {
      toast("Sync profiles across devices from your profile menu");
    }, 2500);
  }

  function init() {
    lastSyncAt = Number(rawGet(LAST_AT_KEY)) || 0;
    window.__sbProfileChanged = onProfileChanged;

    var tmdb = tmdbToken();
    var github = githubToken();
    var startedWithSync = !!(tmdb && github);

    if (startedWithSync) {
      setTimeout(function () {
        pull({ startup: true });
      }, 700);
    } else if (tmdb) {
      maybeTip();
    }

    document.addEventListener("visibilitychange", function () {
      if (
        !document.hidden &&
        isConnected() &&
        Date.now() - lastSyncAt > 60000
      ) {
        pull({ resume: true });
      }
    });

    // First-run setup stores the TMDB token without reloading the page. Watch
    // briefly for it so the tip (and sync, if a token is already present) can
    // start in the same session.
    var started = Date.now();
    var watcher = setInterval(function () {
      if (Date.now() - started > 120000) {
        clearInterval(watcher);
        return;
      }
      if (!tmdbToken()) return;
      clearInterval(watcher);
      if (githubToken()) {
        if (Date.now() - lastPullRequestedAt > 15000) pull({ setup: true });
      } else {
        maybeTip();
      }
    }, 2000);
  }

  // -----------------------------------------------------------------------
  // Hardware Back (overlay first, then the profiles chain)
  // -----------------------------------------------------------------------
  var previousBack = window.__streambertBack;
  window.__streambertBack = function () {
    if (overlay) {
      closeOverlay();
      return true;
    }
    return previousBack ? previousBack() : false;
  };

  window.SBGistSync = {
    available: true,
    open: open,
    isConnected: isConnected,
    status: status,
    syncNow: function (opts) {
      return pull(opts || { forceReload: true, manual: true });
    },
    disconnect: disconnectSync,
    _hash: hashKey,
    _merge: mergePayloads,
    _pull: pull,
    _push: pushNow,
    _onChanged: onProfileChanged,
  };

  init();
})();

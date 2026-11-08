/* Streambert Mobile - Movies / TV Shows / Anime tabs on the home page.
 *
 * The port starts on a single mixed home feed. This module adds a small tab
 * strip at the top of it and swaps the feed for a dedicated catalogue page:
 * only movies, only series, or Japanese animation. Each catalogue is built
 * from TMDB lists and rendered with the app's own carousel (the one behind
 * "Trending Movies"), so navigation, carousel behaviour, age badges and the
 * age limit stay identical to the rest of the app.
 *
 * HomePage-*.js imports it and passes the home tree plus the carousel:
 *   import { HomeTabs as HT } from "./HomeTabs.js";
 *   return t.jsx(HT, { home: ..., apiKey, onSelect, Row, ... });
 */

import { r as React, h as ageRestricted } from "./movie-BkXF27iw.js";
import { u as useRatings, g as ratingInfo } from "./index-C4x6xwoJ.js";

const TMDB = "https://api.themoviedb.org/3";
const ITEMS_PER_ROW = 10;
const CACHE_TTL_MS = 10 * 60 * 1000;

const TABS = [
  { id: "home", label: "Home" },
  { id: "movies", label: "Movies" },
  { id: "tv", label: "TV Shows" },
  { id: "anime", label: "Anime" },
];

const MOVIE_ROWS = [
  { title: "Trending Movies", path: "/trending/movie/week" },
  { title: "Popular Movies", path: "/movie/popular?page=1" },
  { title: "Top Rated Movies", path: "/movie/top_rated?page=1" },
  {
    title: "New Releases",
    path: "/discover/movie?sort_by=primary_release_date.desc&vote_count.gte=20&page=1",
  },
  {
    title: "Action",
    path: "/discover/movie?with_genres=28&sort_by=popularity.desc&vote_count.gte=20&page=1",
  },
  {
    title: "Comedy",
    path: "/discover/movie?with_genres=35&sort_by=popularity.desc&vote_count.gte=20&page=1",
  },
];

const TV_ROWS = [
  { title: "Trending Series", path: "/trending/tv/week" },
  { title: "Popular Series", path: "/tv/popular?page=1" },
  { title: "Top Rated Series", path: "/tv/top_rated?page=1" },
  { title: "Airing Now", path: "/tv/on_the_air?page=1" },
  {
    title: "Comedy Series",
    path: "/discover/tv?with_genres=35&sort_by=popularity.desc&vote_count.gte=20&page=1",
  },
  {
    title: "Drama Series",
    path: "/discover/tv?with_genres=18&sort_by=popularity.desc&vote_count.gte=20&page=1",
  },
];

const ANIME_ROWS = [
  {
    title: "Popular Anime Series",
    path:
      "/discover/tv?with_genres=16&with_original_language=ja" +
      "&sort_by=popularity.desc&vote_count.gte=20&page=1",
  },
  {
    title: "Popular Anime Films",
    path:
      "/discover/movie?with_genres=16&with_original_language=ja" +
      "&sort_by=popularity.desc&vote_count.gte=20&page=1",
  },
  {
    title: "Top Rated Anime",
    path:
      "/discover/tv?with_genres=16&with_original_language=ja" +
      "&sort_by=vote_average.desc&vote_count.gte=100&page=1",
  },
  {
    title: "Top Rated Anime Films",
    path:
      "/discover/movie?with_genres=16&with_original_language=ja" +
      "&sort_by=vote_average.desc&vote_count.gte=100&page=1",
  },
  {
    title: "New Anime",
    path:
      "/discover/tv?with_genres=16&with_original_language=ja" +
      "&sort_by=first_air_date.desc&vote_count.gte=5&page=1",
  },
];

const ROWS = { movies: MOVIE_ROWS, tv: TV_ROWS, anime: ANIME_ROWS };

const SUBTITLES = {
  movies: "Popular, top rated and new films",
  tv: "Series everyone is watching",
  anime: "Japanese animation, films and series",
};

// Restore the last chosen tab when the user comes back from a detail page.
let lastTab = "home";
// Remember the scroll offset of each tab so returning from a detail page
// lands where the user left off instead of jumping to the top.
const scrollPositions = Object.create(null);

function openProfiles() {
  try {
    if (window.SBProfiles && typeof window.SBProfiles.openSheet === "function") {
      window.SBProfiles.openSheet();
      return;
    }
  } catch (e) {}
  openSettings();
}

function openSettings() {
  try {
    const buttons = document.querySelectorAll(".sidebar-btn");
    for (let i = 0; i < buttons.length; i++) {
      const label = (buttons[i].getAttribute("aria-label") || buttons[i].textContent || "").trim();
      if (/^Settings$/i.test(label)) {
        buttons[i].click();
        return;
      }
    }
  } catch (e) {}
}

const cache = new Map();

function language() {
  try {
    const stored = JSON.parse(localStorage.getItem("streambert_tmdbLang") || '"en-US"');
    return typeof stored === "string" && stored ? stored : "en-US";
  } catch (e) {
    return "en-US";
  }
}

function mediaTypeOf(path) {
  return /\/movie(?:\/|\?|$)/.test(path) ? "movie" : "tv";
}

function fetchRow(def, apiKey) {
  const lang = language();
  const url =
    TMDB +
    def.path +
    (def.path.indexOf("?") === -1 ? "?" : "&") +
    "language=" +
    encodeURIComponent(lang);
  const hit = cache.get(url);
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) {
    return Promise.resolve(hit.items);
  }

  const type = mediaTypeOf(def.path);
  return fetch(url, { headers: { Authorization: "Bearer " + apiKey } })
    .then(function (response) {
      if (!response.ok) throw new Error("TMDB " + response.status);
      return response.json();
    })
    .then(function (data) {
      const items = (data.results || [])
        .slice(0, ITEMS_PER_ROW)
        .map(function (item) {
          return Object.assign({}, item, { media_type: type });
        });
      cache.set(url, { at: Date.now(), items: items });
      return items;
    });
}

function TabBar(props) {
  return React.createElement(
    "div",
    { className: "sb-home-tabs", role: "tablist", "aria-label": "Browse" },
    TABS.map(function (tab) {
      const active = tab.id === props.active;
      return React.createElement(
        "button",
        {
          key: tab.id,
          type: "button",
          role: "tab",
          "aria-selected": active ? "true" : "false",
          className: "sb-home-tab" + (active ? " active" : ""),
          onClick: function () {
            props.onSelectTab(tab.id);
          },
        },
        tab.label
      );
    })
  );
}

function Empty(props) {
  return React.createElement(
    "div",
    { className: "sb-catalog-empty" },
    React.createElement("div", { className: "sb-catalog-empty-title" }, props.title),
    props.detail
      ? React.createElement("div", { className: "sb-catalog-empty-text" }, props.detail)
      : null,
    props.onRetry
      ? React.createElement(
          "button",
          {
            type: "button",
            className: "btn btn-primary",
            onClick: props.onRetry,
          },
          "Retry"
        )
      : null
  );
}

function Catalog(props) {
  const tab = props.tab;
  const defs = ROWS[tab] || [];
  const [rows, setRows] = React.useState(null);
  const [attempt, setAttempt] = React.useState(0);

  React.useEffect(
    function () {
      if (!props.apiKey) {
        setRows([]);
        return undefined;
      }
      let alive = true;
      setRows(null);
      Promise.all(
        defs.map(function (def) {
          return fetchRow(def, props.apiKey).catch(function () {
            return [];
          });
        })
      ).then(function (result) {
        if (alive) setRows(result);
      });
      return function () {
        alive = false;
      };
    },
    [tab, props.apiKey, attempt]
  );

  const allItems = React.useMemo(
    function () {
      return rows ? [].concat.apply([], rows) : [];
    },
    [rows]
  );

  // Same certification / age-limit machinery the rest of the home page uses.
  const ratings = useRatings(allItems);
  const ratingsMap = ratings.ratingsMap || {};
  const ageLimit = ratings.ageLimitSetting;

  let body;
  if (!props.apiKey) {
    body = React.createElement(Empty, {
      title: "Add a TMDB token",
      detail: "Open Settings → General & API and paste a token to load titles.",
    });
  } else if (!rows) {
    body = React.createElement(
      "div",
      { className: "loader" },
      React.createElement("div", { className: "spinner" })
    );
  } else {
    const carousels = rows
      .map(function (items, index) {
        const visible = items.filter(function (item) {
          return !ageRestricted(ratingInfo(item, ratingsMap).minAge, ageLimit);
        });
        if (visible.length === 0) return null;
        return React.createElement(props.Row, {
          key: tab + "-" + index,
          items: visible,
          title: defs[index].title,
          onSelect: props.onSelect,
          ratingsMap: ratingsMap,
        });
      })
      .filter(Boolean);

    if (carousels.length > 0) {
      body = carousels;
    } else {
      const hadItems = rows.some(function (items) {
        return items.length > 0;
      });
      body = React.createElement(Empty, {
        title: hadItems ? "Nothing at this age rating" : "Couldn't load titles",
        detail: hadItems
          ? "Raise the age limit in Settings to see more."
          : "Check your connection and try again.",
        onRetry: function () {
          setAttempt(function (value) {
            return value + 1;
          });
        },
      });
    }
  }

  return React.createElement(
    "div",
    { className: "sb-catalog" },
    React.createElement(
      "div",
      { className: "sb-catalog-head" },
      React.createElement(
        "div",
        { className: "sb-catalog-title" },
        TABS.filter(function (item) {
          return item.id === tab;
        })[0].label
      ),
      React.createElement(
        "div",
        { className: "sb-catalog-sub" },
        SUBTITLES[tab] || ""
      )
    ),
    body
  );
}

export function HomeTabs(props) {
  const [tab, setTab] = React.useState(lastTab);
  const touch = React.useRef(null);

  const selectTab = React.useCallback(function (id) {
    lastTab = id;
    setTab(id);
  }, []);

  // Tapping Home in the bottom nav while already on the home route returns
  // from the Movies / TV / Anime tab to the mixed feed.
  React.useEffect(
    function () {
      const handler = function () {
        selectTab("home");
      };
      window.addEventListener("streambert:home", handler);
      return function () {
        window.removeEventListener("streambert:home", handler);
      };
    },
    [selectTab]
  );

  // Save the current offset while scrolling (and when the tab/page unmounts).
  React.useEffect(
    function () {
      const main = document.querySelector(".main");
      if (!main) return undefined;
      const onScroll = function () {
        scrollPositions[tab] = main.scrollTop;
      };
      main.addEventListener("scroll", onScroll, { passive: true });
      return function () {
        scrollPositions[tab] = main.scrollTop;
        main.removeEventListener("scroll", onScroll);
      };
    },
    [tab]
  );

  // Restore this tab's offset. Content loads after mount, so retry briefly
  // until the page is tall enough to honour it.
  React.useEffect(
    function () {
      const main = document.querySelector(".main");
      if (!main) return undefined;
      const target = scrollPositions[tab] || 0;
      let tries = 0;
      let timer = null;
      const step = function () {
        if (!main.isConnected) return;
        const tall =
          target === 0 ||
          main.scrollHeight >= target + Math.min(600, main.clientHeight);
        if (tall || tries >= 10) {
          main.scrollTop = target;
          if (target > 0) scrollPositions[tab] = target;
          return;
        }
        tries += 1;
        timer = setTimeout(step, 150);
      };
      timer = setTimeout(step, 0);
      return function () {
        if (timer) clearTimeout(timer);
      };
    },
    [tab]
  );

  function onTouchStart(e) {
    if (!e.touches || e.touches.length !== 1) return;
    const target = e.target;
    if (
      target &&
      target.closest &&
      target.closest(
        ".carousel-wrapper, .sb-top10-strip, .cards-grid, .scroll-row, .sb-pin-overlay"
      )
    ) {
      return;
    }
    touch.current = {
      x: e.touches[0].clientX,
      y: e.touches[0].clientY,
    };
  }

  function onTouchEnd(e) {
    const start = touch.current;
    touch.current = null;
    if (!start || !e.changedTouches || e.changedTouches.length !== 1) return;
    const dx = e.changedTouches[0].clientX - start.x;
    const dy = e.changedTouches[0].clientY - start.y;
    if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    const index = TABS.map(function (item) {
      return item.id;
    }).indexOf(tab);
    const next = dx < 0 ? index + 1 : index - 1;
    if (next >= 0 && next < TABS.length) selectTab(TABS[next].id);
  }

  const profile = typeof window !== "undefined" ? window.SB_PROFILE : null;

  return React.createElement(
    "div",
    {
      className: "sb-home-wrap",
      onTouchStart: onTouchStart,
      onTouchEnd: onTouchEnd,
    },
    React.createElement(
      "div",
      { className: "sb-home-brand" },
      React.createElement("span", { className: "sb-home-logo" }, "STREAMBERT"),
      React.createElement(
        "button",
        {
          type: "button",
          className: "sb-home-avatar",
          title: "Profiles",
          "aria-label": profile ? "Profile: " + profile.name : "Profiles",
          style: profile ? { background: profile.color } : undefined,
          onClick: openProfiles,
        },
        profile ? profile.emoji : "S"
      )
    ),
    React.createElement(TabBar, { active: tab, onSelectTab: selectTab }),
    tab === "home"
      ? props.home
      : React.createElement(Catalog, {
          key: tab,
          tab: tab,
          apiKey: props.apiKey,
          onSelect: props.onSelect,
          Row: props.Row,
        })
  );
}

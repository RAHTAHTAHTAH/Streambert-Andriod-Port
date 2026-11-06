/* Streambert Mobile - genre carousels on the home page.
 *
 * The desktop home page renders "Browse by Genre" as a static block of 34
 * chips; tapping one navigates to a separate GenrePage. On a phone that is a
 * lot of vertical space and a full page transition for what is really just a
 * filtered list.
 *
 * This module replaces that block with six poster carousels that reuse the
 * app's own carousel component (the one behind "Trending Movies"), so arrows,
 * dots, autoplay, poster sizing and the detail navigation all behave
 * identically. The full genre page stays reachable from the compact chip strip
 * at the bottom.
 *
 * It is a plain ES module imported by HomePage-*.js:
 *   import { HomeGenres as GR } from "./HomeGenres.js";
 * and rendered in the "genres" slot of the home layout.
 */

import { r as React, h as ageRestricted } from "./movie-BkXF27iw.js";
import {
  u as useRatings,
  g as ratingInfo,
  M as MOVIE_GENRES,
  T as TV_GENRES,
} from "./index-C4x6xwoJ.js";

const TMDB = "https://api.themoviedb.org/3";
const ITEMS_PER_ROW = 10;
const CACHE_TTL_MS = 10 * 60 * 1000;

/* Six rows: three movie genres and three series genres. Swap the `id` and
 * `title` here to change what the home page shows. */
const ROWS = [
  { id: 28, type: "movie", title: "Action Movies" },
  { id: 35, type: "movie", title: "Comedy Movies" },
  { id: 878, type: "movie", title: "Sci-Fi Movies" },
  { id: 10759, type: "tv", title: "Action & Adventure Series" },
  { id: 35, type: "tv", title: "Comedy Series" },
  { id: 10765, type: "tv", title: "Sci-Fi & Fantasy Series" },
];

const cache = new Map();

function language() {
  try {
    const stored = JSON.parse(localStorage.getItem("streambert_tmdbLang") || '"en-US"');
    return typeof stored === "string" && stored ? stored : "en-US";
  } catch (e) {
    return "en-US";
  }
}

function fetchRow(def, apiKey) {
  const lang = language();
  const key = def.type + ":" + def.id + ":" + lang;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) {
    return Promise.resolve(hit.items);
  }

  const url =
    TMDB +
    "/discover/" +
    def.type +
    "?with_genres=" +
    def.id +
    "&sort_by=popularity.desc&include_adult=false&vote_count.gte=20&page=1" +
    "&language=" +
    encodeURIComponent(lang);

  return fetch(url, { headers: { Authorization: "Bearer " + apiKey } })
    .then(function (response) {
      if (!response.ok) throw new Error("TMDB " + response.status);
      return response.json();
    })
    .then(function (data) {
      const items = (data.results || [])
        .slice(0, ITEMS_PER_ROW)
        .map(function (item) {
          return Object.assign({}, item, { media_type: def.type });
        });
      cache.set(key, { at: Date.now(), items: items });
      return items;
    });
}

function GenreChip(props) {
  return React.createElement(
    "button",
    {
      type: "button",
      className: "genre-chip",
      onClick: function () {
        props.onOpenGenre({ key: props.genre.key, mediaType: props.mediaType });
      },
    },
    props.genre.name
  );
}

function AllGenres(props) {
  function strip(genres, mediaType) {
    return React.createElement(
      "div",
      { className: "sb-genre-browse-strip" },
      genres.map(function (genre) {
        return React.createElement(GenreChip, {
          key: mediaType + "-" + genre.key,
          genre: genre,
          mediaType: mediaType,
          onOpenGenre: props.onOpenGenre,
        });
      })
    );
  }

  return React.createElement(
    "div",
    { className: "sb-genre-browse" },
    React.createElement(
      "div",
      { className: "sb-genre-browse-title" },
      "Browse by genre"
    ),
    React.createElement(
      "div",
      { className: "sb-genre-browse-group" },
      React.createElement("span", { className: "sb-genre-browse-label" }, "Movies"),
      strip(MOVIE_GENRES, "movie")
    ),
    React.createElement(
      "div",
      { className: "sb-genre-browse-group" },
      React.createElement("span", { className: "sb-genre-browse-label" }, "Series"),
      strip(TV_GENRES, "tv")
    )
  );
}

export function HomeGenres(props) {
  const Row = props.Row;
  const Fallback = props.Fallback;
  const apiKey = props.apiKey;
  const [rows, setRows] = React.useState(null);

  React.useEffect(
    function () {
      if (!apiKey) {
        setRows([]);
        return undefined;
      }

      let alive = true;
      Promise.all(
        ROWS.map(function (def) {
          return fetchRow(def, apiKey).catch(function () {
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
    [apiKey]
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

  // Nothing resolved at all: keep the old chip block rather than an empty gap.
  if (rows && rows.every(function (items) { return items.length === 0; })) {
    return Fallback
      ? React.createElement(Fallback, { onOpenGenre: props.onOpenGenre })
      : null;
  }

  if (!rows) return null;

  const carousels = rows
    .map(function (items, index) {
      const visible = items.filter(function (item) {
        return !ageRestricted(ratingInfo(item, ratingsMap).minAge, ageLimit);
      });
      if (visible.length === 0) return null;
      return React.createElement(Row, {
        key: "genre-" + index,
        items: visible,
        title: ROWS[index].title,
        onSelect: props.onSelect,
        ratingsMap: ratingsMap,
      });
    })
    .filter(Boolean);

  return React.createElement(
    "div",
    { className: "sb-genre-home" },
    carousels,
    React.createElement(AllGenres, { onOpenGenre: props.onOpenGenre })
  );
}

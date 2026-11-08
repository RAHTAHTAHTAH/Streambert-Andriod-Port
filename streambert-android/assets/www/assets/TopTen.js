/* Streambert Mobile - Netflix-style "Top 10 Today" row for the home page.
 *
 * TMDB's weekly trending feed is already ordered, so the first ten titles are
 * used as the ranking. The huge outlined numbers are pure CSS; tapping a card
 * goes through the same onSelect callback as every other home row.
 *
 * HomePage-*.js renders it directly below the hero:
 *   import { TopTen as TT } from "./TopTen.js";
 *   t.jsx(TT, { apiKey: S, onSelect: l })
 */

import { r as React, h as ageRestricted } from "./movie-BkXF27iw.js";
import { u as useRatings, g as ratingInfo } from "./index-C4x6xwoJ.js";

const TMDB = "https://api.themoviedb.org/3";
const CACHE_TTL_MS = 10 * 60 * 1000;
const IMAGE_BASE = "https://image.tmdb.org/t/p/w342";

let cache = null;

function language() {
  try {
    const stored = JSON.parse(localStorage.getItem("streambert_tmdbLang") || '"en-US"');
    return typeof stored === "string" && stored ? stored : "en-US";
  } catch (e) {
    return "en-US";
  }
}

function fetchTop(apiKey) {
  const lang = language();
  if (cache && cache.lang === lang && Date.now() - cache.at < CACHE_TTL_MS) {
    return Promise.resolve(cache.items);
  }
  const url =
    TMDB +
    "/trending/all/week?language=" +
    encodeURIComponent(lang);
  return fetch(url, { headers: { Authorization: "Bearer " + apiKey } })
    .then(function (response) {
      if (!response.ok) throw new Error("TMDB " + response.status);
      return response.json();
    })
    .then(function (data) {
      const items = (data.results || [])
        .filter(function (item) {
          return item && item.media_type && item.media_type !== "person";
        })
        .slice(0, 20)
        .map(function (item) {
          return Object.assign({}, item, {
            media_type: item.media_type === "tv" ? "tv" : "movie",
          });
        });
      cache = { at: Date.now(), lang: lang, items: items };
      return items;
    });
}

export function TopTen(props) {
  const [items, setItems] = React.useState(cache ? cache.items : null);

  React.useEffect(
    function () {
      if (!props.apiKey) return undefined;
      let alive = true;
      fetchTop(props.apiKey)
        .then(function (result) {
          if (alive) setItems(result);
        })
        .catch(function () {
          if (alive) setItems([]);
        });
      return function () {
        alive = false;
      };
    },
    [props.apiKey]
  );

  const ratings = useRatings(items || []);
  const ratingsMap = ratings.ratingsMap || {};
  const ageLimit = ratings.ageLimitSetting;

  const visible = (items || [])
    .filter(function (item) {
      return !ageRestricted(ratingInfo(item, ratingsMap).minAge, ageLimit);
    })
    .slice(0, 10);

  if (visible.length < 3) return null;

  return React.createElement(
    "div",
    { className: "section sb-top10-section" },
    React.createElement("div", { className: "section-title" }, "Top 10 Today"),
    React.createElement(
      "div",
      { className: "sb-top10-strip" },
      visible.map(function (item, index) {
        const title = item.title || item.name || "Untitled";
        const image = item.poster_path
          ? IMAGE_BASE + item.poster_path
          : item.backdrop_path
          ? "https://image.tmdb.org/t/p/w500" + item.backdrop_path
          : null;
        return React.createElement(
          "button",
          {
            key: item.media_type + "-" + item.id,
            type: "button",
            className: "sb-top10-card",
            "aria-label": "Number " + (index + 1) + ": " + title,
            onClick: function () {
              props.onSelect(item);
            },
          },
          React.createElement(
            "span",
            { className: "sb-top10-rank", "aria-hidden": "true" },
            index + 1
          ),
          React.createElement(
            "span",
            { className: "sb-top10-poster" },
            image
              ? React.createElement("img", {
                  src: image,
                  alt: title,
                  loading: "lazy",
                  draggable: false,
                })
              : React.createElement("span", { className: "sb-top10-fallback" }, title)
          )
        );
      })
    )
  );
}

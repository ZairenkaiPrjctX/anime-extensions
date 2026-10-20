# Internet Archive (`all.internetarchive`)

Streams animation from the [Internet Archive](https://archive.org) through its public, documented
APIs. No scraping, no login, no access-control circumvention.

| Feature   | Implementation                                                                                  |
|-----------|--------------------------------------------------------------------------------------------------|
| Popular   | Advanced Search, `collection:animationandcartoons`, sorted by downloads                          |
| Latest    | Same query sorted by `addeddate desc`                                                            |
| Search    | Escaped keywords + filters: collection, sort order, "declared license only" (`licenseurl:*`)     |
| Details   | Metadata API (`/metadata/{identifier}`): title, creator, director, subjects, date, license       |
| Episodes  | One episode per *work*: files grouped by the stem of their original upload                       |
| Videos    | Every public video rendition (h.264, MPEG4, Ogg, Matroska, …) as direct `/download/` URLs         |
| Subtitles | `X.srt` / `X.vtt` / auto-generated `X.asr.srt` next to video `X.*`                                 |

## Source-specific behaviour

- **Restricted content is excluded.** Queries add `NOT access-restricted-item:(true)`; items that are
  dark, lending-only or restricted raise `ContentUnavailableException`, and files flagged
  `private` are never listed.
- **Deep paging.** Advanced Search returns at most 10,000 results per query (`[DEEP_PAGING]`), so
  `hasNextPage` stops at that window. Narrow the query with search terms or filters.
- **Rate limit.** At most 4 requests/second to `archive.org`; metadata calls retry transient
  failures (timeouts, 429, 5xx) twice with exponential backoff.
- **Stored URLs.** `SAnime.url` is `/details/{identifier}` and `SEpisode.url` is
  `/details/{identifier}/{file}` — both are the public web pages, so "Open in WebView" works.
- **Grouping limitation.** Items that hold several masters of one film under different file names
  (e.g. `ed_1024.avi` and `ed_hd.avi` in *Elephants Dream*) show one episode per master.
- **No language filter.** The `language` metadata field is too sparse in these collections to filter
  on reliably (verified: 0 items tagged Indonesian, 13 Japanese), so none is offered.
- The fan-uploaded `anime` collection is intentionally not offered: its items rarely declare rights.

## Rights

Each item states its own rights (`licenseurl`, `rights`); the extension shows the declared license in
the description. Use is subject to the [Internet Archive Terms of Use](https://archive.org/about/terms.php).
This project is not affiliated with the Internet Archive.

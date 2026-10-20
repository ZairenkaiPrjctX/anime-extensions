# Fixtures

Recorded on 2026-10-20 from a local **Jellyfin 12.2.0** server (official Linux build) with
`Accept: application/json` and token authentication. The server's library contained:

- *The Conceited General* (1956), public domain, video and `.srt` downloaded from
  https://archive.org/details/the_conceited_general;
- "ASE Test Series": four 4-second clips generated with ffmpeg `testsrc`, laid out as two seasons,
  used only to exercise series/season structure.

Sanitized: `AccessToken` replaced by `REDACTED`; `SessionInfo`, `Path`, `ServerId`, `Etag`, user
`Policy`/`Configuration`, addresses and login dates removed. Item ids are the server's real ids.

| File | Request |
|------|---------|
| `catalog_popular.json` | `GET /Items?Recursive=true&IncludeItemTypes=Movie%2CSeries&SortBy=SortName&SortOrder=Ascending&StartIndex=0&Limit=50&EnableImageTypes=Primary&ImageTypeLimit=1` |
| `catalog_paged.json` | `GET /Items?Recursive=true&IncludeItemTypes=Movie%2CSeries&SortBy=SortName&SortOrder=Ascending&StartIndex=0&Limit=1&EnableImageTypes=Primary&ImageTypeLimit=1` |
| `catalog_search_empty.json` | `GET /Items?Recursive=true&IncludeItemTypes=Movie%2CSeries&SortBy=SortName&SortOrder=Ascending&StartIndex=0&Limit=50&EnableImageTypes=Primary&ImageTypeLimit=1&SearchTerm=nomatchxyz` |
| `item_movie.json` | `GET /Items/{movieId}?Fields=Overview%2CGenres%2CStudios%2CProductionYear%2CDateCreated` |
| `item_series.json` | `GET /Items/{seriesId}?Fields=Overview%2CGenres%2CStudios%2CProductionYear%2CDateCreated` |
| `episodes_series.json` | `GET /Shows/{seriesId}/Episodes?Fields=DateCreated%2COverview` |
| `item_movie_sources.json` | `GET /Items/{movieId}?Fields=MediaSources%2CDateCreated` |
| `item_episode_sources.json` | `GET /Items/{episodeId}?Fields=MediaSources%2CDateCreated` |
| `item_root_folder.json` | `GET /Items/00000000000000000000000000000000?Fields=Overview%2CGenres%2CStudios%2CProductionYear%2CDateCreated` |
| `auth_response.json` | `POST /Users/AuthenticateByName` (token redacted) |

# Jellyfin (`all.jellyfin`)

Browse and play the user's **own** [Jellyfin](https://jellyfin.org) media server through the
documented Jellyfin REST API (https://api.jellyfin.org). Nothing is fetched from third parties.

## Setup (in the app: Extensions → Jellyfin → settings)

| Setting        | Notes                                                                        |
|----------------|------------------------------------------------------------------------------|
| Server address | `https://jellyfin.example.org`, a reverse-proxy path such as `https://example.org/jellyfin`, or a LAN address `http://192.168.1.10:8096` |
| Username       | Jellyfin account name                                                        |
| Password       | Stored only in this source's private preferences on the device              |

Changing any setting discards the stored session token; the next request logs in again.

## Capabilities

| Feature  | Implementation                                                                         | Verified against Jellyfin 12.2 |
|----------|-----------------------------------------------------------------------------------------|--------------------------------|
| Popular  | `GET /Items` movies + series, sorted by name                                             | fixtures + live test           |
| Latest   | Same, sorted by `DateCreated` descending                                                 | URL test                       |
| Search   | `SearchTerm`, filters: type (movies/series/both) and sort order                          | URL + fixture tests            |
| Details  | `GET /Items/{id}`: overview, genres, studios, year, primary image                        | fixtures + live test           |
| Episodes | Movies: one episode. Series: `GET /Shows/{id}/Episodes`, all seasons, numbered 1..n       | fixtures + live test           |
| Playback | One direct-play video per media source (`/Videos/{id}/stream?static=true`), text subtitles converted by the server | fixtures + live ranged GET |

## Behaviour and limitations

- **Authentication:** `POST /Users/AuthenticateByName`; the token is sent in the `Authorization:
  MediaBrowser …` header for API calls and for the player's stream/subtitle requests. It is never
  put in a URL, logged, or included in error messages. A 401 triggers one re-login.
- **JSON casing:** requests send `Accept: application/json`, which makes Jellyfin answer in
  PascalCase (a wildcard `Accept` returns camelCase — verified), decoded via `jellyfinJson`.
- **Unknown items:** the all-zero id resolves to the library root instead of a 404 (verified), so
  responses are checked for the expected id and type.
- **No transcoding.** Original files are streamed; the app's player must support the codec.
  Image-based subtitles (PGS, VobSub) are not offered because the server cannot convert them to text.
- **Seasons** are flattened into one list (`S2E1 · Name`), not exposed as lib-16 seasons.
- Implements `UnmeteredSource` (the app does not throttle a user's own server). `baseUrl` is empty
  until configured, as for other user-configured sources in the published ecosystem.
- One server per installation (no multi-server factory yet).

## Rights

Users stream media from their own server. Make sure you have the right to the media you add to it.

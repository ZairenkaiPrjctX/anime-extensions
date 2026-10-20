# Fixtures

Recorded from the public Internet Archive APIs on 2026-10-20 and sanitized:

- top-level server/mirror fields, reviews and `uploader`/`updater` (e-mail addresses) removed;
- per-file checksums removed; thumbnail/frame files trimmed;
- collections trimmed to a few works so the files stay small.

| File                            | Source request                                        | Covers                                  |
|---------------------------------|-------------------------------------------------------|-----------------------------------------|
| `search_page1.json`             | `advancedsearch.php` default popular query, `rows=4`  | catalog mapping, paging                 |
| `metadata_single_film.json`     | `metadata/Popeye_forPresident`                        | single work, MPEG2 original, license    |
| `metadata_subtitled.json`       | `metadata/the_conceited_general`                      | file title, `.srt` subtitle             |
| `metadata_asr_subtitles.json`   | `metadata/OldToonTheater` (2 works)                   | auto-generated `.asr.srt` captions      |
| `metadata_collection.json`      | `metadata/popeye-collection` (3 works)                | grouping `.mkv`/`.mp4`/`.ia.mp4`, order |
| `metadata_elephants_dream.json` | `metadata/ElephantsDream`                             | HTML description, derivatives, non-video|
| `metadata_restricted.json`      | `metadata/TheHunt`                                    | `access-restricted-item`, private files |

Item metadata is published by the Internet Archive; each item's rights are stated in its own
`licenseurl`/`rights` fields. Only metadata (no media) is stored here.

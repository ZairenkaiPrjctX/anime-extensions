# Source template

Files in `extension/` are rendered by [`tools/new_source.py`](../tools/new_source.py); do not copy
them by hand. Placeholders use `{{KEY}}` so that Kotlin `$` string templates stay untouched.

```bash
python3 tools/new_source.py en mysite --display-name "My Site" --base-url https://mysite.example
```

The generated module compiles immediately (CI proves this on every run) but is deliberately
rejected by `tools/validate_extensions.py` until every `notImplemented()` placeholder is replaced.

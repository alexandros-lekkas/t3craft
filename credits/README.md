# Credits system

Everything T3 Craft uses that someone else made (code, fonts, builds, mods, names) is credited from one file, [`credits.json`](credits.json). A generator turns it into every credits page, and CI fails if anything is missing.

```
credits/credits.json ──► credits/generate.py ──► CREDITS.md
                                             ├─► THIRD_PARTY_LICENSES.md
                                             ├─► modpack/CREDITS.md
                                             ├─► src/main/resources/assets/t3craft/credits.json  (in-game Credits screen)
                                             └─► ATTRIBUTION.md next to bundled fonts and schematics
```

## Everyday use

| You did this | Do this |
|---|---|
| Added a file under `assets/` or `data/` | Add its path to a credit's `files` (or a new credit), then run `python3 -I credits/generate.py` |
| Added a mod to the pack | Add a `kind: "mod"` credit with `modpack_file: "mods/<slug>.pw.toml"` |
| Removed a mod (for example Iris) | Delete its `.pw.toml` and regenerate. The credit drops out on its own; delete the entry when you like |
| Added a library to `build.gradle` | Add a `kind: "code"` credit with `active_if: {"grep": {"files": "build.gradle", "text": "<group id>"}}` |
| Imported schematics | Have the pipeline write `schematics/credits.json` (format below) and regenerate |

CI runs `scripts/check-credits.sh`, which runs the generator with `--check`. It fails when:

- a file under `src/main/resources/assets`, `src/main/resources/data`, `assets/` or `data/` matches no credit,
- a modpack `.pw.toml` has no credit,
- a bundled third-party file has no license, or a license that does not allow redistribution (add `permission` with a link to written permission, or remove the file),
- a bundled MIT, BSD or OFL credit has no `copyright` line, or its license text is missing from `licenses/`,
- the generated files are out of date.

## Rules the generator follows

- **Bundled or referenced.** `bundled` means the files ship in the repo or the jar, so their license text goes in `THIRD_PARTY_LICENSES.md`. `referenced` means the thing is only linked or named, like the pack's mods (downloaded from Modrinth, never re-hosted), trademarks and services.
- **Credits appear when their files do.** A bundled credit with `files` is listed only once a matching file exists. So Inter shows up the moment `inter-regular.ttf` lands. `always: true` lists it anyway; the in-house art uses this.
- **Original work** (`original: true`) is MIT and made for T3 Craft. It still needs a credit entry so that every asset is accounted for.
- **Licenses.** Use SPDX ids. Full texts live in `licenses/<id>.txt`, copied from [spdx/license-list-data](https://github.com/spdx/license-list-data). Custom terms use `LicenseRef-*` with a `license_url`.

## Schematic credits format (for ingest)

The generator reads either a list or `{"schematics": [...]}`. Field names are flexible (`name` or `title`, `author` or `creator`, `url` or `source_url`, `file` or `files`):

```json
{
  "schematics": [
    {
      "name": "Lakeside Cabin",
      "author": "SomeBuilder",
      "source_url": "https://www.planetminecraft.com/project/...",
      "license": "CC-BY-4.0",
      "file": "cabin.nbt",
      "changes": "Trimmed terrain; palette swapped to birch"
    }
  ]
}
```

File paths without `src/` get the ingest entry's `files_prefix`, for example `src/main/resources/data/t3craft/structure/`. A build with no known license fails the check; it must not ship until the license is found or the builder gives written permission.

## Commands

```bash
python3 -I credits/generate.py                    # write all outputs
python3 -I credits/generate.py --check            # what CI runs
python3 -I credits/generate.py --modpack ../t3craft-modpack   # read the pack from elsewhere
python3 -I credits/generate.py --ingest path/to/credits.json=src/main/resources/data/t3craft/structure/
```

# ISSUES

A scratchpad of problems found in the code but not fixed, pending triage.
Each item is separated by `---` and has key-value metadata followed by
optional free-text context.

Raised by the code comment audit, which changes comments only — anything
requiring a code change is recorded here instead.

`status` is one of `untriaged`, `accepted`, `wontfix`, `fixed`.

---
title: `:addon/toc` spec does not declare `::group-addons`
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/specs.clj:321
tags: specs, addon
summary: A key attached at runtime is absent from the spec that is meant to describe it

`addon.clj:117` assocs `:group-addons` onto an addon map, but the `:addon/toc`
spec lists it in neither `:req-un` nor `:opt-un`. The existing comment asks
whether this is a circular dependency; it cannot be answered from the code.
Either the spec is incomplete or the comment is stale.
---
title: Remove commented-out `:addon/toc+match` specs
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/specs.clj:425
tags: specs, dead-code
summary: Two spec definitions sit commented out and annotated 'unused'

`:addon/toc+match` and `:addon/toc+nfo+match` describe the case where an addon
was matched against the catalogue and no match was found. Both are commented
out. Git history would show whether they were ever used.
---
title: Unresolved question about lock release in `with-lock`
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/utils.clj:851
tags: concurrency, utils
summary: A comment asks whether synchronised access is required when releasing locks, and is never answered

Lock acquisition deliberately combines `locking` and `dosync` so the read and
the update happen together. The release path in the `finally` block uses a bare
`swap!` with the comment "synchronised access not required ?". Either the
asymmetry is safe and the comment should state why, or it is a race.
---
title: Duplicate log message suppression is disabled by `(and false ...)`
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/logging.clj:148
tags: logging, dead-code
summary: An unreachable branch left in place with a dated note explaining the disabling

The UI appender has a guard against repeating the previous message, switched off
on 2021-11-10 because it stopped addons reporting errors correctly. The note says
no error floods have been seen since. If that still holds the branch can go.
---
title: Two commented-out job predicates awaiting tests
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/joblib.clj:127
tags: joblib, dead-code
summary: `job-started?` and `cancel-job` are written but disabled, each marked 'todo: test this, integrate or something'

`job-started?` complements the four `job-*` predicates that are live.
`cancel-job` has no equivalent at all, so cancellation is currently only
observable through `job-cancelled?`, never triggerable.
---
title: `add-nfo` overwrite warning may report the wrong addon
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/nfo.clj:205
tags: nfo
summary: The warning names the last entry in the nfo list, which may not be the one being replaced

Flagged by an existing `todo` comment asking "when stacked N high, won't this
report incorrectly?". The warning is built from `(last nfo-data-list)` after
entries matching the new group-id have been removed. Unverified either way.
---
title: `update-nfo!` mixes updating nfo data with writing and removing the file
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/nfo.clj:233
tags: nfo
summary: Update logic and file removal logic are entangled, per a standing note in the source

The function decides what the new nfo data should be, then decides whether that
data means the file should be written or deleted. The empty-map case doubles as
the signal to delete. A pre-existing comment says it needs a second pass.
---
title: Warn when a toc file defines no interface version
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/toc.clj:161
tags: toc
summary: A `todo` comment marks a missing warning where the code silently falls back to a default

When `interface-version-list` is empty, `-parse-addon-toc` substitutes
`default-interface-version` without telling anyone. Better suited to `TODO.md`
than here if it is wanted as a feature rather than treated as a defect.
---
title: `ignored-dir-list` had a docstring stating the opposite of its behaviour
added: 2026-08-15
status: fixed
effort: low
source: src/strongbox/addon.clj:426
tags: addon, docs
summary: Docstring said 'not being ignored' where the code filters for ignored addons

Corrected during the audit. Recorded because the same comment carried a standing
question — "is it flawed like `pinned-dir-list` is?" — which is untriaged.
`ignored-dir-list` collects `:dirname` from `flatten-addon` over ignored addons;
`pinned-dir-list` does the same over two passes. Whether either is flawed is unverified.
---
title: `strip-unspecced-keys` removes keys to pass validation
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/config.clj:168
tags: config, specs
summary: Config keys are stripped before validation rather than the spec allowing them

`::sp/user-config` requires an exact set of keys, so anything extra is discarded
before validation rather than being permitted by a sub-spec. Flagged by a
pre-existing `todo` comment.
---
title: `pinnable?` may be missing an `:installed-version` check in `installed?`
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/addon.clj:599
tags: addon
summary: An open question in the source about where the installed-version check belongs

`installed?` tests only for `:dirname`. `pinnable?` separately requires
`:installed-version`. A comment asks whether that check belongs in `installed?`.
Moving it would change every caller of `installed?`, so it is not a local change.
---
title: `find-latest-release` docstring was truncated mid-sentence
added: 2026-08-15
status: fixed
effort: low
source: src/strongbox/github_api.clj:274
tags: github, docs
summary: The docstring ended on 'and should only be ' with no completion

Rewritten from the body during the audit. Recorded because the original suggests
a caveat the author intended to state about when the literal latest release is
the wrong choice, and that caveat is now lost.
---
title: Warn when a release.json flavor cannot be mapped to a game track
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/github_api.clj:266
tags: github
summary: Unrecognised flavors are silently dropped from the detected game track list

`find-gametracks-release-json` removes any flavor that `guess-game-track` cannot
map. A new WoW flavor would silently narrow an addon's supported game tracks.
Flagged by a pre-existing `todo` comment.
---
title: `install-addon` may orphan a subdirectory when a zip is installed directly
added: 2026-08-15
status: untriaged
effort: high
source: src/strongbox/addon.clj:342
tags: addon, install
summary: A renamed or dropped subdirectory can survive an install when `remove-addon!` was not called

Per a standing note in the source: installing from a zip cannot rely on
`remove-addon!` having run. `remove-completely-overwritten-addons` only removes
an old addon when the new one is a superset of it, so an addon that drops or
renames a subdirectory leaves the original behind.
---
title: Importing addons over pre-existing addons bypasses mutual dependency handling
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/addon.clj:36
tags: addon, import
summary: An unhandled case noted in `-remove-addon!` when importing the same addons over each other

A pre-existing `todo` comment states the case is unhandled and that mutual
dependency handling is bypassed. No reproduction recorded.
---
title: `-find-in-db` builds a full result vector to take the first item
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/core.clj:837
tags: core, performance
summary: Every catalogue row matching the join is collected before `first` discards all but one

`(into [] (filter match?) db)` realises every match across the whole catalogue,
then `(-> results first)` keeps one. A pre-existing `todo` suggests composing the
transducer with `first`. The catalogue holds tens of thousands of rows and this
runs once per join per installed addon.
---
title: `db` layer checks `:ignore?`, which is an application-level concern
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/core.clj:852
tags: core, layering
summary: `-find-first-in-db` refuses to match ignored addons, a policy decision inside a lookup function

Flagged by a pre-existing `todo` asking whether the check belongs there.
---
title: `group-id` is a URL, which blocks addons switching sources
added: 2026-08-15
status: untriaged
effort: high
source: src/strongbox/core.clj:1074
tags: core, nfo
summary: Polyfilling unmatched addons derives source and source-id from the group-id URL

A standing note says this is relied upon when falling back for addons with no
catalogue match, and that `group-id` should become something other than a URL to
remove the temptation. `TODO.md` carries a related entry: "nfo, replace the URL
as the group-id with something random".
---
title: v2 import pads addon maps with dummy values to satisfy matching
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/core.clj:1608
tags: core, import
summary: Export records are padded with a dummy dirname and version to look like installed addons

Two pre-existing `todo` comments ask why `:dirname` is needed and why
`expand-summary-wrapper` is not called directly. The padding exists so bare
export records pass `-match-installed-addon-list-with-catalogue`.
---
title: `install-many` has no protection against mutual dependencies
added: 2026-08-15
status: untriaged
effort: medium
source: src/strongbox/cli.clj:420
tags: cli, install
summary: Parallel installs from the catalogue skip the directory locking that other install paths use

`install-update-these-in-parallel` and `install-addons-from-file-in-parallel`
both take `utils/with-lock` over the directories an addon will write to.
`install-many` runs its jobs without any such lock. Flagged by a pre-existing
`todo` comment.
---
title: Log entry helpers in `cli.clj` may belong in `logging.clj`
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/cli.clj:561
tags: cli, logging, layering
summary: A block of log filtering and counting functions sits in the UI action layer

`log-entries-since-last-refresh` through `addon-has-errors?`. Flagged by a
pre-existing `todo`.
---
title: `addon-source-map-to-url` may belong in `core.clj` or `addon.clj`
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/cli.clj:649
tags: cli, layering
summary: URL construction from source data sits in the UI action layer

Flagged by a pre-existing `todo`. The function dispatches to each host module's
`make-url`, which is not a UI concern.
---
title: Theme selection and star lookup call `core` directly from the GUI
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/jfx.clj:1450
tags: jfx, layering
summary: Two `todo` comments mark GUI code that should route through `cli.clj`

`jfx.clj:1450` saves settings after a theme change and `jfx.clj:1645` does
similar. The namespace docstring for `cli.clj` now records that the GUI still
calls `core` directly in places; these are two named instances.
---
title: `installed?` check in search results may be slow
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/jfx.clj:2007
tags: jfx, performance
summary: A linear membership test runs per search result row

`(utils/in? (utils/source-map %) installed-addon-idx)` scans a collection per
row where a set lookup would do. Flagged by a pre-existing `todo`. Search
results are capped at 60 rows a page, so the impact is probably small.
---
title: `:row-updateable-selected` colour is hardcoded rather than derived
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/jfx.clj:109
tags: jfx, style
summary: A brighter variant of an existing theme colour is written out by hand

Flagged by a pre-existing `todo` asking whether it can be derived from
`:row-updateable`. Applies across every theme in `theme-map`.
---
title: `parse` has an empty set guarding the 'force :cli' branch
added: 2026-08-15
status: untriaged
effort: low
source: src/strongbox/main.clj:158
tags: main, dead-code
summary: `(contains? #{} (:action options))` is always false, so the branch never runs

The neighbouring branch for `:noui` has `#{:print-config}` and works. Either an
action was removed from the `:cli` set or the branch was never populated.
---

# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done

* gitlab as addon host
    - https://gitlab.com/search?search=wow+addon
    - returned to bucket 2019-12-04, notes:
        - gitlab doesn't handle releases like github does
            - https://stackoverflow.com/questions/29520905/how-to-create-releases-in-gitlab
        - there are very few gitlab addons (88)
            - where did this number come from?
        - api is quite slow
    - update: as of Oct 2020 gitlab sucks a little bit less and, like github, you can attach binaries to releases
        - https://gitlab.com/explore/projects?tag=World+of+Warcraft
        - https://gitlab.com/shrugal/PersoLootRoll
        - any others ...?

## todo

* multi-toc support
    - https://github.com/Stanzilla/WoWUIBugs/issues/68#issuecomment-830351390
    - https://gitlab.com/woblight/strategos 
        - has no otherwise identifying game track in it's name, toc file or releases
        - but it does support all three versions of wow

* add release.json support for github/gitlab addons

* refresh catalogue is not so healthy

## todo bucket (no particular order)

* bug, gui, 'updated' column is using dummy date in certain cases
    - I thought I fixed this?

* gitlab, add optional API authentication like github

* column profiles
    - 'skinny', 'fat', 'default'

* offer to clean up .nfo files when removing a directory

* http, curseforge, don't pause between requests if resource was cached

* bug, stacktrace on double refresh

* grouping
    - I think the tree-table-view allows us to 'group' things now ...
        - it's 'flat' at the moment, but it could be grouped by 'ignored', 'pinned', 'updates available'
            - ignored are collapsed

* add a 'add to user-catalogue' option to make an addon always available despite selected catalogue

* add a 'catalogue is N days old' somewhere

* gui, try replacing the auto fit columns with something like this:
    - https://stackoverflow.com/questions/14650787/javafx-column-in-tableview-auto-fit-size#answer-49134109

* gui, toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - highlight mismatched game track
    - highlight mismatched update host
        - installing addon from a different host
    - touch of colour against each menuitem would serve as a legend
    - 2021-10: not sure about this one anymore
        - returned to the bucket.

* gui 'wow' column is inconsistent
    - curseforge, tukui and github return new `interface-version` values with the update data, wowi stores this in it's `fileList` file.
    - wowi has `UICompatibility` in v3 of it's `fileList` and `gameVersion` in v4 of it's `fileList`, but nothing when fetching an addon's updates. 
        - I'd need to combine the catalogue data (which could be a week old already) with the update data.
    - for curseforge, it's pulling it's value from :gameVersion, which may be empty
        - in which case it pulls it's value from the toc file, which may be different from the selected game track
    - the value in the gui should reflect the installed version if no update pending, else the interface version of the pending update.
    - returning to bucket 2021-10
        - it works well enough for now

* checkbox column for selecting addon rows
    - might be nicer than ctrl-click

* centralised download location on filesystem
    - The Undermine Journal is large (75MB) and it sucks to download it again and again from different dirs
        - perhaps tie this in with a rename of the downloaded zip file so unambiguous reverse lookups can be done:
            - source--sourceid--version.zip => curseforge--543210--1-9-26.zip

* centralised addon directory db
    - install an addon, then 'deactivate' it
        - essentially uninstalls the addon but it's still available at the tick of a box
            - see WADM https://github.com/MBODM/WADM
            - does Nexus Mod Manager do something similar?
                - that UI is so shit though ... who knows what it is doing.

* gui, "fat rows"
    - add option to switch to fatter rows with more styled data
        - clicking on the row expands it from small to medium
        - clicking 'more' (or whatever) takes to addon detail page
    - perhaps coincide with catalogue v3 with more addon details



* wowinterface, revisit the pages that are being scraped, make sure we're not missing any

* export/import addons to/from github
    - I have a github account, I'd like to push/pull addons to it
        - use a gist?
        - dedicated repo?
    - other targets to publish to?
        - always keep the base import/export to a *file*
        - ftp? s3 bucket? ssh? gitlab?
    - feels a bit like scope creep to me
        - it would be nice and convenient, but a lot of work to build and maintain

* schedule user catalogue refreshes
    - ensure the user catalogue doesn't get too stale and perform an update in the background if it looks like it's very old
        - update README

* 'update all' should be a no-op if nothing has updates available
    - don't disable the button, just don't do anything

* clear non-catalogue cache after session
    - it seems reasonable that stopping and starting the app will have it re-fetch addon summaries.

* install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
    - would be good for installing older versions of an addon?
    - would be good for installing addons from unsupported sources
        - wouldn't be able to update it however :(
            - we would if it matched against the catalogue, like all the other reconciled addons
    - 'import addon' dialog could do double time
        - 'from url' and 'from file'

* deleting an addon should also remove any of it's zip files

* acquire locks on affected addons during installatinon
    - this will let us uninstall and install addons in parallel

* share a pool of connections between jobs
    - https://github.com/dakrone/clj-http#user-content-persistent-connections
    - N connections serving M threads
    - pretty fast just by making requests in parallel
        - moving this back to the bucket until I start really looking for optimisations

* addon detail, mutual dependencies pane
    - for example, I would like to see what is happening when:
        adibags anima & conduits is overwritten by adibags anima filter

* change split button 'outdent' to 'indent'
    - and if split, keep it 'pressed in'

* tags, make clickable in search results, 
    - adds a filter that can be removed
    - add clickable tags to addon detail page

* preferences, "update all addons automatically"
    - update README features
    - punted back to bucket 2021-06-02

* update check
    - ignore pre-releases

* logging, app level 'help'
    - messages to the user that are not informational, or debug or warnings or errors, but simple helpful messages
        - 'help' messages strike me now as being 'info' level
        - perhaps attach 'detail' metadata to info messages that elaborate on the message and what can be done about it
    - it should stand out from the other messages, look friendly, etc
        - going with the further 'detail' metadata idea, adding an info icon would make it stand out

* classic addon dir detection
    - also check for 
        - '_classic_' '_classic_beta_' '_classic_ptr_'
    - is there an official source on these somewhere?

* bug, I should be able to re-install a pinned addon if the pinned release is available, but I'm getting an error
    - "refusing to install addon that will overwrite a pinned addon"
    - this is actually a bit more involved than it first looks. shifting to it's own ticket

* nfo, spend some time futzing with nfo files on disk and how they can break the UI
    - I've managed to get some weird error messages by changing 'source' to an int, to a catalogue that doesn't exist, etc

* zip, switch to apache commons compress for decompressing
    - https://commons.apache.org/proper/commons-compress/
    - .tar.gz and 7z support would be interesting
    - rar should just die already
    - this would fix a major showstopper in porting to windows

* test, can gui-diff and main/test be pushed back into the testing namespace and elided from release somehow?

* create a parser for that shit markup that is preventing reconcilation
    - see aliases

* add checksum checks after downloading
    - curseforge have an md5 that can be used
        - unfortunately no checksum in api results
        - they do have a 'fileLength' and a 'fingerprint'
            - fingerprint is 9 digits and all decimal, so not a hex digest
    - wowinterface checksum is hidden behind a javascript tabber but still available
        - wowinterface do have a md5sum in results! score

* EOL planning
    - I'm not going away and neither is strongbox, but! *should* I or my free time disappear will strongbox continue being useful?
        - what can I do to ensure it is the most useful if I just give up on it tomorrow?
            - catalogues have been pushed into user config, so they can be swapped out if necessary
                - more can be done around this to make catalogue generation more accessible and safe
        - what can't I control?
            - addon hosts
                - our interface with them is their API or in wowi's case, their API and website

* reconciliation, add dirname support
    - not sure which hosts support these

* wowinterface, multiple game tracks
    - investigate just what is being downloaded when a classic version of a wowi addon is downloaded
    - see 'LagBar'

* reconciliation, rename 'reinstall all' to 'reconcile'
    - steal from the best
    - make the reconcile automatic
        - if a nfo file isn't found
    - remove the 'first time instructions' from the readme
        - it should just fucking do it

* investigate better popularity metric than 'downloads'
    - if we make an effort to scrape everyday, we can generate this popularity graph ourselves

* add a 'tabula rasa' option that wipes *everything* 
    - cache, catalog, config, downloaded zip files

## next major version (v5)

* default to keeping last three zip files by default
* replace 'installed' and 'available' columns with the composite 'version' column
* remove the (pinned) and (installed) labels from from the 'available' column
* drop support catalogue v1
    - a prerequisite for v5 then would be introducing a new catalogue
* readme, the ~your machine's `hostname`~ bit.
* readme, the "Original Swing GUI was last available in version 3.x using" bit

## catalogue v3 / capture more addon data

* 'website'
    - 'x-website'/'x-url' in toc
    - add 'website' to addon-detail pane next to 'browse local files' and addon host
        - depends on capturing x-website
    - add a 'website' column to installed-addons
* 'author'
    - add an 'author' column to installed-addons
    - add to addon-detail
    - search other addons by author

## releases

* addon detail, 'releases' widget, including *all* possible releases to download and install
    - add an 'WoW' column to know which game-track/interface
    - disable releases excluded by selected game-track/strictness setting

* alpha/beta opt-in
    - user can opt to install alpha/beta releases per-addon
    - make it a simple preference

* no-lib
    - user can opt to prefer no-lib versions
        - what if addon only ever released one no-lib then decided not to use them again?
            - addon would be stuck on a very old version

* keep a list of previously installed addons
    - eh. tie it in with downloading more release information
    - defer until after job queue
        - very large downloads are possible. just see curseforge dbm

* addon detail, 'releases' widget
    - installed release should be highlighted

## import/exports

* import and export addons using addon urls

* cli, exports

* import, export, capture 'pinned' information
    - we can now import addons at a specific version
        - when importing a pinned addon, should we keep the pin? 
        - or drop the pin it and display updates?
    - we can now export addons at specific versions
        - I think we already have this though ... called :version

* import, skip importing an addon if addon already exists in addon dir

* import, why can't an export record be matched to the catalogue and then installed that way?
    - no need for padding and dummy dirnames then
    - installing normally would also include the mutual dependency handling

## github 

* toc, add support for x-github key
    - X-Github: https://github.com/teelolws/Altoholic-Retail 
        - repo no longer exists
        - github search:
            - https://github.com/search?q=%22X-Github%22++extension%3Atoc&type=Code&ref=advsearch&l=&l=
    - and what would it do?
        - I could switch between sources I suppose ...

* github, importing an exported addon list with a github addon won't see that addon installed
    - unless that addon is present in the user catalogue
        - which in a fresh install where a list of addons are being restored is unlikely...
    - this is interesting actually. the exported addon list has become a mini-catalogue
        - some addons require the larger catalogue to resolve
        - github addons are resolved and installed by a different means...

* github, add any tags if they exist

* github, add 'created date'

## ui/gui

* dedicated tab for "user-catalogue" ?
    - add, delete, update github addons
    - see accumulating release history for addons?

* bug, changing sort order during refresh doesn't reflect which addon is being updated
    - I think changing column ordering and moving columns should be disabled while updates happen
        - just freeze or disable them or something.

* internationalisation?
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
    - wowman was mentioned on a french forum the other day ..

* gui, get log window scrolling in other direction

## import/export

* export to markdown
    - I think I'd like a simple list like:
        * [addon name](https://source/path/to/addon)
    - of course, this would be a different type of export than the one used for import
        - although ... I could possibly parse the list ... and nah.
    - clostache?
        - https://github.com/fhd/clostache

## search

* search, order by date only orders the *current page* of results
* search, group results
    - group by downloads/age/category?
        - it would finally be the best use for category data

## cli

* cli, update specific addon
* cli, install specific addon
* cli, colours!
* cli, replace with a repl
    - lein --cli gives you access to the code directly

## unified UI

this is still an interesting idea

* remove log split
* remove tabs
* gui, both panes, filter by categories
* gui, group results
    - installed
    - updates
    - category ...

## wontfix

* importing addons, skip db lookup for addon urls that don't need it
    - if we can 'expand it' then we can download it and install it.
        - yes, and the explicit url should be respected.
            - if the user imported from wowi or curseforge, that host should be used for installation
                - after installation, we can match it against the current catalogue and go from there.
                    - because the addon is added to the user catalogue, it will always be available, there will always be a match.
                        - but not initially.
                            - no, but we have the source and source-id (or url) to match against.
            - we need to find a download url
                - wowi needs source-id
                - curse needs a source-id which comes from the db
                - tukui needs source-id and a name if the id negative
                    - name could come from the url string to import
    - I think tukui, wowi can, github obs, curseforge could not
    - I'm moving this to 'wontfix'
        - it's possible for wowi and tukui, not curse
        - the error message is clear (can't find in catalogue)
* github, add a github catalogue
    - just a simple list of wow addons on github that can be installed with strongbox
    - yeah, nah
        - I don't want that responsibility
* logs, persistent addon events
    - installed, updated, pin, ignore events
    - like ... stored in addon history?
        - possible, but why? to please data freaks like me?
    - going to need a better reason than 'just cos' for this
* add a 'Delete all' option to cache menu
    - we don't really want legitimate nfo files to be accidentally deleted
* investigate `.csv` as a human-readable but more compact representation
    - might be able to save a MB on extraneous syntax
    - might be able to speed up parsing and loading
    - depends on profile task
        - update: profiling happened, it's not the json loading that is slow it was many other things. 
        - reading the file and parsing the json is actually very quick, validation is slower but necessary
        - json is just more flexible all around than csv.
* windows support
    - windows is just the worst, most awful dystopian software I've ever seen and it hurts my soul every time I try to use it
    - I just plain hate it, it epitomises the very opposite of what I stand for and I refuse to work on it ever again
* .rar/.tar.gz addons
    - !BeautyLoot on wowinterface is an example of this
        - https://www.wowinterface.com/downloads/info20212
    - rar is a proprietary format
    - the vast majority of addons use .zip
    - no native support in java/clojure for it
        - library here: https://github.com/junrar/junrar
            - just found it while going through minion source

* cli, interactive interface when no specific action specified 
    - you have N addons installed. what do you want to do? (list, update, update-all, delete) etc
    - this is a huge amount of hassle

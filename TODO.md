# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done

## todo

* gui, download progress bar *inside* the grid ...?
    - pure fantasy?
    - defer until after job queue

* greater parallelism
    - internal job queue
    - replace log at bottom of screen with a list of jobs being processed and how far along they are
        - each job can be cancelled/stopped/discarded

* a 'stop' button to stop updates would be nice

* only unique jobs in queue
    - pumping the update all button won't do much

* download addon details in parallel
    - speed benefits, mostly
    - share a pool of connections between threads
        - N connections serving M threads

## todo bucket (no particular order)

* add release.json support for github addons

* importing addons, skip db lookup for addon urls that don't need it
    - if we can 'expand it' then we can download it and install it.
    - I think tukui, wowi can, github obs, curseforge could not

* import and export addons using addon urls

* toc, addon detail, add 'x-website' / 'x-url' alongside 'browse local files' and addon host

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

* addon detail, 'releases' widget, including *all* possible releases to download and install
    - add an 'WoW' column to know which game-track/interface
    - disable releases excluded by selected game-track/strictness setting

* logging, app level 'help'
    - messages to the user that are not informational, or debug or warnings or errors, but simple helpful messages
        - 'help' messages strike me now as being 'info' level
        - perhaps attach 'detail' metadata to info messages that elaborate on the message and what can be done about it
    - it should stand out from the other messages, look friendly, etc
        - going with the further 'detail' metadata idea, adding an info icon would make it stand out

* bug, catalogue loading
    - while updating the catalogue with the new tukui addons I discovered a case where the catalogue *should* be failing validation but it wasn't.
        - it came down to an :opt vs :opt-un in the spec
            - the key in question wasn't qualified and thus not matched for validation
    - the catalogue should always be loadable by previous versions of strongbox that support the given spec version
        - ...
    - when the catalogue fails validation it shouldn't freeze the app while the reason is printed in the console

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

* zip, better errors for failing to decompress .rar files
    - see !FREEZING from wowinterface
        - it's a .rar addon
        - the full path is emitted in the error, which is impossible to fully read
        - the extension has been replaced with .zip
            - if the extension were preserved we could dismiss it immediately as unsupported

        2021-03-20 01:35:58.026 DEBUG [strongbox.zip:23] - failed to open+close zip file: /home/torkus/path/to/wine/dir/drive_c/program files/World of Warcraft/_retail_/Interface/Addons/-freezing--1-04.zip
        path [] triggered :strongbox.ui.jfx$start$update_gui_state__39204@608569a040151
        2021-03-20 01:35:58.027 ERROR [strongbox.core:419] - failed to read zip file '/home/torkus/path/to/wine/dir/drive_c/program files/World of Warcraft/_retail_/Interface/Addons/-freezing--1-04.zip', could not install -freezing

* investigate *warn-on-reflections*
    - I think there may be some solid performance gains by turning this on
        - remember to profile first

* test, can gui-diff and main/test be pushed back into the testing namespace and elided from release somehow?


# job queue


* performance, check addons for updates immediately after loading
    - if after we've read the nfo data and we have everything we need, check the addon for updates immediately
        - don't wait for db loading and addon matching
            - we already have a match!
        - this might fit in with the greater-parallelism/queue based infrastructure


# releases

* alpha/beta opt-in
    - user can opt to install alpha/beta/no-lib releases per-addon

* keep a list of previously installed addons
    - eh. tie it in with downloading more release information
    - defer until after job queue
        - very large downloads are possible. just see curseforge dbm

# import/exports

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

# github 

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

* add any tags, if they exist

# addon

* create a parser for that shit markup that is preventing reconcilation
    - see aliases

* if a match has been made and the addon installed using that match, and then the catalogue changes, addon should still be downloadable
    - right?
        - we have the source and source-id, even the group-id to some extent
    - switching catalogues may see the addon matched against another host
        - nothing wrong with that, but ...

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

* EOL planning, robustness, only download/update the catalogue *after* an existing catalogue has been confirmed
    - github is down, wowman is erroring with a 500
    - failure to download a catalogue shouldn't prevent addons from being displayed
    - failure to contact a host shouldn't prevent addons on other hosts from working

* EOL planning, bundle a catalogue with the installation
    - load it as a resource with static-slurp, like we do with the sql?
        - also compressed so it's tiny?
    - behind the scenes we download and load the full-catalogue
        - would this block reconciliation?
            - perhaps if there are unmatched addons after reconciliation we then wait and try again ...?

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

# ---

* reconciliation, add dirname support
    - not sure which hosts support these

* wowinterface, multiple game tracks
    - investigate just what is being downloaded when a classic version of a wowi addon is downloaded
    - see 'LagBar'

* when curseforge api is down users get a wall of red error messages with very little useful information
    - see issue 91: https://github.com/ogri-la/wowman/issues/91
        - the error message has been improved but we still get a red wall of text
        - aggregate error messages?

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

* testing, capture metrics with an eye to improving performance and speed
    - we have coverage metrics now
    - would like some timing around certain operations
        - like loading the catalog
            - done
        - like downloading and installing the top-10, top-20, top-N addons
            - this could be a good benchmark actually
                - how quickly can one go from 'nothing installed' to '20 addons installed' ?
                - could be tied in with backups/exports
                    - got to have backups+imports happening first
        - identify slow things and measure their improvement

# ui/gui


* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
    - would be good for installing older versions of an addon?
    - would be good for installing addons from unsupported sources
        - wouldn't be able to update it however :(
        - I think I'll stick with supporting sources of addons
            - rather than enabling ad-hoc installation of unsupported addons

* dedicated tab for "user-catalogue" ?
    - add, delete, update github addons
    - see accumulating release history for addons?

* bug, changing sort order during refresh doesn't reflect which addon is being updated
    - I think changing column ordering and moving columns should be disabled while updates happen
        - just freeze or disable them or something.



* gui 'wow' column is inconsistent
    - for curseforge, it's pulling it's value from :gameVersion, which may be empty
        - in which case it pulls it's value from the toc file, which may be different from the selected game track
    - since this is the 'installed addons pane', should the value reflect the value of the installed addon?
        - (and not the value of the addon to be installed)
        - and would this be inconsistent with the other fields that are also changing with new catalog information?

* gui, toggleable columns as a menuitem

* internationalisation?
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
    - wowman was mentioned on a french forum the other day ..

* gui, toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend


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
* nightly unstable builds
    - building the 'develop' branch once a day
        - making it available as the 'unstable' release that always gets replaced
    - project.clj "x.y.z-unreleased" would be changed to "x.y.z-unstable"
    - development would happen mainly in feature branches
    - too much effort for what? more user reports? I don't have that sort of time
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
* fallback to using :group-id (a uri) if curseforge.json is not available
    - low priority
    - curseforge.json will only ever be missing:
        - fresh install and
        - your network connection goes down, or
        - you're a victim of github's 99.999 uptime rating
    - wontfix because:
        - group-id is 'group-id' and *not* 'uri'
            - it may change in the future
        - there is a really really slim chance of this actually happening
            - I don't think it justifies the extra complexity tbh
    - 2020-11-28: I think this was about being able to download catalogues (curseforge.json) when github is down
        - if a catalogue is embedded then we can always fall back to that
        - see EOL planning
* gui, search pane, clear search button
    - I don't think this is necessary anymore
* gui, stateful buttons
    - don't allow enabled 'delete selected' buttons if nothing is selected
    - not going to coddle the user. deleting nothing will see nothing deleted.
* cli, interactive interface when no specific action specified 
    - you have N addons installed. what do you want to do? (list, update, update-all, delete) etc
    - this is a huge amount of hassle

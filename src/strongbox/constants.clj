(ns strongbox.constants)

(def mascot "ᕙ[°▿°]ᕗ")

(def release-of-previous-expansion
  "'Dragonflight', released November 28th/29th 2022. Used to shorten the 'full' catalogue.
  https://warcraft.wiki.gg/wiki/Expansion#World_of_Warcraft"
  "2022-11-28T00:00:00Z")

(def release-of-wow-classic
  "the date wow classic went live (addon development may have started before that). Used to guess possible game tracks when it's ambiguous.
  https://warcraft.wiki.gg/wiki/Public_client_builds
  https://worldofwarcraft.com/en-us/news/22990080/mark-your-calendars-wow-classic-launch-and-testing-schedule"
  "2019-08-26T00:00:00Z")

;; used as a placeholder for an addon's supported version when we're forced to guess.
;; don't fret too much about patch versions. These values don't affect much.
;; https://warcraft.wiki.gg/wiki/Public_client_builds
(def latest-retail-game-version "11.0.0")
(def latest-classic-game-version "1.14.3")
(def latest-classic-tbc-game-version "2.5.4")
(def latest-classic-wotlk-game-version "3.4.0")
(def latest-classic-cata-game-version "4.0.1")

;; interface version to use if .toc file is missing one.
;; assume addon is compatible with the most recent version of retail (see above).
;; these values need to match the latest-* values above.
(def default-interface-version 110000)
(def default-interface-version-classic 11400)

;; take all of the game tracks to the right of your position
;; then all to the left.
;; [1 2 3 4 5 6] => 6 => [6 5 4 3 2 1]
;; [1 2 3 4 5 6] => 5 => [5 6 4 3 2 1]
;; [1 2 3 4 5 6] => 4 => [4 5 6 3 2 1]
;; [1 2 3 4 5 6] => 3 => [3 4 5 6 2 1]
;; [1 2 3 4 5 6] => 2 => [2 3 4 5 6 1]
;; [1 2 3 4 5 6] => 1 => [1 2 3 4 5 6]
(def game-track-priority-map
  "when `strict?` is `false` and an addon fails to match against a given `game-track`, other game tracks will be checked.
  the strategy is to assume the next-best game tracks are the ones 'closest' to the given `game-track`, newest to oldest.
  for example, if a release for wotlk classic is not available and releases for cata, bcc and vanilla are, which to choose?
  this strategy prioritises cata, then bcc and finally vanilla."
  {:retail [:retail :classic :classic-tbc :classic-wotlk :classic-cata]
   :classic [:classic :classic-tbc :classic-wotlk :classic-cata :retail]
   :classic-tbc [:classic-tbc :classic-wotlk :classic-cata :classic :retail]
   :classic-wotlk [:classic-wotlk :classic-cata :classic-tbc :classic :retail]
   :classic-cata [:classic-cata :classic-wotlk :classic-tbc :classic :retail]})

(def bullet "\u2022") ;; •

;; used when a placeholder datetime is needed.
;; like when we're polyfilling nfo data to create an addon summary.
(def fake-datetime "2001-01-01T01:01:01Z")

;; used when a placeholder date is needed.
;; like during testing the formatting of date durations.
(def fake-date "2001-01-01")

(def glyph-map--regular
  {:tick "\u2714" ;; '✔'
   :unsteady "\u2941" ;; '⥁' CLOCKWISE CLOSED CIRCLE ARROW
   :warnings "\u2501" ;; '━' heavy horizontal
   :errors "\u2A2F" ;; '⨯' vector or cross product
   :update "\u21A6" ;; '↦' rightwards arrow from bar
   :ignored "\u26AA" ;; '⚪' medium white circle
   :pinned "\u26ab" ;; '⚫' medium black circle
   :star "\u2605" ;; '★' black star
   :right-arrow "\u2794" ;; '➔' HEAVY WIDE-HEADED RIGHTWARDS ARROW
   })

(def glyph-map--fontawesome
  {:tick "\uf00c" ;; check
   :unsteady "\uf021" ;; arrows rotate
   :warnings "\uf068" ;; minus
   :errors "\uf00d" ;; xmark
   :update "\uf061" ;; arrow-right
   :ignored "\uf056" ;; circle minus
   :pinned "\uf192" ;; circle dot
   :star "\uf005" ;; star
   :right-arrow "\uf061" ;; arrow-right
   })

(def glyph-map glyph-map--fontawesome)

(def curseforge-cutoff-label "Feb 1st, 2022")
(def tukui-cutoff-label "June 1st, 2023")

(def releases
  "https://warcraft.wiki.gg/wiki/Patch"

  {"11.0.0" "The War Within"

   "10.2.7" "Dragonflight: Dark Heart"
   "10.2.6" "Dragonflight: Plunderstorm"
   "10.2.5" "Dragonflight: Seeds of Renewal"
   "10.2" "Dragonflight: Guardians of the Dream"
   "10.1" "Dragonflight: Embers of Neltharion"
   "10.0" "Dragonflight"

   "9.2" "Shadowlands: Eternity's End"
   "9.1" "Shadowlands: Chains of Domination"
   "9" "Shadowlands"

   "8.3" "Battle for Azeroth: Visions of N'Zoth"
   "8.2" "Battle for Azeroth: Eternity's End"
   "8.1" "Battle for Azeroth: Tides of Vengeance"
   "8" "Battle for Azeroth"

   "7.3" "Legion: Shadows of Argus"
   "7.2" "Legion: The Tomb of Sargeras"
   "7.1" "Legion: Return to Karazhan"
   "7" "Legion"

   "6.2" "Warlords of Draenor: Fury of Hellfire"
   "6" "Warlords of Draenor"

   "5.4" "Mists of Pandaria: Siege of Orgrimmar"
   "5.3" "Mists of Pandaria: Escalation"
   "5.2" "Mists of Pandaria: The Thunder King"
   "5.1" "Mists of Pandaria: Landfall"
   "5" "Mists of Pandaria"

   "4.3" "Cataclysm: Hour of Twilight"
   "4.2" "Cataclysm: Rage of the Firelands"
   "4.1" "Cataclysm: Rise of the Zandalari"
   "4" "Cataclysm"

   "3.3" "Wrath of the Lich King: Fall of the Lich King"
   "3.2" "Wrath of the Lich King: Call of the Crusade"
   "3.1" "Wrath of the Lich King: Secrets of Ulduar"
   "3" "Wrath of the Lich King"

   "2.4" "The Burning Crusade: Fury of the Sunwell"
   "2.3" "The Burning Crusade: The Gods of Zul'Aman"
   "2.1" "The Burning Crusade: Black Temple"
   "2" "The Burning Crusade"

   "1.12" "World of Warcraft: Drums of War"
   "1.11" "World of Warcraft: Shadow of the Necropolis"
   "1.10" "World of Warcraft: Storms of Azeroth"
   "1.9" "World of Warcraft: The Gates of Ahn'Qiraj"
   "1.8" "World of Warcraft: Dragons of Nightmare"
   "1.7" "World of Warcraft: Rise of the Blood God"
   "1.6" "World of Warcraft: Assault on Blackwing Lair"
   "1.5" "World of Warcraft: Battlegrounds"
   "1.4" "World of Warcraft: The Call to War"
   "1.3" "World of Warcraft: Ruins of the Dire Maul"
   "1.2" "World of Warcraft: Mysteries of Maraudon"
   "1" "World of Warcraft"})

(def max-user-catalogue-age 28)

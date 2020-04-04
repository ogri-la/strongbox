(ns strongbox.tags
  (:require
   [strongbox.specs :as sp]
   [clojure.string :refer [trim lower-case]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   ;;[taoensso.timbre :refer [debug info warn error spy]]
   ))

;; wowinterface-specific categories that are replaced


(def wowi-replacements
  {"Character Advancement" [:quests :leveling :achievements]
   "Other" [:misc]
   "Suites" [:compilations]
   "Graphic UI Mods" [:ui :ui-replacements]
   "UI Media" [:ui] ;; audio video ?
   "ROFL" [:misc :mini-games]
   "Combat Mods" [:combat]
   "Buff, Debuff, Spell" [:buffs :debuffs]
   "Casting Bars, Cooldowns" [:buffs :debuffs :ui]
   "Map, Coords, Compasses" [:map :minimap :coords :ui]
   "RolePlay, Music Mods" [:role-play :audio]
   "Chat Mods" [:chat]
   "Unit Mods" [:unit-frames]
   "Raid Mods" [:unit-frames :raid-frames]
   "Data Mods" [:data]
   "Utility Mods" [:utility] ;; misc?
   "Action Bar Mods" [:action-bars :ui]
   "Tradeskill Mods" [:tradeskill]
   "Classic - General" [:classic]})

;; wowinterface-specific categories that gain new tags


(def wowi-supplements
  {"Pets" [:battle-pets :companions]
   "Data Broker" [:data]
   "Titan Panel" [:plugins]
   "FuBar" [:plugins]
   "Mail" [:ui]})

;; curseforge-specific categories that are replaced


(def curse-replacements
  {"HUDs" [:ui :unit-frames]
   "Minigames" [:mini-games]
   "Auction & Economy" [:action-house]
   "Chat & Communication" [:chat]
   "Development Tools" [:dev]
   "Libraries" [:libs]
   "Damage Dealer" [:dps]
   "Boss Encounters" [:boss]
   "Twitch Integration" []})

;; curseforge-specific categories that gain new tags


(def curse-supplements
  {"Quests & Leveling" [:achievements]
   "Arena" [:pvp]
   "Battleground" [:pvp]
   "Battle Pets" [:pets :companions :mounts]
   "Map & Minimap" [:coords :ui]
   "Raid Frames" [:unit-frames]
   "Data Export" [:data]
   "Data Broker" [:data]
   "Titan Panel" [:plugins]})

(def tukui-replacements
  {"Edited UIs & Compilations" [:ui :compilations]
   "Full UI Replacements" [:ui]
   "Skins" [:ui]
   "Tooltips" [:tooltip] ;; singular
   "Plugins: Other" [:plugins :misc]})

(def tukui-supplements
  {"Map & Minimap" [:coords :ui]})

;; categories shared by all addon hosts already that are replaced


(def general-replacements
  {"Miscellaneous" [:misc]})

;; categories shared by all addon hosts that gain new tags


(def general-supplements
  {"Druid" [:class]
   "Warlock" [:class]
   "Warrior" [:class]
   "Rogue" [:class]
   "Healers" [:role]
   "Death Knight" [:class]
   "Paladin" [:class]
   "Mage" [:class :caster]
   "Priest" [:class :caster]
   "Tank" [:class]
   "Monk" [:class]
   "Shaman" [:class :caster]
   "Demon Hunter" [:class]
   "Hunter" [:class]

   "Alchemy" [:professions]
   "Cooking" [:professions]
   "Mining" [:professions]
   "Engineering" [:professions]
   "Jewelcrafting" [:professions]
   "Tailoring" [:professions]
   "First Aid" [:professions]
   "Fishing" [:professions]
   "Leatherworking" [:professions]
   "Enchanting" [:professions]
   "Blacksmithing" [:professions]
   "Inscription" [:professions]
   "Skinning" [:professions]
   "Archaeology" [:professions]
   "Herbalism" [:professions]})

(def replacement-map
  {"wowinterface" wowi-replacements
   "curseforge" curse-replacements
   "tukui" tukui-replacements})

(def supplement-map
  {"wowinterface" wowi-supplements
   "curseforge" curse-supplements
   "tukui" tukui-supplements})

(defn-spec category-to-tag (s/or :ok ::sp/tag, :bad nil?)
  [category ::sp/category]
  (when-not (empty? category)
    (-> category
        lower-case
        trim

        ;; hyphenate white space
        (clojure.string/replace #" +" "-")
        keyword)))

(defn-spec category-to-tag-list (s/or :singluar ::sp/tag, :composite ::sp/tag-list)
  "given a `category` string, converts it into one or many tags."
  [addon-host ::sp/catalogue-source, category ::sp/category]
  (let [replacements (merge general-replacements (get replacement-map addon-host))
        supplements (merge general-supplements (get supplement-map addon-host))

        ;; if there is a replacement set of tags, we don't continue searching for supplementary tags
        replacement-tags (get replacements category [])
        supplementary-tags (get supplements category [])

        tag-list (into replacement-tags supplementary-tags)]

    (if-not (empty? replacement-tags)
      ;; we found a set of replacement tags so we're done
      tag-list

      ;; couldn't find a replacement set of tags so parse the category
      (let [bits (clojure.string/split category #"( & |, |: )+")]
        (->> bits (map category-to-tag) (into tag-list) (remove nil?) vec)))))

(defn-spec category-list-to-tag-list ::sp/tag-list
  "given a list of category strings, converts them into a distinct list of tags by calling `category-to-tag-list`."
  [addon-host ::sp/catalogue-source, category-list ::sp/category-list]
  ;; sorting cuts down on noise in diffs.
  ;; `set` because curseforge has duplicate categories
  (->> category-list (map (partial category-to-tag-list addon-host)) flatten set sort vec))

;;

(st/instrument)

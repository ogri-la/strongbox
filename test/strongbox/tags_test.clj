(ns strongbox.tags-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.tags :as tags]))

(deftest category-to-tag
  (testing "a token from a standard category string is converted into a 'tag'"
    (let [cases [["" nil]
                 ["foo" :foo]
                 ["foo bar" :foo-bar]

                 ;; doesn't handle tokenisation, see `category-to-tag-list`
                 ["foo & bar" :foo-&-bar]
                 ;; see? total mess
                 ["foo, bar" (keyword "foo,-bar")]]]
      (doseq [[given expected] cases]
        (is (= expected (tags/category-to-tag given)))))))

(deftest category-to-tag-list
  (testing "standard category is parsed in to a list of tags."
    (let [cases [["" []]
                 ["foo" [:foo]]
                 ["foo bar" [:foo-bar]]
                 ["foo & bar" [:foo :bar]]
                 ["foo & bar & baz" [:foo :bar :baz]]
                 ["foo, bar" [:foo :bar]]
                 ["foo, bar, baz" [:foo :bar :baz]]
                 ["foo: bar" [:foo :bar]]
                 ["foo: bar: baz", [:foo :bar :baz]]

                 ;; nothing does this, but it's supported :)
                 ["foo & bar: baz, bup" [:foo :bar :baz :bup]]]]
      (doseq [[given expected] cases]
        (is (= expected (tags/category-to-tag-list "unhandled-addon-host" given)))))))

(deftest category-to-tag-list-replacement
  (testing "specific categories get a better replacement"
    (let [cases [;; we won't bother with a 'spell' tag for wowinterface
                 ["wowinterface" "Buff, Debuff, Spell" [:buffs :debuffs]]

                 ;; curseforge doesn't bother with it either but if we say it came from
                 ;; curseforge then we have no specific replacement rules for it
                 ["curseforge" "Buff, Debuff, Spell" [:buff :debuff :spell]]

                 ["wowinterface" "Classic - General" [:classic]]
                 ["curseforge" "Damage Dealer" [:dps]]
                 ["tukui" "Edited UIs & Compilations" [:ui :compilations]]]]
      (doseq [[addon-host given expected] cases]
        (is (= expected (tags/category-to-tag-list addon-host given))))))

  (testing "specific categories get a better replacement no matter which host they come from"
    (is (= [:misc] (tags/category-to-tag-list "unhandled-addon-host" "Miscellaneous")))))

(deftest category-to-tag-list-supplement
  (testing "specific categories get parsed like usual, but we tack on extra tags as well"
    (let [cases [;; the simple 'pets' category gets supplemented with tags present for other hosts
                 ["wowinterface" "Pets" [:battle-pets :companions :pets]]
                 ["curseforge" "Arena" [:pvp :arena]]
                 ;; composite categories are parsed into individual tags as well
                 ["tukui" "Map & Minimap" [:coords :ui :map :minimap]]]]
      (doseq [[addon-host given expected] cases]
        (is (= expected (tags/category-to-tag-list addon-host given))))))

  (testing "specific categories get extra tags no matter which host they come from"
    (is (= [:class :caster :priest] (tags/category-to-tag-list "unhandled-addon-host" "Priest")))))

(deftest category-list-to-tag-list
  (testing "list of categories are parsed, sorted, filtered and de-duplicated correctly"
    (let [cases [[[""] []]
                 [["" "" ""] []]
                 [["foo" "foo bar" "bar & baz" "bup, bap"] [:bap :bar :baz :bup :foo :foo-bar]]
                 [["Miscellaneous" "Miscellaneous"] [:misc]]]]
      (doseq [[given expected] cases]
        (is (= expected (tags/category-list-to-tag-list "unhandled-addon-host" given)))))))

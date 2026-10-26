(ns workshop.text
  (:require [clojure.string :as str]))

;; The task: read a novel with Clojure. Os Maias (1888) by Eça de Queiroz,
;; from Project Gutenberg.
;;
;; Work through the (comment ...) block one exercise at a time. The spelling
;; is from 1888: Affonso, elle, d'uma. Expect surprises.

(def book (slurp "data/os-maias.txt"))

;; The same book straight from Project Gutenberg, with its licence header and
;; footer. slurp reads URLs as well as files. For the stretch goals.
(def gutenberg-url "https://www.gutenberg.org/cache/epub/40409/pg40409.txt")

;; Common words that say little about the book. A set is also a function:
;; (stop-words "de") returns "de", and (stop-words "carlos") returns nil.
(def stop-words
  #{"a" "á" "à" "ao" "aos" "as" "às" "com" "como" "d" "da" "das" "de" "do" "dos"
    "e" "é" "ella" "ellas" "elle" "elles" "em" "era" "foi" "lhe" "lhes" "mais"
    "mas" "me" "n" "na" "nas" "não" "no" "nos" "o" "os" "para" "pela" "pelo"
    "por" "que" "se" "seu" "sua" "seus" "suas" "te" "um" "uma" "umas" "uns"})

;; A ready-made bar chart for the View pane:
;;   (tap> (bars {"carlos" 1798 "ega" 1125}))
;; Takes a map, or a list of [label number] pairs, and keeps their order.
(defn bars [counts]
  (let [biggest (max 1 (apply max (map second counts)))]
    [:table {:style "border-collapse: collapse"}
     (for [[label n] counts]
       [:tr
        [:td {:style "padding: 2px 8px 2px 0; text-align: right"} (str label)]
        [:td {:style "width: 320px"}
         [:div {:style (str "height: 14px; background: #5b4fd6; width: "
                            (* 100.0 (/ n biggest)) "%")}]]
        [:td {:style "padding-left: 8px"} (str n)]])]))

(comment

  ;; 1. How many characters long is the book?
  ;;    Hint: count
  (count book)

  ;; 2. Show the first 300 characters.
  ;;    Hint: subs

  ;; 3. Split the book into words, all in lower case, and call it words.
  ;;    First try this. What goes wrong?
  (re-seq #"\w+" "coração")
  ;;    In Java regular expressions, \w only matches English letters. \p{L}
  ;;    matches a letter in any language.
  ;;    Hint: str/lower-case, re-seq, #"\p{L}+". Then (def words ...)

  ;; 4. How many words are there? How many different words?
  ;;    Hint: count, set

  ;; 5. The 20 most common words.
  ;;    Hint: frequencies, sort-by, take. A map entry works with key and val.
  ;;    Keep the frequencies for later: (def freqs ...)

  ;; 6. Those are mostly little words like "de" and "que". Leave out the
  ;;    stop-words defined above, and look again.
  ;;    Hint: remove. A set is a function.

  ;; 7. How often are the main characters named: carlos, ega, maria and
  ;;    affonso? Now try "afonso". Why the difference?
  ;;    Hint: select-keys, or call freqs as a function: (freqs "carlos")

  ;; 8. The ten longest different words.
  ;;    Hint: distinct, sort-by, count

  ;; 9. The average word length. What kind of number comes back?
  ;;    Hint: reduce, map count. Then try double.

  ;; 10. Split the book into its chapters, and count them. Each chapter
  ;;     starts with a Roman numeral alone on a line, like "IV".
  ;;     Hint: str/split with #"(?m)^[IVXLC]+$". (?m) makes ^ and $ match at
  ;;     the start and end of every line. What is the first piece?
  ;;     Then (def chapters ...)

  ;; 11. How many words are in each chapter?
  ;;     Hint: map over chapters, with re-seq and count from exercise 3

  ;; 12. Show the main characters as a bar chart in the View pane.
  ;;     Hint: bars is defined above. tap> sends a value to the View pane.
  ;;     Replace these made-up numbers with the real ones:
  (tap> (bars {"carlos" 3 "ega" 2 "maria" 1})))

;; ---------------------------------------------------------------------------
;; Stretch goals
;;
;; A. Who appears when? Chart how often "maria" is named in each chapter,
;;    with the chapter numbers as labels. Then try another character.
;;    Hint: map-indexed, or (map vector (range 1 19) ...)
;;
;; B. Read the book straight from Project Gutenberg with (slurp gutenberg-url).
;;    Cut off the licence text: the book is between a line starting with
;;    "*** START OF" and one starting with "*** END OF".
;;    Hint: str/split with a regex and a limit, or str/index-of and subs
;;
;; C. Another book by Eça de Queiroz. O Mandarim is short:
;;    "https://www.gutenberg.org/cache/epub/16384/pg16384.txt"
;;    Which book uses longer words? Which words does each use most?
;;
;; D. Words that come in pairs: the most common two-word phrases.
;;    Hint: (partition 2 1 words) gives each word with the next one

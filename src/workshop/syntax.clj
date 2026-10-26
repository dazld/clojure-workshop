(ns workshop.syntax
  (:require [clojure.string :as str]))

;; A tour of Clojure, evaluated one form at a time.
;;
;; Put your cursor anywhere in a form and press Ctrl+Enter (or Cmd+Enter).
;; The result appears next to it. Alt+Enter evaluates just the form at the
;; cursor: try it on the (+ 3 4) inside (* 2 (+ 3 4)) below.
;;
;; Nothing in a (comment ...) block runs when the file loads; it is a
;; scratchpad for the REPL.

(comment

  ;; ---------------------------------------------------------------------
  ;; 1. Everything is an expression, and calls are (function args...)

  (+ 1 2)
  (+ 1 2 3 4 5)
  (* 2 (+ 3 4))          ; nesting replaces operator precedence
  (str "Hello, " "world")
  (println "side effect")  ; prints, then returns nil
  (inc 41)
  (max 3 9 2)

  ;; ---------------------------------------------------------------------
  ;; 2. Scalars

  42                     ; long
  3.14                   ; double
  1/3                    ; ratio: exact, not a float
  "a string"
  \c                     ; character
  true false
  nil                    ; the absence of a value
  :keyword               ; a name that evaluates to itself
  :user/email            ; keywords can have a namespace
  'a-symbol              ; the quote stops it being looked up

  ;; ---------------------------------------------------------------------
  ;; 3. The four collection literals

  [1 2 3]                ; vector: indexed, appends at the end
  '(1 2 3)               ; list: the quote stops it being called as code
  {:name "Ada" :age 36}  ; map
  #{:red :green}         ; set

  ;; Commas are whitespace. Use them if they help.
  {:a 1, :b 2}

  ;; Any value can go in any collection.
  {:tags #{"lisp" "jvm"} :scores [9 7 10] :nested {:deep true}}

  ;; ---------------------------------------------------------------------
  ;; 4. Naming things

  (def answer 42)        ; a global name in this namespace
  answer

  (let [x 10             ; local names, only inside the let
        y (* x 2)]
    (+ x y))

  ;; ---------------------------------------------------------------------
  ;; 5. Functions

  (defn greet [name]
    (str "Hello, " name "!"))

  (greet "Ada")

  (defn area
    "Docstrings go before the arguments."
    [width height]
    (* width height))

  (area 3 4)

  ;; Anonymous functions
  (fn [x] (* x x))
  ((fn [x] (* x x)) 5)
  #(* % %)               ; shorthand, % is the argument
  (#(* % %) 5)

  ;; Functions are values: pass them around
  (map inc [1 2 3])
  (map greet ["Ada" "Grace"])

  ;; ---------------------------------------------------------------------
  ;; 6. Maps and keywords

  (def ada {:name "Ada" :born 1815 :langs #{"Analytical Engine"}})

  (get ada :name)
  (:name ada)            ; keywords are functions that look themselves up
  (:missing ada)         ; nil, not an error
  (:missing ada "default")
  (keys ada)
  (vals ada)

  (get-in {:a {:b {:c 1}}} [:a :b :c])

  ;; ---------------------------------------------------------------------
  ;; 7. Immutability: "changing" a value returns a new one

  (assoc ada :died 1852)
  ada                    ; unchanged
  (dissoc ada :langs)
  (update ada :born inc)
  (conj [1 2 3] 4)       ; result?
  (conj '(1 2 3) 4)      ; result? why?
  (conj #{:a} :b)

  ;; But def does change something: the name. Values never change; a name
  ;; (a var) can be pointed at a new value. That is how the REPL works.
  (def scores [1 2 3])
  (def saved scores)                ; holds the value [1 2 3]
  (defn total [] (reduce + scores)) ; looks the name up each time it runs
  (def scores [10 20])              ; point the name at a new value

  saved                             ; still [1 2 3]: the value did not change
  (total)                           ; 30: total sees what scores names now
  #'scores                          ; the var itself, the box the name refers to

  ;; ---------------------------------------------------------------------
  ;; 8. Truthiness and control flow

  ;; Only nil and false are falsey. 0, "" and [] are all truthy.
  (if 0 "truthy" "falsey")
  (if nil "truthy" "falsey")

  (when (> 3 2) "yes")   ; if without an else
  (cond
    (< answer 10)  "small"
    (< answer 100) "medium"
    :else          "large")

  ;; ---------------------------------------------------------------------
  ;; 9. Sequence functions: the core of everyday Clojure

  (def nums (range 1 11))
  nums

  (filter even? nums)
  (remove even? nums)
  (map #(* % %) nums)
  (reduce + nums)
  (take 3 nums)
  (sort-by - nums)
  (first nums)
  (rest nums)

  (frequencies ["a" "b" "a" "c" "a"])
  (group-by count ["hi" "hey" "yo" "hello"])

  ;; They work on every collection. Seen as a sequence, a map is [key value] pairs.
  (map key {:a 1 :b 2})

  ;; Laziness: map, filter and friends do nothing until something asks for
  ;; the result. This prints nothing:
  (def shouted (map #(do (println "shouting" %) (str/upper-case %)) ["a" "b" "c"]))

  ;; Asking for the first item runs the function on all three: sequences
  ;; over vectors and ranges are computed 32 items at a time (chunking).
  (first shouted)

  ;; So never use map for side effects. It may run late, partly, more than
  ;; you asked for, or never. For side effects, use doseq or run!:
  (doseq [w ["a" "b" "c"]]
    (println "shouting" w))
  (run! println ["a" "b" "c"])

  ;; And mapv when you want the results now, as a vector:
  (mapv str/upper-case ["a" "b" "c"])

  ;; ---------------------------------------------------------------------
  ;; 10. Threading macros: write pipelines top to bottom

  ;; Inside out, hard to read:
  (reduce + (map #(* % %) (filter even? nums)))

  ;; ->> puts each result in the LAST argument position of the next form:
  (->> nums
       (filter even?)
       (map #(* % %))
       (reduce +))

  ;; -> puts it in the FIRST position. Good for maps:
  (-> ada
      (assoc :died 1852)
      (update :born inc)
      (dissoc :langs))

  ;; A classic Clojure gotcha: functions take their collection in one of two
  ;; places, and the threading macro has to match.
  ;; - Sequence functions (map, filter, reduce, group-by) take it LAST: ->>
  ;; - Functions on one map or collection (assoc, update, get, update-vals)
  ;;   take it FIRST: ->
  ;;
  ;; Count words, then double each count. This fails: ->> puts the map last,
  ;; but update-vals wants it first. The error, "Don't know how to create
  ;; ISeq from" a function, is the clue that the arguments are swapped.
  (->> ["a" "b" "a"]
       frequencies
       (update-vals #(* 2 %)))

  ;; Fixed: build the map, then -> it into update-vals.
  (-> (frequencies ["a" "b" "a"])
      (update-vals #(* 2 %)))

  ;; ---------------------------------------------------------------------
  ;; 11. Destructuring: pull values apart in the binding

  (let [[a b & more] [1 2 3 4 5]]
    [a b more])

  (let [{:keys [name born]} ada]
    (str name " was born in " born))

  (defn describe [{:keys [name born]}]
    (str name " (" born ")"))

  (describe ada)

  ;; ---------------------------------------------------------------------
  ;; 12. Namespaces and the REPL helpers

  (str/upper-case "shout")  ; clojure.string, aliased as str in the ns form
  (str/split "a,b,c" #",")  ; #"..." is a regex literal

  (clojure.repl/doc map)
  (clojure.repl/apropos "split")

  ;; Errors are data too. Read the message, not the whole stack trace.
  (/ 1 0)
  (inc "one")

  ;; ---------------------------------------------------------------------
  ;; 13. Special forms: where it all comes down to

  ;; Why can't if be an ordinary function? Write one and see:
  (defn my-if [test then else]
    (if test then else))

  ;; Both lines print. A function's arguments are evaluated before it runs,
  ;; so both branches run. SICP (exercise 1.6) makes the same point.
  (my-if true (println "then ran") (println "else ran"))

  ;; So if is a special form: one of about a dozen built into the language.
  (special-symbol? 'if)
  (special-symbol? 'def)
  (special-symbol? 'let)          ; false: let is a macro over let*

  ;; Much of what looks like syntax is macros that come down to if.
  ;; macroexpand shows the code a macro turns into.
  (macroexpand '(when x (a) (b)))
  (macroexpand '(cond x 1 :else 2))
  (macroexpand '(and a b))
  (macroexpand '(defn f [x] x))

  ;; A macro gets its arguments as code, not yet evaluated, and returns new
  ;; code. ` builds code, ~ puts a value into it. So a macro decides what
  ;; runs, and here only one branch prints.
  (defmacro my-if-macro [test then else]
    `(cond ~test ~then :else ~else))

  (my-if-macro true (println "then ran") (println "else ran")))

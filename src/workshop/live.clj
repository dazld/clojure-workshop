(ns workshop.live)

;; A program that keeps running while you change it.
;;
;; A loop runs in the background, ten times a second: it moves the state on
;; one step, draws it, and sends the drawing to the View pane with tap>.
;; Start it, then change step or render and re-evaluate them (Ctrl+Enter).
;; The running program picks up the new definition on its next frame.
;; No restart, and the state carries on where it was.

(defonce state (atom {:tick 0}))

(defn step [s]
  (update s :tick inc))

(defn render [{:keys [tick]}]
  [:svg {:viewBox "0 0 400 200" :width 400 :height 200}
   (for [i (range 20)]
     (let [h (+ 100 (* 80 (Math/sin (/ (+ i tick) 4))))]
       [:rect {:x      (* i 20)
               :y      (- 200 h)
               :width  16
               :height h
               :fill   "#5b4fd6"}]))])

(defonce running? (atom false))

(defn start! []
  (when-not @running?
    (reset! running? true)
    (future
      (while @running?
        (try
          (swap! state step)
          (tap> (render @state))
          (catch Exception e
            ;; A broken render does not stop the loop. Fix it and re-evaluate.
            (println "Error:" (ex-message e))
            (Thread/sleep 1000)))
        (Thread/sleep 100)))
    :started))

(defn stop! []
  (reset! running? false)
  :stopped)

(comment

  (start!)

  ;; Things to change while it runs. Edit render or step above, then press
  ;; Ctrl+Enter with the cursor anywhere in the defn.
  ;;
  ;; - Change :fill to "tomato", or compute it from i:
  ;;     (str "hsl(" (* i 18) ", 70%, 55%)")
  ;; - (range 20) to (range 40), with :width 8 and (* i 10)
  ;; - Math/sin to Math/cos
  ;; - In step, (update s :tick inc) to (update s :tick + 3)
  ;; - Break render on purpose, e.g. (/ 1 0), then fix it

  ;; Look at the state, or change it, from the outside:
  @state
  (reset! state {:tick 0})
  (swap! state update :tick + 100)

  ;; Watch the state: the function runs on every change. This is the whole
  ;; Observer pattern. Ten lines a second appear in the Output pane.
  (add-watch state :log (fn [_key _atom old new]
                          (println "tick" (:tick old) "->" (:tick new))))
  (remove-watch state :log)

  (stop!)

  ;; Output from any thread goes to the Output pane:
  (future (dotimes [i 5] (println "tick" i) (Thread/sleep 500)))

  ;; Safety nets. Try these: the editor survives all of them.
  (range)                   ; infinite, but prints only the first 50
  (count (range))           ; never finishes: press Restart
  @(promise)                ; waits forever: gives up after 10 s
  (vec (range 1e9)))        ; runs out of memory: an error, nothing breaks


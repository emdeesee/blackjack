(ns backtype.blackjack.core
  (:refer-clojure :exclude [shuffle])
  (:use [clojure.contrib.shell-out :only (sh)]))

;; ## Blackjack Data Structures
;;
;; Not sure if this is the best way to proceed, but we'll use a global
;; variable here to represent the total number of decks used in the
;; game. This can be rebound, if necessary to the game.

(def *total-decks* 6)

(def suits
  #{:hearts :spades :clubs :diamonds})

(def ranks
  (merge (zipmap [:ace :two :three :four :five
                  :six :seven :eight :nine :ten]
                 (map inc (range)))
         {:jack 10 :queen 10 :king 10}))

;; `deck-gen` generates every card in the deck, representing a card as
;; a map with `:suit`, `:rank` and `:showing?`, to let us know if it's
;; face up. This is going to be helpful in calculating the percent
;; chance of winning a specific hand.
;;
;; Interesting to note that we can't actually use a set to represent a
;; hand, if we're going to have multiple decks.

(def deck-gen
  (for [suit suits
        rank (keys ranks)]
    {:suit suit :rank rank :showing? false}))

(defn shuffle
  "Shuffled the supplied (dereffed) decks together."
  [& decks]
  (clojure.core/shuffle (reduce into decks)))

(defn new-deck
  "Returns a new, shuffled deck. If n is supplied, returns `n` decks
   shuffled together."
  [n]
  {:pre [(pos? n)]}
  (->> deck-gen
       (repeat n)
       (apply shuffle)
       ref))

(defn new-hand
  "Generates a new, empty hand."
  [] (ref []))

(defn new-discard
  "Generates a new, empty hand."
  [] (ref []))

(defn new-game
  "Initializes a new game of Blackjack."
  [chips decks]
  {:deck (new-deck decks)
   :discard (ref [])
   :chips (atom chips)
   :player-hand (new-hand)
   :dealer-hand (new-hand)})

;; ## Game Play Mechanics

(defn add-cards
  "Adds the given item to a hand collection. Must be wrapped in a
  dosync transaction."
  [hand card-seq]
  (alter hand into card-seq))

;; TODO: deal with the fact that the deck might become empty. We can't
;; just add a new deck! But once the six decks are exhausted, we're
;; going to want to replace the deck. Do we want to do that in this
;; function? Not really sure about that.
;;
;; TODO: Perhaps we should check the count of the decks -- if we play
;; a hand that gets down below three decks, or maybe below one deck,
;; after the turn we shuffle the discards back into the deck. This has
;; to reset our card counting, of course.

(defn deal-cards
  "Deals a card from the supplied deck into the supplied hand. If the
  deck is empty, the deck will be refreshed before dealing a card out
  to the players."
  [deck hand count & {:keys [show?]}]
  (let [set-show (partial map #(assoc % :showing? (boolean show?)))]
    (dosync
     (if-let [f (take count @deck)]
       (do (ref-set deck (vec (drop count @deck)))
           (add-cards hand (set-show f)))
       (do (ref-set deck @(new-deck *total-decks*))
           (add-cards hand (set-show (take count @deck))))))))

(defn set-showing?
  [val hand]
  (dosync
   (ref-set hand
            (->> @hand
                 (map #(assoc % :showing? val))
                 vec))))

;; TODO: This needs some work, as a hit isn't this simple, of course.

(defn play-hit
  [deck hand]
  (deal-cards deck hand 1 :show? true))

(defn dump-hands
  "Dumps the contents of the hand into the given discard pile, and
  sets the hand back to its fresh, empty state."
  [discard & hands]
  (dosync 
   (doseq [hand hands]
     (alter discard into @hand)
     (ref-set hand @(new-hand)))))

;; Based on this, the way a game would play would be... initialize the
;; decks and the hands, and then on each turn, based on the player
;; input or the game logic, decide whether to hit or stay. If we hit,
;; we need to `deal-card` into the supplied hand, the calculate scores. 

;; ### Hand Scoring

(defn score-hand
  "Returns the two possible scores of any given deal. An ace can never
  count as 11 more than once, as this would cause an instant bust --
  we accept an optional argument that returns the highest possible
  value of rank, based on the presence of an ace."
  [hand]
  (let [rank-seq (map :rank @hand)
        score (reduce (fn [acc card]
                        (+ acc (card ranks)))
                      0
                      rank-seq)]
    (if (some #{:ace} rank-seq)
      [score (+ 10 score)]
      [score])))

(defn highest-scores
  [& hands]
  (map (comp last score-hand) hands))

(defn busted?
  [hand]
  (not (some #(<= % 21) (score-hand hand))))

(defn push?
  [hand1 hand2]
  (let [[s1 s2] (highest-scores hand1 hand2)]
    (= s1 s2)))

(defn beats?
  [hand1 hand2]
  (let [[s1 s2] (highest-scores hand1 hand2)]
    (and (not (busted? hand1))
         (> s1 s2))))

(defn over-16?
  [hand]
  (every? #(>= % 17) (score-hand hand)))

(defn blackjack?
  "Determines whether or not the given hand is a blackjack."
  [hand]
  (and (= 2 (count @hand))
       (some #{21} (score-hand hand))))

(defn report-outcome
  [dealer-hand player-hand]
  (cond (or (busted? dealer-hand)
            (beats? player-hand dealer-hand)) (println "Player wins.")
            (push? dealer-hand player-hand) (println "Push!")
            :else (println "Dealer wins.")))

;; ## Text Representations

(defn score-str
  "Returns a string representation of the score of the game."
  [hand]
  (let [[score-a score-b] (filter #(<= % 21)
                                  (score-hand hand))]
    (when score-a
      (apply str score-a (when score-b ["/" score-b])))))

(defn print-hand
  "Prints out a text representation of the supplied hand."
  [hand]
  (doseq [card @hand :let [{:keys [suit rank showing?]} card]]
    (println (if-not showing?
               "Hidden card."
               (format "%s of %s"
                       (name rank)
                       (name suit)))))
  (println))

(defn print-interface
  "TODO: Clean up the internal ref business."
  [dealer-hand player-hand]
  (println (sh "clear"))
  (println (str "Here's the dealer's hand "
                (if-let [s (score-str (ref (filter :showing? @dealer-hand)))]
                  (format "(with %s points showing):" s)
                  "(a bust!)")))
  (print-hand dealer-hand)
  (println (str "Here's your hand "
                (if-let [s (score-str player-hand)]
                  (format "(worth %s points):" s)
                  "(a bust!)")))
  (print-hand player-hand))

;; ## Game Loop Functions.

(defn prompt [message]
  (println message)
  (read-line))

(defn get-move []
  (prompt "What is your move? Your choices are hit, stay, or exit."))

;; TODO: Check the atom thing, for the chips.
;;
;; Interesting -- so, if the player, hits, you recur and do all of
;; this over again. If the user stays, then the computer goes into
;; playing mode and does its thing.

(defn initial-deal
  [game]
  (let [{:keys [deck dealer-hand player-hand]} game]
    (do (deal-cards deck player-hand 2 :show? true)
        (deal-cards deck dealer-hand 1 :show? true)
        (deal-cards deck dealer-hand 1 :show? false))))

(defn restart-hand
  "Dumps the hands into the discard, and gets everything going again."
  [game]
  (let [{:keys [discard dealer-hand player-hand]} game]
    (dump-hands discard dealer-hand player-hand)
    (initial-deal game)))

(defn end-turn
  [game]
  (let [{:keys [dealer-hand player-hand]} game]
    (print-interface dealer-hand player-hand)
    (report-outcome dealer-hand player-hand)
    (restart-hand game)
    (prompt "Please hit enter to play again.")))

(defn enact-dealer
  [game]
  (let [{:keys [deck dealer-hand player-hand]} game]
    (set-showing? true dealer-hand)
    (loop []
      (print-interface dealer-hand player-hand)
      (Thread/sleep 600)
      (if (over-16? dealer-hand)
        (end-turn game)
        (do (play-hit deck dealer-hand)
            (recur))))))

(defn enact-player
  [game]
  (let [{:keys [deck dealer-hand player-hand]} game]
    (loop []
      (print-interface dealer-hand player-hand)
      (let [move (get-move)]
        (case move
              "hit" (do (play-hit deck player-hand)
                        (cond (busted? player-hand) (end-turn game)
                              (blackjack? player-hand) (enact-dealer game)
                              :else (recur)))
              "stay" (enact-dealer game)
              "exit" :quit
              (prompt "Hmm, sorry, I didn't get that. Hit enter to continue."))))))

(defn start []
  (let [game (new-game 500 *total-decks*)]
    (initial-deal game)
    (loop []
      (if (= :quit (enact-player game))
        "Goodbye!"
        (recur)))))

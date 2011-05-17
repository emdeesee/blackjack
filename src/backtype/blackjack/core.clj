(ns backtype.blackjack.core
  (:refer-clojure :exclude [shuffle])
  (:use [clojure.java.shell :only (sh)])
  (:gen-class))

;; ## Blackjack Data Structures

(def suits #{:hearts :spades :clubs :diamonds})

(def ranks
  (merge (zipmap [:ace :two :three :four :five
                  :six :seven :eight :nine :ten]
                 (map inc (range)))
         {:jack 10 :queen 10 :king 10}))

(defn shuffle
  "Returns a vector containing each of the supplied decks, shuffled
  into some random order."
  [& decks]
  (clojure.core/shuffle (reduce into decks)))

(defn new-deck
  "Returns one deck containing `n` 52-card decks, shuffled into random
  order."
  [n]
  {:pre [(pos? n)]}
  (->> (for [suit suits, rank (keys ranks)]
         {:suit suit :rank rank :showing? false})
       (repeat n)
       (apply shuffle)))

(def empty-hand [])
(def empty-discard [])

(defn new-game
  "Returns a data representation of a new game of blackjack. The
  player will have access to the supplied number of chips, and will be
  able to place bets up to the supplied `bet-limit`. The supplied
  number of decks will be used -- BackType's Blackjack requires
  between 4 and 8 decks."
  [chips bet-limit decks]
  {:pre [(>= decks 4), (<= decks 8)
         (pos? chips), (pos? bet-limit)]}
  {:deck (new-deck decks)
   :discard empty-discard
   :player empty-hand
   :dealer empty-hand
   :chips chips
   :card-limit 52
   :bet-limit bet-limit
   :current-bet 0
   :turns 0})

(def outcome-map
  {:surrender {:message "Player surrendered."
               :payout (/ 1 2)}
   :blackjack {:message "Blackjack!"
               :payout (/ 5 2)}
   :push      {:message "Push!"
               :payout 1}
   :win       {:message "Player wins."
               :payout 2}
   :lose      {:message "Dealer wins."
               :payout 0}})

;; ## Game Verbs

(defn set-showing
  "For each card in `cards`, sets the values of `:showing?` to the
   truthy value of `bool`."
  [cards bool]
  (map #(assoc % :showing? (boolean bool))
       cards))

(defn show-hand
  "Returns a new game with all cards for the referenced player flipped
  face up. (Note that `player` must be either `:player` or
  `:dealer`)."
  [game player]
  {:pre [(some #{player} [:dealer :player])]}
  (assoc game
    player (set-showing (player game)
                        true)))

(defn deal-cards
  "Returns a new game generated by dealing `n` cards to the player
   referenced by `player`"
  [game n player & {:keys [show?]}]
  {:pre [(some #{player} [:dealer :player])
         (-> game :deck count (>= n))]}
  (let [[cards new-deck] (split-at n (:deck game))
        cards (set-showing cards show?)]
    (assoc game
      player (into (player game) cards)
      :deck new-deck)))

(defn dump-hands
  "Returns a new game generated by dumping the player's and dealer's
  hands into the discard pile. If the number of cards in the deck has
  fallen below the value of `:card-limit` for the supplied game, the
  discard pile will be shuffled back into the deck."
  [{:keys [deck discard dealer player card-limit] :as game}]
  (let [discard (reduce into discard [dealer player])
        [deck discard] (if (< (count deck) card-limit)
                         [(shuffle deck discard) empty-discard]
                         [deck discard])]
    (assoc game
      :deck deck
      :discard discard
      :dealer empty-hand
      :player empty-hand
      :turns 0)))

(defn make-bet
  "Returns a new game generated by placing a bet of the supplied
  amount for player."
  [{:keys [chips bet-limit] :as game} bet]
  {:post [(>= (:chips %) 0)
          (= bet (:current-bet %))
          (<= bet (:bet-limit %))]}
  (assoc game
    :chips (- chips bet)
    :current-bet bet))

(defn resolve-bet
  "Returns a new game generated by calculating the payoff for the
  supplied result based on the current bet, and adding that payoff
  back into the pool. If the game was a loss, all chips disappear, of
  course."
  [{:keys [chips current-bet] :as game} result]
  {:pre [(contains? outcome-map result)]}
  (let [pay (->> (get-in outcome-map [result :payout])
                (* current-bet)
                Math/floor)]
    (assoc game
      :chips (+ chips (int pay))
      :current-bet 0)))

(defn scale-bet
  "Returns a new game generated by scaling the current bet by the
   supplied scale factor. Bets can't be scaled by a factor that would
   cause the chips balance to drop below zero."
  [{:keys [current-bet chips] :as game} scale]
  {:pre [(>= (+ chips current-bet)
             (int (* scale current-bet)))]}
  (let [new-bet (int (* scale current-bet))]
    (assoc game
      :current-bet new-bet
      :chips (+ chips current-bet (- new-bet)))))

(defn play-hit
  "Returns a new game generated by dealing a single, face-up card to
  the supplied player."
  [game player]
  {:pre [(some #{player} [:dealer :player])]}
  (-> game
      (deal-cards 1 player :show? true)
      (assoc :turns (-> game :turns inc))))

;; ### Hand Predicates

(defn score-hand
  "Returns a vector of up to two possible scores for the supplied
  hand; if an ace exists in the hand and a value of 11 wouldn't cause
  a bust, two scores are returned. (An ace can never count as 11 more
  than once, as this would cause an instant bust)."
  [hand]
  (let [rank-seq (map :rank hand)
        score (reduce (fn [acc card]
                        (+ acc (card ranks)))
                      0
                      rank-seq)]
    (if (some #{:ace} rank-seq)
      [score (+ 10 score)]
      [score])))

(defn top-score
  "Returns the greatest legal score (21 or under) possible with the
  supplied hand."
  [hand]
  (last (filter #(<= % 21)
                (score-hand hand))))

(defn busted? [hand]
  (every? #(> % 21) (score-hand hand)))

(defn over-16? [hand]
  (every? #(>= % 17) (score-hand hand)))

(defn twenty-one? [hand]
  (some #{21} (score-hand hand)))

(defn push?
  "Returns true of the top scores of the two supplied hands match,
  false otherwise."
  [hand1 hand2]
  (= (top-score hand1)
     (top-score hand2)))

(defn beats?
  "Returns true if the `hand1` achieve a higher score than `hand2`
  without busting, false otherwise."
  [hand1 hand2]
  (and (not (busted? hand1))
       (> (top-score hand1)
          (top-score hand2))))

(defn broke?
  "Returns true of the supplied game contains no chips, between the
  pool and the current bet, and false otherwise."
  [{:keys [chips current-bet]}]
  (zero? (+ chips current-bet)))

(defn game-outcome
  "Returns a keyword representation of the outcome of the supplied
  game. The boolean `surrender?` indicates whether or not the supplied
  game was surrendered by the user."
  [{:keys [dealer player turns]} surrender?]
  (cond surrender? :surrender
        (busted? dealer) :win
        (push? dealer player) :push
        (beats? player dealer) (if (and (zero? turns)
                                        (twenty-one? player))
                                 :blackjack
                                 :win)
        :else :lose))

;; ## Text Representations

(defn score-str
  "Returns a string representation of every possible score of the
  supplied hand."
  [hand]
  (let [[s1 s2] (filter #(<= % 21) (score-hand hand))]
    (str s1 (when s2 "/") s2)))

(defn print-outcome
  "Writes the appropriate outcome message for the supplied key
  `outcome` the to the output stream."
  [game outcome]
  {:pre [(contains? outcome-map outcome)]}
  (println (get-in outcome-map [outcome :message]))
  game)

(defn print-hand
  "Writes a text representation of the supplied hand to the output
  stream. The value of cards with `:showing?` set to false will not be
  revealed."
  [hand]
  (doseq [card hand :let [{:keys [suit rank showing?]} card]]
    (println (if-not showing?
               "Hidden card."
               (format "%s of %s"
                       (name rank)
                       (name suit))))))

(defn print-hands
  "For the supplied game, prints a text representation of the player's
  and dealer's hands to the output stream, along with a header line
  and a report of the current showing points. Other than output side
  effects, acts as identity and returns game."
  [game]
  (let [{:keys [dealer player]} game]
    (when-not (empty? dealer)
      (doseq [[holder title] [[dealer "Dealer's"]
                              [player "Your"]]
              :let [points (score-str (filter :showing? holder))]]
        (println (str title " hand"
                      (if (= points "")
                        " (a bust!)"
                        (format ", showing %s points:" points))))
        (print-hand holder)
        (newline)))
    game))

(defn print-betline
  "Prints a text representation of the current number of remaining
  chips and the current bet to the output stream. Other than output
  side effects, acts as identity and returns game."
  [{:keys [chips current-bet] :as game}]
  (println (format "You have %d chips left. Your current bet is %d.\n"
                   chips current-bet))
  game)

(defn print-interface
  "Clears the terminal screen, and prints a text representation of the
  entire game interface to the output stream. Note that this function
  will only succeed if run from a unix terminal capable of accepting
  the `clear` command."
  [game]
  (-> "clear" sh :out println)
  (-> game print-hands print-betline))

;; ## Game Loop Functions.

(defn prompt [message]
  (println message)
  (read-line))

(defn get-move
  "Obtains a choice from among the supplied `choices` sequence by
  querying the current value of *in*."
  [choices]
  (let [prompt-str (format "What is your move? Your choices are %s, and %s."
                           (->> (butlast choices)
                                (map name)
                                (interpose ", ")
                                (apply str))
                           (name (last choices)))
        opt (keyword (prompt prompt-str))]
    (if-let [choice (some #{opt} choices)]
      choice
      (do (println "Hmm, sorry, I didn't get that. Let's try again.")
          (recur choices)))))

(defn get-bet
  "Obtains a valid bet (based on the value of `chips` and the supplied
  bet limit) by querying the current value of *in*."
  [chips bet-limit]
  (let [limit (if (< chips bet-limit) chips bet-limit)
        prompt-str (format "How many chips (up to %d) would you like to bet?"
                           limit)
        bet (try (Integer. (prompt prompt-str))
                 (catch Exception e :invalid))]
    (if-let [statement (cond (= :invalid bet) "Sorry, that input seems to be invalid."
                             (<= bet 0) "Only positive bets, please."
                             (> bet bet-limit) (str bet-limit " or fewer, please.")
                             (neg? (- chips bet)) "Not enough funding for that!")]
      (do (println statement)
          (recur chips bet-limit))
      bet)))

(defn special-options
  "Returns a sequence of non-standard blackjack options, based on the
  state of the supplied game.

  Different variations on the game allow different moves, such as
  doubling down, only on the first turn. All such special cases are
  covered within."
  [{:keys [turns chips current-bet bet-limit player]}]
  (let [first-turn? (zero? turns)
        funding? (and (>= chips current-bet)
                      (<= (* 2 current-bet) bet-limit))]
    (when first-turn?
      (if funding?
        [:surrender :double-down]
        [:surrender]))))

(defn move-choices
  "Returns a sequence of all possible moves, given the current state
  of the supplied game."
  [game]
  (reduce into [:hit :stay]
          [(special-options game)
           [:exit]]))

;; ### Gameplay

(defn initial-deal
  "Returns a new game generated by dealing 2 cards to the dealer and
  the player. As is standard, the dealer gets to keep one card face
  down."
  [game]
  (-> game
      (deal-cards 2 :player :show? true)
      (deal-cards 1 :dealer :show? true)
      (deal-cards 1 :dealer :show? false)))

(defn start-turn
  "Returns a new game generated by accepting a bet from the user, and
  setting up the initials state of the game."
  [game]
  (let [{:keys [chips bet-limit]} game]
    (-> game
        print-interface
        (make-bet (get-bet chips bet-limit))
        initial-deal)))

(defn end-turn
  "Returns a new game generated by resolving all bets, adjusting the
  chips pool accordingly, and waiting for the user's prompt to
  proceed."
  [game & {:keys [surrender?]}]
  (let [result (game-outcome game surrender?)
        ret-game (-> game
                     (show-hand :dealer)
                     (resolve-bet result)
                     (print-outcome result)
                     dump-hands)]
    (prompt "Please hit enter to proceed.")
    ret-game))

(defn dealer-turn
  "Game loop of the dealer. Plays out a dealer's hand, following
  blackjack's rules for a dealer forced to hit on a soft 17, and
  returns the resulting game."
  [game]
  (loop [{:keys [dealer player] :as game} (show-hand game :dealer)]
    (print-interface game)
    (Thread/sleep 600)
    (if (or (over-16? dealer)
            (busted? player))
      (end-turn game)
      (recur (play-hit game :dealer)))))

(defn player-turn
  "The player's game loop. With appropriate input from the user, plays
  out a hand until the user busts, reaches 21, or calls stay. After
  this, the game will be resolved by allowing the daeler to play out a
  hand or jumping directly to bet resolution, as appropriate for the
  turn in question. Returns the state of the game reached by playing
  out the current turn."
  [game]
  (if (-> game :player twenty-one?)
    (end-turn game)
    (loop [game game]
      (print-interface game)
      (case (-> game move-choices get-move)
            :exit :quit
            :stay (dealer-turn game)
            :surrender (end-turn game :surrender? true)
            :double-down (-> game
                             (scale-bet 2)
                             (play-hit :player)
                             dealer-turn)
            :hit (let [game (play-hit game :player)
                       player (:player game)]
                   (if (or (busted? player)
                           (twenty-one? player))
                     (dealer-turn game)
                     (recur game)))
            (recur game)))))

(defn game-loop
  "Full game loop, housing the internal player and dealer loops and
  allowing each to simulate state by continually recursing through
  each with a game of blackjack. Exits when the user quits or runs out
  of money."
  [& {:keys [chips bet-limit decks]
      :or {chips 500, bet-limit 100, decks 6}}]
  (loop [game (new-game chips bet-limit decks)]
    (cond (= game :quit) (println "Goodbye!")
          (broke? game)  (println "Sorry, you're out of chips!")
          :else          (recur (-> game start-turn player-turn)))))

(defn -main
  [& args]
  (apply game-loop (map read-string args)))

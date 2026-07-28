(ns kmet.test-keys
  (:require [clojure.test :as t]
            [kmet.tui.keys :as k]))

(t/deftest test-key-constants
  (t/is (= "up" k/KEY-UP))
  (t/is (= "down" k/KEY-DOWN))
  (t/is (= "left" k/KEY-LEFT))
  (t/is (= "right" k/KEY-RIGHT))
  (t/is (= "enter" k/KEY-ENTER))
  (t/is (= "escape" k/KEY-ESC))
  (t/is (= "tab" k/KEY-TAB))
  (t/is (= "backspace" k/KEY-BACKSPACE))
  (t/is (= "delete" k/KEY-DELETE))
  (t/is (= "home" k/KEY-HOME))
  (t/is (= "end" k/KEY-END))
  (t/is (= "space" k/KEY-SPACE)))

(t/deftest test-modifier-helpers
  (t/is (= "ctrl+c" (k/ctrl "c")))
  (t/is (= "shift+a" (k/shift "a")))
  (t/is (= "alt+left" (k/alt "left"))))

(t/deftest test-parse-key-ctrl
  (t/is (= "ctrl+a" (k/parse-key (str (char 1)))))
  (t/is (= "ctrl+z" (k/parse-key (str (char 26)))))
  (t/is (= "ctrl+c" (k/parse-key (str (char 3)))))
  (t/is (= "ctrl+u" (k/parse-key (str (char 21)))))
  (t/is (= "ctrl+k" (k/parse-key (str (char 11)))))
  (t/is (= "ctrl+-" (k/parse-key (str (char 31)))))
  (t/is (= "ctrl+\\" (k/parse-key (str (char 28))))))

(t/deftest test-parse-key-special
  (t/is (= "backspace" (k/parse-key "\u007f")))
  (t/is (= "backspace" (k/parse-key "\u0008")))
  (t/is (= "tab" (k/parse-key "\t")))
  (t/is (= "enter" (k/parse-key "\r")))
  (t/is (= "escape" (k/parse-key "\u001b"))))

(t/deftest test-parse-key-legacy
  (t/is (= "up" (k/parse-key "\u001b[A")))
  (t/is (= "down" (k/parse-key "\u001b[B")))
  (t/is (= "right" (k/parse-key "\u001b[C")))
  (t/is (= "left" (k/parse-key "\u001b[D")))
  (t/is (= "home" (k/parse-key "\u001b[H")))
  (t/is (= "end" (k/parse-key "\u001b[F")))
  (t/is (= "delete" (k/parse-key "\u001b[3~")))
  (t/is (= "pageUp" (k/parse-key "\u001b[5~"))))

(t/deftest test-parse-key-alt
  (t/is (= "alt+left" (k/parse-key "\u001bb")))
  (t/is (= "alt+right" (k/parse-key "\u001bf")))
  (t/is (= "alt+up" (k/parse-key "\u001bp"))))

(t/deftest test-parse-key-regular
  (t/is (= "a" (k/parse-key "a")))
  (t/is (= "Z" (k/parse-key "Z")))
  (t/is (= "3" (k/parse-key "3")))
  (t/is (= "." (k/parse-key ".")))
  (t/is (= " " (k/parse-key " "))))

(t/deftest test-matches-key
  (t/is (k/matches-key? "q" "q"))
  (t/is (k/matches-key? "\u001b[A" "up"))
  (t/is (k/matches-key? "\u007f" "backspace"))
  (t/is (k/matches-key? (str (char 3)) "ctrl+c"))
  (t/is (k/matches-key? (str (char 31)) "ctrl+-"))
  (t/is (k/matches-key? "\r" "enter"))
  (t/is (k/matches-key? "\u001bb" "alt+left"))
  (t/is (k/matches-key? "\u001bf" "alt+right"))
  (t/is (k/matches-key? "\u001b[H" "home"))
  (t/is (k/matches-key? "\u001b[3~" "delete"))
  (t/is (not (k/matches-key? "a" "b"))))

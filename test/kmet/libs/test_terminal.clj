(ns kmet.libs.test-terminal
  "Tests for the portable terminal lib: the incremental UTF-8 decoder a
   byte-oriented backend (Jolt's read(2)) needs, where a fixed-size read
   can split a multi-byte character at any byte offset."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.libs.terminal :as lib]))

(defn- decode [& bytes]
  (lib/utf8-decode bytes))

(deftest utf8-decode-ascii
  (t/is (= [[104 105 33] []] (decode 0x68 0x69 0x21)))
  (t/is (= [[0] []] (decode 0x00)) "NUL is a code point, not a terminator")
  (t/is (= [[] []] (decode))))

(deftest utf8-decode-multibyte
  (testing "2-, 3- and 4-byte sequences decode to their code points"
    (t/is (= [[233] []] (decode 0xC3 0xA9)))          ; é
    (t/is (= [[10003] []] (decode 0xE2 0x9C 0x93)))   ; ✓
    (t/is (= [[128075] []] (decode 0xF0 0x9F 0x91 0x8B))) ; 👋
    (t/is (= [[104 128075 120] []] (decode 0x68 0xF0 0x9F 0x91 0x8B 0x78)))))

(deftest utf8-decode-split-sequences
  (testing "an incomplete trailing sequence is returned as the tail"
    (t/is (= [[104] [0xF0 0x9F]] (decode 0x68 0xF0 0x9F)))
    (t/is (= [[] [0xC3]] (decode 0xC3)))
    (t/is (= [[] [0xE2 0x9C]] (decode 0xE2 0x9C))))
  (testing "the tail prepended to the next chunk completes the character"
    (let [[_ tail] (decode 0x68 0xF0 0x9F)]
      (t/is (= [[128075] []] (lib/utf8-decode (into tail [0x91 0x8B])))))))

(deftest utf8-decode-invalid-bytes
  (testing "invalid leads, stray continuations and overlong encodings are U+FFFD"
    (t/is (= [[0xFFFD] []] (decode 0xFF)))
    (t/is (= [[0xFFFD] []] (decode 0x80)))
    (t/is (= [[0xFFFD 0xFFFD] []] (decode 0xC0 0x80)))
    ;; ED A0 80 would be a surrogate: rejected per byte
    (t/is (= [[0xFFFD 0xFFFD 0xFFFD] []] (decode 0xED 0xA0 0x80)))
    ;; F4 90 80 80 is past U+10FFFF: rejected, then the continuation bytes
    (t/is (= [[0xFFFD 0xFFFD 0xFFFD 0xFFFD] []] (decode 0xF4 0x90 0x80 0x80)))
    ;; a bad continuation invalidates the lead only; the rest re-syncs
    (t/is (= [[0xFFFD 104] []] (decode 0xC3 0x68)))))

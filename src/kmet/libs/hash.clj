(ns kmet.libs.hash
  "Deterministic hashing helpers (pi: packages/ai/src/utils/hash.ts).")

(defn- imul
  "32-bit signed multiply (JS Math.imul — babashka has no builtin; the
   operands are masked to unsigned 32-bit and the product truncated)."
  [a b]
  (unchecked-int
   (bit-and (unchecked-multiply (bit-and a 0xFFFFFFFF)
                                (bit-and b 0xFFFFFFFF))
            0xFFFFFFFF)))

(defn- ushr32
  "Unsigned 32-bit shift right (JS >>> — the shift applies to the int32
   pattern, not the 64-bit long the value is held in)."
  [x n]
  (unsigned-bit-shift-right (bit-and x 0xFFFFFFFF) n))

(defn short-hash
  "Fast deterministic hash to shorten long strings (pi shortHash — used for
   Mistral's 9-char alphanumeric tool-call ids). Returns a base-36 string of
   the two 32-bit hashes (h2 then h1), matching pi's
   (h2 >>> 0).toString(36) + (h1 >>> 0).toString(36)."
  [s]
  (let [[h1 h2] (reduce (fn [[h1 h2] ch]
                          [(imul (bit-xor h1 ch) 2654435761)
                           (imul (bit-xor h2 ch) 1597334677)])
                        [0xDEADBEEF 0x41C6CE57]
                        (map int s))
        h1 (bit-xor (imul (bit-xor h1 (ushr32 h1 16)) 2246822507)
                    (imul (bit-xor h2 (ushr32 h2 13)) 3266489909))
        h2 (bit-xor (imul (bit-xor h2 (ushr32 h2 16)) 2246822507)
                    (imul (bit-xor h1 (ushr32 h1 13)) 3266489909))]
    (str (Long/toString (bit-and h2 0xFFFFFFFF) 36)
         (Long/toString (bit-and h1 0xFFFFFFFF) 36))))

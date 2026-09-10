(ns kmet.libs.num
  "Portable number predicates (pi: Number.isFinite — Double/isFinite is
   unshimmed on Jolt, while the isNaN/isInfinite statics are).")

(defn finite?
  "True when V is a finite number (portable replacement for
   Double/isFinite — composes the isNaN/isInfinite statics)."
  [v]
  (and (number? v)
       (let [d (double v)]
         (not (or (Double/isNaN d) (Double/isInfinite d))))))

#_{:clj-kondo/ignore [:redefined-var]}
(defn parse-long
  "Portable clojure.core/parse-long: bb/JVM returns nil on overflow while
   jolt returns a BigInt (M16), so callers branching on nil diverge.
   Returns the Long on in-range input, nil otherwise (non-numeric and
   out-of-range alike) on both hosts."
  [s]
  (let [n (try (clojure.core/parse-long s) (catch Exception _ nil))]
    (when (instance? Long n) n)))

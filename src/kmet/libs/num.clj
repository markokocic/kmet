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

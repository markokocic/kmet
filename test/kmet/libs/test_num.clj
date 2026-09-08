(ns kmet.libs.test-num
  "kmet.libs.num — finite? parity with Double/isFinite."
  (:require [clojure.test :as t :refer [deftest is]]
            [kmet.libs.num :as num]))

(def ^:private jolt?
  "True on the Jolt host (Double/isFinite is unshimmed there, so the
   parity block below only runs on bb/JVM)."
  (boolean (find-var 'clojure.core/*jolt-version*)))

(deftest test-finite?
  (is (true? (num/finite? 1)))
  (is (true? (num/finite? 1.5)))
  (is (true? (num/finite? 0)))
  (is (true? (num/finite? -3.25)))
  (is (true? (num/finite? 9e9)))
  (is (false? (num/finite? Double/NaN)) "NaN is not finite")
  (is (false? (num/finite? Double/POSITIVE_INFINITY)) "+Inf is not finite")
  (is (false? (num/finite? Double/NEGATIVE_INFINITY)) "-Inf is not finite")
  (is (false? (num/finite? nil)) "nil is not finite")
  (is (false? (num/finite? "1")) "strings are not finite")
  (when-not jolt?
    (t/testing "parity with Double/isFinite on bb"
      (doseq [v [0 1 -1 1.5 -3.25 9e9 Double/NaN
                 Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]]
        (is (= (Double/isFinite (double v)) (num/finite? v))
            (str "parity for " v))))))

(ns kmet.libs.diff
  "Line-oriented Myers O(ND) diff algorithm.
   Pure functions, no project dependencies. In a JVM Clojure project this
   would be io.github.java-diff-utils; kept in-house because Babashka can't
   run it."
  (:require [clojure.string :as str]))

(defn diff-region
  "Trim the common prefix/suffix from old/new line vectors.
   Returns {:start int :old-changed [...] :new-changed [...]} (0-indexed start)."
  [old-lines new-lines]
  (let [n (count old-lines) m (count new-lines)
        p (loop [i 0]
            (if (and (< i n) (< i m) (= (nth old-lines i) (nth new-lines i)))
              (recur (inc i)) i))
        s (loop [k 0]
            (if (and (>= (- n 1 k) p) (>= (- m 1 k) p)
                     (= (nth old-lines (- n 1 k)) (nth new-lines (- m 1 k))))
              (recur (inc k)) k))]
    {:start p
     :old-changed (subvec old-lines p (- n s))
     :new-changed (subvec new-lines p (- m s))}))

(defn- myers-ops
  "Myers O(ND) line diff of vectors a and b (pi: the diff package's diffLines).
   Returns a vector of {:type :eq|:del|:add :line str} ops in order."
  [a b]
  (let [n (count a) m (count b)
        maxd (+ n m)
        offset maxd
        v (int-array (inc (* 2 maxd)) 0)
        trace (atom [])]
    (aset v (inc offset) 0)
    (loop [d 0]
      (swap! trace conj (aclone v))
      (let [done (atom nil)]
        (loop [k (- d)]
          (if (> k d)
            nil
            (do
              (let [ki (+ offset k)
                    down? (or (= k (- d))
                              (and (not= k d)
                                   (< (aget v (dec ki)) (aget v (inc ki)))))
                    x (if down? (aget v (inc ki)) (inc (aget v (dec ki))))
                    x (loop [x x y (- x k)]
                         (if (and (< x n) (< y m) (= (nth a x) (nth b y)))
                           (recur (inc x) (inc y))
                           x))
                    y (- x k)]
                (aset v ki x)
                (when (and (>= x n) (>= y m))
                  (reset! done [d x y])))
              (when-not @done
                (recur (+ k 2))))))
        (if-let [[d x y] @done]
          (let [{:keys [ops x y]}
                (loop [depth d x x y y acc (transient [])]
                  (if (zero? depth)
                    {:ops acc :x x :y y}
                    (let [v (nth @trace depth)
                          k (- x y)
                          down? (or (= k (- depth))
                                    (and (not= k depth)
                                         (< (aget v (dec (+ offset k)))
                                            (aget v (inc (+ offset k))))))
                          prev-k (if down? (inc k) (dec k))
                          prev-x (aget v (+ offset prev-k))
                          prev-y (- prev-x prev-k)
                          [x y acc] (loop [x x y y acc acc]
                                      (if (and (> x prev-x) (> y prev-y))
                                        (recur (dec x) (dec y)
                                               (conj! acc {:type :eq :line (nth a (dec x))}))
                                        [x y acc]))]
                      (if (= x prev-x)
                        (recur (dec depth) x (dec y)
                               (conj! acc {:type :add :line (nth b (dec y))}))
                        (recur (dec depth) (dec x) y
                               (conj! acc {:type :del :line (nth a (dec x))}))))))
                ops (loop [x x y y acc ops]
                      (if (and (pos? x) (pos? y))
                        (recur (dec x) (dec y)
                               (conj! acc {:type :eq :line (nth a (dec x))}))
                        acc))]
            (vec (reverse (persistent! ops))))
          (recur (inc d)))))))

(defn- group-ops
  "Group consecutive ops of the same type into parts
   {:type :eq|:del|:add :lines [str]}."
  [ops]
  (reduce (fn [acc op]
            (if-let [last-part (peek acc)]
              (if (= (:type last-part) (:type op))
                (conj (pop acc) (update last-part :lines conj (:line op)))
                (conj acc {:type (:type op) :lines [(:line op)]}))
              (conj acc {:type (:type op) :lines [(:line op)]})))
          []
          ops))

(defn line-diff
  "Diff two line vectors, returning grouped parts
   {:type :eq|:del|:add :lines [str]} in order.
   The common prefix/suffix is trimmed before the Myers pass so unchanged
   lines at the edges collapse into single :eq parts."
  [old-lines new-lines]
  (let [{:keys [start old-changed new-changed]} (diff-region old-lines new-lines)
        prefix-eq (mapv #(hash-map :type :eq :line %) (subvec old-lines 0 start))
        suffix-eq (mapv #(hash-map :type :eq :line %) (subvec old-lines (+ start (count old-changed))))]
    (group-ops (concat prefix-eq (myers-ops old-changed new-changed) suffix-eq))))

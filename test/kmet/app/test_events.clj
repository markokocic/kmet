(ns kmet.app.test-events
  (:require [clojure.test :as t]
            [kmet.app.events :as events]))

(t/deftest test-events-vocabulary
  (t/is (map? events/event-types))
  (t/is (pos? (count events/event-types)))
  (t/is (every? keyword? (keys events/event-types)))
  (t/is (every? string? (vals events/event-types))))

(t/deftest test-events-core-types-known
  (doseq [t [:agent-start :agent-end :turn-start :turn-end
             :message-start :message-update :message-end
             :tool-execution-start :tool-execution-update :tool-execution-end
             :status :error :user-bash]]
    (t/is (events/known-event-type? t) (str t " should be a known event type"))))

(t/deftest test-events-unknown-types
  (t/is (not (events/known-event-type? :bogus)))
  (t/is (not (events/known-event-type? nil)))
  (t/is (not (events/known-event-type? "agent-start"))))

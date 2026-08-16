(ns kmet.ai.test-proxy
  "Tests for the thin kmet.ai.proxy layer (post-stream + hooks).
   Proxy parsing, selection, curl transport, and request-json live in
   kmet.libs.proxy — tested in kmet.libs.test-proxy."
  (:require [clojure.test :as t]
            [kmet.ai.proxy :as proxy]))

(t/deftest test-ai-proxy-re-exports-libs
  (t/is (fn? proxy/proxy-for-url))
  (t/is (fn? proxy/curl-post))
  (t/is (fn? proxy/finish-curl!))
  (t/is (fn? proxy/request-json)))

(t/deftest test-post-stream-is-defined
  (t/is (fn? proxy/post-stream)))

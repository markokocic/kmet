(ns kmet.ai.http
  "Provider HTTP decorator over the single outbound-HTTP boundary
   (kmet.libs.http). Applies the provider-event hooks (pi:
   before_provider_headers / after_provider_response — the slots live in
   kmet.ai.hooks) around the transport call; contains no networking itself.

   Provider implementations call ai-http/request (stream responses finished
   with ai-http/close! / ai-http/abort!); OAuth, model generation, and
   other ordinary calls use kmet.libs.http directly."
  (:require [kmet.ai.hooks :as hooks]
            [kmet.libs.http :as http]))

(defn request
  "One provider HTTP request: the final :headers map runs through the
   before-provider-headers hook right before the HTTP call; after the
   response arrives, the after-provider-response hook fires with
   {:status n :headers {...}}. Delegates the transport to
   kmet.libs.http/request — see its docstring for OPTS (:method defaults
   to :post, the provider streams' convention; :signal is accepted for
   the curl transport's mid-stream cancellation)."
  [url opts signal]
  (let [opts (update opts :headers hooks/apply-before-provider-headers-hook)
        response (http/request (assoc opts :url url
                                      :method (or (:method opts) :post)
                                      :signal signal))]
    (hooks/apply-after-provider-response-hook (:status response)
                                              (:headers response))
    response))

(def close! http/close!)
(def abort! http/abort!)

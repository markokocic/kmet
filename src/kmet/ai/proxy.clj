(ns kmet.ai.proxy
  "Thin layer over kmet.libs.proxy that adds provider-event hooks.
   post-stream is the only function here — everything else (proxy selection,
   curl transport, java-client, request-json) lives in kmet.libs.proxy."
  (:require [babashka.http-client :as http]
            [kmet.ai.hooks :as hooks]
            [kmet.libs.proxy :as lib]))

(def proxy-for-url lib/proxy-for-url)
(def curl-proxy? lib/curl-proxy?)
(def java-client lib/java-client)
(def curl-post lib/curl-post)
(def finish-curl! lib/finish-curl!)
(def abort-stream! lib/abort-stream!)
(def watch-cancel! lib/watch-cancel!)
(def request-json lib/request-json)

(defn post-stream
  "POST url with babashka.http-client opts, routing through the proxy selected
   from env vars (proxy-for-url). Returns a map with :body as an input stream,
   plus :proc/:pid for curl-backed (SOCKS) responses — call finish-curl! after
   the stream is read. signal — cancel atom: for curl-backed requests it kills
   the process tree when set mid-stream.

   Provider-event hooks (pi: before_provider_headers /
   after_provider_response): the final :headers map runs through the
   before-provider-headers hook right before the HTTP call; after the
   response arrives, the after-provider-response hook fires with
   {:status n :headers {...}} (only when the transport exposes them — the
   curl-backed path reports no status/headers)."
  [url opts signal]
  (let [opts (update opts :headers hooks/apply-before-provider-headers-hook)
        response (if-let [p (lib/proxy-for-url url)]
                   (if (lib/curl-proxy? p)
                     (lib/curl-post url opts p signal)
                     (http/post url (assoc opts :client (lib/java-client p))))
                   (http/post url opts))]
    (when (and (map? response) (contains? response :status))
      (hooks/apply-after-provider-response-hook (:status response)
                                                (:headers response)))
    response))

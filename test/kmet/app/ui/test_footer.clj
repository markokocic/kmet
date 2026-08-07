(ns kmet.app.ui.test-footer
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [kmet.tui.core :as core]
            [kmet.app.session :as s]
            [kmet.app.ui.footer :as ft]
            [kmet.app.ui.footer-data-provider :as fdp]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [c width]
  (mapv strip-ansi (core/render c width)))

(defn- make-footer-with-session [& {:keys [session cwd context-window provider-count model provider thinking auto-compact]}]
  (let [p (fdp/make-footer-data-provider
           :session session
           :cwd (or cwd "/home/user/project")
           :provider-count (or provider-count 1)
           :context-window context-window
           :model (or model "gpt-4o")
           :provider (or provider :openai)
           :thinking thinking)]
    (ft/make-footer :provider p :auto-compact auto-compact)))

(deftest test-create
  (testing "create footer component"
    (is (some? (ft/make-footer)))))

(deftest test-cwd-line
  (testing "first line shows the cwd"
    (let [c (make-footer-with-session :cwd "/some/project")
          plain (render-plain c 50)]
      (is (some #(re-find #"/some/project" %) plain)
          "footer should show the cwd")
      (is (= 2 (count plain))
          "two content lines, no separator"))))

(deftest test-home-substitution
  (testing "cwd inside home renders as ~"
    (let [home (System/getProperty "user.home")
          c (make-footer-with-session :cwd (str home "/project"))
          plain (render-plain c 50)]
      (is (some #(re-find #"^~/project" %) plain)
          "cwd under HOME renders home-substituted"))))

(deftest test-git-branch
  (testing "git branch renders after the cwd when resolved"
    ;; In a non-git test environment the branch resolves to nil — the footer
    ;; must still render. The branch suffix is covered by the provider test.
    (let [c (make-footer-with-session)
          plain (render-plain c 50)]
      (is (= 2 (count plain))))))

(deftest test-model-right-aligned
  (testing "model renders on the right side of the stats line"
    (let [c (make-footer-with-session :model "gpt-4o" :provider-count 1)
          plain (render-plain c 60)]
      (is (some #(re-find #"gpt-4o" %) plain)))))

(deftest test-provider-prefix-when-multiple
  (testing "(provider) prefix only when more than one provider is configured"
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai :provider-count 1)
          plain (render-plain c 60)]
      (is (not-any? #(re-find #"\(openai\)" %) plain)))
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai :provider-count 3)
          plain (render-plain c 60)]
      (is (some #(re-find #"\(openai\) gpt-4o" %) plain)))))

(deftest test-thinking-level
  (testing "thinking level renders after the model when not off"
    (let [c (make-footer-with-session :model "claude" :thinking :high)
          plain (render-plain c 60)]
      (is (some #(re-find #"claude • thinking high" %) plain)))
    (let [c (make-footer-with-session :model "claude" :thinking :off)
          plain (render-plain c 60)]
      (is (not-any? #(re-find #"thinking" %) plain)))))

(deftest test-context-percent
  (testing "context percent renders with the window and auto indicator"
    (let [c (make-footer-with-session :context-window 200000 :auto-compact true)
          plain (render-plain c 60)]
      (is (some #(re-find #"200k" %) plain))
      (is (some #(re-find #"\(auto\)" %) plain)))))

(deftest test-extension-statuses
  (testing "keyed extension statuses render dim on a third line, sorted by key"
    (let [c (make-footer-with-session)]
      (ft/footer-set-extension-status! c "ext-b" "✓ ready")
      (ft/footer-set-extension-status! c "ext-a" "● active")
      (let [plain (render-plain c 60)]
        (is (= 3 (count plain)))
        (is (some #(re-find #"● active" %) plain))
        (is (some #(re-find #"✓ ready" %) plain))))
    (testing "nil text clears the key"
      (let [c (make-footer-with-session)]
        (ft/footer-set-extension-status! c "ext-a" "● active")
        (ft/footer-set-extension-status! c "ext-a" nil)
        (let [plain (render-plain c 60)]
          (is (= 2 (count plain)))
          (is (not-any? #(re-find #"● active" %) plain)))))))

(deftest test-wide-footer
  (testing "footer handles wide terminal"
    (let [c (make-footer-with-session :model "gpt-4o" :provider :openai :provider-count 3)
          lines (core/render c 120)]
      (is (pos? (count lines))))))

(deftest test-narrow-footer
  (testing "footer truncates instead of overflowing on narrow terminals"
    (let [c (make-footer-with-session :model "some-very-long-model-name-here" :provider-count 3)
          plain (render-plain c 20)]
      (is (every? #(<= (count %) 20) plain)
          "every rendered line fits the width"))))

(deftest test-session-name
  (testing "session display name renders after the cwd (pi: pwd • name)"
    (let [dir (str "target/test-footer-name-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (s/append-session-info! sess "my project")
        (let [c (make-footer-with-session :session sess :cwd "/srv/workspace")
              plain (render-plain c 60)]
          ;; a git branch may resolve in the test env — allow an optional suffix
          (is (some #(re-find #"/srv/workspace( \([^)]*\))? • my project" %) plain)))
        (finally (fs/delete-tree dir)))))
  (testing "no name renders the plain cwd line"
    (let [dir (str "target/test-footer-name-" (System/currentTimeMillis))
          sess (s/create-session dir)]
      (try
        (let [c (make-footer-with-session :session sess :cwd "/srv/workspace")
              plain (render-plain c 60)]
          (is (some #(re-find #"/srv/workspace( \([^)]*\))?$" %) plain)))
        (finally (fs/delete-tree dir))))))

(deftest test-format-tokens
  (testing "pi formatTokens scaling"
    (is (= "999" (ft/format-tokens 999)))
    (is (= "1.2k" (ft/format-tokens 1234)))
    (is (= "12k" (ft/format-tokens 12345)))
    (is (= "1.2M" (ft/format-tokens 1234567)))
    (is (= "12M" (ft/format-tokens 12345678)))))

(deftest test-format-cwd
  (testing "pi formatCwdForFooter home substitution"
    (is (= "~" (ft/format-cwd-for-footer "/home/user" "/home/user")))
    (is (= "~/project" (ft/format-cwd-for-footer "/home/user/project" "/home/user")))
    (is (= "/opt/other" (ft/format-cwd-for-footer "/opt/other" "/home/user")))))

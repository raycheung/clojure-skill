(ns clojure-skill.nrepl-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure-skill.nrepl :as nrepl])
  (:import [java.net ServerSocket Socket]))

(def ^:dynamic *port* nil)
(def ^:dynamic *dir* nil)

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- await-port [port]
  (loop [tries 50]
    (when-not (try (.close (Socket. "localhost" (int port))) true (catch Exception _ false))
      (if (pos? tries)
        (do (Thread/sleep 200) (recur (dec tries)))
        (throw (ex-info "nREPL server did not start" {:port port}))))))

(use-fixtures :once
  (fn [run]
    (let [port (free-port)
          dir (str (fs/canonicalize (fs/create-temp-dir)))
          server (process/process {:dir dir :out :string :err :string} "bb" "nrepl-server" (str port))]
      (try
        (await-port port)
        (binding [*port* port *dir* dir] (run))
        (finally (process/destroy-tree server))))))

(defn- run-eval [code & {:as opts}]
  (let [err (java.io.StringWriter.)
        exit (volatile! nil)
        out (with-out-str
              (binding [*err* err]
                (vreset! exit (nrepl/evaluate (merge {:port *port* :timeout 10000} opts) code))))]
    {:out out :err (str err) :exit @exit}))

(deftest parses-arguments
  (is (= {:timeout 60000 :port 7888 :code "(+ 1 2)"} (nrepl/parse-args ["-p" "7888" "(+ 1 2)"])))
  (is (= {:timeout 500 :new-session true} (nrepl/parse-args ["--new-session" "--timeout" "500"])))
  (is (:list (nrepl/parse-args ["--ports"])))
  (is (= "x.clj" (:file (nrepl/parse-args ["-f" "x.clj"])))))

(deftest prints-output-and-values
  (let [{:keys [out exit]} (run-eval "(println \"hi\") (+ 1 2)")]
    (is (= 0 exit))
    (is (re-find #"hi\r?\n" out))
    (is (str/includes? out "=> 3"))))

(deftest the-session-keeps-the-current-namespace-until-a-new-one
  (run-eval "(ns kept-ns)")
  (is (str/includes? (:out (run-eval "(str *ns*)")) "\"kept-ns\""))
  (is (str/includes? (:out (run-eval "(str *ns*)" :new-session true)) "\"user\"")))

(deftest an-exception-exits-non-zero
  (let [{:keys [exit err]} (run-eval "(throw (ex-info \"boom\" {}))")]
    (is (= 1 exit))
    (is (str/includes? err "boom"))))

(deftest a-timeout-exits-with-2
  (let [{:keys [exit err]} (run-eval "(Thread/sleep 3000)" :timeout 300)]
    (is (= 2 exit))
    (is (str/includes? err "Timed out after 300 ms"))))

(deftest resolves-the-port
  (testing "an explicit port wins"
    (is (= {:port 1} (nrepl/resolve-port 1 *dir*))))
  (testing ".nrepl-port in an ancestor directory"
    (let [root (fs/create-temp-dir)
          nested (fs/create-dirs (fs/path root "src" "a"))]
      (spit (str (fs/path root ".nrepl-port")) "4567\n")
      (is (= {:port 4567} (nrepl/resolve-port nil nested)))))
  (testing "the one discovered server running from cwd or above"
    (let [nested (fs/create-dirs (fs/path *dir* "sub"))]
      (is (= {:port *port*} (nrepl/resolve-port nil nested))))))

(deftest discovers-running-servers
  (is (some #{{:port *port* :dir *dir*}} (nrepl/servers))))

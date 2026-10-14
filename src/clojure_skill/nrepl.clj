(ns clojure-skill.nrepl
  "Evaluates code in a running nREPL server, keeping one session per port."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [bencode.core :as bencode]
            [clojure.string :as str])
  (:import [java.io PushbackInputStream]
           [java.net InetSocketAddress Socket SocketTimeoutException]))

(def usage
  "Usage: nrepl [-p PORT] [--timeout MS] [--new-session] [-f FILE | CODE]   (code from stdin when omitted)
       nrepl --ports")

(defn- decode [x]
  (cond (bytes? x) (String. ^bytes x "UTF-8")
        (map? x) (update-keys (update-vals x decode) keyword)
        (sequential? x) (mapv decode x)
        :else x))

(defn connect [port timeout-ms]
  (let [socket (doto (Socket.)
                 (.connect (InetSocketAddress. "localhost" (int port)) 1000)
                 (.setSoTimeout (int timeout-ms)))]
    {:socket socket
     :in (PushbackInputStream. (.getInputStream socket))
     :out (.getOutputStream socket)}))

(defn request
  "Sends msg and calls on-reply with each reply to it until one reports done.
  Returns the replies."
  ([conn msg] (request conn msg identity))
  ([{:keys [in out]} msg on-reply]
   (let [id (or (get msg "id") (str (random-uuid)))]
     (bencode/write-bencode out (assoc msg "id" id))
     (.flush out)
     (loop [replies []]
       (let [reply (decode (bencode/read-bencode in))]
         (if (= id (:id reply))
           (let [replies (conj replies (doto reply on-reply))]
             (if (some #{"done"} (:status reply)) replies (recur replies)))
           (recur replies)))))))

(defn- session-file [port]
  (fs/path (fs/temp-dir) "clojure-skill-nrepl" (str port)))

(defn session
  "The stored session for port if the server still has it, else a new one."
  [conn port new?]
  (let [file (session-file port)
        stored (when (and (not new?) (fs/exists? file)) (str/trim (slurp (str file))))
        live (set (mapcat :sessions (request conn {"op" "ls-sessions"})))]
    (if (live stored)
      stored
      (let [id (some :new-session (request conn {"op" "clone"}))]
        (fs/create-dirs (fs/parent file))
        (spit (str file) id)
        id))))

(def ^:private windows-listeners-command
  ;; Single quotes only: ProcessBuilder strips literal " from a Windows command line.
  ["powershell" "-NoProfile" "-Command"
   (str "Get-NetTCPConnection -State Listen | Where-Object { "
        "(Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName "
        "-match 'java|bb|babashka|clojure' } | ForEach-Object LocalPort")])

(defn- listening-ports []
  (->> (if (fs/windows?)
         (re-seq #"(?m)^(\d+)\s*$" (:out (process/sh windows-listeners-command)))
         (re-seq #":(\d+) \(LISTEN\)"
                 (:out (process/sh "sh" "-c" "lsof -nP -iTCP -sTCP:LISTEN | grep -Ei 'java|bb|babashka|clojure'"))))
       (map (comp parse-long second))
       distinct
       sort))

(defn- server-dir [port]
  (try
    (let [conn (connect port 2000)]
      (try
        (some-> (some :value (request conn {"op" "eval" "code" "(System/getProperty \"user.dir\")"}))
                read-string)
        (finally (.close ^Socket (:socket conn)))))
    (catch Exception _ nil)))

(defn servers
  "Listening nREPL servers as {:port :dir}, dir being the server's working directory."
  []
  (keep (fn [port] (some->> (server-dir port) (hash-map :port port :dir))) (listening-ports)))

(defn- nrepl-port-file [dir]
  (->> (iterate fs/parent (fs/absolutize dir))
       (take-while some?)
       (map #(fs/path % ".nrepl-port"))
       (some #(when (fs/exists? %) (parse-long (str/trim (slurp (str %))))))))

(defn- within? [dir cwd]
  (str/starts-with? (str (fs/normalize (fs/absolutize cwd)))
                    (str (fs/normalize (fs/absolutize dir)))))

(defn resolve-port
  "Port to use: given, else .nrepl-port in cwd or above, else the one server
  running from cwd or above. Returns {:port} or {:error}."
  [port cwd]
  (or (some->> (or port (nrepl-port-file cwd)) (hash-map :port))
      (let [found (servers)
            here (filter #(within? (:dir %) cwd) found)]
        (if (= 1 (count here))
          {:port (:port (first here))}
          {:error (str (if (seq here) "Several" "No") " nREPL servers run from " cwd
                       "; pass -p. Running:"
                       (if (seq found)
                         (str/join (map #(format "\n  %d  %s" (:port %) (:dir %)) found))
                         " none"))}))))

(defn- print-reply [{:keys [out err value ns]}]
  (when out (print out) (flush))
  (when err (binding [*out* *err*] (print err) (flush)))
  (when value (println (str (when ns (str ns "=> ")) value))))

(defn evaluate
  "Evaluates code, printing output as it arrives. Returns the exit code."
  [{:keys [port timeout new-session]} code]
  (let [conn (connect port timeout)
        sid (session conn port new-session)
        id (str (random-uuid))]
    (try
      (let [replies (request conn {"op" "eval" "code" code "session" sid "id" id} print-reply)]
        (if (some #(some #{"eval-error"} (:status %)) replies) 1 0))
      (catch SocketTimeoutException _
        (try
          (request (connect port 5000) {"op" "interrupt" "session" sid "interrupt-id" id})
          (catch Exception _ nil))
        (binding [*out* *err*] (println (str "Timed out after " timeout " ms; evaluation interrupted.")))
        2)
      (finally (.close ^Socket (:socket conn))))))

(defn parse-args [args]
  (loop [opts {:timeout 60000} [a b & more :as args] args]
    (case a
      nil opts
      ("-p" "--port") (recur (assoc opts :port (parse-long b)) more)
      "--timeout" (recur (assoc opts :timeout (parse-long b)) more)
      ("-f" "--file") (recur (assoc opts :file b) more)
      "--new-session" (recur (assoc opts :new-session true) (rest args))
      "--ports" (recur (assoc opts :list true) (rest args))
      ("-h" "--help") (assoc opts :help true)
      (assoc opts :code (str/join " " args)))))

(defn -main [& args]
  (let [{:keys [help list code file] :as opts} (parse-args args)]
    (System/exit
     (cond
       help (do (println usage) 0)
       list (do (doseq [{:keys [port dir]} (servers)] (println (format "%d  %s" port dir))) 0)
       :else (let [{:keys [port error]} (resolve-port (:port opts) (fs/cwd))]
               (if error
                 (do (binding [*out* *err*] (println error)) 2)
                 (evaluate (assoc opts :port port) (or code (some-> file slurp) (slurp *in*)))))))))

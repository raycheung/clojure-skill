(ns clojure-skill.guard
  "PostToolUse hook for Edit/Write: repairs unbalanced delimiters from
  indentation, then reports clj-kondo errors for the edited file."
  (:require [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def max-changed-lines 8)

(def project-markers ["deps.edn" "bb.edn" "project.clj" "shadow-cljs.edn"])

(defn clojure-file? [path]
  (boolean (re-find #"\.(clj[scd]?|edn|bb)$" (str path))))

(defn delimiter-error
  "Edamame's error data when text has unbalanced delimiters, else nil."
  [text]
  (try
    (edamame/parse-string-all text {:all true
                                    :read-cond :allow
                                    :features #{:bb :clj :cljs :cljd :cljr :default}
                                    :readers (constantly identity)
                                    :auto-resolve name})
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (when (contains? data :edamame/opened-delimiter) data)))))

(defn- infer-closers [text]
  (deps/add-deps '{:deps {parinferish/parinferish {:mvn/version "0.8.0"}}})
  (require 'parinferish.core)
  ((resolve 'parinferish.core/flatten)
   ((resolve 'parinferish.core/parse) text {:mode :indent})))

(defn- changed-lines [before after]
  (->> (map vector (range 1 ##Inf) (str/split-lines before) (str/split-lines after))
       (remove (fn [[_ a b]] (= a b)))
       (mapv (fn [[n _ b]] [n b]))))

(defn repair
  "nil when text is balanced; {:text :changed} when indentation settles it;
  {:unfixable error-data} otherwise."
  [text]
  (when-let [error (delimiter-error text)]
    (let [crlf? (str/includes? text "\r\n")
          lf-text (str/replace text "\r\n" "\n")
          repaired (try (infer-closers lf-text) (catch Exception _ nil))
          changed (some->> repaired (changed-lines lf-text))]
      (if (and repaired
               (not (delimiter-error repaired))
               (<= 1 (count changed) max-changed-lines))
        {:text (cond-> repaired crlf? (str/replace "\n" "\r\n"))
         :changed changed}
        {:unfixable error}))))

(defn- project-root [file]
  (or (->> (iterate fs/parent (fs/parent (fs/absolutize file)))
           (take-while some?)
           (some (fn [dir] (when (some #(fs/exists? (fs/path dir %)) project-markers) dir))))
      (fs/parent (fs/absolutize file))))

(defn kondo-errors [file]
  (when (fs/which "clj-kondo")
    (let [{:keys [out]} (process/sh {:dir (str (project-root file))}
                                    "clj-kondo" "--lint" (str (fs/absolutize file))
                                    "--config" "{:output {:format :edn}}")]
      (->> (some-> out not-empty edn/read-string :findings)
           (filter #(= :error (:level %)))))))

(defn- describe-location [{:keys [row col] :edamame/keys [opened-delimiter opened-delimiter-loc]}]
  (if (str/blank? opened-delimiter)
    (format "unmatched closing delimiter at %s:%s" row col)
    (format "%s opened at %s:%s is not closed (reader stopped at %s:%s)"
            opened-delimiter (:row opened-delimiter-loc) (:col opened-delimiter-loc) row col)))

(defn check
  "Inspects file, repairing it in place if needed. Returns {:block reason},
  {:context text} or nil."
  [file]
  (let [{:keys [text changed unfixable]} (repair (slurp file))]
    (if unfixable
      {:block (str file ": " (describe-location unfixable)
                   ". Indentation does not settle it safely, so the file was left as is."
                   " Rewrite the enclosing top-level form with correct nesting.")}
      (do
        (when text (spit file text))
        (let [repaired (when text
                         (str "Repaired unbalanced delimiters in " file
                              " from indentation; the file on disk changed, so re-read it before the next Edit."
                              " Confirm these lines are what you meant:\n"
                              (str/join "\n" (map (fn [[n line]] (format "%5d: %s" n line)) changed))))
              errors (when-let [findings (seq (kondo-errors file))]
                       (str "clj-kondo errors in " file ":\n"
                            (str/join "\n" (map #(format "%5d:%d %s" (:row %) (:col %) (:message %)) findings))))]
          (when (or repaired errors)
            {:context (str/join "\n\n" (remove nil? [repaired errors]))}))))))

(defn -main [& _]
  (let [file (get-in (json/parse-stream *in* true) [:tool_input :file_path])]
    (when (and (clojure-file? file) (fs/exists? file))
      (when-let [{:keys [block context]} (check file)]
        (println (json/generate-string
                  (if block
                    {:decision "block" :reason block}
                    {:hookSpecificOutput {:hookEventName "PostToolUse"
                                          :additionalContext context}})))))))

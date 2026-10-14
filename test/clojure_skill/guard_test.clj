(ns clojure-skill.guard-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.guard :as guard]))

(defn- temp-file [name content]
  (let [file (fs/path (fs/create-temp-dir) name)]
    (spit (str file) content)
    (str file)))

(deftest recognises-clojure-files
  (is (every? guard/clojure-file? ["a.clj" "a.cljs" "a.cljc" "a.cljd" "a.edn" "a.bb"]))
  (is (not-any? guard/clojure-file? ["a.cljx" "a.py" "clj" nil])))

(deftest leaves-balanced-text-alone
  (is (nil? (guard/repair "(ns a)\n(defn f [x] #(inc %) #?(:cljs 1) #inst \"2020\")\n"))))

(deftest infers-a-missing-closer-from-indentation
  (let [{:keys [text changed]} (guard/repair "(defn f [x]\n  (let [y 1]\n    (+ x y)\n\n(defn g [])\n")]
    (is (= "(defn f [x]\n  (let [y 1]\n    (+ x y)))\n\n(defn g [])\n" text))
    (is (= [[3 "    (+ x y)))"]] changed))))

(deftest drops-a-stray-closer
  (is (= "(defn f [x]\n  (inc x))\n" (:text (guard/repair "(defn f [x]\n  (inc x)))\n")))))

(deftest keeps-crlf-line-endings
  (is (= "(defn f [x]\r\n  (inc x))\r\n" (:text (guard/repair "(defn f [x]\r\n  (inc x)\r\n")))))

(deftest refuses-what-indentation-cannot-settle
  (testing "an unterminated string"
    (is (:unfixable (guard/repair "(def s \"abc)\n"))))
  (testing "a repair touching more lines than allowed"
    (with-redefs [guard/max-changed-lines 0]
      (is (:unfixable (guard/repair "(defn f [x]\n  (inc x)\n"))))))

(deftest check-rewrites-the-file-and-says-which-lines-changed
  (let [file (temp-file "a.clj" "(ns a)\n\n(defn f [x]\n  (inc x)\n")
        {:keys [context]} (guard/check file)]
    (is (= "(ns a)\n\n(defn f [x]\n  (inc x))\n" (slurp file)))
    (is (str/includes? context "    4:   (inc x))"))))

(deftest check-blocks-with-the-location-when-unfixable
  (let [file (temp-file "a.clj" "(ns a)\n(def s \"abc)\n")
        {:keys [block]} (guard/check file)]
    (is (str/includes? block "\" opened at 2:8"))
    (is (= "(ns a)\n(def s \"abc)\n" (slurp file)) "the file is left as written")))

(deftest check-reports-kondo-errors-only
  (if (fs/which "clj-kondo")
    (let [file (temp-file "a.clj" "(ns a)\n(defn f [x unused] (g x))\n")
          {:keys [context]} (guard/check file)]
      (is (str/includes? context "2:21 Unresolved symbol: g"))
      (is (not (str/includes? context "unused")) "warnings stay out"))
    (println "clj-kondo not on PATH; skipping")))

(deftest main-ignores-other-files-and-clean-ones
  (doseq [file [(temp-file "a.py" "(") (temp-file "a.clj" "(ns a)\n")]]
    (is (= "" (with-out-str
                (binding [*in* (java.io.BufferedReader.
                                (java.io.StringReader.
                                 (json/generate-string {:tool_input {:file_path file}})))]
                  (guard/-main)))))))

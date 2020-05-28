(ns niddle.print
  (:require
   [clojure.string :refer [index-of]]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.print :refer [wrap-print]]
   [nrepl.transport :refer [Transport]]
   [puget.color.ansi :as ansi]
   [puget.printer :as pug]))

(def pug-options
  {:color-scheme {:number [:yellow]
                  :string [:green]
                  :delimiter [:red]
                  :keyword [:magenta]}})

(defn try-cpr [form]
  (try
    (pug/cprint-str form pug-options)
    (catch Throwable t
      (str t "\n...eval was successful, but color printing failed."))))

(defn- cpr-server
  ([form]
   (.println System/out (try-cpr form)))
  ([prefix form]
   (.println
    System/out
    (let [cstr (try-cpr form)]
      (str prefix (if (index-of cstr "\n") (str "...\n" cstr) cstr))))))

(defn extract-form [{:keys [code]}] (if (string? code) (read-string code) code))

(def ^:dynamic *debug* false)
(def skippable-sym #{'in-ns 'find-ns '*ns*})

(defn print-form? [form]
  "Skip functions & symbols that are unnecessary outside of interactive REPL"
  (or (and (symbol? form) (->> form (contains? skippable-sym) not))
      (and (list? form) (->> form flatten (not-any? skippable-sym)))
      (and (-> form symbol? not) (-> form list? not))))

(defn- handle-print-eval
  "Reify transport to catpure eval-ed values for printing"
  [{:keys [transport] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this resp]
      (when (-> resp :nrepl.middleware.print/keys (contains? :value))
        (when *debug*
          (.println System/out "----- Debug -----")
          (cpr-server "Request: "  (dissoc msg :session :transport))
          (cpr-server "Response: " (dissoc resp :session)))
        (when-let [form (extract-form msg)]
          (when (print-form? form)
            (cpr-server (str "(" (:ns resp) ")" (ansi/sgr " => " :blue)) form)
            (cpr-server (:value resp)))))
      (.send transport resp)
      this)))

(defn print-eval
  "Middleware to print :code :value from ops that leas to an eval in the repl."
  [h]
  (fn [{:keys [op] :as msg}]
    (case op
      "eval" (h (assoc msg :transport (handle-print-eval msg)))
      "load-file" (do (println (ansi/sgr (str "Loading file... " (:file-name msg)) :bold :black)) (h msg))
      :else (h msg))))

(set-descriptor! #'print-eval
                 {:requires #{#'wrap-print}
                  :expects #{"eval" "load-file"}
                  :handles {}})


(comment
  (+ 1 2 3)
  (+ 123 (+ 1 2 (- 4 3)))
  (assoc {:a 1 :b 2} :c 3)
  *ns*
  (prn (ansi/sgr "Loading file..." :bold :black))
  (extract-form {:code "#(identity %)"}))


(comment
  (require '[nrepl.core :as nrepl])
  (-> (nrepl/connect :port 54177)
      (nrepl/client 1000)
      (nrepl/message {:op "describe"})))

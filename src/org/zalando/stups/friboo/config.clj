(ns org.zalando.stups.friboo.config
  (:require [environ.core :as environ]
            [org.zalando.stups.friboo.log :as log]
            [clojure.string :as str]))

(defn- nil-or-empty?
  "If x is a string, returns true if nil or empty. Else returns true if x is nil"
  [x]
  (if (string? x)
    (empty? x)
    (nil? x)))

(defn require-config
  "Helper function to fail of a configuration value is missing."
  [configuration key]
  (let [value (get configuration key)]
    (if (nil-or-empty? value)
      (throw (IllegalArgumentException. (str "Configuration " key " is required but is missing.")))
      value)))

(defn- is-sensitive-key [k]
  (let [kname (name k)]
    (or (.contains kname "pass")
        (.contains kname "private")
        (.contains kname "secret"))))

(defn mask [config]
  "Mask sensitive information such as passwords"
  (into {} (for [[k v] config] [k (if (is-sensitive-key k) "MASKED" v)])))

(defn- strip [namespace k]
  (keyword (str/replace-first (name k) (str (name namespace) "-") "")))

(defn- namespaced [config namespace]
  (if (contains? config namespace)
    (config namespace)
    (into {} (map (fn [[k v]] [(strip (name namespace) k) v])
                  (filter (fn [[k v]]
                            (.startsWith (name k) (str (name namespace) "-")))
                          config)))))

(defn parse-namespaces [config namespaces]
  (let [namespaced-configs (into {} (map (juxt identity (partial namespaced config)) namespaces))]
    (doseq [[namespace namespaced-config] namespaced-configs]
      (log/debug "Destructured %s into %s." namespace (mask namespaced-config)))
    namespaced-configs))

(defn remap-keys [input mapping]
  (merge
    (into {} (for [[new-key old-key] mapping
                   :let [old-value (get input old-key)]
                   :when old-value]
               [new-key old-value]))
    input))

(defn load-config
  "Loads the configuration from different sources and transforms it.

  Merges default config with environment variables:
  {:http-port 8080 :tokeninfo-url \"foo\"}, {:http-port 9090} -> {:http-port 9090 :tokeninfo-url \"foo\"}
  Then optionally renames some keys, but only if the new key does not exist:
  {:http-tokeninfo-url :tokeninfo-url}, {:http-port 9090 :tokeninfo-url \"foo\"}
    -> {:http-port 9090 :tokeninfo-url \"foo\" :http-tokeninfo-url \"foo\"}
  Then filters out by provided namespace prefixes:
  [:http], {:http-port 9090 :tokeninfo-url \"foo\" :http-tokeninfo-url \"foo\"}
    -> {:http-port 9090 :http-tokeninfo-url \"foo\"}
  Then extracts namespaces:
  {:http-port 9090 :http-tokeninfo-url \"foo\"} -> {:http {:port 9090 :tokeninfo-url \"foo\"}}"

  [default-config namespaces & [{:keys [mapping]}]]
  (-> (merge default-config environ/env)
      (remap-keys mapping)
      (parse-namespaces (conj namespaces :system))))

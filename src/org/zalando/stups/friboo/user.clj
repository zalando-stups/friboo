(ns org.zalando.stups.friboo.user
  (:require [clj-http.client :as http]
            [org.zalando.stups.friboo.ring :as r]
            [io.sarnowski.swagger1st.util.api :as api]
            [org.zalando.stups.friboo.config :refer [require-config]]
            [org.zalando.stups.friboo.log :as log]
            [org.zalando.stups.friboo.log :as log]
            [clojure.core.memoize :as memo]
            [clojure.core.cache :as cache]
            [com.netflix.hystrix.core :refer [defcommand]]))

; get team for human
(defcommand
  fetch-teams
  [team-service-url access-token user-id]
  (:body (http/get (r/conpath team-service-url "/api/accounts/aws")
                   {:query-params {:member user-id}
                    :oauth-token access-token
                    :as          :json})))

; get team for service user
(defcommand
  fetch-service-teams
  [service-user-url access-token user-id]
  (let [team (-> (http/get (r/conpath service-user-url "/services/" user-id)
                           {:oauth-token access-token
                            :as :json})
                 (get-in [:body :owner]))]
    (vector {:name team}))) ; for consistency with human teams api

(def get-teams
  "Cache team information for 5 minutes"
  (memo/fifo fetch-teams
             (cache/ttl-cache-factory {} :ttl 300000)
             :fifo/threshold 100))

(def get-service-teams
  "Cache team info for service users 5 minutes"
  (memo/fifo fetch-service-teams
             (cache/ttl-cache-factory {} :ttl 300000)
             :fifo/threshold 100))

(defn trim-realm
  "Trims leading and trailing slashes from the given realm.
  If input is the top-level realm \"/\", leaves it as is."
  [string]
  (when string
    (if (= string "/")
      string
      (clojure.string/replace string #"^\/*(.*?)\/*$" "$1"))))

(defn require-realms
  "Throws an exception if user is not in the given realms, else returns the user's realm"
  [realms {:keys [tokeninfo]}]
  (let [realm (trim-realm (get tokeninfo "realm"))
        user-id (get tokeninfo "uid")]
    (if (contains? realms realm)
      realm
      (do
        (log/warn "ACCESS DENIED (unauthorized) because user is not in realms %s." realms)
        (api/throw-error 403 (str "user not in realms '" realms "'")
                         {:user            user-id
                          :required-realms realms
                          :user-realm      realm})))))

(defn require-teams
  "Returns a set of teams, a user is part of or throws an exception if user is in no team."
  ([request]
   (require-teams (:tokeninfo request)
                  (require-config (:configuration request)
                                  :team-service-url)
                  (require-config (:configuration request)
                                  :service-user-url)))
  ([tokeninfo team-service-url service-user-url]
   (require-teams (get tokeninfo "uid")
                  (get tokeninfo "access_token")
                  team-service-url
                  service-user-url))
  ([user-id token team-service-url service-user-url]
   (when-not user-id
     (log/warn "ACCESS DENIED (unauthenticated) because token does not contain user information.")
     (api/throw-error 403 "no user information available"))
   (let [realm (require-realms #{"employees" "services"} token)
         teams (if (= realm "employees")
                   (get-teams team-service-url
                              token
                              user-id)
                   (get-service-teams service-user-url
                                      token
                                      user-id))]
     (if (empty? teams)
       (do
         (log/warn "ACCESS DENIED (unauthorized) because user is not in any team.")
         (api/throw-error 403 "user has no teams"
                          {:user user-id}))
       (set (map :name teams))))))

(defn require-team
  "Throws an exception if user is not in the given team, else returns nil."
  ([team request]
   (require-team team (:tokeninfo request) (require-config (:configuration request) :team-service-url)))
  ([team tokeninfo team-service-url]
   (require-team team (get tokeninfo "uid") (get tokeninfo "access_token") team-service-url))
  ([team user-id token team-service-url]
   (let [in-team? (require-teams user-id token team-service-url)]
     (when-not (in-team? team)
       (log/warn "ACCESS DENIED (unauthorized) because user is not in team %s." team)
       (api/throw-error 403 (str "user not in team '" team "'")
                        {:user          user-id
                         :required-team team
                         :user-teams    in-team?})))))

(defn require-internal-user
  "Makes sure the user is either a service user, or an employee.
   In the latter case the user must belong to at least one team."
  [{:keys [tokeninfo] :as request}]
  (when tokeninfo
    (let [realm (require-realms #{"employees" "services"} request)]
      (when (= realm "employees")
        (require-teams request)))))

(defn require-internal-team
  "Makes sure the user is an employee and belongs to the given team."
  [team {:keys [tokeninfo] :as request}]
  (when tokeninfo
    (require-realms #{"employees"} request)
    (require-team team request)))

(defn require-any-internal-team
  "Makes sure the user is an employee and belongs to any team."
  [{:keys [tokeninfo] :as request}]
  (when tokeninfo
    (require-realms #{"employees"} request)
    (require-teams request)))

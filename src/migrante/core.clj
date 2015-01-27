(ns migrante.core
  (:require [clojure.pprint :refer (pprint)]
            [taoensso.timbre :as timbre]
            [slingshot.slingshot :refer [throw+ try+]]
            [suricatta.core :as sc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *localdb* nil)
(def ^:dynamic *verbose* false)
(def ^:dynamic *fake* false)

(def ^:private
  sql (str "create table if not exists migrations ("
           " module varchar(255),"
           " step varchar(255),"
           " created_at timestamp,"
           " unique(module, step)"
           ");"))

(defn- localdb
  "Get a suricatta opened context to the local state database."
  [{:keys [localdb] :or {localdb "_migrations.h2"}}]
  (let [dbspec {:subprotocol "h2" :subname localdb}]
    (sc/context dbspec)))

(defn- migration-registred?
  "Check if concrete migration is already registred."
  [module step]
  {:pre [(string? module) (string? step)]}
  (let [sql (str "select * from migrations"
                 " where module=? and step=?")
        res (sc/fetch *localdb* [sql module step])]
    (pos? (count res))))

(defn- register-migration!
  "Register a concrete migration into local migrations database."
  [module step]
  {:pre [(string? module) (string? step)]}
  (let [sql "insert into migrations (module, step) values (?, ?)"]
    (sc/execute *localdb* [sql module step])))

(defn- unregister-migration!
  "Unregister a concrete migration from local migrations database."
  [module step]
  {:pre [(string? module) (string? step)]}
  (let [sql "delete from migrations where module=? and step=?;"]
    (sc/execute *localdb* [sql module step])))

(defn- bootstrap-if-needed
  "Bootstrap the initial database for store migrations."
  [options]
  (with-open [ctx (localdb options)]
    (sc/execute ctx sql)))

(defn- do-migrate
  [ctx {:keys [name steps]} {:keys [until]}]
  (sc/atomic ctx
    (reduce (fn [_ [stepname step]]
              (timbre/info (format "- Applying migration [%s] %s." name stepname))
              (let [upfn (:up step)]
                (sc/atomic ctx
                  (upfn ctx))))
            nil
            steps)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute
  "Execute a query and return a number of rows affected."
  ([q]
   (when (false? *fake*)
     (sc/execute q)))
  ([ctx q]
   (when (false? *fake*)
     (sc/execute q ctx))))

(defn fetch
  "Fetch eagerly results executing a query.

  This function returns a vector of records (default) or
  rows (depending on specified opts). Resources are relased
  inmediatelly without specific explicit action for it."
  ([q]
   (when (false? *fake*)
     (sc/fetch q)))
  ([ctx q]
   (when (false? *fake*)
     (sc/fetch q ctx)))
  ([ctx q opts]
   (when (false? *fake*)
     (sc/fetch q ctx opts))))

(defn migrate
  "Main entry point for apply migrations."
  ([dbspec migration] (migrate dbspec migration {}))
  ([dbspec migration {:keys [verbose fake] :or {verbose true fake false} :as options}]
   (bootstrap-if-needed options)
   (binding [*localdb* (localdb options)
             *verbose* verbose
             *fake* fake]
     (with-open [ctx (sc/context dbspec)]
       (do-migrate ctx migration options)))))
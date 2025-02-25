(ns ^:no-doc portal.ui.rpc.runtime
  (:refer-clojure :exclude [deref pr-str])
  (:require [clojure.pprint :as pprint]
            [portal.runtime.cson :as cson]
            [portal.ui.state :as state]
            [reagent.core :as r]))

(defn call [f & args]
  (apply state/invoke f args))

(defonce current-values (r/atom {}))

(defn tag [value] (:tag (.-object value)))

(defn rep [value] (:rep (.-object value)))

(defn id [value] (:id (.-object value)))

(defn deref [this]
  (-> ((.-runtime this) 'clojure.core/deref this)
      (.then #(swap! current-values
                     assoc-in [(id this) 'clojure.core/deref] %))))

(defn- runtime-deref [this]
  (when (= ::not-found (get-in @current-values [(id this) 'clojure.core/deref] ::not-found))
    (deref this))
  (get-in @current-values [(id this) 'clojure.core/deref]))

(declare ->id)

(defn- runtime-to-json [buffer this]
  (let [object (.-object this)]
    (if-let [to-object (:to-object cson/*options*)]
      (to-object buffer this :runtime-object nil)
      (if-let [id (->id this)]
        (cson/tag buffer "ref" id)
        (cson/-to-json
         (cson/tagged-value "remote" (:pr-str object))
         buffer)))))

(defprotocol Runtime)

(deftype RuntimeObject [runtime object]
  Runtime
  cson/ToJson (-to-json [this buffer] (runtime-to-json buffer this))
  IMeta       (-meta    [_] (:meta object))
  IHash       (-hash    [_] (:id object))
  IEquiv
  (-equiv [_this other]
    (and (instance? RuntimeObject other)
         (= (:id object) (id other))))
  IWithMeta
  (-with-meta [_this m]
    (RuntimeObject.
     runtime
     (assoc object :meta m)))
  IPrintWithWriter
  (-pr-writer [_this writer _opts]
    (-write writer (:pr-str object))))

(defmethod pprint/simple-dispatch RuntimeObject [value] (pr value))

(deftype RuntimeAtom [runtime object]
  Runtime
  cson/ToJson (-to-json [this buffer] (runtime-to-json buffer this))
  IDeref      (-deref   [this] (runtime-deref this))
  IMeta       (-meta    [_] (:meta object))
  IHash       (-hash    [_] (:id object))
  IEquiv
  (-equiv [_this other]
    (and (instance? RuntimeAtom other)
         (= (:id object) (id other))))
  IWithMeta
  (-with-meta [_this m]
    (RuntimeAtom.
     runtime
     (assoc object :meta m)))
  IPrintWithWriter
  (-pr-writer [_this writer _opts]
    (-write writer (:pr-str object))))

(defmethod pprint/simple-dispatch RuntimeAtom [value] (pr value))

(defn runtime? [value]
  (satisfies? Runtime value))

(defn ->runtime [call object]
  (if (and
       (not= (:tag object) :var)
       (contains? (:protocols object) :IDeref))
    (->RuntimeAtom call object)
    (->RuntimeObject call object)))

(declare ->value)

(defn- cleanup [id]
  (when-not (->value id)
    ;; Only evict a value if the id hasn't been re-used as part of a new ws
    ;; connection session which wouldn't have any knowledge of previously sent
    ;; values, especially on process restarts.
    (swap! state/value-cache dissoc id)
    (swap! current-values dissoc id)
    (call 'portal.runtime/cache-evict id)))

(defonce ^:private registry
  (when (exists? js/FinalizationRegistry)
    (js/FinalizationRegistry. #(cleanup %))))

(defn- runtime-id [value]
  (or (-> value meta :portal.runtime/id)
      (when (runtime? value) (id value))))

(defn ->weak-ref [value]
  (if-not registry
    value
    (js/WeakRef. value)))

(defn- weak-ref-value [^js weak-ref]
  (let [object (if weak-ref
                 (.deref weak-ref)
                 js/undefined)]
    (when-not (undefined? object) object)))

(defn ->value [id]
  (let [value (get @state/value-cache id)]
    (if-not registry value (weak-ref-value value))))

(defn ->id [value]
  (when-let [id (runtime-id value)]
    (when (= value (->value id)) id)))

(defn transform [value]
  (when-let [id (runtime-id value)]
    (when-not (contains? @state/value-cache id)
      (when registry
        (.register ^js registry value id))
      (swap! state/value-cache assoc id (->weak-ref value))))
  value)

(defn ->object [call object]
  (or (->value (:id object)) (->runtime call object)))

(defn reset-cache! []
  (swap!
   state/value-cache
   (fn [cache]
     (when registry
       (doseq [weak-ref (vals cache)
               :let [object (weak-ref-value weak-ref)]
               :when object]
         (.unregister ^js registry object)))
     {}))
  (state/reset-value-cache!))
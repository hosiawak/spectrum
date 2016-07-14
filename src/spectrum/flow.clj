(ns spectrum.flow
  (:require [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.reflect :as reflect]
            [clojure.set :as set]
            [clojure.spec :as s]
            [spectrum.analyzer-spec]
            [spectrum.conform :as c]
            [spectrum.data :as data]
            [spectrum.java :as j]
            [spectrum.util :as util :refer (zip with-a unwrap-a print-once)]))

(def empty-fn-spec {:args nil, :ret nil, :fn nil})

(s/def ::args ::c/spect)
(s/def ::ret ::c/spect)
(s/def ::fn (s/nilable ::c/spect))

(s/def ::fn-spec (s/keys :req-un [::args ::ret ::fn]))
(s/def ::spec (s/or ::c/spect ::fn-spec))
(s/def ::args-spec ::spec)
(s/def ::ret-spec ::spec)
(s/def ::var var?)

(s/def ::analysis (s/keys :req []
                          :req-un [::ana.jvm/op ::ana.jvm/form]
                          :opt [::var ::args-spec ::ret-spec]))

(s/def ::analysis? (s/nilable ::analysis))

(s/def ::analyses (s/coll-of ::analysis))

(s/fdef get-var-fn-spec :args (s/cat :v var?) :ret (s/nilable ::c/spect))

(defn get-var-fn-spec [v]
  (assert (var? v))
  (let [s (s/get-spec v)]
    (when s
      (c/parse-fn-spec s))))

(defn a-loc-str
  "A human-formatted string for the file & line of the current analysis"
  [a]
  (let [{{:keys [file line column]} :env} a]
    (str "file " file " line " line " col " column)))

(s/fdef flow-dispatch :args (s/cat :a ::analysis) :ret keyword?)
(defn flow-dispatch [a]
  (assert (:op a))
  (:op a))

(s/fdef flow :args (s/cat :a ::analysis) :ret ::analysis)

(defmulti flow
  "Given an analysis, walk and update-in the the analysis attaching ::specs and ::ret to values"
  #'flow-dispatch)

(s/fdef flow-ns :args (s/cat :as ::analyses) :ret ::analyses)

(defn flow-ns
  "Given the result of analyze-ns, flow all forms"
  [as]
  (mapv flow as))

(defn java-type->spec [t]
  (c/class-spec
   (cond
     (j/primitive? t) (j/primitive->class t)
     (symbol? t) (j/resolve-class t)
     (class? t) t
     :else (assert "unknown type:" t))))

(s/fdef maybe-assoc-var-name :args (s/cat :a ::analysis) :ret ::analysis)
(defn maybe-assoc-var-name
  "Given a def analysis, if the def stores a fn, update the :fn analysis to contain the varname, for future analysis"
  [a]
  (let [path (if (-> a :init :op (= :with-meta))
               [:init :expr]
               [:init])]
    (if (= :fn (:op (get-in a path)))
      (assoc-in a (conj path ::var) (:var a))
      a)))

(defmethod flow :default [a]
  (print-once "TODO" "flow op" (:op a))
  a)

(defmethod flow :def [a]
  (data/store-var-analysis a)
  (let [a (maybe-assoc-var-name a)]
    (if (-> a :init)
      (assoc a :init (flow (util/zip a :init)))
      a)))

(defmethod flow :with-meta [a]
  (update-in a [:expr] flow))

(defmethod flow :fn [a]
  (let [v (get a ::var)
        spec (when v
               (get-var-fn-spec v))
        a (if spec
            (assoc a
                   ::args-spec (:args spec)
                   ::ret-spec (:ret spec))
            a)]
    (-> a
        (update-in [:methods] (fn [methods]
                                (mapv (fn [m]
                                        (flow (with-meta m {:a a}))) methods))))))

(defmethod flow :if [a]
  (let [a (-> a
              (update-in [:test] (fn [form] (flow (with-a form a))))
              (update-in [:then] (fn [form] (flow (with-a form a))))
              (update-in [:else] (fn [form] (flow (with-a form a)))))]
    (-> a
        (assoc ::ret-spec (if (and (-> a :then ::ret-spec)
                                   (-> a :else ::ret-spec))
                            (if (= (-> a :then ::ret-spec)
                                   (-> a :else ::ret-spec))
                              (-> a :then ::ret-spec)
                              (c/or- [(-> a :then ::ret-spec)
                                    (-> a :else ::ret-spec)]))

                            (c/unknown (:form a)))))))

(defn const->spec [a]
  (java-type->spec (:o-tag a)))

(defmethod flow :const [a]
  (assoc a ::ret-spec (const->spec a)))

(defmethod flow :do [a]
  (-> a
      (update-in [:statements] (fn [statements] (mapv flow statements)))
      (update-in [:ret] flow)))

(defmethod flow :try [a]
  (update-in a [:body] flow))

(defn analysis->arg*-dispatch [x]
  (:op x))

(defmulti analysis->arg* #'analysis->arg*-dispatch)

(defmethod analysis->arg* :default [x]
  (or (::ret-spec x) (c/unknown (:form x))))

(s/fdef find-binding :args (s/cat :a ::analysis :name symbol?) :ret (s/nilable ::analysis))
(defn find-binding
  "recursively unwrap a, looking for a :binding for 'name"
  [a name]
  (or
   (condp = (:op a)
     :let (->> a
               :bindings
               (filter (fn [b] (= name (:name b))))
               first)
     :binding (->> a
                   :bindings
                   (filter (fn [b] (= name (:name b))))
                   first)
     :fn-method (->> a
                     :params
                     (filter (fn [b] (= name (:name b))))
                     first)
     nil)

   (when-let [a* (unwrap-a a)]
     (recur a* name))))

(defmethod analysis->arg* :local [a]
  (let [b (find-binding a (:name a))]
    (when-not b
      (print "failed to find binding:" (:name a)))
    (or (::ret-spec b) (c/unknown (:name a)))))

(s/fdef analysis-args->spec :args (s/cat :a ::ana.jvm/analyses) :ret (s/coll-of ::c/spect))

(defn analysis-args->spec
  "Given the analysis of a fn invoke, return the args for a compatible c/conforms? call"
  [args]
  (c/map->RegexCat {:ps (mapv (fn [arg]
                                (analysis->arg* (with-a arg args))) args)
                    :ret []}))

(s/fdef spec->java-args :args (s/cat :arg-spec ::c/spect) :ret ::j/java-args)
(defn spec->java-args
  "Given args spec, convert to java"
  [arg-spec]
  ;;{:post [(every? identity %)]}
  (assert (instance? spectrum.conform.RegexCat arg-spec))
  (mapv (fn [arg]
          (or (c/spec->class arg) (c/unknown arg))) (:ps arg-spec)))


(s/fdef compatible-java-method? :args (s/cat :v ::c/spect :m (s/coll-of (s/or :prim j/primitive? :sym symbol? :cls class?))) :ret boolean?)
(defn compatible-java-method?
  "True if args conforming to spec s can be passed to a method that takes method-types"
  [arg-spec method-types]
  ;;{:post [(do (println "compatible-java-method:" arg-spec method-types "=>" %) true)]}
  (let [spec (c/cat- (mapv java-type->spec method-types))
        argv (-> arg-spec :ps)]
    (assert argv)
    (c/conform spec argv)))

(s/def ::reflect-name symbol?)
(s/def ::reflect-return-type ::j/java-type)
(s/def ::reflect-parameter-types ::j/java-args)

(s/def ::reflect-method (s/keys :req-un [::reflect-name ::reflect-return-type ::reflect-parameter-types]))

(s/fdef more-specific? :args (s/cat :v ::reflect-method :m ::reflect-method) :ret integer?)
(defn more-specific-compare
  "sort comparator for two vectors of java args"
  [a b]
  (loop [[a & as] (:parameter-types a)
         [b & bs] (:parameter-types b)]
    (if (and a b)
      (cond
        (or (j/primitive? a) (contains? (parents a) (class b))) 1
        (or (j/primitive? b) (contains? (parents b) (class a))) -1
        :else (recur as bs))
      0)))

(s/fdef most-specific :args (s/cat :vecs (s/coll-of j/reflect-method?)) :ret ::j/java-args)
(defn most-specific
  "Given a seq of vectors of java args, return the most specific method"
  [arg-vecs]
  (-> (sort more-specific-compare arg-vecs) last))

(s/fdef get-java-method :args (s/cat :cls class? :method symbol?) :ret (s/coll-of j/reflect-method?))
(defn get-java-method
  [cls method]
  (some->> (reflect/reflect cls)
           :members
           (filterv (fn [m]
                      (and (instance? clojure.reflect.Method m)
                           (= method (:name m)))))))

(s/fdef get-conforming-java-method :args (s/cat :cls class? :method symbol? :arg-spec ::c/spect) :ret (s/nilable j/reflect-method?))
(defn get-conforming-java-method
  "Returns the java method that conforms to arg-spec "
  [cls method arg-spec]
  (some->> (get-java-method cls method)
           (filterv (fn [m]
                      (not= ::c/invalid (compatible-java-method? arg-spec (:parameter-types m)))))
           (most-specific)))

(s/fdef get-java-method-spec :args (s/cat :cls class? :method symbol? :arg-spec ::c/spect) :ret (s/nilable ::fn-spec))
(defn get-java-method-spec
  "Return a fake spec for a java interop call"
  [cls method arg-spec]
  (when-let [m (get-conforming-java-method cls method arg-spec)]
    (let [java-args (->> (mapv java-type->spec (:parameter-types m)))
          ret (c/parse-spec (java-type->spec (:return-type m)))]
      {:args (c/map->RegexCat {:ps (mapv c/parse-spec java-args)
                               :forms java-args
                               :ret []})
       :ret (c/parse-spec ret)})))

(defmethod flow :static-call [a]
  (let [cls (:class a)
        method (:method a)
        a (update-in a [:args] (fn [args]
                               (mapv (fn [arg]
                                       (flow (with-meta arg {:a a}))) args)))
        args-spec (analysis-args->spec (:args a))
        spec (get-java-method-spec cls method args-spec)]
    (-> a
        (assoc ::ret-spec (:ret spec)
               ::args-spec (:args spec)))))

(defmethod flow :binding [a]
  a)

(declare assoc-form-spec)

(s/fdef get-invoke-fn-spec :args (s/cat :a ::analysis) :ret (s/nilable ::c/spect))

(defn get-invoke-fn-spec
  "Given an :fn a, return the spec"
  [a]
  (when (-> a :op (= :var))
    (assert (var? (:var a))))

  (condp = (:op a)
    :var (get-var-fn-spec (:var a))))

(defn assoc-invoke-var [a]
  (let [v (-> a :fn :var)
        _ (assert v)
        s (get-var-fn-spec v)]
    (if s
      (assoc a
             ::args-spec (:args s)
             ::ret-spec (:ret s))
      a)))

;; (s/fdef get-form-spec :args (s/cat :a ::ana.jvm/analysis) :ret ::ana.jvm/analysis)
;; (defn get-form-spec
;;   "Given the :init of a binding, return the spec for the form, if any"
;;   [a]
;;   (or (::spec a) (assoc-form-spec a)))

(s/fdef assoc-spec-bindings :args (s/cat :a ::analysis) :ret ::analysis)
(defn assoc-spec-bindings
  "Given the :bindings from a let, assoc ::flow/spec to the binding, based on the right-hand value"
  [a]
  (-> a
      (update-in [:bindings] (fn [bindings]
                               (mapv (fn [b]
                                       (flow (with-meta b {:a a}))) bindings)))))

(defmethod flow :local [a]
  (let [b (find-binding a (:name a))]
    ;; (assert b (format "flow :local: failed to find binding: %s %s" (:name a) (a-loc-str a)))
    (assoc a ::ret-spec (::ret-spec b))))

(defmethod flow :binding [a]
  (let [a (-> a
              (update-in [:init] (fn [init]
                                   (flow (with-a init a)))))]
    (assoc a ::ret-spec (::ret-spec (:init a)))))

(defmethod flow :let [a]
  (let [a (assoc-spec-bindings a)
        a (update-in a [:body] (fn [body] (flow (with-meta body {:a a}))))]
    (-> a
        (assoc ::ret-spec (if (-> a :body :op (= :do))
                            (-> a :body last ::ret-spec)
                            (-> a :body ::ret-spec))))))

(s/fdef arity-conform? :args (s/cat :spec ::c/spect :params ::ana.jvm/bindings) :ret boolean?)
(defn arity-conform?
  "Without knowing the types of args, return true if it's possible for args to conform, based on arity alone"
  [spec args]
  (if (and spec args)
    (if (:variadic? (first args))
      true
      (if (empty? args)
        (if (c/accept-nil? spec)
          true
          false)
        (if-let [spec* (c/derivative spec (c/parse-spec (c/will-accept spec)))]
          (recur spec* (rest args))
          false)))
    false))

(s/fdef destructure-fn-params :args (s/cat :params ::ana.jvm/bindings :spec ::c/spect) :ret ::ana.jvm/bindings)
(defn destructure-fn-params
  "Given a spect and ana.jvm/fn-method params, update params to include spec"
  [params spec]
  (if (arity-conform? spec params)
    (loop [ret []
           params params
           spec spec]
      (if (and (seq params)
               spec)
        (let [param (first params)
              _ (inspect spec)
              s (c/first* spec)]
          (assert s)
          (if (:variadic? param)
            (conj ret (assoc param ::ret-spec (c/rest* spec)))
            (recur (conj ret (assoc param ::ret-spec s)) (rest params) (c/rest* spec))))
        ret))
    params))

(defmethod flow :fn-method [a]
  (let [v (-> a meta :a ::var)
        s (when v
            (get-var-fn-spec v))
        a (if s
            (update-in a [:params] destructure-fn-params (:args s))
            (do
              (print-once "fn-method: no spec for " v)
              a))]
    (-> a
        (update-in [:body] (fn [body]
                             (flow (with-meta body {:a a})))))))

(defn analyze+flow [form]
  (flow (ana.jvm/analyze form)))

(defn analyze+flow-ns [ns]
  (mapv flow (ana.jvm/analyze-ns ns)))

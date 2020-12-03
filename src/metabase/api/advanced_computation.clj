(ns metabase.api.advanced-computation
  "/api/advanced_computation endpoints, like pivot table generation"
  (:require [compojure.core :refer [POST]]
            [metabase.api.common :as api]
            [metabase.models.database :as database :refer [Database]]
            [metabase.query-processor :as qp]
            [metabase.query-processor
             [pivot :as pivot]
             [store :as qp.store]]
            [metabase.util.i18n :refer [tru]]
            [schema.core :as s]))

(api/defendpoint POST "/pivot/dataset"
  "Generate a pivoted dataset for an ad-hoc query"
  [:as {{:keys      [database]
         query-type :type
         :as        query} :body}]
  {database (s/maybe s/Int)}

  (when-not database
    (throw (Exception. (str (tru "`database` is required for all queries.")))))
  (api/read-check Database database)

  ;; aggregating the results (simulating a SQL union)
  ;; 1. find the combination of all the columns, which we get from taking the original query and adding a discriminator column
  ;; 2. run the first query
  ;; 3. Start returning from the API:
  ;;    {:data {:native_form nil ;; doesn't work for the fact that we're generating multiple queries
  ;;            :insights nil    ;; doesn't work for the fact that we're generating multiple queries
  ;;            :cols             <from step 1>
  ;;            :results_metadata <from step 1>
  ;;            :results_timezone <from first query>
  ;;            :rows []         ;; map the :rows responses from *all* queries together, using lazy-cat, adding breakout indicator and additional columns / setting to nil as necessary
  ;;            }
  ;;     :row_count <a total count from the rows collection above
  ;;     :status :completed}

  (qp.store/with-store
    (let [main-breakout           (:breakout (:query query))
          col-determination-query (-> query
                                      ;; TODO: move this to a bitmask or something that scales better / easier to use
                                      (assoc-in [:query :expressions] {"pivot-grouping" [:ltrim (str (vec main-breakout))]})
                                      (assoc-in [:query :fields] [[:expression "pivot-grouping"]]))
          main-query              (qp/query->preprocessed query)
          all-expected-cols       (qp/query->expected-cols (qp/query->preprocessed col-determination-query))]
      {:database_id (:database_id main-query)
       :data        {:native_form      nil
                     :cols             all-expected-cols
                     :results_metadata all-expected-cols
                     :rows             (mapcat identity (for [pivot-query (pivot/generate-queries query)
                                                              :let        [query-result (qp/process-query pivot-query)
                                                                           result-cols (map-indexed vector (:cols (:data query-result)))
                                                                           column-mapping (map (fn [item]
                                                                                                 (some #(when (= (:name item) (:name (second %)))
                                                                                                          (first %)) result-cols))
                                                                                               all-expected-cols)
                                                                           rows (:rows (:data query-result))]]
                                                          (map (fn [row]
                                                                 (map (fn [mapping]
                                                                        (if mapping
                                                                          (nth row mapping)
                                                                          nil))
                                                                      column-mapping)) rows)))}})))

(api/define-routes)

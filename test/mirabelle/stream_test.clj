(ns mirabelle.stream-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [corbihttp.metric :as metric]
            [mirabelle.action :as a]
            [mirabelle.index :as index]
            [mirabelle.io.file :as io-file]
            [mirabelle.io :refer [IO]]
            [mirabelle.pool :as pool]
            [mirabelle.pubsub :as pubsub]
            [mirabelle.stream :as stream]))

(deftest custom-action-test
  (testing "can compile with a custom action"
    (let [custom-actions {:my-custom-action 'mirabelle.action/where*}
          recorder (atom [])
          stream {:description "foo"
                  :actions {:action :my-custom-action
                            :params [[:> :metric 10]]
                            :children [{:action :test-action
                                        :params [recorder]}]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:custom-actions custom-actions}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= [{:metric 12}] @recorder))
      (entrypoint {:metric 9})
      (is (= [{:metric 12}] @recorder))
      (entrypoint {:metric 13})
      (is (= [{:metric 12} {:metric 13}] @recorder))))

  (testing "can use the custom function to build the stream"
    (let [custom-actions {:my-custom-action 'mirabelle.action/where*}
          recorder (atom [])
          stream {:description "foo"
                  :actions (a/custom :my-custom-action
                                     [[:> :metric 10]]
                                     (a/test-action recorder))}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:custom-actions custom-actions}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= [{:metric 12}] @recorder))
      (entrypoint {:metric 9})
      (is (= [{:metric 12}] @recorder))
      (entrypoint {:metric 13})
      (is (= [{:metric 12} {:metric 13}] @recorder)))))

(deftest compile!-test
  (let [recorder (atom [])
        stream {:description "foo"
                :actions (a/sdo
                          (a/decrement
                           (a/test-action recorder))
                          (a/where [:> :metric 10]
                                   (a/increment
                                    (a/test-action recorder))))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (is (fn? entrypoint))
    (entrypoint {:metric 12})
    (is (= [{:metric 11} {:metric 13}] @recorder))
    (entrypoint {:metric 30})
    (is (= [{:metric 11} {:metric 13} {:metric 29} {:metric 31}] @recorder))))

(deftest above-dt-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/above-dt {:threshold 5 :duration 10}
                                     (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 12 :time 1})
    (is (= [] @recorder))
    (entrypoint {:metric 12 :time 2})
    (entrypoint {:metric 12 :time 10})
    (entrypoint {:metric 12 :time 12})
    (is (= [{:metric 12 :time 12}] @recorder))
    (entrypoint {:metric 13 :time 14})
    (entrypoint {:metric 1 :time 15})
    (entrypoint {:metric 14 :time 20})
    (is (= [{:metric 12 :time 12} {:metric 13 :time 14}] @recorder))
    (entrypoint {:metric 15 :time 31})
    (is (= [{:metric 12 :time 12} {:metric 13 :time 14} {:metric 15 :time 31}]
           @recorder))))

(deftest between-dt-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/between-dt {:low 20 :high 30 :duration 10}
                                       (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 21 :time 1})
    (is (= [] @recorder))
    (entrypoint {:metric 22 :time 2})
    (entrypoint {:metric 23 :time 10})
    (entrypoint {:metric 24 :time 12})
    (is (= [{:metric 24 :time 12}] @recorder))
    (entrypoint {:metric 25 :time 14})
    (entrypoint {:metric 1 :time 15})
    (entrypoint {:metric 29 :time 20})
    (is (= [{:metric 24 :time 12} {:metric 25 :time 14}] @recorder))
    (entrypoint {:metric 28 :time 31})
    (is (= [{:metric 24 :time 12} {:metric 25 :time 14} {:metric 28 :time 31}]
           @recorder))))

(deftest outside-dt-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/outside-dt {:low 20 :high 30 :duration 10}
                                       (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 1 :time 1})
    (is (= [] @recorder))
    (entrypoint {:metric 2 :time 2})
    (entrypoint {:metric 3 :time 10})
    (entrypoint {:metric 4 :time 12})
    (is (= [{:metric 4 :time 12}] @recorder))
    (entrypoint {:metric 5 :time 14})
    (entrypoint {:metric 25 :time 15})
    (entrypoint {:metric 10 :time 20})
    (is (= [{:metric 4 :time 12} {:metric 5 :time 14}] @recorder))
    (entrypoint {:metric 40 :time 31})
    (is (= [{:metric 4 :time 12} {:metric 5 :time 14} {:metric 40 :time 31}]
           @recorder))))

(deftest critical-dt-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/critical-dt {:duration 10}
                                        (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:state "critical" :time 1})
    (is (= [] @recorder))
    (entrypoint {:state "critical" :time 2})
    (entrypoint {:state "critical" :time 10})
    (entrypoint {:state "critical" :time 12})
    (is (= [{:state "critical" :time 12}] @recorder))
    (entrypoint {:state "critical" :time 14})
    (entrypoint {:state "ok" :time 15})
    (entrypoint {:state "critical" :time 20})
    (is (= [{:state "critical" :time 12} {:state "critical" :time 14}] @recorder))
    (entrypoint {:state "critical" :time 31})
    (is (= [{:state "critical" :time 12}
            {:state "critical" :time 14}
            {:state "critical" :time 31}]
           @recorder))))

(deftest ddt-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/ddt
                          (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 1 :time 1})
    (is (= [] @recorder))
    (entrypoint {:metric 4 :time 2})
    (entrypoint {:metric 0 :time 12})
    (is (= [{:metric 3 :time 2}
            {:metric (/ -4 10) :time 12}] @recorder))))

(deftest coll-rate-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions
                (a/coll-rate (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint
     [{:host "341694922644", :service "2", :state nil, :description nil, :metric 20, :tags nil, :time 1.614428853E9, :ttl nil}
      {:host "341694922644", :service "2", :state nil, :description nil, :metric 20, :tags nil, :time 1.614428853E9, :ttl nil}
      {:host "341694922644", :service "2", :state nil, :description nil, :metric 20, :tags nil, :time 1.614428853E9, :ttl nil}
      {:host "341694922644", :service "2", :state nil, :description nil, :metric 20, :tags nil, :time 1.614428853E9, :ttl nil}
      {:host "341694922644", :service "2", :state nil, :description nil, :metric 20, :tags nil, :time 1.614428853E9, :ttl nil}])
    (is (= [{:host "341694922644", :service "2", :state nil, :description nil, :metric 100, :tags nil, :time 1.614428853E9, :ttl nil}]
           @recorder))))

(deftest ddt-pos-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/ddt-pos
                          (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 1 :time 1})
    (is (= [] @recorder))
    (entrypoint {:metric 4 :time 2})
    (entrypoint {:metric 0 :time 12})
    (entrypoint {:metric 2 :time 14})
    (is (= [{:metric 3 :time 2}
            {:metric 1 :time 14}] @recorder))))

(deftest with-test-stream
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/with {:foo 1 :metric 2}
                                 (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 1 :time 1})
    (is (= [{:metric 2 :time 1 :foo 1}] @recorder)))
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/with :metric 2
                                 (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 1 :time 1})
    (is (= [{:metric 2 :time 1}] @recorder))))

(deftest io-file-test
  (testing "non-test mode"
    (let [stream {:name "my-stream"
                  :description "foo"
                  :actions (a/push-io! :file-example-io)}
          file "/tmp/mirabelle-test-io"
          io-component (io-file/map->FileIO {:path file})
          {:keys [entrypoint]} (stream/compile-stream!
                                {:io {:file-example-io {:component io-component}}}
                                stream)]
      (entrypoint {:state "critical" :time 1})
      (entrypoint {:state "critical" :time 1 :tags ["discard"]})
      (entrypoint [{:state "critical" :time 1 :tags ["discard"]}
                   {:state "critical" :time 1 :tags ["discard"]}
                   {:state "critical" :time 1 :tags ["ok"]}])
      (entrypoint {:state "critical" :time 2})
      (let [result (slurp file)]
        (is (= ["{:state \"critical\", :time 1}"
                "{:state \"critical\", :time 1, :tags [\"ok\"]}"
                "{:state \"critical\", :time 2}"]
               (string/split result #"\n"))))
      (io/delete-file file)))
  (testing "test mode"
    (let [stream {:name "my-stream"
                  :description "foo"
                  :actions (a/push-io! :file-example-io)}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:test-mode? true}
                                stream)]
      (entrypoint {:state "critical" :time 1}))))

(deftest by-test
  (testing "simple example"
    (let [recorder (atom [])
          stream {:name "my-stream"
                  :description "foo"
                  :actions (a/by [:host]
                                 (a/fixed-event-window {:size 2}
                                                       (a/test-action recorder)))}
          {:keys [entrypoint]} (stream/compile-stream! {} stream)]
      (entrypoint {:host "foo" :metric 1 :time 1})
      (entrypoint {:host "foo" :metric 2 :time 1})
      (entrypoint {:host "bar" :metric 3 :time 1})
      (entrypoint {:host "bar" :metric 4 :time 1})
      (is (= [[{:host "foo" :metric 1 :time 1}
               {:host "foo" :metric 2 :time 1}]
              [{:host "bar" :metric 3 :time 1}
               {:host "bar" :metric 4 :time 1}]]
             @recorder))
      (entrypoint {:host "bar" :metric 5 :time 2})
      (entrypoint {:host "bar" :metric 6 :time 2})
      (entrypoint {:host "baz" :metric 4 :time 1})
      (entrypoint {:host "baz" :metric 7 :time 4})
      (is (= [[{:host "foo" :metric 1 :time 1}
               {:host "foo" :metric 2 :time 1}]
              [{:host "bar" :metric 3 :time 1}
               {:host "bar" :metric 4 :time 1}]
              [{:host "bar" :metric 5 :time 2}
               {:host "bar" :metric 6 :time 2}]
              [{:host "baz" :metric 4 :time 1}
               {:host "baz" :metric 7 :time 4}]]
             @recorder)))))

(deftest split-test
  (let [recorder (atom [])
        recorder2 (atom [])
        recorder3 (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/split
                          [:> :metric 10] (a/test-action recorder)
                          [:> :metric 5] (a/test-action recorder2)
                          (a/test-action recorder3))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 11 :time 1})
    (entrypoint {:metric 6 :time 2})
    (entrypoint {:metric 12 :time 3})
    (entrypoint {:metric 1 :time 4})
    (entrypoint {:metric 2 :time 5})
    (is (= [{:metric 11 :time 1}
            {:metric 12 :time 3}]
           @recorder))
    (is (= [{:metric 6 :time 2}]
           @recorder2))
    (is (= [{:metric 1 :time 4}
            {:metric 2 :time 5}]
           @recorder3)))
  (let [recorder (atom [])
        recorder2 (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/split
                          [:> :metric 10] (a/test-action recorder)
                          [:> :metric 5] (a/test-action recorder2))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 11 :time 1})
    (entrypoint {:metric 6 :time 2})
    (entrypoint {:metric 12 :time 3})
    (entrypoint {:metric 1 :time 4})
    (entrypoint {:metric 2 :time 5})
    (is (= [{:metric 11 :time 1}
            {:metric 12 :time 3}]
           @recorder))
    (is (= [{:metric 6 :time 2}]
           @recorder2)))
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/split
                          [:> :metric 10] (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:metric 11 :time 1})
    (entrypoint {:metric 6 :time 2})
    (entrypoint {:metric 12 :time 3})
    (entrypoint {:metric 1 :time 4})
    (entrypoint {:metric 2 :time 5})
    (is (= [{:metric 11 :time 1}
            {:metric 12 :time 3}]
           @recorder))))

(deftest compile-io!-test
  (testing "file io"
    (let [io-compiled (stream/compile-io! nil
                                          :foo
                                          {:type :file
                                           :config {:path "/tmp/foo"}}
                                          {})]
      (is (satisfies? IO (:component io-compiled)))))
  (testing "custom io"
    (let [io-compiled (stream/compile-io! nil
                                          :foo
                                          {:type :custom
                                           :config {:path "/tmp/foo"}}
                                          {:custom 'mirabelle.io.file/map->FileIO})]
      (is (satisfies? IO (:component io-compiled))))))

(deftest config-keys-test
  (is (= #{:foo :bar}
         (stream/config-keys {:foo {} :bar {}})))
  (is (= #{}
         (stream/config-keys {}))))

(deftest new-config-test
  (testing "same config"
    (let [old-config {:foo {} :bar {}}
          new-config {:foo {} :bar {}}]
      (is (empty? (:to-remove (stream/new-config old-config new-config))))
      (is (empty? (:to-add (stream/new-config old-config new-config))))
      (is (empty? (:to-reload (stream/new-config old-config new-config))))))
  (testing "to-add"
    (let [old-config {:foo {} :bar {}}
          new-config {:foo {} :bar {} :baz {}}]
      (is (empty? (:to-remove (stream/new-config old-config new-config))))
      (is (= #{:baz} (:to-add (stream/new-config old-config new-config))))
      (is (empty? (:to-reload (stream/new-config old-config new-config))))))
  (testing "to-reload"
    (let [old-config {:foo {} :bar {}}
          new-config {:foo {} :bar {:foo 1} :baz {}}]
      (is (empty? (:to-remove (stream/new-config old-config new-config))))
      (is (= #{:baz} (:to-add (stream/new-config old-config new-config))))
      (is (= #{:bar} (:to-reload (stream/new-config old-config new-config))))))
  (testing "to-remove"
    (let [old-config {:foo {} :bar {}}
          new-config {:foo {}}]
      (is (= #{:bar} (:to-remove (stream/new-config old-config new-config))))
      (is (empty? (:to-add (stream/new-config old-config new-config))))
      (is (empty? (:to-reload (stream/new-config old-config new-config)))))))

(deftest async-queue-test
  (testing "non-test mode"
    (let [recorder (atom [])
          queue {:component (pool/dynamic-thread-pool-executor nil :foo {})}
          stream {:name "my-stream"
                  :description "foo"
                  :actions (a/async-queue! :foo
                                           (a/test-action recorder))}
          {:keys [entrypoint]} (stream/compile-stream! {:io {:foo queue}} stream)]
      (entrypoint {:metric 12 :time 1})
      (entrypoint {:metric 13 :time 1})
      (Thread/sleep 200)
      (is (= [{:metric 12 :time 1}
              {:metric 13 :time 1}]
             @recorder))
      (pool/shutdown (:component queue))))
  (testing "test-mode"
    (let [recorder (atom [])
          stream {:name "my-stream"
                  :description "foo"
                  :actions (a/async-queue! :foo
                                           (a/test-action recorder))}
          {:keys [entrypoint]} (stream/compile-stream! {:test-mode? true}
                                                       stream)]
      (entrypoint {:metric 12 :time 1})
      (entrypoint {:metric 13 :time 1})
      (is (= [{:metric 12 :time 1}
              {:metric 13 :time 1}]
             @recorder)))))

(deftest stream-component-test
  (let [streams-path (.getPath (io/resource "streams"))
        io-path (.getPath (io/resource "ios"))
        streams {:foo {:actions {:action :fixed-event-window
                                 :params [{:size 100}]
                                 :children []}}
                 :bar {:actions {:action :above-dt
                                 :params [[:> :metric 100] 200]
                                 :children []}}}
        new-streams {:bar {:actions {:action :above-dt
                                     :params [[:> :metric 200] 200]
                                     :children []}}
                     :baz {:actions {:action :fixed-event-window
                                     :params [{:size 200}]
                                     :children []}}}
        handler (stream/map->StreamHandler {:streams-directories [streams-path]
                                            :io-directories [io-path]
                                            :registry (metric/registry-component {})})]
    (spit (str streams-path "/" "streams.edn") (pr-str streams))
    (let [{:keys [compiled-streams
                  streams-configurations]} (stream/reload handler)]
      (is (= streams-configurations streams))
      (is (= (set (keys compiled-streams)) #{:foo :bar})))
    (spit (str streams-path "/" "streams.edn") (pr-str new-streams))
    (let [{:keys [compiled-streams
                  streams-configurations]} (stream/reload handler)]
      (is (= streams-configurations new-streams))
      (is (= (set (keys compiled-streams)) #{:bar :baz})))
    (let [{:keys [compiled-streams
                  streams-configurations]} (stream/reload handler)]
      (is (= streams-configurations new-streams))
      (is (= (set (keys compiled-streams)) #{:bar :baz})))))

(deftest io-test
  (testing "Not in test mode"
    (let [recorder (atom [])
          stream {:description "foo"
                  :actions {:action :io
                            :children [{:action :test-action
                                        :params [recorder]}
                                       {:action :test-action
                                        :params [recorder]}]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= [{:metric 12} {:metric 12}]  @recorder))))
  (testing "In test mode"
    (let [recorder (atom [])
          stream {:description "foo"
                  :actions {:action :io
                            :children [{:action :test-action
                                        :params [recorder]}
                                       {:action :test-action
                                        :params [recorder]}]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:test-mode? true}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= []  @recorder)))))

(deftest tap-test
  (testing "outside tests"
    (let [tap (atom {})
          stream {:description "foo"
                  :actions {:action :sdo
                            :children [{:action :tap
                                        :params [:foo]}]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:test-mode? false
                                 :tap tap}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= {} @tap))))
  (testing "in tests"
    (let [tap (atom {})
          stream {:description "foo"
                  :actions {:action :sdo
                            :children [{:action :tap
                                        :params [:foo]}]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:test-mode? true
                                 :tap tap}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= {:foo [{:metric 12}]} @tap))))
  (testing "multiple taps"
    (let [tap (atom {})
          stream {:description "foo"
                  :actions {:action :sdo
                            :children [{:action :tap
                                        :params [:foo]}
                                       {:action :increment
                                        :children [{:action :tap
                                                    :params [:bar]}]}]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:test-mode? true
                                 :tap tap}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12})
      (is (= {:foo [{:metric 12}]
              :bar [{:metric 13}]} @tap))))
  (testing "tap top level"
    (let [tap (atom {})
          stream {:description "foo"
                  :actions {:action :tap
                            :params [:foo]}}
          {:keys [entrypoint]} (stream/compile-stream!
                                {:test-mode? false
                                 :tap tap}
                                stream)]
      (is (fn? entrypoint))
      (entrypoint {:metric 12}))))

(deftest index-test
  (let [index (component/start (index/map->Index {}))
        pubsub (component/start (pubsub/map->PubSub {}))
        stream {:name "my-stream"
                :description "foo"
                :actions (a/index [:host])}
        pubsub-result (atom [])
        {:keys [entrypoint]} (stream/compile-stream! {:index index
                                                      :pubsub pubsub
                                                      :source-stream "my-stream"}
                                                     stream)]
    (pubsub/add pubsub (index/channel "my-stream") (fn [event]
                                                     (swap! pubsub-result conj event)))
    (entrypoint {:host "f" :metric 12 :time 1})
    (is (= [{:host "f" :metric 12 :time 1}]
           @pubsub-result))
    (entrypoint {:host "a" :metric 13 :time 1})
    (is (= [{:host "f" :metric 12 :time 1}
            {:host "a" :metric 13 :time 1}]
           @pubsub-result))
    (is (= 1 (index/current-time index)))
    (is (= 2 (index/size-index index)))
    (is (= (set [{:host "f" :metric 12 :time 1}
                 {:host "a" :metric 13 :time 1}])
           (set (index/search index [:always-true]))))
    (entrypoint {:host "a" :metric 13 :time 10})
    (entrypoint {:host "a" :metric 13 :time 9})
    (is (= 10 (index/current-time index)))
    (entrypoint {:host "a" :metric 13 :time 12})
    (is (= 12 (index/current-time index)))))

(deftest reaper-test
  (let [index (component/start (index/map->Index {}))
        recorder (atom [])
        dest (atom [])
        pubsub (component/start (pubsub/map->PubSub {}))
        stream {:name "my-stream"
                :description "foo"
                :actions (a/sdo (a/index [:host])
                                (a/reaper 20))}
        {:keys [entrypoint]} (stream/compile-stream!
                              {:index index
                               :pubsub pubsub
                               :source-stream "my-stream"
                               :reinject (fn [event destination-stream]
                                           (swap! dest conj destination-stream)
                                           (swap! recorder conj event))}
                              stream)]
    (entrypoint {:host "f" :metric 12 :time 1 :ttl 15})
    (is (= 1 (index/current-time index)))
    (is (= 1 (index/size-index index)))
    (entrypoint {:host "b" :metric 12 :time 17})
    (is (= 17 (index/current-time index)))
    ;; not expired yet because of the reaper interval
    (is (= 2 (index/size-index index)))
    (entrypoint {:host "b" :metric 12 :time 20})
    (is (= ["my-stream"] @dest))
    (is (= [{:host "b" :metric 12 :time 20}]
           (index/search index [:always-true])))
    (is (= [{:host "f" :metric 12 :time 1 :ttl 15 :state "expired"}]
           @recorder))))

(deftest full-test
  (let [include-path (.getPath (io/resource "include/action"))
        stream {:name "my-stream"
                :description "foo"
                :actions (a/sdo
                          (a/above-dt {:threshold 5 :duration 10})
                          (a/default :service "foobar")
                          (a/between-dt {:low 10 :high 20 :duration 10})
                          (a/below-dt {:threshold 20 :duration 10})
                          (a/decrement)
                          (a/include include-path {:variables {:service "toto"}
                                                   :profile :prod})
                          (a/stable 5 :state)
                          (a/project [[:= :host "foo"]
                                      [:= :service "bar"]])
                          (a/to-base64 :host
                                       (a/from-base64 :host))
                          (a/to-base64 [:host :service]
                                       (a/from-base64 [:host :service]))
                          (a/sdissoc :foo)
                          (a/sformat "%s" :host [:service])
                          (a/exception-stream
                           (a/by [:host])
                           (a/decrement))
                          (a/by [:host])
                          (a/by [:host :service])
                          (a/sdissoc [:host :service])
                          (a/throttle {:count 1 :duration 10})
                          (a/warning)
                          (a/ewma-timeless 1)
                          (a/over 1)
                          (a/under 1)
                          (a/tap :foo)
                          (a/io)
                          (a/io
                           (a/by [:host]))
                          (a/fixed-time-window {:duration 5})
                          (a/split
                           [:> :metric 10] (a/critical))
                          (a/critical)
                          (a/critical-dt {:duration 10})
                          (a/debug)
                          (a/info)
                          (a/error)
                          (a/expired)
                          (a/fixed-event-window {:size 3}
                                                (a/coll-sum)
                                                (a/coll-percentiles [0 0.5 1])
                                                (a/coll-mean
                                                 (a/sflatten))
                                                (a/coll-max)
                                                (a/coll-quotient)
                                                (a/coll-min)
                                                (a/coll-count)
                                                (a/coll-rate)
                                                (a/coll-top 2)
                                                (a/coll-bottom 2))
                          (a/increment)
                          (a/changed {:field :state :init "ok"})
                          (a/not-expired)
                          (a/tag "foo")
                          (a/ddt)
                          (a/moving-event-window {:size 3})
                          (a/ddt-pos)
                          (a/tagged-all ["foo" "bar"])
                          (a/tagged-all "bar")
                          (a/untag "foo")
                          (a/tag ["foo" "bar"])
                          (a/untag ["foo" "bar"])
                          (a/keep-keys [:host :service])
                          (a/json-fields [:foo :bar])
                          (a/json-fields :foo)
                          (a/outside-dt {:low 2 :high 10 :duration 5})
                          (a/coalesce {:duration 2 :fields [:host]})
                          (a/scale 100)
                          (a/with :foo 1)
                          (a/rename-keys {:host :service})
                          (a/with {:foo 1})
                          (a/where [:> :metric 10])
                          (a/where [:always-true]
                                   (a/critical))
                          (a/where [:and
                                    [:< :metric 10]
                                    [:> :metric 1]]))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (is (fn? entrypoint))
    (entrypoint {:state "ok" :time 2 :metric 1 :host "foo" :service "a"})
    (entrypoint {:state "ok" :time 4 :metric 1 :host "foo" :service "a"})
    (entrypoint {:state "ok" :time 100 :metric 1 :host "foo" :service "b"})
    (entrypoint {:state "ok" :time 200 :metric 1 :host "foo" :service "a"})))

(deftest aggr-sum-test
  (let [recorder (atom [])
        stream {:name "my-stream"
                :description "foo"
                :actions (a/aggr-sum {:duration 10}
                                     (a/test-action recorder))}
        {:keys [entrypoint]} (stream/compile-stream! {} stream)]
    (entrypoint {:time 0 :metric 10})
    (entrypoint {:time 7 :metric 1})
    (entrypoint {:time 11 :metric 3})
    (entrypoint {:time 14 :metric 8})
    (entrypoint {:time 19 :metric 1})
    (entrypoint {:time 20 :metric 2})
    (entrypoint {:time 23 :metric 4})
    (entrypoint {:time 60 :metric 1})
    (is (= [{:time 10 :metric 11}
            {:time 20 :metric 12}
            {:time 30, :metric 6}
            {:time 40, :metric 0}
            {:time 50, :metric 0}
            {:time 60 :metric 0}]
           @recorder))))

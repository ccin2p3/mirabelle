{:foo
 {:actions
  {:action :sdo,
   :children
   ({:action :where,
     :params [[:> :metric 10]],
     :children
     ({:action :info}
      {:action :index, :params [[:host :service]]}
      {:action :increment,
       :children ({:action :tap, :params [:foo]} {:action :info})})})}}}

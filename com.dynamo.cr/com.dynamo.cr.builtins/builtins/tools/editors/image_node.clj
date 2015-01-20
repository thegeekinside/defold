(ns editors.image-node
  (:require [dynamo.node :as n]
            [dynamo.image :as i]
            [dynamo.project :as p]
            [dynamo.system :as ds]))

(n/defnode ImageResourceNode
  "Produces an image on demand. Can be shown in an outline view."
  (inherits n/OutlineNode)
  (inherits i/ImageSource))

(when (ds/in-transaction?)
  (p/register-node-type "png" ImageResourceNode)
  (p/register-node-type "jpg" ImageResourceNode))
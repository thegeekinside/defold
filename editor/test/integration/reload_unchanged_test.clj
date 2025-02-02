;; Copyright 2020-2023 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns integration.reload-unchanged-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [dynamo.graph :as g]
            [editor.code.resource :as code.resource]
            [editor.collection :as collection]
            [editor.defold-project :as project]
            [editor.game-object :as game-object]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.resource-update :as resource-update]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [support.test-support :refer [spit-until-new-mtime touch-until-new-mtime valid-node-value with-clean-system]]))

(set! *warn-on-reflection* true)

(defn- folder-file-resource? [resource]
  (and (resource/file-resource? resource)
       (= :folder (resource/source-type resource))))

(defn- all-file-resources [workspace]
  (->> (workspace/resolve-workspace-resource workspace "/")
       (tree-seq folder-file-resource? resource/children)
       (filter (fn [resource]
                 (and (= :file (resource/source-type resource))
                      (not (resource/internal? resource)))))))

(defn- touch-and-resource-sync! [resources]
  (doseq [resource resources]
    (touch-until-new-mtime (io/as-file resource)))
  (when-some [workspace (some resource/workspace resources)]
    (workspace/resource-sync! workspace)))

(defn resources->proj-paths [resources]
  (->> resources
       (map resource/proj-path)
       (sort)
       (vec)))

(defn resource-node-ids->proj-paths [resource-node-ids]
  (->> resource-node-ids
       (map resource-node/resource)
       (resources->proj-paths)))

(defn- save-data->proj-path [save-data]
  (resource/proj-path (:resource save-data)))

(defn- save-datas->proj-path-set [save-datas]
  (into (sorted-set)
        (map save-data->proj-path)
        save-datas))

(defmulti ^:private edit-resource-node
  (fn [node-id]
    (if (g/node-instance? code.resource/CodeEditorResourceNode node-id)
      :code
      (resource/type-ext (resource-node/resource node-id)))))

(defmethod edit-resource-node :code [node-id]
  (test-util/update-code-editor-lines node-id conj ""))

(defmethod edit-resource-node "atlas" [node-id]
  (g/set-property node-id :margin 4))

(defmethod edit-resource-node "collection" [node-id]
  (g/delete-node (test-util/first-subnode-of-type node-id collection/InstanceNode)))

(defmethod edit-resource-node "font" [node-id]
  (g/set-property node-id :render-mode :mode-multi-layer))

(defmethod edit-resource-node "go" [node-id]
  (g/delete-node (test-util/first-subnode-of-type node-id game-object/ComponentNode)))

(defmethod edit-resource-node "input_binding" [node-id]
  (g/update-property node-id :pb assoc-in [:mouse-trigger 0 :input] :mouse-button-2))

(defmethod edit-resource-node "project" [node-id]
  (test-util/set-setting node-id ["project" "title"] "New Title"))

(defmethod edit-resource-node "shared_editor_settings" [node-id]
  (test-util/set-setting node-id ["performance" "cache_capacity"] 12345))

(defmethod edit-resource-node "sprite" [node-id]
  (g/set-property node-id :blend-mode :blend-mode-mult))

(defn- resource-changes [resource-change-plan]
  {:kept (resources->proj-paths (:kept resource-change-plan))
   :reloaded (resources->proj-paths (:new resource-change-plan))
   :invalidated (resource-node-ids->proj-paths (:invalidate-outputs resource-change-plan))})

(defn- expect-reloaded [project touched-resources reloaded-proj-paths]
  {:pre [(coll? touched-resources)
         (vector? reloaded-proj-paths)]}
  (let [stateful-resources
        (into #{}
              (remove #(:stateless? (resource/resource-type %)))
              touched-resources)

        ;; We expect the outputs on all stateless resource nodes to be
        ;; invalidated. It would be nice to only do this if their contents have
        ;; changed, but there is no way for us to tell if they've been modified
        ;; or not, since we don't keep any state for them at all.
        stateless-resource-node-ids
        (sort
          (eduction
            (remove stateful-resources)
            (map #(project/get-resource-node project %))
            touched-resources))]

    {:kept (filterv (complement (set reloaded-proj-paths))
                    (resources->proj-paths stateful-resources))
     :reloaded reloaded-proj-paths
     :invalidated (resource-node-ids->proj-paths stateless-resource-node-ids)}))

(def ^:private lazy-loaded-editable-file-proj-paths
  ["/.gitattributes"
   "/.gitignore"
   "/editable/json.json"
   "/editable/script_api.script_api"
   "/editable/xml.xml"])

(def ^:private lazy-loaded-non-editable-file-proj-paths
  ["/non-editable/json.json"
   "/non-editable/script_api.script_api"
   "/non-editable/xml.xml"])

(def ^:private lazy-loaded-file-proj-paths
  (-> (into lazy-loaded-editable-file-proj-paths
            lazy-loaded-non-editable-file-proj-paths)
      (sort)
      (vec)))

(defn- make-resource-change-plans-atom! [project]
  ;; Add a resource listener to the workspace, so we can get the resulting
  ;; resource change plans. We need to be the first listener in the list so that
  ;; we get the resulting resource change plan before it is executed.
  (let [resource-change-plans-atom (atom [])
        workspace (project/workspace project)

        observing-resource-listener
        (reify resource/ResourceListener
          (handle-changes [_this changes _render-progress!]
            (let [old-nodes-by-path (valid-node-value project :nodes-by-resource-path)
                  old-node->old-disk-sha256 (valid-node-value workspace :disk-sha256s-by-node-id)
                  resource-change-plan (resource-update/resource-change-plan old-nodes-by-path old-node->old-disk-sha256 changes)]
              (swap! resource-change-plans-atom conj resource-change-plan))))

        progress-span 1
        resource-listeners (workspace/prepend-resource-listener! workspace progress-span observing-resource-listener)]
    (is (= 2 (count resource-listeners)) "There should only have been one resource listener before us.")
    resource-change-plans-atom))

(defn- perform-edits-to-all-editable-files [project]
  (into []
        (mapcat (fn [[editable-resource-node-id]]
                  (edit-resource-node editable-resource-node-id)))
        (g/sources-of project :save-data)))

(defn- perform-edits-to-all-editable-files! [project]
  (g/transact (perform-edits-to-all-editable-files project))

  ;; Sanity-check: Ensure all editable files are now considered dirty.
  ;; If this fails, our tests here aren't covering everything.
  (is (= (save-datas->proj-path-set (project/all-save-data project))
         (save-datas->proj-path-set (project/dirty-save-data project)))))

(deftest keep-existing-nodes-no-false-positives-test
  (with-clean-system
    (let [workspace (test-util/setup-scratch-workspace! world "test/resources/reload_unchanged_project")
          project (test-util/setup-project! workspace)
          project-graph (g/node-id->graph-id project)
          resource-change-plans-atom (make-resource-change-plans-atom! project)

          resource+edited-contents
          (with-open [_ (test-util/make-graph-reverter project-graph)]
            (perform-edits-to-all-editable-files! project)
            (into []
                  (map (fn [save-data]
                         (let [resource (:resource save-data)
                               content (:content save-data)]
                           (assert (resource/file-resource? resource))
                           (assert (string? content))
                           [resource content])))
                  (sort-by #(resource/proj-path (:resource %))
                           (project/dirty-save-data project))))]

      ;; Simulate external changes to all the editable files in the project.
      (doseq [[resource content] resource+edited-contents]
        (spit-until-new-mtime resource content))

      (workspace/resource-sync! workspace)

      ;; Ensure all the externally edited resources are reloaded in the
      ;; resulting resource change plan.
      (let [externally-edited-resources (mapv first resource+edited-contents)
            externally-edited-proj-paths (mapv resource/proj-path externally-edited-resources)]
        (when (is (= 1 (count @resource-change-plans-atom)))
          (is (= (expect-reloaded project externally-edited-resources externally-edited-proj-paths)
                 (resource-changes (@resource-change-plans-atom 0)))))))))

(deftest keep-existing-nodes-touch-before-save-test
  (with-clean-system
    (let [workspace (test-util/setup-scratch-workspace! world "test/resources/reload_unchanged_project")
          project (test-util/setup-project! workspace)
          resource-change-plans-atom (make-resource-change-plans-atom! project)]

      (testing "Only nodes whose file contents have changed on disk are reloaded."
        ;; Touch all the files in the project.
        (let [touched-resources (all-file-resources workspace)]
          (touch-and-resource-sync! touched-resources)

          ;; Ensure we have no significant changes in the resource change plan.
          (when (is (= 1 (count @resource-change-plans-atom)))
            ;; We expect all lazy-loaded files to be reloaded, since they will
            ;; not have been loaded and thus have no on-disk state to compare
            ;; against.
            (is (= (expect-reloaded project touched-resources lazy-loaded-file-proj-paths)
                   (resource-changes (@resource-change-plans-atom 0)))))))

      (testing "Only nodes whose file contents have changed on disk lose unsaved edits."
        ;; Perform unsaved edits on all editable files in the project.
        (perform-edits-to-all-editable-files! project)

        ;; Touch all the files in the project.
        (let [touched-resources (all-file-resources workspace)]
          (touch-and-resource-sync! touched-resources)

          ;; Ensure we have no significant changes in the resource change plan.
          (when (is (= 2 (count @resource-change-plans-atom)))
            ;; We expect the non-editable lazy-loaded files to be reloaded, as
            ;; they have no on-disk state to compare against. But the editable
            ;; lazy-loaded files will have been loaded as a result of the edit,
            ;; so we do not expect them to be reloaded here.
            (is (= (expect-reloaded project touched-resources lazy-loaded-non-editable-file-proj-paths)
                   (resource-changes (@resource-change-plans-atom 1))))))))))

(deftest keep-existing-nodes-touch-after-save-test
  (with-clean-system
    (let [workspace (test-util/setup-scratch-workspace! world "test/resources/reload_unchanged_project")
          project (test-util/setup-project! workspace)
          resource-change-plans-atom (make-resource-change-plans-atom! project)]

      ;; Perform edits on all editable files in the project.
      (perform-edits-to-all-editable-files! project)

      ;; Save the edits to disk.
      (test-util/save-project! project)

      ;; Ensure no files are considered dirty after saving.
      (is (= #{} (save-datas->proj-path-set (project/dirty-save-data project))))

      ;; Touch all the files in the project.
      (let [touched-resources (all-file-resources workspace)]
        (touch-and-resource-sync! touched-resources)

        ;; Ensure we have no significant changes in the resource change plan.
        (when (is (= 1 (count @resource-change-plans-atom)))
          ;; We expect the non-editable lazy-loaded files to be reloaded, as
          ;; they have no on-disk state to compare against. But the editable
          ;; lazy-loaded files will have been loaded as a result of the edit, so
          ;; we do not expect them to be reloaded here.
          (is (= (expect-reloaded project touched-resources lazy-loaded-non-editable-file-proj-paths)
                 (resource-changes (@resource-change-plans-atom 0)))))))))

(deftest keep-existing-nodes-undo-after-save-test
  (with-clean-system
    (let [workspace (test-util/setup-scratch-workspace! world "test/resources/reload_unchanged_project")
          project (test-util/setup-project! workspace)
          project-graph (g/node-id->graph-id project)
          resource-change-plans-atom (make-resource-change-plans-atom! project)]

      ;; Perform edits on all editable files in the project.
      (perform-edits-to-all-editable-files! project)

      ;; Touch all files except the lazy-loaded non-editable ones. We don't want
      ;; to trigger a reload of these files for this test, as it will cause the
      ;; undo queue to be cleared due to the necessary node replacements.
      (let [touch-resource? (comp (complement (set lazy-loaded-non-editable-file-proj-paths)) resource/proj-path)
            touched-resources (filterv touch-resource? (all-file-resources workspace))]
        (touch-and-resource-sync! touched-resources)

        ;; Ensure we have no significant changes in the resource change plan.
        (when (is (= 1 (count @resource-change-plans-atom)))
          (is (= (expect-reloaded project touched-resources [])
                 (resource-changes (@resource-change-plans-atom 0))))))

      ;; Ensure the undo queue is intact.
      (is (= 1 (g/undo-stack-count project-graph)))

      ;; Ensure all editable files are considered dirty before saving.
      (is (= (save-datas->proj-path-set (project/all-save-data project))
             (save-datas->proj-path-set (project/dirty-save-data project))))

      ;; Save the edits to disk.
      (test-util/save-project! project)

      ;; Ensure no files are considered dirty after saving.
      (is (= #{} (save-datas->proj-path-set (project/dirty-save-data project))))

      ;; Undo our changes past the point of the save.
      (is (= 1 (g/undo-stack-count project-graph)))
      (g/undo! project-graph)

      ;; Ensure all editable files are now considered dirty again.
      (is (= (save-datas->proj-path-set (project/all-save-data project))
             (save-datas->proj-path-set (project/dirty-save-data project))))

      ;; Touch all the files in the project.
      (let [touched-resources (all-file-resources workspace)]
        (touch-and-resource-sync! touched-resources)

        ;; Ensure we have no significant changes in the resource change plan.
        (when (is (= 2 (count @resource-change-plans-atom)))
          ;; We expect the non-editable lazy-loaded files to be reloaded, as
          ;; they have no on-disk state to compare against. But the editable
          ;; lazy-loaded files will have been loaded as a result of the edit, so
          ;; we do not expect them to be reloaded here.
          (is (= (expect-reloaded project touched-resources lazy-loaded-non-editable-file-proj-paths)
                 (resource-changes (@resource-change-plans-atom 1)))))))))

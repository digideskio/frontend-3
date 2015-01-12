(ns frontend.components.aside
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [raise!]]
            [frontend.components.common :as common]
            [frontend.components.shared :as shared]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.models.plan :as pm]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils.seq :refer [select-in]]
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn status-ico-name [build]
  (case (:status build)
    "running" :busy-light

    "success" :pass-light
    "fixed"   :pass-light

    "failed"   :fail-light
    "timedout" :fail-light

    "queued"      :hold-light
    "not_running" :hold-light
    "retried"     :hold-light
    "scheduled"   :hold-light

    "canceled"            :stop-light
    "no_tests"            :stop-light
    "not_run"             :stop-light
    "infrastructure_fail" :stop-light
    "killed"              :stop-light

    :none-light))

(defn sidebar-build [build {:keys [org repo branch latest?]}]
  [:a.status {:class (when latest? "latest")
       :href (routes/v1-build-path org repo (:build_num build))
       :title (str (build-model/status-words build) ": " (:build_num build))}
   (common/ico (status-ico-name build))])

(defn branch [data owner]
  (reify
    om/IDisplayName (display-name [_] "Aside Branch Activity")
    om/IRender
    (render [_]
      (let [{:keys [org repo branch-data]} data
            [name-kw branch-builds] branch-data
            display-builds (take-last 5 (sort-by :build_num (concat (:running_builds branch-builds)
                                                                    (:recent_builds branch-builds))))]
        (html
         [:li
          [:div.branch
           {:role "button"}
           [:a {:href (routes/v1-dashboard-path {:org org :repo repo :branch (name name-kw)})
                :title (utils/display-branch name-kw)}
            (-> name-kw utils/display-branch (utils/trim-middle 23))]]
          [:div.statuses {:role "button"}
           (for [build display-builds]
             (sidebar-build build {:org org :repo repo :branch (name name-kw)}))]])))))

(defn project-aside [data owner opts]
  (reify
    om/IDisplayName (display-name [_] "Aside Project Activity")
    om/IRender
    (render [_]
      (let [login (:login opts)
            project (:project data)
            settings (:settings data)
            project-id (project-model/id project)
            ;; lets us store collapse branches in localstorage without leaking info
            project-id-hash (utils/md5 project-id)
            show-all-branches? (get-in data state/show-all-branches-path)
            collapse-branches? (get-in data (state/project-branches-collapsed-path project-id-hash))
            vcs-url (:vcs_url project)
            org (vcs-url/org-name vcs-url)
            repo (vcs-url/repo-name vcs-url)
            branches-filter (if show-all-branches? identity (partial project-model/personal-branch? {:login login} project))]
        (html
         [:ul {:class (when-not collapse-branches? "open")}
          [:li
           [:div.project {:role "button"}
            [:a.toggle {:title "show/hide"
                        :on-click #(raise! owner [:collapse-branches-toggled {:project-id project-id
                                                                              :project-id-hash project-id-hash}])}
             (common/ico :repo)]

            [:a.title {:href (routes/v1-project-dashboard {:org org
                                                           :repo repo})
                       :title (project-model/project-name project)}
             (project-model/project-name project)]
            (when-not collapse-branches?
             [:a.project-settings-icon {:href (routes/v1-project-settings {:org org :repo repo})
                                        :title (str "Settings for " org "/" repo)}
              (common/ico :settings-light)])
            (when-let [latest-master-build (last (project-model/master-builds project))]
              (sidebar-build latest-master-build {:org org :repo repo :branch (name (:default_branch project)) :latest? true}))]]
          (when-not collapse-branches?
            (for [branch-data (->> project
                                   :branches
                                   (filter branches-filter)
                                   ;; alphabetize
                                   (sort-by first))]
              (om/build branch
                        {:branch-data branch-data
                         :org org
                         :repo repo}
                        {:react-key (first branch-data)})))])))))

(defn context-menu [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.aside-user {:class (when (get-in app state/user-options-shown-path)
                                   "open")}
         [:header
          [:h5 "Your Account"]
          [:a.close-menu
           {:on-click #(raise! owner [:user-options-toggled])}
           (common/ico :fail-light)]]
         [:div.aside-user-options
          [:a.aside-item {:href "/account"} "Settings"]
          [:a.aside-item {:href "/invite-teammates"} "Invite a Teammate"]
          [:a.aside-item {:href "/logout"} "Logout"]]]))))

(defn expand-menu-items [items subpage]
  (for [item items]
    (case (:type item)
      
      :heading
      [:.aside-item.aside-heading
       (:title item)]
      
      :subpage
      [:a.aside-item {:href (:href item)
                      :class (when (= subpage (:subpage item)) "active")}
       (:title item)])))

(defn project-settings-nav-items [data owner]
  (let [navigation-data (:navigation-data data)]
    [{:type :heading :title "Project Settings"}
     {:type :subpage :href "edit" :title "Overview" :subpage :overview}
     {:type :subpage :href (routes/v1-org-settings navigation-data) :title "Org Settings"
      :class "project-settings-to-org-settings"}
     {:type :heading :title "Tweaks"}
     {:type :subpage :href "#parallel-builds" :title "Adjust Parallelism" :subpage :parallel-builds}
     {:type :subpage :href "#env-vars" :title "Environment variables" :subpage :env-vars}
     {:type :subpage :href "#experimental" :title "Experimental Settings" :subpage :experimental}
     {:type :heading :title "Test Commands"}
     {:type :subpage :href "#setup" :title "Dependency Commands" :subpage :setup}
     {:type :subpage :href "#tests" :title "Test Commands" :subpage :tests}
     {:type :heading :title "Notifications"}
     {:type :subpage :href "#hooks" :title "Chat Notifications" :subpage :hooks}
     {:type :subpage :href "#webhooks" :title "Webhook Notifications" :subpage :webhooks}
     {:type :subpage :href "#badges" :title "Status Badges" :subpage :badges}
     {:type :heading :title "Permissions"}
     {:type :subpage :href "#checkout" :title "Checkout SSH keys" :subpage :checkout}
     {:type :subpage :href "#ssh" :title "SSH Permissions" :subpage :ssh}
     {:type :subpage :href "#api" :title "API Permissions" :subpage :api}
     {:type :subpage :href "#aws" :title "AWS Permissions" :subpage :aws}
     {:type :heading :title "Continuous Deployment"}
     {:type :subpage :href "#heroku" :title "Heroku Deployment" :subpage :heroku}
     {:type :subpage :href "#aws-codedeploy" :title "AWS CodeDeploy" :subpage :aws-codedeploy}
     {:type :subpage :href "#deployment" :title "Other Deployments" :subpage :deployment}]))

(defn project-settings-menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            subpage (or (:project-settings-subpage app) :overview)]
        (html
          [:div.aside-user {:class (when (= :project-settings (:navigation-point app)) "open")}
           [:header
            [:h5 "Project Settings"]
            [:a.close-menu {:href "./"} ; This may need to change if we drop hashtags from url structure
             (common/ico :fail-light)]]
           [:div.aside-user-options
            (expand-menu-items (project-settings-nav-items app owner) subpage)]])))))

(defn org-settings-nav-items [can-edit? paid?]
  (concat
   [{:type :heading :title "Overview"}
    {:type :subpage :href "#projects" :title "Projects" :subpage :projects}
    {:type :subpage :href "#users" :title "Users" :subpage :users}
    {:type :heading :title "Plan"}]
   (if-not can-edit?
     [{:type :subpage :href "#plan" :text "Choose plan" :subpage :plan}]
     (concat
      [{:type :subpage :title "Adjust containers" :href "#containers" :subpage :containers}
       {:type :subpage :title "Organizations" :href "#organizations" :subpage :organizations}]
      (when paid?
        [{:type :subpage :title "Billing info" :href "#billing" :subpage :billing}
         {:type :subpage :title "Cancel" :href "#cancel" :subpage :cancel}])))))

(defn determine-org-settings-subpage
  "Determines which subpage we should show the user. If they have
   a plan, then we don't want to show them the plan page; if they
   don't have a plan, then we don't want to show them the invoices page."
  [subpage plan org-name]
  (cond (#{:users :projects} subpage)
        subpage

        (and plan
             (pm/can-edit-plan? plan org-name)
             (= subpage :plan))
        :containers

        (and plan
             (not (pm/can-edit-plan? plan org-name))
             (#{:containers :organizations :billing :cancel} subpage))
        :plan

        :else subpage))

(defn org-settings-menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            plan (get-in app state/org-plan-path)
            org-data (get-in app state/org-data-path)
            org-name (:name org-data)
            subpage (determine-org-settings-subpage (:project-settings-subpage app) plan org-name)
            items (org-settings-nav-items (pm/can-edit-plan? plan org-name) (pm/paid? plan))]
        (html
         [:div.aside-user {:class (when (= :org-settings (:navigation-point app)) "open")}
          [:header
           [:h5 "Organization Settings"]
           [:a.close-menu {:href "./"} ; This may need to change if we drop hashtags from url structure
            (common/ico :fail-light)]]
          [:div.aside-user-options
           (expand-menu-items items subpage)]])))))

(defn activity [app owner opts]
  (reify
    om/IDisplayName (display-name [_] "Aside Activity")
    om/IInitState (init-state [_] {:scrollbar-width 0})
    om/IDidMount (did-mount [_] (om/set-state! owner :scrollbar-width (goog.style/getScrollbarWidth)))
    om/IRender
    (render [_]
      (let [slim-aside? (get-in app state/slim-aside-path)
            show-all-branches? (get-in app state/show-all-branches-path)
            projects (get-in app state/projects-path)
            settings (get-in app state/settings-path)]
        (html
         [:nav.aside-left-menu
          (om/build project-settings-menu app)
          (om/build org-settings-menu app)
          (om/build context-menu app)
          [:div.aside-activity.open
           [:div.wrapper {:style {:width (str (+ 210 (om/get-state owner :scrollbar-width)) "px")}}
            [:header
             [:select {:name "toggle-all-branches"
                       :on-change #(raise! owner [:show-all-branches-toggled
                                                  (utils/parse-uri-bool (.. % -target -value))])
                       :value show-all-branches?}
              [:option {:value false} "Your Branch Activity"]
              [:option {:value true} "All Branch Activity" ]]
             [:div.select-arrow [:i.fa.fa-caret-down]]]
            (for [project (sort project-model/sidebar-sort projects)]
              (om/build project-aside
                        {:project project
                         :settings settings}
                        {:react-key (project-model/id project)
                         :opts {:login (:login opts)}}))]]])))))

(defn aside-nav [app owner opts]
  (reify
    om/IDisplayName (display-name [_] "Aside Nav")
    om/IDidMount
    (did-mount [_]
      (utils/tooltip ".aside-item"))
    om/IRender
    (render [_]
      (let [slim-aside? (get-in app state/slim-aside-path)]
        (html
         [:nav.aside-left-nav

          [:a.aside-item.logo  {:title "Home"
                                :data-placement "right"
                                :data-trigger "hover"
                                :href "/"}
           [:div.logomark
            (common/ico :logo)]]

          [:a.aside-item {:on-click #(raise! owner [:user-options-toggled])
                          :data-placement "right"
                          :data-trigger "hover"
                          :title "Account"
                          :class (when (get-in app state/user-options-shown-path)
                                  "open")}
           [:img {:src (gh-utils/make-avatar-url opts)}]
           (:login opts)]

          [:a.aside-item {:title "Documentation"
                          :data-placement "right"
                          :data-trigger "hover"
                          :href "/docs"}
           [:i.fa.fa-copy]
           [:span "Documentation"]]

          [:a.aside-item {:on-click #(raise! owner [:intercom-dialog-raised])
                          :title "Report Bug"
                          :data-placement "right"
                          :data-trigger "hover"

                          :data-bind "tooltip: {title: 'Report Bug', placement: 'right', trigger: 'hover'}, click: $root.raiseIntercomDialog",}
           [:i.fa.fa-bug]
           [:span "Report Bug"]]

          [:a.aside-item
           {:href "https://www.hipchat.com/gjwkHcrD5",
            :target "_blank",
            :data-placement "right"
            :data-trigger "hover"
            :title "Live Support"}
           [:i.fa.fa-comments]
           [:span "Live Support"]]

          [:a.aside-item {:href "/add-projects",
                                       :data-placement "right"
                                       :data-trigger "hover"
                                       :title "Add Projects"}
           [:i.fa.fa-plus-circle]
           [:span "Add Projects"]]

          [:a.aside-item {:data-placement "right"
                          :data-trigger "hover"
                          :title "Changelog"
                          :href "/changelog"}
           [:i.fa.fa-bell]
           [:span "Changelog"]]

          [:a.aside-item.push-to-bottom {:data-placement "right"
                                         :data-trigger "hover"
                                         :title "Expand"
                                         :on-click #(raise! owner [:slim-aside-toggled])}
           (if slim-aside?
             [:i.fa.fa-long-arrow-right]
             (list
              [:i.fa.fa-long-arrow-left]
              [:span "Collapse"]))]])))))

(defn aside [app owner]
  (reify
    om/IDisplayName (display-name [_] "Aside")
    om/IRender
    (render [_]
      (let [user (get-in app state/user-path)
            login (:login user)
            avatar-url (gh-utils/make-avatar-url user)]
        (html
         [:aside.app-aside-left
          (om/build aside-nav app {:opts user})
          (om/build activity app {:opts {:login login}})])))))

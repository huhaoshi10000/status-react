(ns status-im.chat.views.input.input
  (:require-macros [status-im.utils.views :refer [defview]])
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [taoensso.timbre :as log]
            [status-im.accessibility-ids :as id]
            [status-im.chat.constants :as const]
            [status-im.chat.models.input :as input-model]
            [status-im.chat.styles.input.input :as style]
            [status-im.chat.utils :as chat-utils]
            [status-im.chat.views.input.emoji :as emoji]
            [status-im.chat.views.input.parameter-box :as parameter-box]
            [status-im.chat.views.input.input-actions :as input-actions]
            [status-im.chat.views.input.result-box :as result-box]
            [status-im.chat.views.input.suggestions :as suggestions]
            [status-im.chat.views.input.validation-messages :as validation-messages]
            [status-im.components.animation :as anim]
            [status-im.components.react :refer [view
                                                animated-view
                                                text
                                                scroll-view
                                                text-input
                                                icon
                                                touchable-highlight
                                                dismiss-keyboard!]]
            [status-im.i18n :as i18n]
            [status-im.utils.platform :as platform]))

(defn command-view [first? command]
  [touchable-highlight {:on-press #(dispatch [:select-chat-input-command command nil])}
   [view
    [text {:style (style/command first?)
           :font  :roboto-mono}
     (chat-utils/command-name command)]]])

(defview commands-view []
  [commands [:chat :command-suggestions]
   responses [:get-responses]
   requests [:chat :request-suggestions]
   show-suggestions? [:show-suggestions?]]
  [view style/commands-root
   [view style/command-list-icon-container
    [touchable-highlight {:on-press #(do (dispatch [:toggle-chat-ui-props :show-suggestions?])
                                         (dispatch [:set-chat-ui-props {:validation-messages nil}])
                                         (dispatch [:update-suggestions]))}
     [view style/commands-list-icon
      (if show-suggestions?
        [icon :close_gray style/close-commands-list-icon]
        [icon :input_list style/commands-list-icon])]]]
   [scroll-view {:horizontal                     true
                 :showsHorizontalScrollIndicator false
                 :keyboardShouldPersistTaps      :always}
    (let [requests-names (map :type requests)
          all-commands (merge (into {} commands) (select-keys responses requests-names))
          all-commands-indexed (map-indexed vector (vals all-commands))]
      [view style/commands
       (for [[index command] all-commands-indexed]
         ^{:key (str "command-" index)}
         [command-view (= index 0) command])])]])

(defn- basic-text-input [_]
  (let [input-text           (subscribe [:chat :input-text])
        command              (subscribe [:selected-chat-command])
        sending-in-progress? (subscribe [:chat-ui-props :sending-in-progress?])
        input-focused?       (subscribe [:chat-ui-props :input-focused?])
        input-ref            (atom nil)]
    (fn [{:keys [set-layout-height set-container-width height single-line-input?]}]
      [text-input
       {:ref                    #(when %
                                   (dispatch [:set-chat-ui-props {:input-ref %}])
                                   (reset! input-ref %))
        :accessibility-label    id/chat-message-input
        :multiline              (not single-line-input?)
        :default-value          (or @input-text "")
        :editable               (not @sending-in-progress?)
        :blur-on-submit         false
        :on-focus               #(dispatch [:set-chat-ui-props {:input-focused? true
                                                                :show-emoji?    false}])
        :on-blur                #(do (dispatch [:set-chat-ui-props {:input-focused? false}]))
        :on-submit-editing      (fn [e]
                                  (if single-line-input?
                                    (dispatch [:send-current-message])
                                    (.setNativeProps @input-ref (clj->js {:text (str @input-text "\n")}))))
        :on-layout              (fn [e]
                                  (set-container-width (.-width (.-layout (.-nativeEvent e)))))
        :on-change              (fn [e]
                                  (let [native-event (.-nativeEvent e)
                                        text         (.-text native-event)]
                                    (when-not single-line-input?
                                      (let [height (.. native-event -contentSize -height)]
                                        (set-layout-height height)))
                                    (when (not= text @input-text)
                                      (dispatch [:set-chat-input-text text])
                                      (if @command
                                        (do
                                          (dispatch [:load-chat-parameter-box (:command @command)])
                                          (dispatch [:set-chat-ui-props {:validation-messages nil}]))
                                        (do
                                          (dispatch [:set-chat-input-metadata nil])
                                          (dispatch [:set-chat-ui-props {:result-box          nil
                                                                         :validation-messages nil}]))))))
        :on-content-size-change (when (and (not @input-focused?)
                                           (not single-line-input?))
                                  #(let [h (-> (.-nativeEvent %)
                                               (.-contentSize)
                                               (.-height))]
                                     (set-layout-height h)))
        :on-selection-change    #(let [s (-> (.-nativeEvent %)
                                             (.-selection))]
                                   (when (and (= (.-end s) (+ 2 (count (get-in @command [:command :name]))))
                                              (get-in @command [:command :sequential-params]))
                                     (dispatch [:chat-input-focus :seq-input-ref])))
        :style                  (style/input-view height single-line-input?)
        :placeholder-text-color style/color-input-helper-placeholder
        :auto-capitalize        :sentences}])))

(defn- invisible-input [{:keys [set-layout-width value]}]
  (let [input-text    (subscribe [:chat :input-text])]
    [text {:style     style/invisible-input-text
           :on-layout #(let [w (-> (.-nativeEvent %)
                                   (.-layout)
                                   (.-width))]
                         (set-layout-width w))}
     (or @input-text "")]))

(defn- input-helper [_]
  (let [input-text (subscribe [:chat :input-text])]
    (fn [{:keys [command width]}]
      (when-not (get-in command [:command :sequential-params])
        (let [real-args (remove str/blank? (:args command))]
          (when-let [placeholder (cond
                                   (#{const/command-char const/bot-char} @input-text)
                                   (i18n/label :t/type-a-command)

                                   (and command (empty? real-args))
                                   (get-in command [:command :params 0 :placeholder])

                                   (and command
                                        (= (count real-args) 1)
                                        (input-model/text-ends-with-space? @input-text))
                                   (get-in command [:command :params 1 :placeholder]))]
            [text {:style (style/input-helper-text width)}
             placeholder]))))))

(defn get-options [type]
  (case (keyword type)
    :phone {:keyboard-type "phone-pad"}
    :password {:secure-text-entry true}
    :number {:keyboard-type "numeric"}
    nil))

(defn- seq-input [_]
  (let [command              (subscribe [:selected-chat-command])
        arg-pos              (subscribe [:current-chat-argument-position])
        seq-arg-input-text   (subscribe [:chat :seq-argument-input-text])
        sending-in-progress? (subscribe [:chat-ui-props :sending-in-progress?])]
    (fn [{:keys [command-width]}]
      (when (get-in @command [:command :sequential-params])
        (let [{:keys [placeholder hidden type]} (get-in @command [:command :params @arg-pos])]
          [text-input (merge {:ref               #(dispatch [:set-chat-ui-props {:seq-input-ref %}])
                              :style             (style/seq-input-text command-width)
                              :default-value     (or @seq-arg-input-text "")
                              :on-change-text    #(do (dispatch [:set-chat-seq-arg-input-text %])
                                                      (dispatch [:set-chat-ui-props {:validation-messages nil}]))
                              :placeholder       placeholder
                              :blur-on-submit    false
                              :editable          (not @sending-in-progress?)
                              :on-focus          #(dispatch [:set-chat-ui-props {:show-emoji? false}])
                              :on-submit-editing (fn []
                                                   (when-not (str/blank? @seq-arg-input-text)
                                                     (dispatch [:send-seq-argument]))
                                                   (js/setTimeout
                                                     #(dispatch [:chat-input-focus :seq-input-ref])
                                                     100))}
                             (get-options type))])))))

(defn input-view [_]
  (let [component            (r/current-component)
        set-layout-width     #(r/set-state component {:width %})
        set-layout-height    #(r/set-state component {:height %})
        set-container-width  #(r/set-state component {:container-width %})
        command              (subscribe [:selected-chat-command])]
    (r/create-class
      {:reagent-render
       (fn [{:keys [anim-margin single-line-input?]}]
         (let [{:keys [width height container-width]} (r/state component)
               command @command]
           [animated-view {:style (style/input-root height anim-margin)}
            [invisible-input {:set-layout-width set-layout-width}]
            [basic-text-input {:set-layout-height   set-layout-height
                               :set-container-width set-container-width
                               :height              height
                               :single-line-input?  single-line-input?}]
            [input-helper {:command command
                           :width   width}]
            [seq-input {:command-width width}]
            (if-not command
              [touchable-highlight
               {:on-press #(do (dispatch [:toggle-chat-ui-props :show-emoji?])
                               (dismiss-keyboard!))}
               [view
                [icon :smile style/input-emoji-icon]]]
              (when-not single-line-input?
                [touchable-highlight
                 {:on-press #(do (dispatch [:set-chat-input-text nil])
                                 (dispatch [:set-chat-input-metadata nil])
                                 (dispatch [:set-chat-ui-props {:result-box          nil
                                                                :validation-messages nil}])
                                 (dispatch [:clear-seq-arguments]))}
                 [view style/input-clear-container
                  [icon :close_gray style/input-clear-icon]]]))]))})))

(defview input-container [{:keys [anim-margin]}]
  [command-completion [:command-completion]
   selected-command [:selected-chat-command]
   input-text [:chat :input-text]
   seq-arg-input-text [:chat :seq-argument-input-text]
   result-box [:chat-ui-props :result-box]]
  (let [single-line-input? (:singleLineInput result-box)]
    [view style/input-container
     [input-view {:anim-margin        anim-margin
                  :single-line-input? single-line-input?}]
     (if (:actions result-box)
       [input-actions/input-actions-view]
       (when (and (not (str/blank? input-text))
                  (or (not selected-command)
                      (some #{:complete :less-than-needed} [command-completion])))
         [touchable-highlight {:on-press #(if (get-in selected-command [:command :sequential-params])
                                            (do
                                              (when-not (str/blank? seq-arg-input-text)
                                                (dispatch [:send-seq-argument]))
                                              (js/setTimeout
                                                (fn [] (dispatch [:chat-input-focus :seq-input-ref]))
                                                100))
                                            (dispatch [:send-current-message]))}
          [view style/send-message-container
           [icon :arrow_top style/send-message-icon]]]))]))

(defn container []
  (let [margin                (subscribe [:chat-input-margin])
        show-emoji?           (subscribe [:chat-ui-props :show-emoji?])
        input-text            (subscribe [:chat :input-text])
        anim-margin           (anim/create-value 10)
        container-anim-margin (anim/create-value 16)
        bottom-anim-margin    (anim/create-value 14)]
    (r/create-class
      {:component-did-mount
       (fn []
         (when-not (str/blank? @input-text)
           (.setValue anim-margin 0)
           (.setValue container-anim-margin 8)
           (.setValue bottom-anim-margin 8)))
       :component-did-update
       (fn [component]
         (let [{:keys [text-empty?]} (reagent.core/props component)]
           (let [to-anim-value           (if text-empty? 10 0)
                 to-container-anim-value (if text-empty? 16 8)
                 to-bottom-anim-value    (if text-empty? 14 8)]
             (anim/start
               (anim/timing anim-margin {:toValue  to-anim-value
                                         :duration 100}))
             (anim/start
               (anim/timing container-anim-margin {:toValue  to-container-anim-value
                                                   :duration 100}))
             (anim/start
               (anim/timing bottom-anim-margin {:toValue  to-bottom-anim-value
                                                :duration 100})))))

       :reagent-render
       (fn []
         [view style/input-container-view
          [parameter-box/parameter-box-view]
          [result-box/result-box-view]
          [suggestions/suggestions-view]
          [validation-messages/validation-messages-view]
          [view {:style     (style/root @margin)
                 :on-layout #(let [h (-> (.-nativeEvent %)
                                         (.-layout)
                                         (.-height))]
                               (when (> h 0)
                                 (dispatch [:set-chat-ui-props {:input-height h}])))}
           [animated-view {:style (style/container container-anim-margin bottom-anim-margin)}
            (when (str/blank? @input-text)
              [commands-view])
            [input-container {:anim-margin anim-margin}]]
           (when @show-emoji?
             [emoji/emoji-view])]])})))

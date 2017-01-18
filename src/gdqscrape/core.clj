(ns gdqscrape.core
  (:require [clojure.edn            :as edn]
            [clojure.string         :as str]
            [clj-http.client        :as client]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [net.cgrand.enlive-html :as html])
  (:import (java.util Date)
           (java.text SimpleDateFormat)))

(def base-url      "https://gamesdonequick.com/tracker/")
(def donors-url    "donors/?page=")
(def donations-url "donations/?page=")

(def date-format (SimpleDateFormat. "dd/MM/yyyy kk:mm:ss ZZZZZ"))

(def anonymous '("\n(Anonymous)\n"))
(def donor-re  #"^/tracker/donor/(\d+)$")

(def total-count-re #"^\n\$([\d\.,]+) \((\d+)\)$\n")
(def max-avg-re     #"^\n\$([\d\.,]+)/\$([\d\.,]+)$\n")

(defonce donors    (atom {}))
(defonce donations (atom {}))
(defonce comments  (atom {}))

(defn text [dom]
  (-> dom :content first))

(defn parse-double [str]
  (-> str (str/replace "," "") Double/parseDouble))

(defn parse-date [dom]
  (->> dom text (.parse date-format)))

(defn select-time [dom]
  (html/select dom [:span.datetime]))

(defn select-link [dom]
  (html/select dom [:a]))

(defn parse-time [dom]
  (-> dom select-time first parse-date))

(defn parse-amount* [dom]
  (-> dom text (.substring 1) parse-double))

(defn parse-amount [dom]
  (-> dom select-link first parse-amount*))

(defn parse-rows* [dom]
  (-> dom (html/select [:div.container-fluid :table :tr]) rest))

(defn parse-rows [parse dom]
  (for [row (parse-rows* dom)]
    (-> row (html/select [:td]) parse)))

(defn parse-donor-link [dom]
  (let [a (-> dom select-link first)]
    [(->> a :content first)
     (->> a :attrs :href (re-matches donor-re) second Integer/parseInt)]))

(defn parse-donor [[name col-2 col-3]]
  (let [[_ total count] (re-matches total-count-re (text col-2))
        [_ max   avg]   (re-matches max-avg-re     (text col-3))]
    {:name  (parse-donor-link name)
     :count (Integer/parseInt count)
     :total (parse-double total)
     :max   (parse-double max)
     :avg   (parse-double avg)}))

(defn parse-donation [[name time amount comment?]]
  {:name     (if (= (:content name) anonymous) :anonymous (parse-donor-link name))
   :time     (parse-time   time)
   :amount   (parse-amount amount)
   :comment? (-> comment? text (= "\nYes\n"))})

(def parse-donors    (partial parse-rows #'parse-donor))
(def parse-donations (partial parse-rows #'parse-donation))

(defn scrape [url]
  (-> url client/get :body html/html-snippet))

(defn scrape-page [parse target url-part page]
  (println (str url-part page))
  (swap! target assoc page (-> base-url (str url-part page) scrape parse)))

(def scrape-donors-page    (partial scrape-page parse-donors    donors    donors-url))
(def scrape-donations-page (partial scrape-page parse-donations donations donations-url))

(defn scrape-all [scrape-page total]
  (dotimes [n total]
    (scrape-page (inc n))))

(def scrape-all-donors (partial scrape-all scrape-donors-page 1761))

(defn parse-comment [time amount comment]
  {:time    (parse-date    time)
   :amount  (parse-amount* amount)
   :comment (text          comment)})

(defn scrape-comments-page [donor-id]
  (let [comment-rows (-> (str base-url "donor/" donor-id "/?comments")
                         scrape parse-rows*)]
    (swap! comments assoc donor-id
           (map parse-comment
                (select-time comment-rows)
                (select-link comment-rows)
                (html/select comment-rows [[:td (html/attr= :colspan "3")]])))))

(defn all-donor-ids []
  (->> @donors vals flatten
       (map    :name)
       (filter vector?)
       (map    second)
       (apply  hash-set)))

(defn scrape-all-comments []
  (let [ids (all-donor-ids)
        len (str (count ids))]
    (doseq [donor-id ids]
      (when-not (contains? @comments donor-id)
        (println (str "donor/" donor-id " (" (count @comments) " / " len ")"))
        (scrape-comments-page donor-id)))))

(def donors-lookup (->> @donors vals flatten
                        (map    :name)
                        (filter vector?)
                        (map    (comp vec reverse))
                        (into   {})))

(def comments-list (-> (fn [[donor-id all-comments]]
                         (let [donor (-> donor-id donors-lookup)]
                           (map #(assoc % :donor donor) all-comments)))
                       (map @comments)
                       (flatten)
                       (->> (sort-by :time))))

(defn filter-comments [match]
  (filter #(.contains (.toLowerCase (:comment %)) match) comments-list))

(defn xlsx-row [{:keys [donor time amount comment]}]
  [donor time amount comment])

(defn save-xlsx [data filename]
  (->> (map xlsx-row data)
       (into [["Donor" "Time" "Amount" "Comment"]])
       (spreadsheet/create-workbook "GDQ Donations")
       (spreadsheet/save-workbook! (str filename ".xlsx"))))

(defn txt-row [{:keys [donor time amount comment]}]
  (str donor " - " amount "\n" time "\n" comment))

(defn save-txt [data filename]
  (->> data
       (map txt-row)
       (str/join "\r\n\r\n")
       (spit filename)))

(defn save [value filename]
  (->> value pr-str (spit filename))
  nil)

(defn load [value-atom filename]
  (->> filename slurp edn/read-string (reset! value-atom))
  nil)

(comment (future (scrape-all-donors))
         (future (scrape-all-comments))
         (save @donors   "donors.edn")
         (save @comments "comments.edn")
         (load donors    "donors.edn")
         (load comments  "comments.edn")
         (count @donors)
         (count @comments)
         (save-txt (filter-comments "darn") "darn.txt"))

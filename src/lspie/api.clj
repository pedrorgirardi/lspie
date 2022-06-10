(ns lspie.api
  (:require
   [clojure.string :as str]
   [clojure.data.json :as json])

  (:import
   (java.io
    Reader
    Writer

    InputStream
    OutputStream

    InputStreamReader
    OutputStreamWriter

    BufferedReader
    BufferedWriter)

   (java.util.concurrent
    Executors
    ExecutorService))

  (:gen-class))

(defn response [request result]
  (merge (select-keys request [:id :jsonrpc]) {:result result}))


;; --------------------------------


(defmulti handle :method)

(defmethod handle :default [jsonrpc]
  (response jsonrpc nil))


;; --------------------------------


(defn header [chars]
  (->> (str/split-lines (apply str chars))
    (map
      (fn [line]
        (let [[k v] (str/split line #":")]
          (cond
            (= (str/lower-case k) "content-length")
            [:Content-Length (parse-long (str/trim v))]

            :else
            [k v]))))
    (into {})))

(defn reads [^Reader reader len]
  (let [^"[C" buffer (make-array Character/TYPE len)]
    (loop [off 0]
      (let [off' (.read reader buffer off (- len off))]
        (if (< off len)
          (String. buffer)
          (recur off'))))))

(defn buffered-reader ^BufferedReader [^InputStream in]
  (BufferedReader. (InputStreamReader. in "UTF-8")))

(defn buffered-writer ^BufferedWriter [^OutputStream out]
  (BufferedWriter. (OutputStreamWriter. out "UTF-8")))

(defn message
  "The base protocol consists of a header and a content part (comparable to HTTP).

  The header and content part are separated by a \\r\\n.

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#baseProtocol"
  [content]
  (let [s (json/write-str content)]
    (format "Content-Length: %s\r\n\r\n%s" (alength (.getBytes s)) s)))

(defn write [^Writer writer content]
  (doto writer
    (.write (message content))
    (.flush))

  nil)

(defn start [{:keys [reader writer trace]}]
  (let [trace (or trace identity)

        initial-state {:chars []
                       :newline# 0
                       :return# 0}

        ^ExecutorService ne (Executors/newFixedThreadPool 4)]

    (loop [{:keys [chars newline# return#] :as state} initial-state]
      (cond
        ;; Two consecutive return & newline characters - parse header and content.
        (and (= return# 2) (= newline# 2))
        (let [{:keys [Content-Length] :as header} (header chars)

              jsonrpc-str (reads reader Content-Length)

              ;; Let the client know that the message, request or notification, was decoded.
              trace-decoded (fn [jsonrpc]
                              (trace {:status :message-decoded
                                      :header header
                                      :content jsonrpc}))

              {jsonrpc-id :id :as jsonrpc} (doto (json/read-str jsonrpc-str :key-fn keyword) trace-decoded)

              ;; Let the client know that the message, request or notification, was handled.
              trace-handled (fn [handled]
                              (trace {:status :message-handled
                                      :header header
                                      :content jsonrpc
                                      :handled handled}))]

          ;; > Every processed request must send a response back to the sender of the request.
          ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#requestMessage
          ;;
          ;; > A processed notification message must not send a response back. They work like events.
          ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#notificationMessage

          (cond
            ;; It's assumed that only requests have ID.
            jsonrpc-id
            (let [handled (doto (handle jsonrpc) trace-handled)

                  response-str (message handled)]

              (doto writer
                (.write response-str)
                (.flush ))

              ;; Let the client know that a response was sent for the request.
              (trace {:status :response-sent
                      :response response-str}))

            ;; Execute a notification handler in a separate thread.
            :else
            (.execute ne
              (fn []
                (doto (handle jsonrpc) trace-handled))))

          (recur initial-state))

        :else
        (let [c (.read reader)]
          (when-not (= c -1)
            (recur (merge state {:chars (conj chars (char c))}
                     (cond
                       (= (char c) \newline)
                       {:newline# (inc newline#)}

                       (= (char c) \return)
                       {:return# (inc return#)}

                       ;; Reset return & newline counter when next character is part of the header.
                       :else
                       {:return# 0
                        :newline# 0})))))))))

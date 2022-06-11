(ns lspie.api
  "API to aid in the development of a language server.

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/"
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

;; Implementation of methods defined in the specification.
;;
;; `handle` dispatch value is a method name string as defined in the specification,
;; and it gets passed a JSON-RPC content.
;;
;; JSON-RPC content is converted to Clojure data with keyword keys.
;; (https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#contentPart)
;;
;; Examples:
;;
;; (defmethod lsp/handle "initialize" [request] ...)
;; (defmethod lsp/handle "textDocument/didOpen" [notification] ...)
(defmulti handle :method)

(defn header
 "The header part consists of header fields.
  Each header field is comprised of a name and a value,
  separated by ‘: ‘ (a colon and a space).

  The structure of header fields conform to the HTTP semantic.

  Each header field is terminated by ‘\\r\\n’.

  Considering the last header field and the overall header itself are each terminated with ‘\\r\\n’,
  and that at least one header is mandatory,
  this means that two ‘\\r\\n’ sequences always immediately precede the content part of a message.

  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#headerPart"
  [chars]
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

(defn write
  "Write a message to a client e.g. Visual Studio Code.

  `content` is encoded in a JSON-RPC message.

  Holds the monitor of `writer`.

  Returns message."
  ^String [^Writer writer content]
  (let [s (message content)]
    (locking writer
      (doto writer
        (.write s)
        (.flush)))

    s))

(defn response [request result]
  (merge (select-keys request [:id :jsonrpc]) {:result result}))

(defn start [{:keys [reader writer trace]}]
  (let [trace (or trace identity)

        initial-state {:chars []
                       :newline# 0
                       :return# 0}

        ^ExecutorService re (Executors/newSingleThreadExecutor)

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
            ;; Execute request handler in an event-loop.
            ;; (It's assumed that only requests have ID.)
            jsonrpc-id
            (.execute re
              (fn []
                (let [handled (doto (handle jsonrpc) trace-handled)]
                  (write writer handled))))

            ;; Execute notification handler in a separate thread.
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

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

    StringWriter

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
;; JSON-RPC content encodes a Request or a Notification:
;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#requestMessage
;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#notificationMessage
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

(defn read-content [{:keys [in header trace]}]
  (let [{content-length :Content-Length} header

        ^"[B" buffer (byte-array content-length)

        ;; > Reads the requested number of bytes from the input stream into the given byte array.
        ;;   This method blocks until len bytes of input data have been read, end of stream is detected, or an exception is thrown.
        ;;   The number of bytes actually read, possibly zero, is returned.

        size (.readNBytes ^InputStream in buffer 0 content-length)]

    ;; Let the client know that the message, request or notification, was read.
    (trace
      {:status :read
       :header header
       :read size})

    (String. buffer "UTF-8")))

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

(defn error-response [request error]
  ;; See https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#responseError
  (merge (select-keys request [:id :jsonrpc]) {:error error}))

(defn start [{:keys [in out trace]}]
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
        (let [header (header chars)

              jsonrpc-str (read-content {:in in
                                         :header header
                                         :trace trace})

              ;; Let the client know that the message, request or notification, was decoded.
              tdecoded (fn [jsonrpc]
                         (trace {:status :decoded
                                 :header header
                                 :content jsonrpc}))

              {jsonrpc-id :id :as jsonrpc} (try
                                             (doto (json/read-str jsonrpc-str :key-fn keyword) tdecoded)
                                             (catch Exception ex
                                               (trace {:status :decode-error
                                                       :header header
                                                       :content jsonrpc-str
                                                       :error ex})

                                               (throw (ex-info "Failed to decode JSON-RPC content."
                                                        {:header header
                                                         :content jsonrpc-str}
                                                        ex))))

              ;; Let the client know that the message, request or notification, was handled.
              thandled (fn [handled]
                         (trace {:status :handled
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
                (let [handled (doto (handle jsonrpc) thandled)]
                  (write out handled))))

            ;; Execute notification handler in a separate thread.
            :else
            (.execute ne
              (fn []
                (doto (handle jsonrpc) thandled))))

          (recur initial-state))

        :else
        (let [c (.read in)]
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


(comment

  (require 'clojure.java.io)

  (def filepath "/Users/pedro/Developer/Nightincode/src/nightincode/server.clj")

  (.length (clojure.java.io/file filepath))
  ;; => 8679

  (def len 10000)

  (def stream (java.io.ByteArrayInputStream. (.getBytes (slurp filepath))))

  (def  buffer (byte-array len))

  (def offset (atom 0))

  (reset! offset (.read stream buffer @offset (- len @offset)))

  (read-content
    {:in stream
     :header {:Content-Length 27997}
     :trace tap>})

  )

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

(defn error-response [request error]
  ;; See https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#responseError
  (merge (select-keys request [:id :jsonrpc]) {:error error}))

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
        (let [theader (fn [header]
                        (trace {:status :read-header
                                :header header}))

              {:keys [Content-Length] :as header} (doto (header chars) theader)

              jsonrpc-str (reads reader Content-Length)

              ;; Let the client know that the message, request or notification, was decoded.
              tdecoded (fn [jsonrpc]
                         (trace {:status :content-decoded
                                 :header header
                                 :content jsonrpc}))

              {jsonrpc-id :id :as jsonrpc} (try
                                             (doto (json/read-str jsonrpc-str :key-fn keyword) tdecoded)
                                             (catch Exception ex
                                               (trace {:status :content-decode-failed
                                                       :header header
                                                       :content jsonrpc-str
                                                       :error ex})

                                               (throw (ex-info "Failed to decode JSON-RPC content."
                                                        {:header header
                                                         :content jsonrpc-str}
                                                        ex))))

              ;; Let the client know that the message, request or notification, was handled.
              thandled (fn [handled]
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
                (let [handled (doto (handle jsonrpc) thandled)]
                  (write writer handled))))

            ;; Execute notification handler in a separate thread.
            :else
            (.execute ne
              (fn []
                (doto (handle jsonrpc) thandled))))

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


(comment

  (reads
    (java.io.StringReader.
      (slurp "/Users/pedro/Developer/Nightincode/src/nightincode/server.clj"))
    29199)

  (require 'clojure.java.io)

  (.length (clojure.java.io/file "/Users/pedro/Developer/Nightincode/src/nightincode/server.clj"))
  ;; => 28009

  (reads
    (java.io.StringReader.
      (slurp "/Users/pedro/Developer/Nightincode/src/nightincode/server.clj"))
    28009)


  (let [f (java.io.File. "/Users/pedro/Developer/Nightincode/src/nightincode/server.clj")
        ary (byte-array (.length f))
        is (java.io.FileInputStream. f)]
    (.read is ary)
    (.close is)
    (String. ary))


  "(ns nightincode.server\n  (:require\n   [clojure.core.server :refer [start-server]]\n   [clojure.java.io :as io]\n   [clojure.data.json :as json]\n   [clojure.string :as str]\n   [clojure.tools.logging :as log]\n   [clojure.java.shell :as shell]\n\n   [clj-kondo.core :as clj-kondo]\n   [lspie.api :as lsp])\n\n  (:import\n   (java.util.concurrent\n    ScheduledExecutorService\n    Executors\n    TimeUnit)\n\n   (java.io\n    Writer)\n\n   (java.net\n    ServerSocket\n    URI))\n\n  (:gen-class))\n\n;; The compiler will emit warnings when reflection is needed\n;; to resolve Java method calls or field accesses.\n(set! *warn-on-reflection* true)\n\n\n(def default-clj-kondo-config\n  {:analysis\n   {:arglists true\n    :locals true\n    :keywords true\n    :java-class-usages true}\n   :output\n   {:canonical-paths true}})\n\n(defn text-document-uri [textDocument]\n  (:uri textDocument))\n\n(defn text-document-path [textDocument]\n  (.getPath (URI. (text-document-uri textDocument))))\n\n(defn text-document-text [textDocument]\n  (:text textDocument))\n\n(defn filepath-uri ^URI [filepath]\n  (->  (io/file filepath) .toPath .toUri))\n\n(defn clojuredocs\n  \"ClojureDocs.org database.\"\n  []\n  (json/read (io/reader (io/resource \"clojuredocs.json\")) :key-fn keyword))\n\n(defn clojuredocs-completion []\n  (into []\n    (comp\n      (filter\n        (fn [m]\n          (= (:ns m) \"clojure.core\")))\n      (map\n        (fn [{var-ns :ns\n              var-name :name\n              var-args :arglists\n              var-doc :doc :as m}]\n          (let [completion-item-kind {\"var\" 6\n                                      \"function\" 3\n                                      \"macro\" 3}\n\n                arglists (str/join \" \" (map #(str \"[\" % \"]\") var-args))\n\n                detail (format \"%s/%s %s\" var-ns var-name arglists)\n                detail (if (str/blank? var-doc)\n                         detail\n                         (format \"%s\\n\\n%s\" detail var-doc))]\n\n            {:label var-name\n             :kind (completion-item-kind (:type m))\n             :detail detail}))))\n    (:vars (clojuredocs))))\n\n(def clojuredocs-completion-delay\n  (delay (clojuredocs-completion)))\n\n\n;; ---------------------------------------------------------\n\n\n(defn analyze\n  \"Analyze Clojure/Script forms with clj-kondo.\n\n  `uri` is used to report the filename.\"\n  [{:keys [uri text config]}]\n  (with-in-str text\n    (clj-kondo/run!\n      {:lint [\"-\"]\n       :filename (.getPath (URI. uri))\n       :config (or config default-clj-kondo-config)})))\n\n(defn index-V\n  \"Index Var definitions and usages by symbol and row.\"\n  [analysis]\n  (let [index {;; Definitions indexed by name.\n               :nightincode/IVD {}\n\n               ;; Definitions indexed by row.\n               :nightincode/IVD_ {}\n\n               ;; Usages indexed by name.\n               :nightincode/IVU {}\n\n               ;; Usages indexed by row.\n               :nightincode/IVU_ {}}\n\n        ;; -- Usage index\n\n        index (reduce\n                (fn [index usage]\n                  (let [{var-namespace :to\n                         var-name :name\n                         var-name-row :name-row} usage\n\n                        sym (symbol (str var-namespace) (str var-name))\n\n                        index (update-in index [:nightincode/IVU sym] (fnil conj #{}) usage)\n\n                        index (update-in index [:nightincode/IVU_ var-name-row] (fnil conj []) usage)]\n\n                    index))\n                index\n                (:var-usages analysis))\n\n\n        ;; -- Definition index\n\n        index (reduce\n                (fn [index definition]\n                  (let [{var-namespace :ns\n                         var-name :name\n                         var-name-row :name-row} definition\n\n                        sym (symbol (str var-namespace) (str var-name))\n\n                        index (update-in index [:nightincode/IVD sym] (fnil conj #{}) definition)\n\n                        index (update-in index [:nightincode/IVD_ var-name-row] (fnil conj []) definition)]\n\n                    index))\n                index\n                (:var-definitions analysis))]\n\n    index))\n\n(defn index-K\n  \"Index keyword definitions (reg) and usages by keyword and row.\"\n  [analysis]\n  (let [index {;; Definitions indexed by keyword.\n               :nightincode/IKD {}\n\n               ;; Definitions indexed by row.\n               :nightincode/IKD_ {}\n\n               ;; Usages indexed by keyword.\n               :nightincode/IKU {}\n\n               ;; Usages indexed by row.\n               :nightincode/IKU_ {}}\n\n        index (reduce\n                (fn [index keyword-analysis]\n                  (let [{keyword-namespace :ns\n                         keyword-name :name\n                         keyword-row :row\n                         keyword-reg :reg} keyword-analysis\n\n                        k (if keyword-namespace\n                            (keyword (str keyword-namespace) keyword-name)\n                            (keyword keyword-name))]\n\n                    (cond\n                      keyword-reg\n                      (-> index\n                        (update-in [:nightincode/IKD k] (fnil conj #{}) keyword-analysis)\n                        (update-in [:nightincode/IKD_ keyword-row] (fnil conj []) keyword-analysis))\n\n                      :else\n                      (-> index\n                        (update-in [:nightincode/IKU k] (fnil conj #{}) keyword-analysis)\n                        (update-in [:nightincode/IKU_ keyword-row] (fnil conj []) keyword-analysis)))))\n                index\n                (:keywords analysis))]\n\n    index))\n\n\n;; -- Indexes\n\n(defn IVD\n  \"Var definitions indexed by symbol.\"\n  [index]\n  (:nightincode/IVD index))\n\n(defn IVD_\n  \"Var definitions indexed by row.\n\n  Note: row is not zero-based.\"\n  [index]\n  (:nightincode/IVD_ index))\n\n(defn IVU\n  \"Var usages indexed by symbol.\"\n  [index]\n  (:nightincode/IVU index))\n\n(defn IVU_\n  \"Var usages indexed by row.\n\n  Note: row is not zero-based.\"\n  [index]\n  (:nightincode/IVU_ index))\n\n(defn IKD\n  \"Keyword definitions indexed by keyword.\"\n  [index]\n  (:nightincode/IKD index))\n\n(defn IKD_\n  \"Keyword definitions indexed by row.\n\n  Note: row is not zero-based.\"\n  [index]\n  (:nightincode/IKD_ index))\n\n(defn IKU\n  \"Keyword usages indexed by keyword.\"\n  [index]\n  (:nightincode/IKU index))\n\n(defn IKU_\n  \"Keyword usages indexed by row.\n\n  Note: row is not zero-based.\"\n  [index]\n  (:nightincode/IKU_ index))\n\n\n;; -- Queries\n\n(defn ?VD_\n  \"Returns Var definition at location, or nil.\"\n  [index [row col]]\n  (reduce\n    (fn [_ {:keys [name-col name-end-col] :as var-definition}]\n      (when (<= name-col col name-end-col)\n        (reduced var-definition)))\n    nil\n    ((IVD_ index) row)))\n\n(defn ?VU_\n  \"Returns Var usage at location, or nil.\"\n  [index [row col]]\n  (reduce\n    (fn [_ {:keys [name-col name-end-col] :as var-usage}]\n      (when (<= name-col col name-end-col)\n        (reduced var-usage)))\n    nil\n    ((IVU_ index) row)))\n\n(defn ?KD_\n  \"Returns keyword definition at location, or nil.\"\n  [index [row col]]\n  (reduce\n    (fn [_ {k-col :col\n            k-end-col :end-col :as keyword-definition}]\n      (when (<= k-col col k-end-col)\n        (reduced keyword-definition)))\n    nil\n    ((IKD_ index) row)))\n\n(defn ?KU_\n  \"Returns keyword usage at location, or nil.\"\n  [index [row col]]\n  (reduce\n    (fn [_ {k-col :col\n            k-end-col :end-col :as keyword-usage}]\n      (when (<= k-col col k-end-col)\n        (reduced keyword-usage)))\n    nil\n    ((IKU_ index) row)))\n\n(defn ?T_\n  \"Returns T at location, or nil.\n\n  Where T is one of:\n   - Namespace definition\n   - Namespace usages\n   - Var definition\n   - Var usage\n   - Local definition\n   - Local usage\n   - Keyword definition\n   - Keyword usage\"\n  [index row+col]\n  (reduce\n    (fn [_ k]\n      (case k\n        :nightincode/VD\n        (when-let [var-definition (?VD_ index row+col)]\n          (reduced (with-meta var-definition {:nightincode/TT :nightincode/VD\n                                              :nightincode/row+col row+col})))\n\n        :nightincode/VU\n        (when-let [var-usage (?VU_ index row+col)]\n          (reduced (with-meta var-usage {:nightincode/TT :nightincode/VU\n                                         :nightincode/row+col row+col})))\n\n        :nightincode/KU\n        (when-let [keyword-usage (?KU_ index row+col)]\n          (reduced (with-meta keyword-usage {:nightincode/TT :nightincode/KU\n                                             :nightincode/row+col row+col})))\n\n        :nightincode/KD\n        (when-let [keyword-definition (?KD_ index row+col)]\n          (reduced (with-meta keyword-definition {:nightincode/TT :nightincode/KD\n                                                  :nightincode/row+col row+col})))\n\n        nil))\n    nil\n    [:nightincode/VU\n     :nightincode/VD\n     :nightincode/LD\n     :nightincode/LU\n     :nightincode/KU\n     :nightincode/KD]))\n\n(defn TT [T]\n  (:nightincode/TT (meta T)))\n\n(defn VD-ident\n  \"Var definition identity symbol.\"\n  [{:keys [ns name]}]\n  (symbol (str ns) (str name)))\n\n(defn VU-ident\n  \"Var usage identity symbol.\"\n  [{:keys [to name]}]\n  (symbol (str to) (str name)))\n\n\n(defn V-location\n  \"Returns a LSP Location for a Var definition or usage.\n\n  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#location\"\n  [{:keys [filename\n           name-row\n           name-end-row\n           name-col\n           name-end-col]}]\n  {:uri (.toString (filepath-uri filename))\n   :range\n   {:start\n    {:line (dec name-row)\n     :character (dec name-col)}\n    :end\n    {:line (dec name-end-row)\n     :character (dec name-end-col)}}})\n\n;; ---------------------------------------------------------\n\n;; -- Functions to read and write from and to state\n\n(def state-ref (atom nil))\n\n(defn _writer ^Writer [state]\n  (:nightincode/writer state))\n\n(defn _repl-port [state]\n  (when-let [^ServerSocket server-socket (:nightincode/repl-server-socket state)]\n    (.getLocalPort server-socket)))\n\n(defn _text-document-index [state textDocument]\n  (get-in state [:nightincode/index (text-document-uri textDocument)]))\n\n(defn !index-document [state {:keys [uri analysis]}]\n  (let [index-V (index-V analysis)\n        index-K (index-K analysis)\n\n        index (merge index-V index-K)\n\n        state (update-in state [:nightincode/index uri] merge index)]\n\n    state))\n\n\n;; ---------------------------------------------------------\n\n\n(defmethod lsp/handle \"initialize\" [request]\n\n  ;; The initialize request is sent as the first request from the client to the server.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#serverCapabilities\n\n  (swap! state-ref assoc :LSP/InitializeParams (:params request))\n\n  (lsp/response request\n    {:capabilities\n     {;; Defines how the host (editor) should sync document changes to the language server.\n      ;;\n      ;; 0: Documents should not be synced at all.\n      ;; 1: Documents are synced by always sending the full content of the document.\n      ;; 2: Documents are synced by sending the full content on open.\n      ;;    After that only incremental updates to the document are send.\n      ;;\n      ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocumentSyncKind\n      :textDocumentSync 1\n      :definitionProvider true\n      :referencesProvider true\n      :completionProvider {:triggerCharacters [\"(\" \":\"]}}\n\n     :serverInfo\n     {:name \"Nightincode\"}}))\n\n(defmethod lsp/handle \"initialized\" [notification]\n\n  ;; The initialized notification is sent from the client to the server after\n  ;; the client received the result of the initialize request but before\n  ;; the client is sending any other request or notification to the server.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialized\n\n  (let [probe-state (when-let [pid (get-in @state-ref [:LSP/InitializeParams :processId])]\n\n                      ;; Nightincode needs to check frequently if the parent process is still alive.\n                      ;; A client e.g. Visual Studio Code should ask the server to exit, but that might not happen.\n                      ;;\n                      ;; > The shutdown request is sent from the client to the server.\n                      ;;   It asks the server to shut down, but to not exit (otherwise the response might not be delivered correctly to the client).\n                      ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#shutdown\n                      ;;\n                      ;; > A notification to ask the server to exit its process.\n                      ;;   The server should exit with success code 0 if the shutdown request has been received before; otherwise with error code 1.\n                      ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#exit\n\n                      (let [^ScheduledExecutorService executor (Executors/newScheduledThreadPool 1)\n\n                            probe-delay 15\n\n                            probe (.scheduleWithFixedDelay executor\n                                    (fn []\n                                      ;; Checking if a process is alive was copied from:\n                                      ;; https://github.com/clojure-lsp/lsp4clj/blob/492c075d145ddb4e46e245a6f7a4de8a4670fe72/server/src/lsp4clj/core.clj#L296\n                                      ;;\n                                      ;; Thanks, lsp4clj!\n\n                                      (let [windows? (str/includes? (System/getProperty \"os.name\") \"Windows\")\n\n                                            process-alive? (cond\n                                                             windows?\n                                                             (let [{:keys [out]} (shell/sh \"tasklist\" \"/fi\" (format \"\\\"pid eq %s\\\"\" pid))]\n                                                               (str/includes? out (str pid)))\n\n                                                             :else\n                                                             (let [{:keys [exit]} (shell/sh \"kill\" \"-0\" (str pid))]\n                                                               (zero? exit)))]\n\n                                        (when-not process-alive?\n                                          (log/debug (format \"Parent process %s no longer exists; Exiting server...\" pid))\n\n                                          (System/exit 1))))\n                                    probe-delay\n                                    probe-delay\n                                    TimeUnit/SECONDS)]\n                        {:nightincode/probe-executor executor\n                         :nightincode/probe probe}))]\n\n    (swap! state-ref merge {:LSP/InitializedParams (:params notification)} probe-state)\n\n    ;; Log a welcome message in the client.\n    (lsp/write (_writer @state-ref)\n      {:jsonrpc \"2.0\"\n       :method \"window/logMessage\"\n       :params {:type 4\n                :message (format \"Nightincode is up and running!\\n\\nA REPL is available on port %s.\\n\\nHappy coding!\" (_repl-port @state-ref))}})))\n\n(defmethod lsp/handle \"shutdown\" [request]\n\n  ;; The shutdown request is sent from the client to the server.\n  ;; It asks the server to shut down, but to not exit (otherwise the response might not be delivered correctly to the client).\n  ;; There is a separate exit notification that asks the server to exit.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#shutdown\n\n  (swap! state-ref assoc :nightincode/shutdown? true)\n\n  (lsp/response request nil))\n\n(defmethod lsp/handle \"exit\" [_]\n\n  ;; A notification to ask the server to exit its process.\n  ;; The server should exit with success code 0 if the shutdown request has been received before; otherwise with error code 1.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#exit\n\n  (System/exit (if (:nightincode/shutdown? @state-ref)\n                 0\n                 1)))\n\n(defmethod lsp/handle \"textDocument/didOpen\" [notification]\n\n  ;; The document open notification is sent from the client to the server to signal newly opened text documents.\n  ;; The document’s content is now managed by the client and the server must not try to read the document’s content using the document’s Uri.\n  ;; Open in this sense means it is managed by the client. It doesn’t necessarily mean that its content is presented in an editor.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didOpen\n\n  (let [textDocument (get-in notification [:params :textDocument])\n\n        text-document-uri (text-document-uri textDocument)\n        text-document-text (text-document-text textDocument)\n\n        result (analyze {:uri text-document-uri\n                         :text text-document-text})]\n\n    (swap! state-ref !index-document {:uri text-document-uri\n                                      :analysis (:analysis result)})))\n\n(defmethod lsp/handle \"textDocument/didChange\" [notification]\n\n  ;; The document change notification is sent from the client to the server to signal changes to a text document.\n  ;; Before a client can change a text document it must claim ownership of its content using the textDocument/didOpen notification.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didChange\n\n  (let [textDocument (get-in notification [:params :textDocument])\n\n        text-document-uri (text-document-uri textDocument)\n\n        ;; The client sends the full text because textDocumentSync capability is set to 1 (full).\n        text-document-text (get-in notification [:params :contentChanges 0 :text])\n\n        result (analyze {:uri text-document-uri\n                         :text text-document-text})]\n\n    (swap! state-ref !index-document {:uri text-document-uri\n                                      :analysis (:analysis result)})))\n\n(defmethod lsp/handle \"textDocument/didSave\" [_notification]\n\n  ;; The document save notification is sent from the client to the server when the document was saved in the client.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didSave\n\n  nil)\n\n(defmethod lsp/handle \"textDocument/didClose\" [notification]\n\n  ;; The document close notification is sent from the client to the server when the document got closed in the client.\n  ;; The document’s master now exists where the document’s Uri points to (e.g. if the document’s Uri is a file Uri the master now exists on disk).\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didClose\n\n  (let [textDocument (get-in notification [:params :textDocument])]\n    (swap! state-ref\n      (fn [state]\n        (let [text-document-uri (text-document-uri textDocument)\n\n              state (update state :nightincode/index dissoc text-document-uri)\n              state (update state :clj-kondo/result dissoc text-document-uri)]\n\n          state)))))\n\n(defmethod lsp/handle \"textDocument/definition\" [request]\n\n  ;; The go to definition request is sent from the client to the server\n  ;; to resolve the definition location of a symbol at a given text document position.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_definition\n\n  (try\n    (let [textDocument (get-in request [:params :textDocument])\n\n          cursor-line (get-in request [:params :position :line])\n          cursor-character (get-in request [:params :position :character])\n\n          index (_text-document-index @state-ref textDocument)\n\n          row+col [(inc cursor-line) (inc cursor-character)]\n\n          T (?T_ index row+col)\n\n          D (case (TT T)\n              :nightincode/VD\n              (map V-location ((IVD index) (VD-ident T)))\n\n              :nightincode/VU\n              (map V-location ((IVD index) (VU-ident T)))\n\n              nil)]\n\n      (lsp/response request (seq D)))\n\n    (catch Exception ex\n      (lsp/error-response request\n        {:code -32803\n         :message (format \"Sorry. Nightincode failed to find a definition. (%s)\"(ex-message ex))}))))\n\n(defmethod lsp/handle \"textDocument/references\" [request]\n\n  ;; The references request is sent from the client to the server\n  ;; to resolve project-wide references for the symbol denoted by the given text document position.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_references\n\n  (try\n    (let [textDocument (get-in request [:params :textDocument])\n\n          cursor-line (get-in request [:params :position :line])\n          cursor-character (get-in request [:params :position :character])\n\n          index (_text-document-index @state-ref textDocument)\n\n          row+col [(inc cursor-line) (inc cursor-character)]\n\n          T (?T_ index row+col)\n\n          R (case (TT T)\n              :nightincode/VD\n              (map V-location ((IVU index) (VD-ident T)))\n\n              :nightincode/VU\n              (map V-location ((IVU index) (VU-ident T)))\n\n              nil)]\n\n      (lsp/response request (seq R)))\n\n    (catch Exception ex\n      (lsp/error-response request\n        {:code -32803\n         :message (format \"Sorry. Nightincode failed to find references. (%s)\"(ex-message ex))}))))\n\n(defmethod lsp/handle \"textDocument/completion\" [request]\n\n  ;; The Completion request is sent from the client to the server to compute completion items at a given cursor position.\n  ;;\n  ;; https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion\n\n  (try\n    (let [textDocument (get-in request [:params :textDocument])\n\n          cursor-line (get-in request [:params :position :line])\n\n          index (_text-document-index @state-ref textDocument)\n\n          row+col [(inc (get-in request [:params :position :line]))\n                   (inc (get-in request [:params :position :character]))]\n\n          T (?T_ index row+col)\n\n          ;; TODO: Extract completions.\n\n          ;; Completions with document definitions.\n          VD-completions (into []\n                           (map\n                             (fn [[sym _]]\n                               ;; Var name only because it's a document definition.\n                               {:label (name sym)\n                                :kind 6}))\n                           (IVD index))\n\n          ;; Completions with document usages - exclude \"self definitions\".\n          VU-completions (into #{}\n                           (comp\n                             (mapcat val)\n                             (remove\n                               (fn [{:keys [from to]}]\n                                 (= from to)))\n                             (map\n                               (fn [{:keys [to alias name]}]\n                                 {:label (cond\n                                           (contains? #{'clojure.core 'cljs.core} to)\n                                           (str name)\n\n                                           (or alias to)\n                                           (format \"%s/%s\" (or alias to) name)\n\n                                           :else\n                                           (str name))\n                                  :kind 6})))\n                           (IVU index))\n\n          K-completions (into []\n                          (map\n                            (fn [[k _]]\n                              {:label (str k)\n                               :kind 14}))\n                          ;; Only the keyword is necessary,\n                          ;; so it's okay to combine indexes.\n                          (merge (IKD index) (IKU index)))\n\n          completions (reduce\n                        into\n                        []\n                        [VD-completions\n                         VU-completions\n                         K-completions])]\n\n      (lsp/response request (merge {:items completions}\n                              (when T\n                                {:itemDefaults\n                                 {:editRange\n                                  {:start\n                                   {:line cursor-line\n                                    :character (dec (or (:name-col T) (:col T)))}\n                                   :end\n                                   {:line cursor-line\n                                    :character (dec (or (:name-end-col T) (:end-col T)))}}}}))))\n    (catch Exception ex\n      (lsp/error-response request\n        {:code -32803\n         :message (format \"Sorry. Nightincode failed to compute completions. (%s)\"(ex-message ex))}))))\n\n(defn start [config]\n  (let [^ServerSocket server-socket (start-server\n                                      {:name \"REPL\"\n                                       :port 0\n                                       :accept 'clojure.core.server/repl})]\n\n    (log/debug \"REPL port:\" (.getLocalPort server-socket))\n\n    (reset! state-ref #:nightincode {:repl-server-socket server-socket\n                                     :reader (:reader config)\n                                     :writer (:writer config)})\n\n    (doto\n      (Thread. #(lsp/start config))\n      (.start))))\n\n(defn -main [& _]\n  (start\n    {:reader (lsp/buffered-reader System/in)\n     :writer (lsp/buffered-writer System/out)\n     :trace (fn [{:keys [header status content error]}]\n              (case status\n                :message-decoded\n                (log/debug (select-keys content [:id :method]))\n\n                ;; Note: `content` is a string when there's a decoding error.\n                :message-decode-error\n                (log/error error\n                  {:header header\n                   :content content\n                   :content-length (alength (.getBytes ^String content))})\n\n                nil))}))\n\n\n(comment\n\n  (keys @state-ref)\n\n  (let [{:keys [LSP/InitializeParams\n                LSP/InitializedParams\n\n                nightincode/index\n                clj-kondo/result]} @state-ref]\n\n    (def initialize-params InitializeParams)\n    (def initialized-params InitializedParams)\n    (def index index)\n    (def clj-kondo-result result))\n\n  (keys index)\n\n  (lsp/handle\n    {:method \"textDocument/completion\"\n     :params\n     {:textDocument\n      {:uri \"file:///Users/pedro/Developer/lispi/src/lispi/core.clj\"}\n\n      :position\n      {:line 119\n       :character 40}}})\n\n  (def lispi-core-uri \"file:///Users/pedro/Developer/lispi/src/lispi/core.clj\")\n\n  (clj-kondo/run!\n    {:lint [lispi-core-uri]\n     :config default-clj-kondo-config})\n\n  '{:row 42,\n    :col 29,\n    :end-row 42,\n    :end-col 32,\n    :name \"as\",\n    :filename \"/Users/pedro/Developer/lispi/src/lispi/core.clj\",\n    :from user}\n\n  '{:end-row 49,\n    :ns lispi,\n    :name \"tokens\",\n    :filename \"/Users/pedro/Developer/lispi/src/lispi/core.clj\",\n    :from lispi.core,\n    :col 8,\n    :reg clojure.spec.alpha/def,\n    :end-col 21,\n    :row 49}\n\n  (IVD_ (_text-document-index @state-ref {:uri lispi-core-uri}))\n  (?VD_ (_text-document-index @state-ref {:uri lispi-core-uri}) [112 13])\n\n  (IVU_ (_text-document-index @state-ref {:uri lispi-core-uri}))\n  (?VU_ (_text-document-index @state-ref {:uri lispi-core-uri}) [184 15])\n\n  (IVD (_text-document-index @state-ref {:uri lispi-core-uri}))\n  (IVU (_text-document-index @state-ref {:uri lispi-core-uri}))\n\n  (meta (?T_ (_text-document-index @state-ref {:uri lispi-core-uri}) [112 13]))\n\n  (keys index)\n\n  (lsp/write (_writer @state-ref)\n    {:jsonrpc \"2.0\"\n     :method \"window/showMessage\"\n     :params {:type 3\n              :message \"Hello!\"}})\n\n\n  )\n"

  )



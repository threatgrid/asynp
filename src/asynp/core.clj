(ns asynp.core
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alts! alt!! alts!! go close!]]
            [clojure.string]
            [taoensso.timbre :as timbre])
  (:import [com.zaxxer.nuprocess NuProcess NuProcessBuilder NuProcessHandler]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset CoderResult]
           [java.util.regex Pattern]))

(defmacro go-ex [& body]
  `(go
     (try
       ~@body
       (catch Exception e
         (timbre/error e)))))

(def ^:dynamic *working-buffer-size*
  "Initial size of the destination buffer for reads. Will be reallocated with a larger size as-needed."
  1024)

(def ^:dynamic *callback-timeout-ms*
  "Maximum number of milliseconds to block in NuProcess callback thread before timing out and failing loudly. Serves as a failsafe for cases where channel readers are hung or not present. Set to nil to disable timeout."
  30000)

(defn array-from-buffer [^ByteBuffer buffer]
  "Given a ByteBuffer, return an array of bytes"
  (let [byte-count (.remaining buffer)
        dest (byte-array byte-count)]
    (.get buffer dest)
    dest))

(defn run-process [argv & {:keys [shutdown-chan]}]
  "Run a process with the given argv, and return a dictionary with access to the process object itself and channels for controlling it.

  Accepts the following optional keyword arguments:
  - `shutdown-chan`: use a preexisting channel to force shutdown

  Returns a dictionary with the following keys:
  - `:process` - a NuProcess object
  - `:in` - an unbuffered channel used for input. Contents will be streamed to the process's stdin.
  - `:out` - an unbuffered channel used for output. Contains contents read from the process's stdout.
  - `:err` - an unbuffered channel used for output. Contains contents read from the process's stderr.
  - `:exit` - a buffered channel used for output. Empty until process has exited. Contains exit status, or INT_MIN if process was not successfully invoked.
  - `:shutdown` - an unbuffered channel used for input. Closing this channel will force the process to exit.

  All objects on the :in, :out or :err channels will be byte arrays."
  (let [in-chan (chan), out-chan (chan), err-chan (chan), exit-status-chan (chan 1)
        shutdown-chan (or shutdown-chan (chan))
        process-atom (atom nil)
        write-callback (fn [^ByteBuffer buffer, dest-chan, dest-name]
                         (when buffer
                           (timbre/trace "In callback")
                           (let [channels [[dest-chan (array-from-buffer buffer)] shutdown-chan]
                                 [v c] (alts!! (if *callback-timeout-ms*
                                                 (conj channels (timeout *callback-timeout-ms*))
                                                 channels))]
                             (timbre/trace "callback write result: " v "," c)
                             (cond
                              (= c dest-chan)
                              (timbre/trace "successful write to" dest-name)

                              (= c shutdown-chan)
                              (timbre/trace "write to" dest-name "interrupted by shutdown")

                              :else
                              (do
                                (timbre/error "timeout occurred in callback writing to" dest-name "-- shutting down")
                                (let [^NuProcess p @process-atom] (.destroy p)))))))]
    (let [^NuProcessHandler handler
          (proxy [NuProcessHandler] []
            (onStart [process]
              (timbre/trace "onStart called for" process)
              (reset! process-atom process))
            (onExit [statusCode]
              (timbre/trace "onExit called with" statusCode)
              (>!! exit-status-chan statusCode)
              (close! out-chan)
              (close! err-chan)
              (close! exit-status-chan))
            (onStdout [^ByteBuffer buffer]
              (timbre/trace "onStdout called with" buffer)
              (write-callback buffer out-chan :out)
              (timbre/trace "onStdout finished; buffer no longer safe"))
            (onStderr [^ByteBuffer buffer]
              (timbre/trace "onStderr called with" buffer)
              (write-callback buffer err-chan :err)
              (timbre/trace "onStderr finished; buffer no longer safe"))
            (onStdinReady [^ByteBuffer buffer]
              (timbre/trace "Performing deferred close")
              (let [^NuProcess process @process-atom]
                (.closeStdin process))
              false))
          ^java.util.List argv-list (apply list argv)
          builder (NuProcessBuilder. handler argv-list)]
      (let [process (.start builder)]
        (go-ex
          (loop []
            (let [[v c] (alts! [in-chan shutdown-chan])]
              (cond
               (= c in-chan)
               (if v
                 (do
                   (timbre/trace "Writing" (alength ^bytes v) "bytes to process")
                   (.writeStdin process (ByteBuffer/wrap v))
                   (recur))
                 (do
                   (timbre/trace "stdin stream ended; telling NuProcess to callback after flush")
                   (.wantWrite process)))

               :else
               (timbre/trace "shutdown chan triggered; ending input goroutine")))))
        {:process process
         :in in-chan
         :out out-chan
         :err err-chan
         :exit exit-status-chan
         :shutdown shutdown-chan}))))

(defn decode-chars
  "given a stream of byte arrays, emit a stream of CharBuffers"
  ([in-chan]
     (decode-chars in-chan (Charset/forName "utf8")))
  ([in-chan, ^Charset charset]
     (let [out-chan (chan)
           decoder (.newDecoder charset)]
       (go-ex
         (loop [^ByteBuffer working-buffer (ByteBuffer/allocate *working-buffer-size*) ; compacted and ready to receive writes
                ^bytes in-bytes (<! in-chan)]
           (timbre/trace "decoder: starting loop with working buffer " working-buffer " processing content " in-bytes)
           (cond
            (nil? in-bytes)
            (do
              (.flip ^ByteBuffer working-buffer) ; use as a source
              (timbre/trace "decoder: end of input stream seen; flushing the rest of " working-buffer)
              (let [out-buffer (CharBuffer/allocate (.remaining ^ByteBuffer working-buffer))
                    decode-result (.decode decoder ^ByteBuffer working-buffer ^CharBuffer out-buffer true)]

                (.flush decoder ^CharBuffer out-buffer)
                (.flip out-buffer)
                (when (pos? (.remaining out-buffer))
                  (>! out-chan out-buffer))
                (close! out-chan))
              nil)

            (< (.remaining ^ByteBuffer working-buffer) (alength ^bytes in-bytes))
            (do
              (timbre/trace "decoder: resizing working buffer;" (.remaining ^ByteBuffer working-buffer)
                            "bytes left of" (alength ^bytes in-bytes) "needed")
              (let [new-working-buffer (ByteBuffer/allocate (+ *working-buffer-size*
                                                               (.capacity ^ByteBuffer working-buffer)
                                                               (alength ^bytes in-bytes)))]
                (.flip ^ByteBuffer working-buffer) ; use as a source for copy
                (.put ^ByteBuffer new-working-buffer ^ByteBuffer working-buffer)
                (recur new-working-buffer in-bytes)))

            :else
            (do
              (timbre/trace "decoder: running a regular cycle; in-bytes " in-bytes ", working-buffer " working-buffer)
              (.put ^ByteBuffer working-buffer ^bytes in-bytes)
              (.flip ^ByteBuffer working-buffer) ; use a source for decoding
              (timbre/trace "working-buffer" working-buffer "populated for decode:"
                            (seq (array-from-buffer (.duplicate ^ByteBuffer working-buffer))))
              (let [out-buffer (CharBuffer/allocate (.remaining ^ByteBuffer working-buffer))
                    decode-result (.decode decoder ^ByteBuffer working-buffer ^CharBuffer out-buffer false)]
                (timbre/trace "Decoding into" out-buffer
                              "of size" (.capacity ^CharBuffer out-buffer)
                              "resulted in" decode-result
                              "with" (.position out-buffer) "characters decoded")
                (cond
                 (or (= decode-result CoderResult/UNDERFLOW)
                     (= decode-result CoderResult/OVERFLOW))
                 (do
                   (.compact ^ByteBuffer working-buffer) ;; leave working-buffer ready to receive writes
                   (.flip ^CharBuffer out-buffer)        ;; leave output buffer ready to for reads
                   (>! out-chan out-buffer)              ;; write decoded content to channel
                   (recur working-buffer (<! in-chan)))  ;; read more content to decode

                 (.isError ^CoderResult decode-result)
                 (do
                   (try
                     (.throwException ^CoderResult decode-result)
                     (catch Exception e
                       (timbre/error e "Ending decode due to error")))
                   (close! out-chan)
                   nil)))))))
       out-chan)))

(defn split-by-char [in-chan delim-char]
  "given a channel delivering character arrays, merge and split by a delimiter"
  (let [out-chan (chan)
        pattern (Pattern/compile (str delim-char) Pattern/LITERAL)]
    (go
      (loop [strings-without-delim []
             input-charbuf (<! in-chan)]
        (timbre/trace "Buffered:" (pr-str strings-without-delim))
        (timbre/trace "Handling:" (pr-str input-charbuf))
        (if input-charbuf
          (let [input-str (str input-charbuf)
                pieces (vec (.split pattern input-str -1))]
            (timbre/trace "Split string" (pr-str input-str)
                          "into pieces" (pr-str pieces)
                          "on delimiter" (pr-str delim-char))
            (if (= (count pieces) 1)
              (do
                ;; single piece is appended to queue
                (recur (conj strings-without-delim input-str) (<! in-chan)))
              (do
                ;; first piece is appended to queued strings
                (>! out-chan (clojure.string/join (conj strings-without-delim (first pieces))))
                ;; middle pieces go out as-is
                (doseq [ready-string (rest (pop pieces))]
                  (>! out-chan ready-string))
                ;; last piece is queued for later
                (let [last-piece (last pieces)]
                  (recur [last-piece] (<! in-chan))))))
          (do
            ;; end of stream
            (let [last-piece (clojure.string/join strings-without-delim)]
              (when (seq last-piece)
                (>! out-chan last-piece)))))))
    out-chan))

(defn log-strings
  "Debugging aid: Given a channel delivering character buffers, log the contained strings using Timbre

  When given a character to split by, log only when a complete character-delimited buffer is received, or at end-of-stream."
  ([in-chan]
     (go
       (loop [char-buffer-in (<! in-chan)]
         (when char-buffer-in
           (timbre/info "Read string: " (str char-buffer-in))
           (recur (<! in-chan))))))
  ([in-chan split-char]
     (let [str-chan (split-by-char in-chan split-char)]
       (go (loop [string-in (<! str-chan)]
             (timbre/info "Read string: " string-in)
             (recur (<! str-chan)))))))


(defn write-str-to-process [proc-dict, ^String s]
  "Given a process dictionary, queue content to be written to its stdin"
  (>!! (:in proc-dict) (.getBytes s)))

(defn close-stdin-for-process [proc-dict]
  "Queue a close event for a process's stdin.

  This event will only be executed after the process is ready to read from its stdin"
  (close! (:in proc-dict)))

(defn wait-for-process
  "Block until a process exits, or an optional timeout occurs; return exit status, or nil for timeout"
  ([proc-dict]
     (<!! (:exit proc-dict)))
  ([proc-dict timeout-ms]
     (let [exit-chan (:exit proc-dict)
           [v c] (alts!! [exit-chan (timeout timeout-ms)])]
       (when (= c exit-chan)
         v))))

(defn shutdown-process
  "Force process to shut down immediately by closing its shutdown channel.

  Note that this can have side effects if an external (shared) shutdown channel was supplied rather than allowing a new one to be created by default."
  [proc-dict]
  (close! (:shutdown proc-dict)))

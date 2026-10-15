(ns edgar.forms.xml
  "Shared XML helpers for the form parsers (plain clojure.xml — no extra
   deps). Tag matching ignores namespace prefixes, so :infoTable matches
   both <infoTable> and <ns1:infoTable>."
  (:require [clojure.xml :as xml]
            [clojure.string :as str]))

(defn- startparse-no-external-entities
  "SAX startparse for clojure.xml/parse with external entities and DTD
   loading disabled (XXE hardening — filings are third-party documents)."
  [^java.io.InputStream s ch]
  (let [factory (doto (javax.xml.parsers.SAXParserFactory/newInstance)
                  (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                  (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
                  (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))]
    (.parse (.newSAXParser factory) s ^org.xml.sax.helpers.DefaultHandler ch)))

(defn parse-str
  "Parse an XML string into a clojure.xml element tree."
  [^String s]
  (xml/parse (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))
             startparse-no-external-entities))

(defn- local-name
  "Tag name without its namespace prefix (:ns1:infoTable -> \"infoTable\")."
  [node-tag]
  (when (keyword? node-tag)
    (let [n (name node-tag)
          i (str/last-index-of n ":")]
      (if i (subs n (inc i)) n))))

(defn tag-matches?
  "True when node-tag is tag, ignoring any namespace prefix on node-tag."
  [tag node-tag]
  (or (= tag node-tag)
      (= (name tag) (local-name node-tag))))

(defn find-tag
  "First descendant element (depth-first, including node itself) whose tag
   matches."
  [node tag]
  (when (map? node)
    (if (tag-matches? tag (:tag node))
      node
      (some #(find-tag % tag) (:content node)))))

(defn find-tags
  "All descendant elements (including node itself) whose tag matches, in
   document order."
  [node tag]
  (when (map? node)
    (let [here (if (tag-matches? tag (:tag node)) [node] [])]
      (into here (mapcat #(find-tags % tag) (:content node))))))

(defn child
  "First *direct* child element of node whose tag matches."
  [node tag]
  (when (map? node)
    (some #(when (and (map? %) (tag-matches? tag (:tag %))) %)
          (:content node))))

(defn text
  "Trimmed text content of an element, or nil when empty."
  [node]
  (some-> node :content first str str/trim not-empty))

(defn tag-text
  "Text of the first descendant matching any of the given tags, tried in
   order (schemas sometimes spell one field several ways)."
  [node & tags]
  (some #(text (find-tag node %)) tags))

(defn child-text
  "Text of the first direct child element matching tag."
  [node tag]
  (text (child node tag)))

(defn nested-text
  "Walk a path of tags (each a descendant search) from node and return the
   leaf text."
  [node & tags]
  (text (reduce find-tag node tags)))

(defn parse-double-safe
  "Parse a numeric string to a double; nil for nil or unparseable input."
  [s]
  (when s
    (try (parse-double s) (catch Exception _ nil))))

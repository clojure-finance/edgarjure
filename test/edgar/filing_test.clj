(ns edgar.filing-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [edgar.core]
            [edgar.filing :as filing]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; filing-index-url — pure function, builds a URL from a filing map
;;; ---------------------------------------------------------------------------

(deftest filing-index-url-test
  (testing "builds correct index URL for dashed accession number"
    (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000064/0000320193-23-000064-index.html"
           (filing/filing-index-url
            {:cik "320193"
             :accessionNumber "0000320193-23-000064"}))))
  (testing "CIK in URL is not zero-padded"
    (let [url (filing/filing-index-url {:cik "320193"
                                        :accessionNumber "0000320193-23-000064"})]
      (is (str/includes? url "/320193/"))))
  (testing "accession number in URL path has dashes stripped"
    (let [url (filing/filing-index-url {:cik "320193"
                                        :accessionNumber "0000320193-23-000064"})]
      (is (str/includes? url "/000032019323000064/"))))
  (testing "index filename ends with -index.html"
    (let [url (filing/filing-index-url {:cik "320193"
                                        :accessionNumber "0000320193-23-000064"})]
      (is (str/ends-with? url "-index.html")))))

;;; ---------------------------------------------------------------------------
;;; parse-filing-index-html — offline HTML fixture tests
;;; ---------------------------------------------------------------------------

(def ^:private form4-index-html
  "Minimal Form 4 filing index HTML fixture.
   Mirrors the SEC's actual structure: two sequence-1 rows — a phantom .html
   entry whose size cell is a non-breaking space (&#160; / \\u00A0), and the
   real .xml entry with an actual byte count. Also includes the formGrouping
   header divs that carry Filing Date, so :filingDate extraction can be tested."
  "<html><body>
     <div id=\"formHeader\">
       <div id=\"formName\"><strong>Form 4</strong></div>
     </div>
     <div class=\"formGrouping\">
       <div class=\"infoHead\">Filing Date</div>
       <div class=\"info\">2026-03-06</div>
       <div class=\"infoHead\">Accepted</div>
       <div class=\"info\">2026-03-06 22:43:01</div>
     </div>
     <table class=\"tableFile\" summary=\"Document Format Files\">
       <tr>
         <th scope=\"col\">Seq</th>
         <th scope=\"col\">Description</th>
         <th scope=\"col\">Document</th>
         <th scope=\"col\">Type</th>
         <th scope=\"col\">Size</th>
       </tr>
       <tr>
         <td>1</td><td>4</td>
         <td><a href=\"/Archives/edgar/data/1/000001-26-001/ownership.html\">ownership.html</a></td>
         <td>4</td><td>&#160;</td>
       </tr>
       <tr>
         <td>1</td><td>4</td>
         <td><a href=\"/Archives/edgar/data/1/000001-26-001/ownership.xml\">ownership.xml</a></td>
         <td>4</td><td>14442</td>
       </tr>
       <tr>
         <td>&nbsp;</td><td>Complete submission text file</td>
         <td><a href=\"/Archives/edgar/data/1/000001-26-001/000001-26-001.txt\">000001-26-001.txt</a></td>
         <td>&nbsp;</td><td>15874</td>
       </tr>
     </table>
   </body></html>")

(def ^:private form10k-index-html
  "Minimal 10-K filing index HTML fixture — single sequence-1 primary document."
  "<html><body>
     <div id=\"formHeader\">
       <div id=\"formName\"><strong>Form 10-K</strong></div>
     </div>
     <table class=\"tableFile\" summary=\"Document Format Files\">
       <tr>
         <th scope=\"col\">Seq</th>
         <th scope=\"col\">Description</th>
         <th scope=\"col\">Document</th>
         <th scope=\"col\">Type</th>
         <th scope=\"col\">Size</th>
       </tr>
       <tr>
         <td>1</td><td>Annual Report</td>
         <td><a href=\"/ix?doc=/Archives/edgar/data/2/000002-24-001/report.htm\">report.htm</a></td>
         <td>10-K</td><td>987654</td>
       </tr>
       <tr>
         <td>2</td><td>Exhibit 31.1</td>
         <td><a href=\"/Archives/edgar/data/2/000002-24-001/ex311.htm\">ex311.htm</a></td>
         <td>EX-31.1</td><td>12345</td>
       </tr>
     </table>
   </body></html>")

(def ^:private form-index-linked-description-html
  "Filing index fixture where the description cell (column 2) contains an <a> tag,
   not the filename cell. This is the bug case for Issue #5: the old code used
   (sel/select (sel/descendant :td :a) row) which would grab 'Click here' from
   the description link instead of 'ownership.xml' from the filename cell."
  "<html><body>
     <div id=\"formHeader\">
       <div id=\"formName\"><strong>Form 4</strong></div>
     </div>
     <table class=\"tableFile\" summary=\"Document Format Files\">
       <tr>
         <th>Seq</th><th>Description</th><th>Document</th><th>Type</th><th>Size</th>
       </tr>
       <tr>
         <td>1</td>
         <td><a href=\"/some-related-link\">Click here for info</a></td>
         <td>ownership.xml</td>
         <td>4</td><td>14442</td>
       </tr>
       <tr>
         <td>2</td>
         <td>Exhibit 99</td>
         <td><a href=\"/Archives/edgar/data/1/000001-26-001/ex99.htm\">ex99.htm</a></td>
         <td>EX-99</td><td>5000</td>
       </tr>
     </table>
   </body></html>")

(deftest parse-filing-index-html-phantom-entries-test
  (testing "phantom .html entry (nbsp size) is excluded; real .xml entry is kept"
    (let [idx (#'filing/parse-filing-index-html form4-index-html)
          files (:files idx)
          names (map :name files)]
      (is (not (some #{"ownership.html"} names))
          "phantom ownership.html must be excluded")
      (is (some #{"ownership.xml"} names)
          "real ownership.xml must be present")))

  (testing "primary-doc returns the real xml, not the phantom html"
    (let [idx (#'filing/parse-filing-index-html form4-index-html)
          primary (filing/primary-doc idx)]
      (is (= "ownership.xml" (:name primary)))))

  (testing "complete-submission-text-file row is included (has a real size)"
    (let [idx (#'filing/parse-filing-index-html form4-index-html)
          names (map :name (:files idx))]
      (is (some #{"000001-26-001.txt"} names))))

  (testing "form type is parsed from <strong> tag"
    (is (= "4" (:formType (#'filing/parse-filing-index-html form4-index-html))))
    (is (= "10-K" (:formType (#'filing/parse-filing-index-html form10k-index-html)))))

  (testing "multi-word form types are captured whole, not truncated at the first space"
    (let [with-form (fn [ft] (str/replace form10k-index-html "Form 10-K" (str "Form " ft)))]
      (is (= "SCHEDULE 13D" (:formType (#'filing/parse-filing-index-html (with-form "SCHEDULE 13D")))))
      (is (= "SC 13G/A" (:formType (#'filing/parse-filing-index-html (with-form "SC 13G/A")))))
      (is (= "S-1 MEF" (:formType (#'filing/parse-filing-index-html (with-form "S-1 MEF")))))))

  (testing "a ' - description' suffix after the form type is dropped"
    (let [html (str/replace form10k-index-html "Form 10-K"
                            "Form 10-K - Annual report [Section 13 and 15(d)]")]
      (is (= "10-K" (:formType (#'filing/parse-filing-index-html html))))))

  (testing ":filingDate is extracted from the Filing Date infoHead/info pair"
    (is (= "2026-03-06" (:filingDate (#'filing/parse-filing-index-html form4-index-html)))))

  (testing ":filingDate is nil when no infoHead divs are present"
    (is (nil? (:filingDate (#'filing/parse-filing-index-html form10k-index-html)))))

  (testing "iXBRL viewer href does not corrupt the filename"
    (let [idx (#'filing/parse-filing-index-html form10k-index-html)
          primary (filing/primary-doc idx)]
      (is (= "report.htm" (:name primary)))
      (is (not (str/starts-with? (:name primary) "/ix?"))))))

(deftest parse-filing-index-html-standard-test
  (testing "sequence, description, type, size are parsed correctly"
    (let [idx (#'filing/parse-filing-index-html form10k-index-html)
          primary (filing/primary-doc idx)]
      (is (= "1" (:sequence primary)))
      (is (= "Annual Report" (:description primary)))
      (is (= "10-K" (:type primary)))
      (is (= "987654" (:size primary)))))

  (testing "exhibit entries are present"
    (let [idx (#'filing/parse-filing-index-html form10k-index-html)
          ex311 (->> (:files idx) (filter #(= "EX-31.1" (:type %))) first)]
      (is (some? ex311))
      (is (= "ex311.htm" (:name ex311))))))

(deftest parse-filing-index-html-name-scoping-test
  ;; Regression test for Issue #5:
  ;; :name was extracted via (sel/select (sel/descendant :td :a) row), which
  ;; searched ALL <a> tags across ALL <td> cells in the row.  When the
  ;; description cell (column 2) contained a link, that link's text was
  ;; returned instead of the filename from the document cell (column 3).
  ;; Fix: extract :name only from the 3rd <td> (index 2 = document column).
  (testing "filename is taken from document cell (col 3), not description cell (col 2)"
    (let [idx (#'filing/parse-filing-index-html form-index-linked-description-html)
          files (:files idx)
          primary (filing/primary-doc idx)]
      (testing "primary doc name is 'ownership.xml', not 'Click here for info'"
        (is (= "ownership.xml" (:name primary)))
        (is (not= "Click here for info" (:name primary))))
      (testing "exhibit row name is extracted from its <a> tag correctly"
        (let [ex99 (->> files (filter #(= "EX-99" (:type %))) first)]
          (is (= "ex99.htm" (:name ex99)))))))
  (testing "filename cell with no <a> tag falls back to cell text content"
    ;; ownership.xml row in fixture has no <a>, just plain text
    (let [idx (#'filing/parse-filing-index-html form-index-linked-description-html)
          primary (filing/primary-doc idx)]
      (is (string? (:name primary)))
      (is (not (str/blank? (:name primary))))))
  (testing "existing iXBRL href fixture still returns correct filename (not /ix?... path)"
    (let [idx (#'filing/parse-filing-index-html form10k-index-html)
          primary (filing/primary-doc idx)]
      (is (= "report.htm" (:name primary))))))

;;; ---------------------------------------------------------------------------
;;; filing-text — script/style exclusion
;;; ---------------------------------------------------------------------------

(deftest filing-text-excludes-script-style-test
  (let [html "<html><head>
                <style>body { color: red; }</style>
                <script>alert(1);</script>
              </head>
              <body><p>Hello world</p></body></html>"]
    (with-redefs [edgar.filing/filing-html (fn [_] html)]
      (let [result (filing/filing-text {})]
        (testing "plain text is included"
          (is (str/includes? result "Hello world")))
        (testing "CSS content is excluded"
          (is (not (str/includes? result "color"))))
        (testing "JavaScript content is excluded"
          (is (not (str/includes? result "alert")))))))

  (testing "script/style subtrees are fully excluded — not just the tag"
    (let [html "<html><body>
                  <p>Before</p>
                  <script type=\"text/javascript\">
                    var x = 'injected'; document.write(x);
                  </script>
                  <style>.cls { display: none; }</style>
                  <p>After</p>
                </body></html>"]
      (with-redefs [edgar.filing/filing-html (fn [_] html)]
        (let [result (filing/filing-text {})]
          (is (str/includes? result "Before"))
          (is (str/includes? result "After"))
          (is (not (str/includes? result "injected")))
          (is (not (str/includes? result "display"))))))))

;;; ---------------------------------------------------------------------------
;;; filing-save! — nil primary-doc guard
;;; ---------------------------------------------------------------------------

(deftest filing-save-nil-primary-doc-test
  (testing "filing-save! returns nil when filing has no primary document"
    (with-redefs [edgar.filing/filing-index (fn [_] {:files [] :formType "4"})
                  edgar.filing/primary-doc (fn [_] nil)]
      (is (nil? (filing/filing-save! {} "/tmp"))))))

;;; ---------------------------------------------------------------------------
;;; filing-save-all! — duplicate filename deduplication (Issue #9)
;;; ---------------------------------------------------------------------------

(deftest filing-save-all-dedup-test
  ;; Regression test for Issue #9:
  ;; The SEC filing index HTML can (rarely) contain duplicate :name entries.
  ;; Without dedup, filing-save-all! would download and overwrite the same
  ;; file twice, which is wasteful and could clobber a good file with a bad one.
  ;; Fix: deduplicate (:files idx) by :name before the for loop.
  (testing "duplicate filenames in the index are downloaded only once"
    (let [download-calls (atom [])
          idx {:files [{:name "primary.htm" :type "10-K" :sequence "1" :size "10000"}
                       {:name "exhibit.pdf" :type "EX-21" :sequence "2" :size "5000"}
                       {:name "primary.htm" :type "10-K" :sequence "1" :size "10000"}]}
          tmp-dir (java.io.File/createTempFile "edgar-test-" "")
          _ (do (.delete tmp-dir) (.mkdirs tmp-dir))]
      (try
        (with-redefs [edgar.filing/filing-index (fn [_] idx)
                      edgar.filing/filing-doc-url (fn [_ n] (str "https://sec.gov/" n))
                      edgar.core/edgar-get (fn [url & _]
                                             (swap! download-calls conj url)
                                             "<html/>")
                      edgar.core/edgar-get-bytes (fn [url]
                                                   (swap! download-calls conj url)
                                                   (byte-array []))]
          (filing/filing-save-all! {:cik "320193"
                                    :accessionNumber "0000320193-24-000001"
                                    :form "10-K"}
                                   (.getAbsolutePath tmp-dir)))
        (is (= 2 (count @download-calls))
            "exactly 2 downloads — primary.htm once, exhibit.pdf once (not 3)")
        (is (= 2 (count (distinct @download-calls)))
            "no URL downloaded twice")
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "return value has no duplicate paths"
    (let [idx {:files [{:name "doc.htm" :type "10-K" :sequence "1" :size "1000"}
                       {:name "doc.htm" :type "10-K" :sequence "1" :size "1000"}
                       {:name "ex.htm" :type "EX-21" :sequence "2" :size "500"}]}
          tmp-dir (java.io.File/createTempFile "edgar-test-" "")
          _ (do (.delete tmp-dir) (.mkdirs tmp-dir))]
      (try
        (with-redefs [edgar.filing/filing-index (fn [_] idx)
                      edgar.filing/filing-doc-url (fn [_ n] (str "https://sec.gov/" n))
                      edgar.core/edgar-get (fn [& _] "<html/>")]
          (let [paths (filing/filing-save-all! {:cik "320193"
                                                :accessionNumber "0000320193-24-000001"
                                                :form "10-K"}
                                               (.getAbsolutePath tmp-dir))]
            (is (= 2 (count paths)) "2 paths returned — not 3")
            (is (= (count paths) (count (distinct paths))) "no duplicate paths")))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest filing-save-amendment-dir-test
  ;; "10-K/A" used to become a nested 10-K/A/ directory pair
  (let [amended {:cik "0000320193" :accessionNumber "0001193125-10-012091" :form "10-K/A"}]
    (testing "save-dir flattens the \"/\" in an amendment form type"
      (is (= (str (fs/path "/data" "10-K_A" "0000320193" "0001193125-10-012091"))
             (str (filing/save-dir amended "/data")))))
    (testing "filing-save! and filing-save-all! write under dir/10-K_A/..."
      (let [tmp-dir (str (fs/create-temp-dir))]
        (try
          (with-redefs [edgar.filing/filing-index
                        (fn [_] {:files [{:name "d10ka.htm" :type "10-K/A" :sequence "1" :size "10"}
                                         {:name "ex31.htm" :type "EX-31" :sequence "2" :size "10"}]})
                        edgar.core/edgar-get (fn [& _] "<html/>")]
            (let [p (filing/filing-save! amended tmp-dir)
                  ps (filing/filing-save-all! amended tmp-dir)
                  form-dirs (map str (fs/list-dir tmp-dir))]
              (is (= [(str (fs/path tmp-dir "10-K_A"))] form-dirs)
                  "exactly one form directory, no nested 10-K/A")
              (is (= (str (fs/path tmp-dir "10-K_A" "0000320193" "0001193125-10-012091" "d10ka.htm")) p))
              (is (every? #(fs/exists? %) (cons p ps)))))
          (finally
            (fs/delete-tree tmp-dir)))))))

(deftest binary-filename-test
  (let [f #'edgar.filing/binary-filename?]
    (testing "known binary extensions are recognised"
      (is (f "report.pdf"))
      (is (f "data.xls"))
      (is (f "data.xlsx"))
      (is (f "archive.zip"))
      (is (f "logo.gif"))
      (is (f "photo.jpg"))
      (is (f "photo.jpeg"))
      (is (f "image.png"))
      (is (f "doc.doc"))
      (is (f "doc.docx")))
    (testing "extension check is case-insensitive"
      (is (f "REPORT.PDF"))
      (is (f "Data.XLS"))
      (is (f "Logo.PNG")))
    (testing "text extensions are not binary"
      (is (not (f "report.htm")))
      (is (not (f "report.html")))
      (is (not (f "data.xml")))
      (is (not (f "filing.txt")))
      (is (not (f "schema.xsd"))))
    (testing "nil or empty name is not binary"
      (is (not (f nil)))
      (is (not (f ""))))))

(deftest save-doc-uses-bytes-for-binary-test
  (testing "binary file triggers edgar-get-bytes, not edgar-get"
    (let [bytes-called (atom false)
          text-called (atom false)
          tmp-file (java.io.File/createTempFile "edgar-test-" ".pdf")]
      (try
        (with-redefs [edgar.core/edgar-get-bytes (fn [_] (reset! bytes-called true) (byte-array [1 2 3]))
                      edgar.core/edgar-get (fn [& _] (reset! text-called true) "text")]
          (#'edgar.filing/save-doc!
           {:cik "320193" :accessionNumber "0000320193-24-000001"}
           {:name "exhibit.pdf"}
           (.toPath tmp-file)))
        (is (true? @bytes-called) "edgar-get-bytes must be called for .pdf")
        (is (false? @text-called) "edgar-get must NOT be called for .pdf")
        (finally (.delete tmp-file)))))
  (testing "text file triggers edgar-get (spit path), not edgar-get-bytes"
    (let [bytes-called (atom false)
          text-called (atom false)
          tmp-file (java.io.File/createTempFile "edgar-test-" ".htm")]
      (try
        (with-redefs [edgar.core/edgar-get-bytes (fn [_] (reset! bytes-called true) (byte-array []))
                      edgar.core/edgar-get (fn [& _] (reset! text-called true) "<html/>")]
          (#'edgar.filing/save-doc!
           {:cik "320193" :accessionNumber "0000320193-24-000001"}
           {:name "report.htm"}
           (.toPath tmp-file)))
        (is (false? @bytes-called) "edgar-get-bytes must NOT be called for .htm")
        (is (true? @text-called) "edgar-get must be called for .htm")
        (finally (.delete tmp-file)))))
  (testing "binary file content is written correctly as bytes"
    (let [expected-bytes (byte-array [10 20 30 40 50])
          tmp-file (java.io.File/createTempFile "edgar-test-" ".pdf")]
      (try
        (with-redefs [edgar.core/edgar-get-bytes (fn [_] expected-bytes)]
          (#'edgar.filing/save-doc!
           {:cik "320193" :accessionNumber "0000320193-24-000001"}
           {:name "exhibit.pdf"}
           (.toPath tmp-file)))
        (is (= (seq expected-bytes) (seq (java.nio.file.Files/readAllBytes (.toPath tmp-file)))))
        (finally (.delete tmp-file))))))

;;; ---------------------------------------------------------------------------

(deftest filing-by-accession-test
  (let [requested (atom [])
        stub (fn [idx]
               (fn [f]
                 (swap! requested conj f)
                 (if (instance? Exception idx) (throw idx) idx)))
        primary {:sequence "1" :name "d269120ddef14a.htm" :type "DEF 14A" :size "100"}
        agent-idx {:formType "DFAN14A"
                   :filingDate "2012-01-20"
                   :files [primary]
                   :filers [{:name "Strategic Organizing Center" :role "Filed by" :cik "0002001721"}
                            {:name "STARBUCKS CORP" :role "Subject" :cik "0000829224"}]}]
    (testing "index is fetched under the submitter path; :cik comes from :filers by role"
      (reset! requested [])
      (with-redefs [filing/filing-index (stub agent-idx)]
        (let [f (filing/filing-by-accession "0001193125-12-025113")]
          (is (= [{:cik "0001193125" :accessionNumber "0001193125-12-025113"}] @requested))
          (is (= "0000829224" (:cik f)) "Subject beats Filed by")
          (is (= "0001193125-12-025113" (:accessionNumber f)))
          (is (= "DFAN14A" (:form f)))
          (is (= "2012-01-20" (:filingDate f)))
          (is (= "d269120ddef14a.htm" (:primaryDocument f)))
          (is (= (:filers agent-idx) (:filers f)))
          (is (= "https://www.sec.gov/Archives/edgar/data/829224/000119312512025113/d269120ddef14a.htm"
                 (:url f))
              ":url is built from the resolved company CIK, not the agent's"))))
    (testing "undashed input is normalised to the dashed form"
      (reset! requested [])
      (with-redefs [filing/filing-index (stub agent-idx)]
        (is (= "0001193125-12-025113"
               (:accessionNumber (filing/filing-by-accession "000119312512025113"))))
        (is (= "0001193125-12-025113" (:accessionNumber (first @requested))))))
    (testing "no :filers → the zero-padded submitter CIK"
      (with-redefs [filing/filing-index (stub (dissoc agent-idx :filers))]
        (let [f (filing/filing-by-accession "0000320193-23-000106")]
          (is (= "0000320193" (:cik f)))
          (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000106/d269120ddef14a.htm"
                 (:url f))))))
    (testing "no primary document → :primaryDocument and :url are nil"
      (with-redefs [filing/filing-index (stub (assoc agent-idx :files []))]
        (let [f (filing/filing-by-accession "0001193125-12-025113")]
          (is (nil? (:primaryDocument f)))
          (is (nil? (:url f))))))
    (testing "missing :formType → ::not-found"
      (with-redefs [filing/filing-index (stub (dissoc agent-idx :formType))]
        (let [ex (try (filing/filing-by-accession "0001193125-12-025113") nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::filing/not-found (:type (ex-data ex))))
          (is (= "0001193125-12-025113" (:accession-number (ex-data ex)))))))
    (testing "index fetch failure → ::not-found wrapping the cause"
      (let [cause (ex-info "HTTP 404 from SEC API" {:type :edgar.core/http-error :status 404})]
        (with-redefs [filing/filing-index (stub cause)]
          (let [ex (try (filing/filing-by-accession "0001193125-12-025113") nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= ::filing/not-found (:type (ex-data ex))))
            (is (identical? cause (ex-cause ex)))))))
    (testing "other HTTP failures propagate unchanged, not as ::not-found"
      (let [cause (ex-info "HTTP 500 from SEC API" {:type :edgar.core/http-error :status 500})]
        (with-redefs [filing/filing-index (stub cause)]
          (let [ex (try (filing/filing-by-accession "0001193125-12-025113") nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (identical? cause ex))))))))

(deftest primary-doc-pre-2001-test
  (testing "an index listing only the complete submission text file → that file"
    ;; real shape: AAPL 10-K 0000320193-94-000016
    (let [txt {:sequence "" :description "Complete submission text file"
               :name "0000320193-94-000016.txt" :type "" :size "240556"}]
      (is (= txt (filing/primary-doc {:files [txt]})))))
  (testing "sequence 1 wins over the complete submission text file"
    (let [doc {:sequence "1" :description "10-K" :name "aapl-20250927.htm" :type "10-K" :size "1"}]
      (is (= doc (filing/primary-doc
                  {:files [doc {:sequence "" :description "Complete submission text file"
                                :name "0000320193-25-000079.txt" :type "" :size "2"}]}))))))

(deftest xbrl-docs-test
  (testing "linkbases, schema and the inline-XBRL extracted instance; not the HTML or exhibits"
    (with-redefs [filing/filing-index
                  (fn [_] {:files [{:sequence "1" :name "aapl-20250927.htm" :type "10-K"}
                                   {:sequence "2" :name "a10-kexhibit21109272025.htm" :type "EX-21.1"}
                                   {:sequence "8" :name "aapl-20250927.xsd" :type "EX-101.SCH"}
                                   {:sequence "9" :name "aapl-20250927_cal.xml" :type "EX-101.CAL"}
                                   {:sequence "" :name "aapl-20250927_htm.xml" :type "XML"}]})]
      (is (= ["aapl-20250927.xsd" "aapl-20250927_cal.xml" "aapl-20250927_htm.xml"]
             (map :name (filing/filing-xbrl-docs {})))))))

(defn- company-info-html
  "Filing index fixture with one companyName span per [name role cik], in the
   SEC's markup (acronym-wrapped CIK label, padded CIK inside a link)."
  [& entities]
  (str "<html><body><div id=\"formName\"><strong>Form DFAN14A</strong></div>"
       (str/join (for [[nm role cik] entities]
                   (str "<div class=\"companyInfo\"><span class=\"companyName\">" nm " (" role ")\n"
                        " <acronym title=\"Central Index Key\">CIK</acronym>: "
                        "<a href=\"/cgi-bin/browse-edgar?CIK=" cik "&amp;action=getcompany\">"
                        cik " (see all company filings)</a></span></div>")))
       "</body></html>"))

(deftest parse-filing-index-html-filers-test
  (testing "every companyName span becomes {:name :role :cik} with a 10-digit padded CIK"
    (is (= [{:name "STARBUCKS CORP" :role "Subject" :cik "0000829224"}
            {:name "Strategic Organizing Center" :role "Filed by" :cik "0002001721"}]
           (:filers (#'filing/parse-filing-index-html
                     (company-info-html ["STARBUCKS CORP" "Subject" "0000829224"]
                                        ["Strategic Organizing Center" "Filed by" "0002001721"]))))))
  (testing "Form 4 role labels padded with spaces — \"( Issuer )\" — are trimmed"
    (is (= ["Issuer" "Reporting"]
           (map :role (:filers (#'filing/parse-filing-index-html
                                (company-info-html ["STARBUCKS CORP" " Issuer " "0000829224"]
                                                   ["KELLY SARA" " Reporting " "0001971792"])))))))
  (testing "names containing parentheses keep everything before the role"
    (is (= "ACME (HOLDINGS) INC"
           (:name (first (:filers (#'filing/parse-filing-index-html
                                   (company-info-html ["ACME (HOLDINGS) INC" "Filer" "0000000042"]))))))))
  (testing ":filers is empty when the page has no company blocks"
    (is (= [] (:filers (#'filing/parse-filing-index-html form10k-index-html))))))

(deftest primary-cik-test
  (let [pick #'filing/primary-cik]
    (testing "Filer wins (agent-filed DEF 14A lists the company only)"
      (is (= "0000829224" (pick [{:role "Filer" :cik "0000829224"}]))))
    (testing "Subject beats Filed by (activist DFAN14A resolves to the target)"
      (is (= "0000829224" (pick [{:role "Filed by" :cik "0002001721"} {:role "Subject" :cik "0000829224"}]))))
    (testing "Issuer beats Reporting (Form 4 resolves to the company, not the insider)"
      (is (= "0000829224" (pick [{:role "Reporting" :cik "0001971792"} {:role "Issuer" :cik "0000829224"}]))))
    (testing "unknown roles fall back to the first listed entity"
      (is (= "0000000007" (pick [{:role "Depositor" :cik "0000000007"} {:role "Other" :cik "0000000008"}]))))
    (testing "nil when nothing is listed, so the caller keeps the submitter CIK"
      (is (nil? (pick []))))))

(deftest filing-doc-url-test
  (testing "builds correct SEC archives URL from filing map and doc name"
    (let [f {:cik "0000320193" :accessionNumber "0000320193-23-000106"}]
      (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000106/report.htm"
             (filing/filing-doc-url f "report.htm")))))
  (testing "strips leading zeros from CIK in URL path"
    (let [f {:cik "0001652044" :accessionNumber "0001652044-26-000026"}]
      (is (= "https://www.sec.gov/Archives/edgar/data/1652044/000165204426000026/goog-20260304.htm"
             (filing/filing-doc-url f "goog-20260304.htm"))))))

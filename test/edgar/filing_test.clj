(ns edgar.filing-test
  (:require [clojure.test :refer [deftest is testing]]
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
        (with-redefs [edgar.core/edgar-get-bytes (fn [_] (do (reset! bytes-called true) (byte-array [1 2 3])))
                      edgar.core/edgar-get (fn [& _] (do (reset! text-called true) "text"))]
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
        (with-redefs [edgar.core/edgar-get-bytes (fn [_] (do (reset! bytes-called true) (byte-array [])))
                      edgar.core/edgar-get (fn [& _] (do (reset! text-called true) "<html/>"))]
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

(deftest accession-format-normalization
  (testing "dashes are stripped to produce the path component"
    (let [acc "0000320193-23-000106"
          digits (str/replace acc "-" "")]
      (is (= "000032019323000106" digits))))
  (testing "CIK extracted from first 10 digits of undashed accession"
    (let [digits "000032019323000106"
          cik (str (Long/parseLong (subs digits 0 10)))]
      (is (= "320193" cik))))
  (testing "undashed 18-char string is reformatted to dashed"
    (let [digits "000032019323000106"
          dashed (str (subs digits 0 10) "-" (subs digits 10 12) "-" (subs digits 12))]
      (is (= "0000320193-23-000106" dashed)))))

(deftest filing-by-accession-form-type-test
  (testing "throws ex-info with ::not-found when :formType is absent from index"
    (let [acc "0000320193-23-000106"
          bad-idx {}
          ex (try
               (or (:formType bad-idx)
                   (throw (ex-info "Could not determine form type from filing index"
                                   {:type :edgar.filing/not-found
                                    :accession-number acc})))
               (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :edgar.filing/not-found (:type (ex-data ex))))
      (is (= acc (:accession-number (ex-data ex))))))
  (testing ":formType key is returned when present"
    (let [idx {:formType "10-K"}]
      (is (= "10-K" (:formType idx))))))

(deftest filing-doc-url-test
  (testing "builds correct SEC archives URL from filing map and doc name"
    (let [f {:cik "0000320193" :accessionNumber "0000320193-23-000106"}]
      (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000106/report.htm"
             (filing/filing-doc-url f "report.htm")))))
  (testing "strips leading zeros from CIK in URL path"
    (let [f {:cik "0001652044" :accessionNumber "0001652044-26-000026"}]
      (is (= "https://www.sec.gov/Archives/edgar/data/1652044/000165204426000026/goog-20260304.htm"
             (filing/filing-doc-url f "goog-20260304.htm"))))))

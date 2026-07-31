;; # edgarjure — a guided tour
;;
;; SEC EDGAR filings, financial statements, and XBRL data from Clojure,
;; ready for research. This is a [Clerk](https://github.com/nextjournal/clerk)
;; notebook: start it with
;;
;; ```
;; clj -M:clerk:nrepl
;; ;; then in the REPL:
;; (require '[nextjournal.clerk :as clerk])
;; (clerk/serve! {:browse? true})
;; (clerk/show! "examples/notebook.clj")
;; ```
(ns notebook
  {:nextjournal.clerk/visibility {:code :show :result :show}}
  (:require [edgar.api :as e]
            [tech.v3.dataset :as ds]))

;; The SEC requires every request to identify you. Put your own name and
;; email here — this is the only setup edgarjure needs.
(e/init! "Your Name your@email.com")

;; ## Companies
;;
;; Every function accepts a ticker or a CIK interchangeably.
(e/cik "AAPL")

(select-keys (e/company-metadata "AAPL")
             [:name :sic :sic-description :fiscal-year-end :exchanges])

;; ## Filings
;;
;; Filing indexes are lazy — ask for exactly what you need.
(ds/head (e/filings-dataset "AAPL" :form "10-K" :limit 5))

;; ## Financial statements — four views
;;
;; The same underlying XBRL facts, in four layers of refinement. `:normalized`
;; (the default) maps variant tags to canonical labels and deduplicates
;; restatements; `:standardized` imputes missing line items from arithmetic
;; identities; `:compustat` reclassifies toward Compustat definitions.
(-> (e/income "AAPL" :shape :wide)
    (ds/select-columns [:end "Revenue" "Gross Profit" "Operating Income" "Net Income"])
    (ds/head 5))

;; Standardized: derived rows carry full provenance (`:method :derived`,
;; `:derived-from`). Amazon never tags `GrossProfit` — here it is imputed:
(->> (ds/rows (e/income "AMZN" :view :standardized) {:nil-missing? true})
     (filter #(= "Gross Profit" (:line-item %)))
     (take 3)
     (map #(select-keys % [:end :val :method :derived-from])))

;; The Compustat view adds line items approximating Compustat's definitions —
;; COGS with D&A stripped out, XSGA with R&D folded in, OIADP with special
;; items added back — alongside the originals, each tagged with the rule
;; that produced it:
(->> (ds/rows (e/income "AAPL" :view :compustat) {:nil-missing? true})
     (filter #(= :reclassified (:method %)))
     (take 6)
     (map #(select-keys % [:line-item :end :val :rule])))

;; The rules are data — inspect or extend them:
(map (juxt :id :target) (:rules (e/reclass-rules :income)))

;; ## Industry routing
;;
;; Banks, insurers, and REITs get industry-specific line items automatically,
;; keyed off the company's SIC code:
(-> (e/income "JPM" :shape :wide)
    (ds/select-columns [:end "Net Interest Income" "Noninterest Income" "Net Income"])
    (ds/head 3))

(-> (e/income "SPG" :shape :wide)
    (ds/select-columns [:end "Rental Revenue" "Property Operating Expense" "D&A" "Net Income"])
    (ds/head 3))

;; ## Quarterly and trailing-twelve-month values
;;
;; 10-Q filings mix 3/6/9-month windows; edgarjure derives clean single
;; quarters (`:val-q`) and LTM sums (`:val-ltm`) from the actual period dates:
(->> (ds/rows (e/income "AAPL" :form "10-Q") {:nil-missing? true})
     (filter #(and (= "Revenue" (:line-item %)) (:val-q %)))
     (take 4)
     (map #(select-keys % [:end :duration-months :val :val-q :val-ltm])))

;; ## Point-in-time mode
;;
;; For backtests and event studies, `:as-of` restricts to filings available
;; on a given date — no look-ahead through restatements:
(-> (e/balance "AAPL" :as-of "2016-08-30" :shape :wide)
    (ds/select-columns [:end "Total Assets" "Stockholders Equity"])
    (ds/head 3))

;; ## Panels across companies
(ds/head (e/panel ["AAPL" "MSFT" "GOOGL"] :concept "Assets" :form "10-K"))

;; ## Insider and beneficial ownership
;;
;; Forms 3/4/5 and Schedules 13D/G parse into structured maps:
(let [f (e/filing "AAPL" :form "4")]
  (-> (e/obj f)
      (select-keys [:form :period-of-report :reporting-owner])
      (update :reporting-owner select-keys [:name :officer-title])))

;; ## Filing content
;;
;; Item-level text extraction (10-K Item 1A risk factors, 8-K items, ...),
;; HTML tables as datasets, exhibits, and full documents are one call away:
(let [f (e/filing "AAPL" :form "10-K")]
  (subs (:text (e/item f "1A")) 0 400))

;; ------------------------------------------------------------------------
;; Where to go next: the README covers the full API surface — validation
;; against benchmarks (`edgar.validation`), bulk FSDS ingestion
;; (`edgar.fsds`), the concept-chain override mechanism, the coverage
;; feedback loop (`e/unmapped-concepts`), and the opt-in disk cache
;; (`e/enable-disk-cache!`) for cross-session batch work.

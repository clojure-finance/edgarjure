(ns edgar.forms.form4
  "Form 4 parser — Statement of Changes in Beneficial Ownership.

   Forms 3, 4, and 5 share the ownershipDocument XML schema; the actual
   parser and the filing-obj registrations for all of them live in
   edgar.forms.ownership. This namespace remains as the historical entry
   point for Form 4.

   Usage:
     (require '[edgar.forms.form4])   ; side-effectful load registers the methods
     (edgar.filing/filing-obj filing) ; => {:form \"4\" :reporting-owner {...}
                                      ;     :transactions [...] :holdings [...]}"
  (:require [edgar.forms.ownership :as ownership]))

(defn parse-form4
  "Parse a Form 4 filing map into a structured map. See
   edgar.forms.ownership/parse-ownership-form for the full result shape."
  [filing]
  (ownership/parse-ownership-form filing))

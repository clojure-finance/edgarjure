(ns edgar.forms
  "Central loader for all built-in edgarjure form parsers.

   Requiring this namespace registers filing-obj methods for all supported
   form types. Individual parsers can also be required directly.

   Usage:
     (require '[edgar.forms])          ; loads all built-in parsers
     (edgar.filing/filing-obj filing)  ; dispatches to the appropriate parser

   Supported form types after loading:
     \"4\"       — Form 4: insider trades (edgar.forms.form4)
     \"13F-HR\"  — Form 13F-HR: institutional holdings (edgar.forms.form13f)
     \"13F-HR/A\" — Form 13F-HR/A: amended institutional holdings (edgar.forms.form13f)"
  (:require [edgar.forms.form4]
            [edgar.forms.form13f]))

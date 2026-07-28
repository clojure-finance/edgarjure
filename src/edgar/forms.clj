(ns edgar.forms
  "Central loader for all built-in edgarjure form parsers.

   Requiring this namespace registers filing-obj methods for all supported
   form types. Individual parsers can also be required directly.

   Usage:
     (require '[edgar.forms])          ; loads all built-in parsers
     (edgar.filing/filing-obj filing)  ; dispatches to the appropriate parser

   Supported form types after loading:
     \"3\" \"3/A\"   — Form 3: initial beneficial ownership (edgar.forms.ownership)
     \"4\" \"4/A\"   — Form 4: insider trades (edgar.forms.ownership)
     \"5\" \"5/A\"   — Form 5: annual beneficial ownership (edgar.forms.ownership)
     \"13F-HR\"  — Form 13F-HR: institutional holdings (edgar.forms.form13f)
     \"13F-HR/A\" — Form 13F-HR/A: amended institutional holdings (edgar.forms.form13f)"
  (:require [edgar.forms.ownership]
            [edgar.forms.form13f]))

# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

### Added
- `edgar.forms.form4` — Form 4 parser (Statement of Changes in Beneficial Ownership / insider trades). Parses issuer, reporting owner, non-derivative and derivative transactions from XML. Registers `filing-obj "4"` via the standard multimethod. No new dependencies — uses only `clojure.xml` and `clojure.string`.

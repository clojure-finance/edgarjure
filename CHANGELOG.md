# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- `edgar.core` — HTTP client with Bucket4j rate limiter (10 req/s), SEC base URLs
- `edgar.company` — ticker↔CIK resolution, company metadata, search; tickers cache
- `edgar.filings` — filing index queries via SEC submissions JSON; quarterly full-index; EFTS full-text search
- `edgar.filing` — individual filing content (HTML, text, attachments); bulk save to disk; `filing-obj` multimethod
- `edgar.download` — bulk downloader for single company and batch (pmap-based); quarterly index download
- `edgar.xbrl` — XBRL company-facts JSON → `tech.ml.dataset`; cross-sectional concept frames
- `edgar.financials` — income statement, balance sheet, cash flow builders (long-format datasets)
- `edgar.extract` — NLP item-section extraction for 10-K, 10-Q, 8-K; table stripping; batch mode
- `edgar.dataset` — panel datasets, cross-sectional snapshots, `pivot-wide`, Datajure-compatible output

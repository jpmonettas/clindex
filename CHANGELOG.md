# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

## 0.2.4 (2019-11-15)

- Fix ::keyword reading
- Add clindex.api/db-schema
- Add :var/[column end-column] and :var-ref/[column end-column] to the schema
- Fix scanner read-namespace-forms for non list forms
- Honor :mvn/repos on project

## 0.2.3 (2019-10-31)

- Don't add to the tracker namespaces inside jars (tracking cljs compiler doesn't work because of dep cycle)

## 0.2.2 (2019-10-31)

- Index :namespace/docstring, :var/docstring and :function/args

## 0.2.1 (2019-10-29)

### Fixes

- Fix a bunch of watchers minors bugs
- Fix defmulti facts when defmulti contains documentation
